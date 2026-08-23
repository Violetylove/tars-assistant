"""server — FastAPI 门面，暴露原生 App 可调用的 HTTP 端点。

对齐 docs/DESIGN.md §5.1：
- POST /agent/run  主入口：1 次任务（可多轮，状态由 session_id 维系）
- GET  /health     存活检查（App 联调用）

设计：
- 只监听 127.0.0.1（D6）：不暴露局域网，agent 与 App 同设备。
- 依赖注入 decision_fn：默认用云端大模型；测试注入 mock。
"""

from __future__ import annotations

import argparse
import logging
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from typing import Callable

from fastapi import FastAPI, HTTPException

from bridge.schemas import PROTOCOL_VERSION
from bridge.validate import validate
from agent.agent_loop import decide_once
from agent.cloud_config import load_cloud_config
from agent.llm_client import LLMClient
from agent.skill_router import route_fixed_skill
from agent.ui_summarizer import summarize_xml, to_llm_prompt

logger = logging.getLogger("tars.server")

def _runtime_not_configured(*, session_id: str, **_kwargs) -> dict:
    return {
        "protocol_version": PROTOCOL_VERSION,
        "session_id": session_id,
        "done": False,
        "reply": "Agent 运行时未配置。请使用 python -m agent.server 启动服务。",
        "actions": [],
        "need_observation": False,
    }


# 决策实现可注入（product 用 decide_once+real llm；测试用 stub）。
# 直接以 uvicorn 导入时 fail-closed，避免缺少 llm 参数而抛 502。
decision_fn: Callable = _runtime_not_configured

def configure_runtime(*, mock: bool = False, base_url: str = "", model: str = "",
                      api_key: str = "", timeout_seconds: float = 60.0,
                      max_retries: int = 2, retry_backoff_seconds: float = 1.0) -> None:
    """Configure the process-local decision backend before serving HTTP requests.

    Mock mode is intentionally explicit: it proves the Android-to-Agent loopback
    protocol without pretending that a local model is running.
    """
    global decision_fn
    if mock:
        def mock_decision(*, session_id: str, **_kwargs) -> dict:
            return {
                "protocol_version": PROTOCOL_VERSION,
                "session_id": session_id,
                "done": True,
                "reply": "协议联调完成（mock，未调用云端模型）",
                "actions": [],
                "need_observation": False,
            }

        decision_fn = mock_decision
        logger.warning("Agent is running in explicit mock mode; no model actions are produced")
        return

    llm = LLMClient(
        base_url=base_url, model=model, api_key=api_key, timeout=timeout_seconds,
        max_retries=max_retries, retry_backoff_seconds=retry_backoff_seconds,
    )
    decision_fn = lambda **kwargs: decide_once(llm=llm, **kwargs)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the TARS loopback Agent service")
    parser.add_argument("--mock", action="store_true", help="use a deterministic no-model backend for integration tests")
    parser.add_argument("--config", default="config/cloud.yaml", help="private cloud deployment YAML")
    parser.add_argument(
        "--log-file",
        default="tars-agent.log",
        help="write Agent logs to this UTF-8 file as well as stderr (default: tars-agent.log)",
    )
    args = parser.parse_args()
    log_format = "%(asctime)s %(levelname)s %(name)s %(message)s"
    handlers: list[logging.Handler] = [logging.StreamHandler()]
    log_path = Path(args.log_file)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    handlers.append(logging.FileHandler(log_path, encoding="utf-8"))
    logging.basicConfig(level=logging.INFO, format=log_format, handlers=handlers, force=True)
    logger.info("Agent file logging enabled: %s", log_path)
    if args.mock:
        configure_runtime(mock=True)
    else:
        config = load_cloud_config(args.config)
        configure_runtime(base_url=config.base_url, model=config.model, api_key=config.api_key,
                          timeout_seconds=config.timeout_seconds, max_retries=config.max_retries,
                          retry_backoff_seconds=config.retry_backoff_seconds)
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8080)


app = FastAPI(title="TARS Assistant Agent", version=PROTOCOL_VERSION)


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "protocol_version": PROTOCOL_VERSION}


def _validate_response_for_request(resp: dict, session_id: str) -> dict:
    """Reject malformed or cross-session decisions before returning them to Android."""
    if resp.get("session_id") != session_id:
        raise ValueError("agent_response session_id 与请求不一致")
    errs = validate(resp, "agent_response")
    if errs:
        raise ValueError("agent_response 校验失败: " + "; ".join(errs[:5]))
    return resp


