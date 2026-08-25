"""server — FastAPI 门面，暴露原生 App 可调用的 HTTP 端点。

对齐 docs/DESIGN.md §5.1：
- POST /agent/run  主入口：1 次任务（可多轮，状态由 session_id 维系）
- GET  /health     存活检查（App 联调用）

设计：
- 默认监听 0.0.0.0，允许受信任设备通过配置的 Agent 地址访问。
- 依赖注入 decision_fn：默认用云端大模型；测试注入 mock。
"""

from __future__ import annotations

import argparse
import logging
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from typing import Callable

from fastapi import FastAPI, Header, HTTPException, Request

from bridge.schemas import PROTOCOL_VERSION
from bridge.validate import validate
from agent.agent_loop import decide_once
from agent.cloud_config import load_cloud_config
from agent.llm_client import LLMClient
from agent.skill_router import route_fixed_skill
from agent.ui_summarizer import summarize_xml, to_llm_prompt
from agent.ui_diff import render_ui_diff

logger = logging.getLogger("tars.server")

_RAW_UI_LOG_ENV = "TARS_LOG_RAW_UI"


def _raw_ui_logging_enabled() -> bool:
    return os.getenv(_RAW_UI_LOG_ENV, "").strip().lower() in {"1", "true", "yes", "on"}


def _port(value: str) -> int:
    try:
        port = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("端口必须是整数") from exc
    if port not in range(1, 65_536):
        raise argparse.ArgumentTypeError("端口必须在 1 到 65535 之间")
    return port


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

# Session-level cache of the previous round's node lines, so the model can compare two
# consecutive UI snapshots and reason about what changed (facts only, no attribution).
_prev_nodes: dict[str, str] = {}
_prev_node_models: dict[str, list[dict]] = {}


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the TARS Agent service")
    parser.add_argument("--mock", action="store_true", help="use a deterministic no-model backend for integration tests")
    parser.add_argument("--config", default="config/cloud.yaml", help="private cloud deployment YAML")
    parser.add_argument("--host", default="0.0.0.0", help="HTTP listen address (default: 0.0.0.0)")
    parser.add_argument("--port", type=_port, default=8080, help="HTTP listen port (default: 8080)")
    parser.add_argument(
        "--log-file",
        default="log/agent/agent.log",
        help="write Agent logs to this UTF-8 file as well as stderr (default: log/agent/agent.log)",
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
    logger.info("Agent listening on %s:%d", args.host, args.port)
    uvicorn.run(app, host=args.host, port=args.port)


app = FastAPI(title="TARS Assistant Agent", version=PROTOCOL_VERSION)

_MAX_ANDROID_LOG_BYTES = 10 * 1024 * 1024
_ANDROID_LOG_DIR = Path(__file__).resolve().parents[1] / "log" / "android"


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "protocol_version": PROTOCOL_VERSION}


@app.post("/logs/android")
async def upload_android_log(
    request: Request,
    filename: str = Header(default="android.log", alias="X-TARS-Log-Filename"),
) -> dict:
    """Store an Android diagnostic log below the project log directory."""
    body = await request.body()
    if len(body) > _MAX_ANDROID_LOG_BYTES:
        raise HTTPException(status_code=413, detail="Android 日志文件过大")
    safe_name = Path(filename or "android.log").name
    if safe_name in {"", ".", ".."}:
        safe_name = "android.log"
    _ANDROID_LOG_DIR.mkdir(parents=True, exist_ok=True)
    (_ANDROID_LOG_DIR / safe_name).write_bytes(body)
    logger.info("Android log uploaded filename=%s bytes=%d", safe_name, len(body))
    return {"status": "ok", "filename": safe_name, "bytes": len(body)}


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
    diag_xml = req.get("ui_xml") or ""
    logger.info(
        "agent context session=%s intent=%r app=%r activity=%r ui_bytes=%d observation=%r launchable_apps=%d",
        req["session_id"], req.get("intent"), req.get("app"), req.get("activity"),
        len(diag_xml), req.get("observation_note") or "",
        len(req.get("launchable_apps") or []),
    )
    if _raw_ui_logging_enabled():
        logger.warning(
            "raw ui enabled session=%s app=%s bytes=%d xml=%r",
            req["session_id"], req.get("app") or "-", len(diag_xml), diag_xml,
        )
    _diag_nodes = summarize_xml(diag_xml)
    try:
        launchable_apps = req.get("launchable_apps") or []
        fixed_response = route_fixed_skill(
            session_id=req["session_id"], intent=req["intent"], launchable_apps=launchable_apps,
        )
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
            previous_nodes=(
                render_ui_diff(_prev_node_models.get(req["session_id"], []), _diag_nodes)
                if _diag_nodes and _prev_node_models.get(req["session_id"])
                else (_prev_nodes.get(req["session_id"], "") if not _diag_nodes else "")
            ),
            launchable_apps=launchable_apps,
        )
        current_nodes = to_llm_prompt(_diag_nodes)
        if current_nodes:
            _prev_nodes[req["session_id"]] = current_nodes
            _prev_node_models[req["session_id"]] = _diag_nodes
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
