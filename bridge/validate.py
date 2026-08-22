"""协议校验器。

用法（CLI）：
    python -m bridge.validate                 # 校验 examples/ 下全部示例 JSON
    python -m bridge.validate <文件>...        # 校验指定文件

作为库：
    from bridge.validate import validate
    errors = validate(obj, kind="task_request")   # -> list[str]，空列表=通过
    errors = validate_action(action)              # 校验单个 action
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

from bridge.schemas import PROTOCOL_VERSION, SCHEMAS

_SCHEMA_DIR = Path(__file__).parent / ".." / "schemas"

# 每次调用都新建 Validator（schema 含 $defs 引用自身，实例间需独立 store）。
# 为性能可缓存，但契约变更场景下保持新建更安全。
def _validator(kind: str) -> Draft202012Validator:
    schema = SCHEMAS[kind]
    return Draft202012Validator(schema)


def validate(obj, kind: str) -> list[str]:
    """校验对象是否符合某契约，返回错误信息列表（空 = 通过）。"""
    if kind not in SCHEMAS:
        return [f"未知契约类型: {kind}（可选: {sorted(SCHEMAS)}）"]
    validator = _validator(kind)
    errors = [e.message for e in validator.iter_errors(obj)]
    return errors


def validate_action(action) -> list[str]:
    """校验单个 action 对象。"""
    return [e.message for e in _validator("agent_response").iter_errors(
        {"protocol_version": PROTOCOL_VERSION,
         "session_id": "x", "done": False, "actions": [action]}
    )]


def _load_file(path: Path):
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def _run_cli() -> int:
    args = sys.argv[1:]
    if args:
        paths = [Path(p) for p in args]
    else:
        # 默认校验 examples/ 下全部 *.json
        paths = sorted(Path(__file__).parent.parent.joinpath("examples").glob("*.json"))

    if not paths:
        print("未找到待校验的 JSON 文件（可用参数指定文件）。")
        return 2

    failed = 0
    for p in paths:
        if not p.exists():
            print(f"[MISS] {p}：文件不存在")
            failed += 1
            continue
        try:
            obj = _load_file(p)
        except json.JSONDecodeError as exc:
            print(f"[BAD-JSON] {p}：不是合法 JSON — {exc}")
            failed += 1
            continue

        # 根据文件名推断契约类型（task_request / agent_response / 其它）
        name = p.name.lower()
        if "request" in name:
            kind = "task_request"
        elif "response" in name:
            kind = "agent_response"
        else:
            kind = None

        if kind is None:
            print(f"[SKIP] {p}：无法从文件名推断契约类型")
            continue

        errors = validate(obj, kind)
        if errors:
            print(f"[FAIL] {p}（{kind}）")
            for e in errors:
                print(f"       - {e}")
            failed += 1
        else:
            print(f"[OK]   {p}（{kind}）")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(_run_cli())
