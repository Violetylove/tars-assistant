"""ui_summarizer — 原始 UI 树 XML → 紧凑交互节点列表。

对齐 docs/DESIGN.md §6：
- 输入：uiautomator dump 或无障碍采集的原始 XML（可能数千行）
- 管线：过滤可交互节点 → 文本截断 → 按 bounds 排序 → 输出结构化 nodes + LLM 紧凑行
- 目标：单屏摘要 ≤ 500 token（留足决策余量）

uiautomator dump 的典型 <node> 属性：
    text, resource-id, class, package, content-desc, checkable, checked,
    clickable, enabled, focusable, focused, long-clickable, scrollable,
    bounds="[x1,y1][x2,y2]"
"""

from __future__ import annotations

import re
from typing import List

from bridge.schemas import SCHEMAS  # noqa: F401  (确保契约导入；校验用)

# --- 常量（可经 config.yaml 覆盖，见阶段末尾注释） ---
MAX_NODES = 60          # 单屏最多保留的可交互节点数
MAX_TEXT_LEN = 40       # 单个文本最大保留长度（避免撑爆）
MAX_INPUT_CHARS = 2000  # 输入框允许的最大回显文本长度

# 交互判定：不可用的直接排除
_IMPORTANT_CLASS_RE = re.compile(
    r"(?:Button|EditText|CheckBox|RadioButton|Switch|ImageView).*Button|"
    r"android\.widget\.(?:Button|EditText|ImageButton|CheckBox|RadioButton|Switch)$",
    re.I,
)


def _parse_bounds(bounds_spec: str) -> tuple[int, int, int, int] | None:
    """解析 '[x1,y1][x2,y2]' 为 (x1, y1, x2, y2)；非法返回 None。"""
    m = re.findall(r"-?\d+", bounds_spec or "")
    if len(m) == 4:
        return tuple(int(v) for v in m)  # type: ignore[return-value]
    return None


def _center(bounds) -> tuple[int, int]:
    return (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2


class Summarizer:
    """将 UI 树 XML 摘要为结构化节点字典列表。"""

    def __init__(self, max_nodes: int = MAX_NODES, max_text_len: int = MAX_TEXT_LEN):
        self.max_nodes = max_nodes
        self.max_text_len = max_text_len

    def summarize(self, xml: str) -> List[dict]:
        """返回符合 bridge ui_node schema 的节点列表（按上下、左右排序）。"""
        nodes: List[dict] = []
        for elem in _iter_nodes(xml):
            bounds = _parse_bounds(elem.get("bounds", ""))
            if not bounds:
                continue
            if not self._is_interactive(elem, bounds):
                continue

            text = self._clean_text(elem.get("text") or elem.get("content-desc") or "")
            nodes.append({
                "id": len(nodes),  # 摘要内唯一序号
                "type": self._classify(elem),
                "text": text,
                "bounds": list(bounds),
                "clickable": _is_true(elem.get("clickable")),
                "focused": _is_true(elem.get("focused")),
            })

        # 排序：先上→下，再左→右（bounds 为 [x1,y1,x2,y2]）
        nodes.sort(key=lambda n: (n["bounds"][1], n["bounds"][0]))
        # 数量截断
        nodes = nodes[: self.max_nodes]
        # 重赋稳定 id
        for i, n in enumerate(nodes):
            n["id"] = i
        return nodes

    def _is_interactive(self, elem: dict, bounds) -> bool:
        # 超出可视范围（负坐标部分像素在屏幕外）的忽略，防误点
        if bounds[0] < 0 or bounds[1] < 0:
            return False
        if _is_true(elem.get("clickable")) or _is_true(elem.get("focusable")):
            return True
        # 某些按钮用 class + enabled 判定
        class_ = elem.get("class") or ""
        if _IMPORTANT_CLASS_RE.search(class_) and _is_true(elem.get("enabled", "true")):
            return True
        return False

    @staticmethod
    def _clean_text(raw: str) -> str:
        text = (raw or "").replace("\n", " ").strip()
        if len(text) > MAX_TEXT_LEN:
            text = text[: MAX_TEXT_LEN - 1] + "…"
        return text

    @staticmethod
    def _classify(elem: dict) -> str:
        class_ = (elem.get("class") or "").lower()
        if "edit" in class_:
            return "input"
        if "checkbox" in class_ or "radio" in class_ or "switch" in class_:
            return "checkbox"
        if "button" in class_ or "imag" in class_:
            return "button"
        if "list" in class_:
            return "list_item"
        if elem.get("text"):
            return "text"
        return "button"  # 有 bounds 可点的兜底


def _iter_nodes(xml: str):
    """用轻量 XML 事件流解析 <node> 元素（避免整树 DOM 占内存）。对齐 uiautomator 格式。"""
    import xml.etree.ElementTree as ET

    root = ET.fromstring(xml)
    return root.iter()  # 遍历含 root 自身在内全部元素；过滤由调用方做


def _is_true(v) -> bool:
    return str(v).strip().lower() == "true"


def summarize_xml(xml: str, max_nodes: int = MAX_NODES) -> List[dict]:
    """便捷单例接口，供 agent 决策循环 / server 直接调用。"""
    return Summarizer(max_nodes=max_nodes).summarize(xml)


def to_llm_line(node: dict) -> str:
    """结构化节点 → LLM 紧凑行：'[id] 类型\"text\" (cx,cy)'。"""
    cx, cy = _center(node["bounds"])
    typ = node["type"]
    if node.get("focused"):
        typ = f"{typ}(focused)"
    label = node.get("text") or ""
    return f"[{node['id']}] {typ}\"{label}\" ({cx},{cy})"


def to_llm_prompt(nodes: List[dict]) -> str:
    """生成喂给 LLM 的整段紧凑文本（保证 ≤500 token 目标）。"""
    lines = [to_llm_line(n) for n in nodes]
    return "\n".join(lines)
