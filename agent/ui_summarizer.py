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
import xml.etree.ElementTree as ET
from typing import List

from bridge.schemas import SCHEMAS  # noqa: F401  (确保契约导入；校验用)

# --- 常量（可经 config.yaml 覆盖，见阶段末尾注释） ---
MAX_NODES = 60          # 单屏最多保留的可交互节点数
MAX_TEXT_LEN = 40       # 单个文本最大保留长度（避免撑爆）
MAX_INPUT_CHARS = 2000  # 输入框允许的最大回显文本长度

# 交互判定：不可用的直接排除。
# 与 android ActionExecutor.collect() 保持一致的 contains 语义（Kotlin 用
# className.contains("EditText") 等子串匹配），否则含自定义输入 View 的界面两端节点集合
# 会漂移、action ID 错位。
_IMPORTANT_CLASS_TOKENS = ("button", "edittext", "checkbox", "radiobutton", "switch", "imagebutton")


def _is_important_class(class_: str) -> bool:
    return any(token in class_.lower() for token in _IMPORTANT_CLASS_TOKENS)


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
        """返回符合 bridge ui_node schema 的节点列表（按上下、左右排序）。

        多图层支持：Android 采集已把多个窗口序列化为 <window layer="N"> 分组，
        顶层（layer 最小）在视觉上覆盖底层。这里按 layer 从上到下做遮挡剔除：
        上层可交互节点的 bounds 占据区域即视为覆盖下层；下层节点 bounds 被
        任一已覆盖矩形完全包含则剔除（视觉不可见），与执行侧 collectVisibleNodes 一致。
        """
        root = ET.fromstring(xml)
        # 屏幕尺寸（窗口图层/区域事实见 to_llm_context 对 <window-info> 的解析）。
        screen_w = _int_attr(root, "screen_w")
        screen_h = _int_attr(root, "screen_h")
        # (layer, node) 分组：解析 <window layer="N"> 层级
        layered: list[tuple[int, ET.Element]] = []
        current_layer = 0
        for elem in root.iter():
            if elem.tag == "window":
                current_layer = int(elem.get("layer", "0"))
                continue
            if elem.tag != "node":
                continue
            bounds = _parse_bounds(elem.get("bounds", ""))
            if not bounds:
                continue
            if not self._is_interactive(elem, bounds):
                continue
            layered.append((current_layer, elem))

        # 按 layer 从上到下（0 最顶）遮挡剔除
        # 遮挡只在跨图层生效：同一 layer 内节点共享同一视觉平面（透明容器不遮挡
        # 兄弟/子节点）；layer 0 的可见节点占据区域才覆盖下层（layer>0）。
        covered: list[tuple[int, int, int, int]] = []
        kept: list[tuple[int, ET.Element, tuple[int, int, int, int]]] = []
        for layer, elem in sorted(layered, key=lambda x: x[0]):
            bounds = _parse_bounds(elem.get("bounds", ""))
            is_fullscreen = bounds[2] >= 1079 and bounds[3] >= 2339
            if layer > 0 and not is_fullscreen and self._is_fully_covered(bounds, covered):
                continue  # 下层节点被上层完全覆盖 → 视觉不可见，剔除
            kept.append((layer, elem, bounds))
            if layer == 0 and not is_fullscreen:
                covered.append(bounds)  # 仅顶层非全屏节点占据区域覆盖下层

        nodes: List[dict] = []
        for _layer, elem, _bounds in kept:
            text = self._semantic_text(elem)
            nodes.append({
                "id": len(nodes),  # 摘要内唯一序号
                "type": self._classify(elem),
                "text": text,
                "bounds": list(_bounds),
                "clickable": _is_true(elem.get("clickable")),
                "focused": _is_true(elem.get("focused")),
                "layer": _layer,  # 节点所在窗口图层（z 轴），模型自行推断遮挡关系
            })

        # 排序：先上→下，再左→右（bounds 为 [x1,y1,x2,y2]）
        nodes.sort(key=lambda n: (n["bounds"][1], n["bounds"][0]))
        # 数量截断
        nodes = nodes[: self.max_nodes]
        # 重赋稳定 id
        for i, n in enumerate(nodes):
            n["id"] = i
        return nodes

    @staticmethod
    def _is_fully_covered(bounds, covered) -> bool:
        """bounds (x1,y1,x2,y2) 是否被 covered 中某矩形完全包含（含边界）。"""
        x1, y1, x2, y2 = bounds
        return any(
            cx1 <= x1 and cy1 <= y1 and cx2 >= x2 and cy2 >= y2
            for cx1, cy1, cx2, cy2 in covered
        )


    def _is_interactive(self, elem: dict, bounds) -> bool:
        # 超出可视范围（负坐标部分像素在屏幕外）的忽略，防误点
        if bounds[0] < 0 or bounds[1] < 0:
            return False
        if _is_true(elem.get("clickable")) or _is_true(elem.get("focusable")):
            return True
        # 某些按钮用 class + enabled 判定
        class_ = elem.get("class") or ""
        if _is_important_class(class_) and _is_true(elem.get("enabled", "true")):
            return True
        return False

    def _semantic_text(self, elem: ET.Element) -> str:
        """保留复合交互控件自身及可见后代的语义标签。

        Android 的动作节点仍由父节点本身决定；这里只补足其文本上下文，
        使列表项、卡片和菜单等复合控件不会因标签落在子节点而失去语义。
        """
        labels: list[str] = []
        for candidate in elem.iter():
            if candidate is not elem and not self._is_visible(candidate):
                continue
            for value in (candidate.get("text"), candidate.get("content-desc")):
                label = (value or "").replace("\n", " ").strip()
                if label and label not in labels:
                    labels.append(label)
        return self._clean_text(" / ".join(labels))

    @staticmethod
    def _is_visible(elem: ET.Element) -> bool:
        if str(elem.get("visible-to-user", "true")).strip().lower() == "false":
            return False
        bounds = _parse_bounds(elem.get("bounds", ""))
        return bounds is not None and bounds[0] >= 0 and bounds[1] >= 0

    def _clean_text(self, raw: str) -> str:
        text = (raw or "").replace("\n", " ").strip()
        if len(text) > self.max_text_len:
            text = text[: self.max_text_len - 1] + "…"
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

