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
import logging
from dataclasses import dataclass, field
from typing import Optional

from bridge.schemas import PROTOCOL_VERSION
from bridge.validate import validate, validate_action
from agent.llm_client import MockLLM, extract_json
from agent.ui_summarizer import summarize_xml, to_window_layers, to_llm_prompt

logger = logging.getLogger("tars.agent_loop")

SYSTEM_PROMPT = (
    "你是手机界面操作助手TARS的决策大脑。用户给出目标意图，你需要严格按照意图，逐步操作手机完成它。\n"
    "每一步按**固定流程**进行：先做可见性规划，再做决策。\n"
    "第一步（必做）——先对**本轮**节点逐个做可见性规划，再对比两轮找变化：\n"
    "- 先看图层（z 轴）：先与 \"窗口图层（z 轴）与区域\" 段对照，某节点若落在**更高图层**窗口的"
    "区域里，则被该窗口覆盖，当前不可操作（即使它有坐标）。\n"
    "- 再看坐标：没有被更高图层覆盖的，再看其坐标是否在当前可见区域内（未被临时组件挤到屏外）。\n"
    "- 父不可见则子不可见：若某节点的父容器不可见/不可操作（被覆盖、被挤出可见区、或高度为 0），"
    "则其所有子节点同样不可见/不可操作，即使子节点自身有坐标也不能操作。\n"
    "- 把结论写进思考：明确列出\"哪些节点当前可见可用、哪些不可见/被覆盖、为什么\"。\n"
    "- 若提供了\"上一轮屏幕节点\"：完成本轮规划后，再**对比两轮**——本轮每个组件的可见性较上轮发生了哪些变化（哪些新增可见、哪些变为不可见、哪些位置/图层变了）；把这些变化及其含义写进思考。\n"
    "第二步——根据可见性规划结果做决策：\n"
    "- 动作前进性：优先选择能让你接近目标的操作。若一步后界面没有变化，重新做可见性规划再决定。\n"
    "- 最后单独输出一行、一个 JSON 对象作为你的指令，从以下动作中选择：\n"
    "  click(需 target_node_id), type(需 target_node_id+text), swipe(x1/y1/x2/y2/duration_ms), "
    "back, home, wait(ms), reply(给用户的话), done。\n"
    "  思考文字务必在前面（含可见性规划结论），JSON 指令放在回复的最后一行。当某一步做不了时指令为 "
    '{"type":"reply","text":"..."}；任务完成时指令为 {"type":"done"}。\n'
    "节点行格式：[id] 类型\"文本\" (cx,cy) [层N]。click 和 type 的 target_node_id 必须是该节点"
    "的 JSON 整数（例如 1，绝不能写成字符串 \"1\"）。\n"
    "- [层N] 表示该节点所在窗口图层，N 越小图层越高（越在上）。"
    "bounds=[x1,y1][x2,y2] 是每个节点的完整矩形；行尾仅列出为真的状态词："
    "clickable（可点）、focusable（仅可聚焦、未必可点的容器）、focused（当前聚焦）。\n"
    "- 节点行按**树序**排列并**缩进**：缩进越深表示嵌套越深（某节点的子节点在其下用缩进表示）；"
    "行尾 <父容器> 是该节点的父容器标签，用以看出节点归属（如某节点属于\"建议\"面板还是\"主题\"区）。\n"
    "- 据此你能**想象出界面结构**：谁在哪个容器里、哪些是同一容器下的兄弟，从而判断哪个节点"
    "是否被更高图层覆盖、哪个是父哪个是子。\n"
    "- \"窗口图层（z 轴）与区域\" 段列出各窗口的类型、图层、区域（含不在节点树里的输入法/"
    "系统/浮层窗口）。这是判断覆盖关系的**首要依据**：先对照它确定哪些区域被更高图层盖住。"
)


def extract_last_json(text: str):
    """从模型回复中提取最后一个 JSON 对象（指令在思考之后）。

    DIAG: 与 llm_client.extract_json 找第一个 JSON 不同，这里取最后一个 ——
    模型回复开头是自然语言思考，指令 JSON 位于末尾。思考文字中可能出现孤立
    的 {}，取最后能稳定命中指令。
    """
    if not text or not text.strip():
        return None
    decoder = json.JSONDecoder()
    last = None
    i = 0
    while i < len(text):
        if text[i] == "{":
            try:
                obj, end = decoder.raw_decode(text[i:])
                if isinstance(obj, dict):
                    last = obj
                i += end
                continue
            except json.JSONDecodeError:
                pass
        i += 1
    return last


# Confirmation is derived from UI content, never trusted to the model's flag alone.
TARS_PACKAGE = "org.atovio.tars"


_SENSITIVE_LABELS = ("发送", "删除", "清除", "支付", "付款", "转账", "send", "delete", "pay")


def _build_user_message(
    intent: str,
    nodes: list[dict],
    history: list[dict],
    app: Optional[str] = None,
    activity: Optional[str] = None,
    window_layers: str = "",
    observation_note: str = "",
    previous_nodes: str = "",
) -> str:
    segs = [f"用户意图：{intent}"]
    if observation_note:
        # The execution side re-observed after an action produced no UI change. Tell the
        # model explicitly so it does not repeat a no-op decision on an unchanged tree.
        segs.append(f"注意（上一轮反馈）：{observation_note}")
    if previous_nodes:
        segs.extend(["上一轮屏幕节点（与当前对比，识别变化）：", previous_nodes])
    if window_layers:
        segs.extend(["窗口图层（z 轴）与区域：", window_layers])
    segs.extend(["当前屏幕节点：", to_llm_prompt(nodes)])
    if app:
        segs.insert(1, f"当前前台应用包名：{app}")
    if app == TARS_PACKAGE:
        segs.append(
            "注意（事实）：当前前台是 TARS 自身界面（org.atovio.tars）——它是承载你的宿主应用，"
            "其控制按钮（如“发送给 TARS”）不是目标任务的一部分，不要点击它们；"
            "直接按用户意图执行（如回到桌面、启动目标应用）。"
        )
    if activity:
        segs.insert(2 if app else 1, f"当前前台窗口类名：{activity}")
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


