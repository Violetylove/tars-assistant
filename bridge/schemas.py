"""通信契约的 JSON Schema 定义。

对齐 docs/DESIGN.md §5（protocol_version: 1.0）。

契约对象：
- task_request    Tasker -> Agent：一次任务的输入
- agent_response  Agent  -> Tasker：决策结果（含待执行动作）
- action          单个动作（click/type/swipe/back/home/wait/reply/done）
- ui_tree          Agent 内部结构（调试/执行用）
"""

PROTOCOL_VERSION = "1.0"

# 允许的 action.type 集合（见 DESIGN.md §5.5）
ACTION_TYPES = {
    "click",
    "type",
    "swipe",
    "back",
    "home",
    "launch",
    "wait",
    "reply",
    "done",
}

# 敏感动作：默认 requires_confirmation=true（见 DESIGN.md §7.2）
SENSITIVE_ACTIONS = {"reply"}  # 预留；send/delete 等由高权限层约束


def _ref(name: str) -> dict:
    return {"$ref": f"#/$defs/{name}"}


# 通用子定义：契约自引用 $defs 需要内嵌到各自 schema 根。定义成字典，校验时合并。
_COMMON_DEFS = {
    "action": {
        "type": "object",
        "properties": {
            "type": {"type": "string", "enum": sorted(ACTION_TYPES)},
            "target_node_id": {"type": "integer", "minimum": 0},
            "text": {"type": "string", "maxLength": 2000},
            "x1": {"type": "number"},
            "y1": {"type": "number"},
            "x2": {"type": "number"},
            "y2": {"type": "number"},
            "duration_ms": {"type": "integer", "minimum": 0},
            "ms": {"type": "integer", "minimum": 0},
            "requires_confirmation": {"type": "boolean"},
            "package_name": {"type": "string", "minLength": 1, "maxLength": 200},
        },
        "required": ["type"],
        "additionalProperties": False,
        "allOf": [
            {
                "if": {"properties": {"type": {"const": "launch"}}},
                "then": {"required": ["package_name"]},
            },
            {
                "if": {"properties": {"type": {"const": "click"}}},
                "then": {"required": ["target_node_id"]},
            },
            {
                "if": {"properties": {"type": {"const": "type"}}},
                "then": {"required": ["target_node_id", "text"]},
            },
            {
                "if": {"properties": {"type": {"const": "swipe"}}},
                "then": {"required": ["x1", "y1", "x2", "y2", "duration_ms"]},
            },
            {
                "if": {"properties": {"type": {"const": "wait"}}},
                "then": {"required": ["ms"]},
            },
            {
                "if": {"properties": {"type": {"const": "reply"}}},
                "then": {"required": ["text"]},
            },
        ],
    },
    "ui_node": {
        "type": "object",
        "properties": {
            "id": {"type": "integer", "minimum": 0},
            "type": {"type": "string"},
            "text": {"type": "string"},
            "bounds": {
                "type": "array",
                "items": {"type": "integer"},
                "minItems": 4,
                "maxItems": 4,
            },
            "clickable": {"type": "boolean"},
            "focused": {"type": "boolean"},
        },
        "required": ["id", "type", "clickable"],
        "additionalProperties": False,
    },
    "ui_tree": {
        "type": "object",
        "properties": {
            "nodes": {
                "type": "array",
                "items": _ref("ui_node"),
            }
        },
        "required": ["nodes"],
        "additionalProperties": False,
    },
    "history_entry": {
        "type": "object",
        "properties": {
            "actions": {
                "type": "array",
                "items": _ref("action"),
                "maxItems": 8,
            },
        },
        "required": ["actions"],
        "additionalProperties": False,
    },
}


SCHEMAS = {
    "protocol_version": {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "tars/protocol/1.0",
        "title": "TARS protocol",
        "description": "通信契约 protocol_version 1.0（见 docs/DESIGN.md §5）",
        "$defs": _COMMON_DEFS,
    },
    "task_request": {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "title": "task_request",
        "type": "object",
        "properties": {
            "protocol_version": {
                "type": "string",
                "enum": [PROTOCOL_VERSION],
            },
            "session_id": {"type": "string", "minLength": 1},
            "intent": {"type": "string", "minLength": 1},
            "app": {"type": "string"},
            "activity": {"type": "string"},
            "ui_xml": {"type": "string"},
            "observation_note": {"type": "string", "maxLength": 500},
            "history": {
                "type": "array",
                "items": _ref("history_entry"),
                "maxItems": 7,
            },
        },
        "required": [
            "protocol_version",
            "session_id",
            "intent",
        ],
        "additionalProperties": False,
        "$defs": _COMMON_DEFS,
    },
    "agent_response": {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "title": "agent_response",
        "type": "object",
        "properties": {
            "protocol_version": {
                "type": "string",
                "enum": [PROTOCOL_VERSION],
            },
            "session_id": {"type": "string", "minLength": 1},
            "done": {"type": "boolean"},
            "reply": {"type": "string"},
            "actions": {
                "type": "array",
                "items": _ref("action"),
                "maxItems": 8,
            },
            "need_observation": {"type": "boolean"},
        },
        "required": [
            "protocol_version",
            "session_id",
            "done",
        ],
        "additionalProperties": False,
        "$defs": _COMMON_DEFS,
    },
}
