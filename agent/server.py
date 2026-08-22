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
from typing import Callable

from fastapi import FastAPI, HTTPException

from bridge.schemas import PROTOCOL_VERSION
from bridge.validate import validate
from agent.agent_loop import decide_once
from agent.cloud_config import load_cloud_config
from agent.llm_client import LLMClient
from agent.skill_router import route_fixed_skill

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
                      api_key: str = "", timeout_seconds: float = 60.0) -> None:
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

    llm = LLMClient(base_url=base_url, model=model, api_key=api_key, timeout=timeout_seconds)
    decision_fn = lambda **kwargs: decide_once(llm=llm, **kwargs)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the TARS loopback Agent service")
    parser.add_argument("--mock", action="store_true", help="use a deterministic no-model backend for integration tests")
    parser.add_argument("--config", default="config/cloud.yaml", help="private cloud deployment YAML")
    args = parser.parse_args()
    if args.mock:
        configure_runtime(mock=True)
    else:
        config = load_cloud_config(args.config)
        configure_runtime(base_url=config.base_url, model=config.model, api_key=config.api_key,
                          timeout_seconds=config.timeout_seconds)
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8080)


app = FastAPI(title="TARS Assistant Agent", version=PROTOCOL_VERSION)


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "protocol_version": PROTOCOL_VERSION}


@app.post("/agent/run")
def agent_run(req: dict) -> dict:
    # 契约校验：非法请求直接 400（决策层不处理不可信输入）
    errs = validate(req, "task_request")
    if errs:
        raise HTTPException(status_code=400, detail=f"task_request 校验失败: {'; '.join(errs[:5])}")

    fixed_response = route_fixed_skill(session_id=req["session_id"], intent=req["intent"])
    if fixed_response is not None:
        return fixed_response

    try:
        resp = decision_fn(
            session_id=req["session_id"],
            intent=req["intent"],
            ui_xml=req.get("ui_xml", ""),
            history=req.get("history"),
        )
        return resp
    except Exception as exc:  # LLM 网络/服务异常 → 50x（Tasker 可重试）
        logger.exception("决策失败")
        raise HTTPException(status_code=502, detail=f"决策服务异常: {exc}") from exc


if __name__ == "__main__":
    main()
