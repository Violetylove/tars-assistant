"""Deterministic, allow-listed skills evaluated before the LLM decision loop."""

from __future__ import annotations

from typing import Optional

from bridge.schemas import PROTOCOL_VERSION

_LAUNCH_SKILLS = {
    "设置": ("com.android.settings", "正在打开系统设置"),
    "system settings": ("com.android.settings", "正在打开系统设置"),
    "settings": ("com.android.settings", "正在打开系统设置"),
    "tars": ("com.tars.assistant", "正在打开 TARS Assistant"),
    "微信": ("com.tencent.mm", "正在打开微信"),
    "wechat": ("com.tencent.mm", "正在打开微信"),
}


def route_fixed_skill(*, session_id: str, intent: str) -> Optional[dict]:
    """Return an allow-listed response for an exact open-app request, else None."""
    normalized = " ".join(intent.casefold().strip().split())
    if not (normalized.startswith("打开") or normalized.startswith("open ")):
        return None
    target = normalized.removeprefix("打开").removeprefix("open ").strip()
    skill = _LAUNCH_SKILLS.get(target)
    if skill is None:
        return None
    package_name, reply = skill
    return {
        "protocol_version": PROTOCOL_VERSION,
        "session_id": session_id,
        "done": True,
        "reply": reply,
        "actions": [{"type": "launch", "package_name": package_name}],
        "need_observation": False,
    }
