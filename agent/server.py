"""server — FastAPI 门面，暴露 Tasker 可调用的 HTTP 端点。

对齐 docs/DESIGN.md §5.1：
- POST /agent/run  主入口：1 次任务（可多轮，状态由 session_id 维系）
- GET  /health     存活检查（Tasker 联调用）

设计：
- 只监听 127.0.0.1（D6）：不暴露局域网，agent 与 Tasker 同设备。
- 依赖注入 decision_fn：默认用 real LLM（阶段 3 接 llama-server）；测试注入 mock。
- llama-server 按需拉起（D5）在阶段 3 接入，此处预留 LIFECYCLE 接口。
"""

from __future__ import annotations

import logging
from typing import Callable, Optional

from fastapi import FastAPI, HTTPException

from bridge.schemas import PROTOCOL_VERSION
from bridge.validate import validate
from agent.agent_loop import decide_once

logger = logging.getLogger("tars.server")

app = FastAPI(title="TARS Assistant Agent", version=PROTOCOL_VERSION)

# 决策实现可注入（product 用 decide_once+real llm；测试用 stub）
# signature: decision_fn(**kwargs) -> dict  (见 agent_loop.decide_once)
decision_fn: Callable = decide_once

# llama-server 生命周期接口（阶段 3 注入：ensure_up / idle_shutdown）
llm_lifecycle: Optional[Callable] = None


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "protocol_version": PROTOCOL_VERSION}


@app.post("/agent/run")
def agent_run(req: dict) -> dict:
    # 契约校验：非法请求直接 400（决策层不处理不可信输入）
    errs = validate(req, "task_request")
    if errs:
        raise HTTPException(status_code=400, detail=f"task_request 校验失败: {'; '.join(errs[:5])}")

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
        llm_lifecycle("idle_shutdown")
    return resp