def _enforce_sensitive_confirmation(resp: dict, nodes: list[dict]) -> None:
    """Mark clicks on sensitive UI targets for mandatory App-side confirmation."""
    by_id = {node["id"]: node for node in nodes}
    for action in resp.get("actions", []):
        if action.get("type") != "click":
            continue
        node = by_id.get(action.get("target_node_id"))
        label = (node or {}).get("text", "").casefold()
        if any(term in label for term in _SENSITIVE_LABELS):
            action["requires_confirmation"] = True


def _normalize_known_node_ids(resp: dict, nodes: list[dict]) -> None:
    """Normalize only an unambiguous model formatting error before schema validation.

    Models occasionally quote an otherwise valid node id.  This is not a general
    coercion layer: the value must be ASCII digits, belong to the current UI
    snapshot, and appear on an action type which actually targets a UI node.
    Every other malformed value continues to fail the protocol schema.
    """
    node_ids = {node["id"] for node in nodes}
    for action in resp.get("actions", []):
        if not isinstance(action, dict) or action.get("type") not in {"click", "type"}:
            continue
        target_node_id = action.get("target_node_id")
        if (
            isinstance(target_node_id, str)
            and target_node_id.isascii()
            and target_node_id.isdecimal()
            and int(target_node_id) in node_ids
        ):
            action["target_node_id"] = int(target_node_id)


def decide_once(
    *,
    llm,
    session_id: str,
    intent: str,
    ui_xml: str,
    history: Optional[list[dict]] = None,
    app: Optional[str] = None,
    activity: Optional[str] = None,
    observation_note: str = "",
    previous_nodes: str = "",
    max_retries: int = 1,
) -> dict:
    """单轮决策：摘要 + 调 LLM + 净化 + schema 校验。返回合法 agent_response。

    若 LLM 输出非法，会带错误提示重试（最多 max_retries 次）；仍失败则返回
    reply=错误说明 + 空 actions（安全拒绝，不抛异常）。
    """
    nodes = summarize_xml(ui_xml)
    user_msg = _build_user_message(
        intent, nodes, history or [], app=app, activity=activity,
        window_layers=to_window_layers(ui_xml), observation_note=observation_note,
        previous_nodes=previous_nodes,
    )
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_msg},
    ]

    for attempt in range(max_retries + 1):
        raw = llm.complete(messages, temperature=0)
        # === DIAG (temporary): log the raw model reply (thinking + instruction) ===
        logger.info(
            "DIAG llm_reply session=%s repr=%r",
            session_id, raw[:2000],
        )
        # === END DIAG ===
        # Instruction sits after the thinking block; take the last JSON object.
        obj = extract_last_json(raw)

        if not isinstance(obj, dict):
            if attempt < max_retries:
                messages.append({"role": "assistant", "content": raw[:2000]})
                messages.append({
                    "role": "user",
                    "content": "你的输出不是一个合法 JSON 对象。请仅输出一个 JSON 对象，不要任何文字。",
                })
                continue
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
            action_type = obj.get("type")
            is_terminal_reply = action_type == "reply"
            is_done = action_type == "done"
            # reply 是面向用户的终态文本，不应下发给 Android 作为可执行动作。
            actions = [] if is_done or is_terminal_reply else [obj]
            resp = {
                "protocol_version": PROTOCOL_VERSION,
                "session_id": session_id,
                "done": is_done or is_terminal_reply,
                "reply": obj.get("text", "") if is_terminal_reply else "",
                "actions": actions,
                "need_observation": not (is_done or is_terminal_reply),
            }
        else:
            if attempt < max_retries:
                messages.append({"role": "assistant", "content": raw[:2000]})
                messages.append({
                    "role": "user",
                    "content": "你的输出缺少可识别的动作字段（type/actions/done）。请重新输出合法 JSON。",
                })
                continue
            return _safe_response(session_id, f"LLM 输出缺少可识别的动作字段：{obj}")

        _normalize_known_node_ids(resp, nodes)
        _enforce_sensitive_confirmation(resp, nodes)
        errs = validate(resp, "agent_response")
        if not errs:
            return resp
        if attempt < max_retries:
            messages.append({"role": "assistant", "content": raw[:2000]})
            messages.append({
                "role": "user",
                "content": "你的输出未通过 schema 校验：" + "; ".join(errs[:5])
                + "。请修正后仅输出一个合法 JSON 对象。其中 target_node_id 必须是节点序号数字，"
                + "text 必须是字符串。",
            })
            continue
        return _safe_response(session_id, "LLM 响应未通过 schema 校验：" + "; ".join(errs[:5]))


def run_decision_loop(
    *,
    llm,
    session_id: str,
    intent: str,
    ui_xml: str,
    app: Optional[str] = None,
    activity: Optional[str] = None,
    max_steps: int = 8,
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
            ui_xml=current_xml, history=history, app=app, activity=activity,
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