def _is_true(v) -> bool:
    return str(v).strip().lower() == "true"


def _int_attr(elem, name: str) -> int:
    """读取元素属性为 int；缺失/非法返回 0。"""
    raw = (elem.get(name) or "").strip()
    try:
        return int(raw) if raw else 0
    except ValueError:
        return 0


def summarize_xml(xml: str, max_nodes: int = MAX_NODES) -> List[dict]:
    """便捷单例接口，供 agent 决策循环 / server 直接调用。"""
    # 无障碍服务尚未连接时，首轮请求可能没有 UI 树；按空屏处理。
    if not (xml or "").strip():
        return []
    return Summarizer(max_nodes=max_nodes).summarize(xml)


def to_llm_line(node: dict) -> str:
    """结构化节点 → LLM 紧凑行：'[id] 类型\"text\" (cx,cy)'。"""
    cx, cy = _center(node["bounds"])
    typ = node["type"]
    if node.get("focused"):
        typ = f"{typ}(focused)"
    label = node.get("text") or ""
    layer = node.get("layer", 0)
    return f"[{node['id']}] {typ}\"{label}\" ({cx},{cy}) [层{layer}]"


def to_llm_prompt(nodes: List[dict]) -> str:
    """生成喂给 LLM 的整段紧凑文本（保证 ≤500 token 目标）。"""
    lines = [to_llm_line(n) for n in nodes]
    return "\n".join(lines)


def to_llm_context(xml: str, max_chars: int = 4500) -> str:
    """Expose bounded structural UI facts without assigning semantic roles."""
    if not (xml or "").strip():
        return ""
    root = ET.fromstring(xml)
    lines: list[str] = ["结构事实（仅原始无障碍属性，不代表语义判断）："]
    # 窗口图层/区域事实（含不在 UI 节点树里的输入法/系统/浮层窗口）——模型据此按 z 轴推断遮挡。
    win_infos = [w for w in root.iter() if w.tag == "window-info"]
    if win_infos:
        renders = []
        for w in win_infos:
            renders.append(f"{w.get('type', '?')}@层{w.get('layer', '?')} {w.get('bounds', '')}")
        lines.append(f"窗口图层（z 轴）与区域：{'; '.join(renders)}")

    def walk(elem: ET.Element, path: list[str], window: str) -> None:
        if elem.tag == "window":
            window = f"window#{elem.get('layer', '0')}"
            path = []
        if elem.tag == "node":
            text = (elem.get("text") or "").replace("\n", " ").strip()
            desc = (elem.get("content-desc") or "").replace("\n", " ").strip()
            resource_id = elem.get("resource-id") or ""
            class_name = elem.get("class") or ""
            package = elem.get("package") or ""
            bounds = elem.get("bounds") or ""
            clickable = elem.get("clickable") or "false"
            focusable = elem.get("focusable") or "false"
            focused = elem.get("focused") or "false"
            interesting = text or desc or resource_id or clickable == "true" or focusable == "true" or focused == "true"
            node_path = "/".join(path[-3:]) or "node"
            if interesting:
                attrs = " ".join(
                    f"{key}={value!r}" for key, value in (
                        ("text", text), ("desc", desc), ("id", resource_id),
                        ("class", class_name), ("pkg", package), ("bounds", bounds),
                        ("clickable", clickable), ("focusable", focusable),
                        ("focused", focused),
                    ) if value
                )
                lines.append(f"- {window} path={node_path} {attrs}")
            path = path + [resource_id or class_name or "node"]
        for child in list(elem):
            walk(child, path, window)

    walk(root, [], "hierarchy")
    return "\n".join(lines)[:max_chars]
