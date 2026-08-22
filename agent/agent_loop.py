"""agent_loop — SmolAgent 风格决策循环。

思考→行动→观察：解析输入 → 摘要 UI 树 → 调用 LLM → 强制 JSON → schema 校验 →
返回 agent_response（done=false 时带 need_observation 触发下一轮 UI 重采）。

设计要点（对齐 docs/DESIGN.md）：
- "LLM 输出不可信"：LLM 回复先 extract_json 净化，再过 bridge schema；非法一律拒绝，
  返回安全错误，绝不下发动作。
- 模型解耦：llm 参数接受 LLMClient 或 MockLLM（鸭子类型，只需 complete(messages)）。
- 多轮：后续轮带 need_observation 重采 UI，把新 UI 摘要并入对话历史。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Optional

from bridge.schemas import PROTOCOL_VERSION
from bridge.validate import validate, validate_action
from agent.llm_client import MockLLM, extract_json
from agent.ui_summarizer import summarize_xml, to_llm_prompt

SYSTEM_PROMPT = (
    "你是一个手机界面操作助手。用户给出意图，你基于屏幕上的可交互节点决策。\n"
    "只能从下列动作中选择：click(需 target_node_id), type(需 target_node_id+text), "
    "swipe(x1/y1/x2/y2/duration_ms), back, home, wait(ms), reply(给用户的话), done。\n"
    "输出严格 JSON 对象，不要任何多余说明。当某一步做不了时应输出 "
    '{"type":"reply","text":"..."}；当任务完成输出 {"type":"done"}。\n'
    "节点行格式：[id] 类型\"文本\" (cx,cy)。"
)


def _build_user_message(intent: str, nodes: list[dict], history: list[dict]) -> str:
    segs = [f"用户意图：{intent}", "当前屏幕节点：", to_llm_prompt(nodes)]
    if history:
        segs.append("前面的动作/观察：")
        segs.extend(json.dumps(h, ensure_ascii=False) for h in history)
    return "\n".join(segs)


@dataclass
class DecisionResult:
    """一轮决策的结果。"""

    response: dict = field(default_factory=lambda: {
        "protocol_version": PROTOCOL_VERSION,
        "session_id": "",
        "done": False,
        "reply": "",
        "actions": [],
        "need_observation": False,
    })
    error: Optional[str] = None


def _safe_response(session_id: str, reply: str) -> dict:
    return {
        "protocol_version": PROTOCOL_VERSION,
        "session_id": session_id,
        "done": False,
        "reply": reply,
        "actions": [],
        "need_observation": False,
    }


def decide_once(
    *,
    llm,
    session_id: str,
    intent: str,
    ui_xml: str,
    history: Optional[list[dict]] = None,
) -> dict:
    """单轮决策：摘要 + 调 LLM + 净化 + schema 校验。返回合法 agent_response。

    若 LLM 输出非法，返回 reply=错误说明 + 空 actions（安全拒绝，不抛异常）。
    """
    nodes = summarize_xml(ui_xml)
    user_msg = _build_user_message(intent, nodes, history or [])
    raw = llm.complete([
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_msg},
    ])

    obj = extract_json(raw)
    if not isinstance(obj, dict):
        return _safe_response(session_id, f"LLM 输出无法解析为 JSON：{raw[:120]!r}")

    # 构造 agent_response（若 LLM 只给单个 action，不强制包 agent_response，兼容两态）
    if "actions" in obj or "done" in obj:
        resp = dict(obj)
        resp.setdefault("protocol_version", PROTOCOL_VERSION)
        resp.setdefault("session_id", session_id)
        resp.setdefault("reply", "")
        resp.setdefault("actions", [])
        resp.setdefault("need_observation", False)
        resp.setdefault("done", False)
    elif "type" in obj:
        # 单个 action 形态
        errs = validate_action(obj)
        if errs:
            return _safe_response(session_id, "LLM 动作用于拒绝：" + "; ".join(errs))
        # done 是结束动作：联动 done 标志
        is_done = obj.get("type") == "done"
        # done 无需执行动作，也不进 actions
        actions = [] if is_done else [obj]
        resp = {
            "protocol_version": PROTOCOL_VERSION,
            "session_id": session_id,
            "done": is_done or False,
            "reply": "",
            "actions": actions,
            "need_observation": (not is_done),  # 有动作需重采，done 不需
        }
    else:
        return _safe_response(session_id, f"LLM 输出缺少可识别的动作字段：{obj}")

    errs = validate(resp, "agent_response")
    if errs:
        return _safe_response(session_id, "LLM 响应未通过 schema 校验：" + "; ".join(errs[:5]))
    return resp


def run_decision_loop(
    *,
    llm,
    session_id: str,
    intent: str,
    ui_xml: str,
    max_steps: int = 4,
    on_step: Optional[callable] = None,
) -> dict:
    """完整决策循环（可多轮）。返回最终 agent_response。

    每轮的 UI 由调用方（server）按 need_observation 重采后再次调用本函数串行推进，
    这里作为单函数测试入口：首轮用 ui_xml，后续轮复用同一 ui_xml 近似观察（测试用）。
    """
    history: list[dict] = []
    current_xml = ui_xml
    for _ in range(max_steps):
        resp = decide_once(
            llm=llm, session_id=session_id, intent=intent,
            ui_xml=current_xml, history=history,
        )
        if on_step:
            on_step(resp)
        if resp.get("done"):
            return resp
        if not resp.get("actions"):
            return resp  # 无动作可做且未 done，停止
        history.append({"actions": resp["actions"]})
        # 测试近似：观察复用（真实重采由 server 负责）
    return _safe_response(session_id, f"达到最大步数 {max_steps} 仍未完成")
