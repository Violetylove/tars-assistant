"""bridge — Tasker ↔ Agent 通信契约与校验。

为避免 `python -m bridge.validate` 时的 runpy 重导入警告，本包不做顶层
from ... import；按需惰性导入。
"""

from bridge.schemas import PROTOCOL_VERSION, SCHEMAS

__all__ = ["PROTOCOL_VERSION", "SCHEMAS"]
