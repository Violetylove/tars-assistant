"""Deterministic, allow-listed skills evaluated before the LLM decision loop."""

from __future__ import annotations

from typing import Optional

from bridge.schemas import PROTOCOL_VERSION

_LAUNCH_SKILLS = {
    "设置": ("com.android.settings", "正在打开系统设置"),
    "system settings": ("com.android.settings", "正在打开系统设置"),
    "settings": ("com.android.settings", "正在打开系统设置"),
    "tars": ("org.atovio.tars", "正在打开 TARS Assistant"),
    "gmail": ("com.google.android.gm", "正在打开 Gmail"),
    "微信": ("com.tencent.mm", "正在打开微信"),
    "wechat": ("com.tencent.mm", "正在打开微信"),
}


def _response(session_id: str, reply: str, actions: list[dict], *, done: bool, observe: bool) -> dict:
    return {
        "protocol_version": PROTOCOL_VERSION,
        "session_id": session_id,
        "done": done,
        "reply": reply,
        "actions": actions,
        "need_observation": observe,
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
    return _response(session_id, reply, [{"type": "launch", "package_name": package_name}], done=True, observe=False)
