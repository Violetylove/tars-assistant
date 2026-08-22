"""server — FastAPI 门面，暴露原生 App 可调用的 HTTP 端点。

对齐 docs/DESIGN.md §5.1：
- POST /agent/run  主入口：1 次任务（可多轮，状态由 session_id 维系）
- GET  /health     存活检查（App 联调用）

设计：
- 只监听 127.0.0.1（D6）：不暴露局域网，agent 与 App 同设备。
- 依赖注入 decision_fn：默认用 real LLM（阶段 3 接 llama-server）；测试注入 mock。
- llama-server 按需拉起（D5）在阶段 3 接入，此处预留 LIFECYCLE 接口。
- 进程生命周期用 FastAPI lifespan（startup/shutdown），不用已弃用的 on_event。
"""

from __future__ import annotations

import logging
import argparse
from contextlib import asynccontextmanager
from typing import Callable, Optional

from fastapi import FastAPI, HTTPException

from bridge.schemas import PROTOCOL_VERSION
from bridge.validate import validate
from agent.agent_loop import decide_once
from agent.llama_manager import LlamaManager
from agent.llm_client import LLMClient, MockLLM
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

# llama-server 生命周期（阶段 3 注入 LlamaManager；提供 ensure_up/touch/check_idle/shutdown）
llm_lifecycle: Optional[Callable] = None


def configure_runtime(*, mock: bool = False, base_url: str = "http://127.0.0.1:11434/v1",
                      model: str = "qwen2.5:3b", start_cmd: list[str] | None = None) -> None:
    """Configure the process-local decision backend before serving HTTP requests.

    Mock mode is intentionally explicit: it proves the Android-to-Agent loopback
    protocol without pretending that a local model is running.
    """
    global decision_fn, llm_lifecycle
    if mock:
        def mock_decision(*, session_id: str, **_kwargs) -> dict:
            return {
                "protocol_version": PROTOCOL_VERSION,
                "session_id": session_id,
                "done": True,
                "reply": "协议联调完成（mock，未调用本地模型）",
                "actions": [],
                "need_observation": False,
            }

        decision_fn = mock_decision
        llm_lifecycle = None
        logger.warning("Agent is running in explicit mock mode; no model actions are produced")
        return

    llm = LLMClient(base_url=base_url, model=model)
    manager = LlamaManager(base_url=base_url.removesuffix("/v1"), start_cmd=start_cmd)
    decision_fn = lambda **kwargs: decide_once(llm=llm, **kwargs)
    llm_lifecycle = manager.lifecycle


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the TARS loopback Agent service")
    parser.add_argument("--mock", action="store_true", help="use a deterministic no-model backend for integration tests")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--llm-base-url", default="http://127.0.0.1:11434/v1")
    parser.add_argument("--model", default="qwen2.5:3b")
    args = parser.parse_args()
    configure_runtime(mock=args.mock, base_url=args.llm_base_url, model=args.model)
    import uvicorn
    uvicorn.run(app, host=args.host, port=args.port)


def _idle_monitor(stop_evt):  # 后台空闲守护：超过空闲阈值退出
    import threading
    import time as _time
    while not stop_evt.is_set():
        try:
            if llm_lifecycle:
                llm_lifecycle("check_idle")
        except Exception:
            logger.exception("idle monitor 异常")
        _time.sleep(5)


_stop_evt = None
_monitor_thread = None


@asynccontextmanager
async def lifespan(fastapi_app: FastAPI):
    """服务进程生命周期：startup 起空闲守护线程；shutdown 停线程并释放 llama-server。"""
    global _stop_evt, _monitor_thread
    if llm_lifecycle is not None:
        import threading
        _stop_evt = threading.Event()
        _monitor_thread = threading.Thread(target=_idle_monitor, args=(_stop_evt,), daemon=True)
        _monitor_thread.start()
    yield
    if _stop_evt:
        _stop_evt.set()
    if llm_lifecycle:
        try:
            llm_lifecycle("shutdown")
        except Exception:
            logger.exception("llm shutdown 异常")


app = FastAPI(title="TARS Assistant Agent", version=PROTOCOL_VERSION, lifespan=lifespan)


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

    if llm_lifecycle:
        llm_lifecycle("ensure_up")

    try:
        resp = decision_fn(
            session_id=req["session_id"],
            intent=req["intent"],
            ui_xml=req.get("ui_xml", ""),
            history=req.get("history"),
        )
    except Exception as exc:  # LLM 网络/服务异常 → 50x（Tasker 可重试）
        logger.exception("决策失败")
        raise HTTPException(status_code=502, detail=f"决策服务异常: {exc}") from exc

    if llm_lifecycle:
        llm_lifecycle("touch")
    return resp


if __name__ == "__main__":
    main()
