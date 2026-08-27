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


def test_task_request_accepts_seven_history_entries():
    req = _load("task_request.json")
    req["history"] = [{"actions": []}] * 7
    assert validate(req, "task_request") == []


def test_task_request_accepts_history_beyond_old_seven_limit():
    # 轮数上限由 Android 侧用户设置决定（1-20），协议不再对 history 设上限。
    req = _load("task_request.json")
    req["history"] = [{"actions": []}] * 8
    assert validate(req, "task_request") == []


def test_task_request_accepts_history_up_to_user_rounds():
    req = _load("task_request.json")
    req["history"] = [{"actions": [{"type": "click", "target_node_id": 0}]}] * 20
    assert validate(req, "task_request") == []


def test_task_request_accepts_summarized_nodes():
    req = _load("task_request.json")
    req["nodes"] = [{
        "id": 0,
        "_resource_id": "com.android.settings:id/entry",
        "type": "button",
        "text": "设置",
        "bounds": [0, 0, 100, 50],
        "clickable": True,
        "focusable": False,
        "focused": False,
        "layer": 0,
        "depth": 0,
        "container": "",
    }]
    assert validate(req, "task_request") == []


def test_task_request_rejects_oversized_nodes():
    req = _load("task_request.json")
    req["nodes"] = [{"id": i, "type": "button", "clickable": True} for i in range(61)]
    assert validate(req, "task_request")


def test_task_request_rejects_invalid_node():
    req = _load("task_request.json")
    req["nodes"] = [{"id": 0, "type": "button"}]  # 缺 clickable
    assert validate(req, "task_request")


def test_task_request_accepts_observation_note():
    req = _load("task_request.json")
    req["observation_note"] = "上一轮动作未使界面发生变化"
    assert validate(req, "task_request") == []


def test_task_request_rejects_oversized_observation_note():
    req = _load("task_request.json")
    req["observation_note"] = "x" * 501
    assert validate(req, "task_request")


def test_task_request_accepts_user_selected_launchable_apps():
    req = _load("task_request.json")
    req["launchable_apps"] = [{"label": "Gmail", "package_name": "com.google.android.gm"}]
    assert validate(req, "task_request") == []


def test_task_request_rejects_invalid_launchable_app_entry():
    req = _load("task_request.json")
    req["launchable_apps"] = [{"label": "Gmail"}]
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


def test_action_type_specific_required_fields():
    invalid_actions = [
        {"type": "click"},
        {"type": "type", "target_node_id": 1},
        {"type": "swipe", "x1": 0, "y1": 0, "x2": 1, "y2": 1},
        {"type": "wait"},
        {"type": "reply"},
        {"type": "launch"},
    ]
    for action in invalid_actions:
        assert validate_action(action), f"应拒绝不完整 action: {action}"