@app.post("/agent/run")
def agent_run(req: dict) -> dict:
    # 契约校验：非法请求直接 400（决策层不处理不可信输入）
    errs = validate(req, "task_request")
    if errs:
        raise HTTPException(status_code=400, detail=f"task_request 校验失败: {'; '.join(errs[:5])}")
    ui_xml = req.get("ui_xml") or ""
    if ui_xml.strip():
        try:
            ET.fromstring(ui_xml)
        except ET.ParseError as exc:
            raise HTTPException(status_code=400, detail=f"ui_xml 不是合法 XML: {exc}") from exc

    logger.info(
        "agent request session=%s app=%s activity=%s nodes=%d history_rounds=%d",
        req["session_id"], req.get("app") or "-", req.get("activity") or "-",
        ui_xml.count("<node"), len(req.get("history") or []),
    )
    # === DIAG (temporary): expose request content to diagnose occluder handling ===
    diag_xml = req.get("ui_xml") or ""
    logger.info(
        "DIAG req session=%s intent=%r app=%r activity=%r ui_bytes=%d obs_note=%r has_suggestion=%s has_subject=%s has_body=%s has_recipient=%s",
        req["session_id"], req.get("intent"), req.get("app"), req.get("activity"),
        len(diag_xml), req.get("observation_note") or "",
        ("Winter Yuan" in diag_xml) or ("建议" in diag_xml) or ("Suggestion" in diag_xml),
        ("主题" in diag_xml), ("正文" in diag_xml) or ("撰写" in diag_xml),
        ("violetylove" in diag_xml),
    )
    # DIAG: record the summarised interactive node list the model actually sees
    _diag_nodes = summarize_xml(diag_xml)
    logger.info(
        "DIAG nodes session=%s count=%d ids=%s",
        req["session_id"], len(_diag_nodes),
        [(n["id"], n["type"], (n.get("text") or "")[:20]) for n in _diag_nodes],
    )
    # DIAG: record the exact prompt text handed to the LLM for this round
    logger.info(
        "DIAG prompt session=%s text=%r",
        req["session_id"], to_llm_prompt(_diag_nodes),
    )
    # DIAG: inspect whether the suggestion row / peoplekit list is present and interactive
    _has_peoplekit = "peoplekit" in diag_xml
    _has_recycler = "RecyclerView" in diag_xml
    _sugg_row_text = "Winter Yuan" in diag_xml
    _has_recycler_id = "peoplekit_autocomplete_results_recyclerview" in diag_xml
    logger.info(
        "DIAG ui_xml session=%s has_peoplekit=%s has_recyclerview=%s has_recycler_id=%s has_winteryuan_text=%s",
        req["session_id"], _has_peoplekit, _has_recycler, _has_recycler_id, _sugg_row_text,
    )
    # DIAG: dump the raw XML snippet around the peoplekit / suggestion area, if present
    _idx = diag_xml.find("peoplekit")
    if _idx >= 0:
        _snippet = diag_xml[max(0, _idx - 120): _idx + 900]
        logger.info(
            "DIAG ui_xml_peoplekit session=%s snippet=%r",
            req["session_id"], _snippet,
        )
    else:
        logger.info(
            "DIAG ui_xml_peoplekit session=%s snippet=(no peoplekit in ui_xml) app=%r ui_bytes=%d",
            req["session_id"], req.get("app"), len(diag_xml),
        )
    # === END DIAG ===
    try:
        fixed_response = route_fixed_skill(session_id=req["session_id"], intent=req["intent"])
        if fixed_response is not None:
            response = _validate_response_for_request(fixed_response, req["session_id"])
            logger.info("agent response session=%s source=fixed actions=%s done=%s observe=%s",
                        req["session_id"], [a.get("type") for a in response.get("actions", [])],
                        response.get("done"), response.get("need_observation"))
            return response
        resp = decision_fn(
            session_id=req["session_id"],
            intent=req["intent"],
            ui_xml=req.get("ui_xml", ""),
            history=req.get("history"),
            app=req.get("app"),
            activity=req.get("activity"),
            observation_note=req.get("observation_note", ""),
        )
        response = _validate_response_for_request(resp, req["session_id"])
        logger.info("agent response session=%s source=llm actions=%s done=%s observe=%s",
                    req["session_id"], [a.get("type") for a in response.get("actions", [])],
                    response.get("done"), response.get("need_observation"))
        return response
    except Exception as exc:  # LLM 网络/服务异常 → 50x（Tasker 可重试）
        logger.exception("决策失败")
        raise HTTPException(status_code=502, detail=f"决策服务异常: {exc}") from exc


if __name__ == "__main__":
    main()
