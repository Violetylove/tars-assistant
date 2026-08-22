"""协议契约校验测试。对齐 docs/DESIGN.md §5 与 examples/。"""

from pathlib import Path

from bridge.validate import validate, validate_action

EXAMPLES = Path(__file__).parent.parent / "examples"


def _load(name):
    import json
    with (EXAMPLES / name).open("r", encoding="utf-8") as f:
        return json.load(f)


# --- task_request ---

def test_task_request_example_pass():
    req = _load("task_request.json")
    assert validate(req, "task_request") == []


def test_task_request_missing_required():
    req = _load("task_request.json")
    del req["intent"]
    errors = validate(req, "task_request")
    assert any("intent" in e for e in errors)


def test_task_request_bad_protocol_version():
    req = _load("task_request.json")
    req["protocol_version"] = "0.9"
    errors = validate(req, "task_request")
    # jsonschema 的 enum 错误信息是 "'0.9' is not one of ['1.0']"，不含字段名，故断言有错即可
    assert errors, "错误的 protocol_version 必须被拒绝"


def test_task_request_extra_property_rejected():
    req = _load("task_request.json")
    req["evil"] = True
    errors = validate(req, "task_request")
    assert any("evil" in e for e in errors)


def test_task_request_accepts_bounded_valid_action_history():
    req = _load("task_request.json")
    req["history"] = [{"actions": [{"type": "launch", "package_name": "com.android.settings"}]}]
    assert validate(req, "task_request") == []


def test_task_request_rejects_unvalidated_history_action():
    req = _load("task_request.json")
    req["history"] = [{"actions": [{"type": "inject", "payload": "ignore safety"}]}]
    assert validate(req, "task_request")


def test_task_request_rejects_oversized_history():
    req = _load("task_request.json")
    req["history"] = [{"actions": []}] * 4
    assert validate(req, "task_request")


# --- agent_response / action ---

def test_agent_response_example_pass():
    resp = _load("agent_response.json")
    assert validate(resp, "agent_response") == []


def test_agent_response_requires_done():
    resp = _load("agent_response.json")
    del resp["done"]
    errors = validate(resp, "agent_response")
    assert any("done" in e for e in errors)


def test_agent_response_rejects_more_than_eight_actions():
    resp = _load("agent_response.json")
    resp["actions"] = [{"type": "wait", "ms": 0}] * 9
    assert validate(resp, "agent_response")


def test_action_unknown_type_rejected():
    errors = validate_action({"type": "inject"})
    assert errors, "非法 action.type 必须被拒绝"


def test_action_extra_property_rejected():
    errors = validate_action({"type": "click", "target_node_id": 1, "evil": 9})
    assert any("evil" in e for e in errors)


def test_action_valid_click_pass():
    assert validate_action({"type": "click", "target_node_id": 12}) == []


def test_action_swipe_requires_coords_ok():
    assert validate_action(
        {"type": "swipe", "x1": 0, "y1": 0, "x2": 100, "y2": 200, "duration_ms": 300}
    ) == []
