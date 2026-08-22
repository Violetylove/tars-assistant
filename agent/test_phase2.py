"""阶段 2 单测：ui_summarizer / llm_client / agent_loop / server。"""

import json

import pytest
from fastapi.testclient import TestClient

from agent.ui_summarizer import summarize_xml, to_llm_prompt
from agent.llm_client import MockLLM, extract_json
from agent.agent_loop import decide_once, run_decision_loop
from agent import server

# --- 夹具：UIAutomator 格式 XML ---

SIMPLE_XML = """<?xml version="1.0" encoding="utf-8"?>
<hierarchy>
  <node text="微信" resource-id="" class="android.widget.TextView"
        bounds="[50,80][350,180]" clickable="false"/>
  <node text="" resource-id="com.tencent.mm:id/search"
        class="android.widget.EditText" bounds="[40,200][760,300]"
        focusable="true" focused="true"/>
  <node text="发送" resource-id="com.tencent.mm:id/send"
        class="android.widget.Button" bounds="[240,900][600,990]"
        clickable="true"/>
  <node text="勿扰模式已开" resource-id="" class="android.widget.FrameLayout"
        bounds="[0,10][1080,60]" clickable="false"/>  <!-- 应被过滤 -->
</hierarchy>
"""


def _fresh_server():
    import importlib
    importlib.reload(server)
    return server


# ===== ui_summarizer =====

def test_summarize_filters_and_sorts():
    nodes = summarize_xml(SIMPLE_XML)
    # 过滤：TextView(不可点) 与 FrameLayout(不可点) 应被剔除
    assert len(nodes) == 2  # EditText + Button
    # 排序：EditText(y=200) 在 Button(y=900) 之上
    assert nodes[0]["type"] == "input"
    assert nodes[1]["type"] == "button"
    assert nodes[0]["focused"] is True


def test_summarize_button_fields():
    nodes = summarize_xml(SIMPLE_XML)
    btn = [n for n in nodes if n["type"] == "button"][0]
    assert btn["text"] == "发送"
    assert btn["bounds"] == [240, 900, 600, 990]


def test_summarize_accepts_missing_ui_tree_as_empty_nodes():
    assert summarize_xml("") == []
    assert summarize_xml("   ") == []


def test_to_llm_prompt_compact():
    nodes = summarize_xml(SIMPLE_XML)
    prompt = to_llm_prompt(nodes)
    assert "[0] input" in prompt
    assert "(400,250)" in prompt  # EditText 中心 x=(40+760)/2=400 y=(200+300)/2=250
    assert len(prompt.split()) <= 500


# ===== llm_client.extract_json =====

def test_extract_json_from_fence():
    raw = '好的，请执行：\n```json\n{"type":"click","target_node_id":0}\n```'
    assert extract_json(raw) == {"type": "click", "target_node_id": 0}


def test_extract_json_from_plain():
    assert extract_json('{"type":"back"}') == {"type": "back"}


def test_extract_json_invalid():
    assert extract_json("抱歉，我无法操作。") is None
    assert extract_json("nothing here") is None


# ===== agent_loop =====

def test_decide_once_valid_action():
    llm = MockLLM(script=[lambda: json.dumps({"type": "click", "target_node_id": 1})])
    resp = decide_once(llm=llm, session_id="s1", intent="点击发送", ui_xml=SIMPLE_XML)
    assert resp["done"] is False
    assert resp["actions"][0]["type"] == "click"
    assert resp["actions"][0]["target_node_id"] == 1
    assert resp["need_observation"] is True


def test_decide_once_marks_sensitive_click_for_confirmation():
    llm = MockLLM(script=[lambda: json.dumps({"type": "click", "target_node_id": 1, "requires_confirmation": False})])
    resp = decide_once(llm=llm, session_id="s1", intent="点击发送", ui_xml=SIMPLE_XML)
    assert resp["actions"][0]["requires_confirmation"] is True


def test_decide_once_rejects_invalid_json():
    llm = MockLLM(script=[lambda: "我完全听不懂"])
    resp = decide_once(llm=llm, session_id="s1", intent="x", ui_xml=SIMPLE_XML)
    assert resp["actions"] == []
    assert "无法解析" in resp["reply"]


def test_decide_once_rejects_bad_action():
    # type 不在枚举 → 被安全拒绝（LLM 输出不可信防线）
    llm = MockLLM(script=[lambda: json.dumps({"type": "rm_rf", "target_node_id": 1})])
    resp = decide_once(llm=llm, session_id="s1", intent="x", ui_xml=SIMPLE_XML)
    assert resp["actions"] == []
    assert ("校验" in resp["reply"]) or ("拒绝" in resp["reply"])


def test_run_decision_loop_terminates_on_done():
    llm = MockLLM(script=[lambda: json.dumps({"type": "done"})])
    resp = run_decision_loop(llm=llm, session_id="s1", intent="完成", ui_xml=SIMPLE_XML)
    assert resp["done"] is True


def test_run_decision_loop_stops_on_no_action():
    llm = MockLLM(script=[lambda: json.dumps({"type": "reply", "text": "需要你手动确认"})])
    resp = run_decision_loop(llm=llm, session_id="s1", intent="问", ui_xml=SIMPLE_XML)
    assert resp["done"] is False
    assert resp["actions"] == []


def test_run_decision_loop_caps_steps():
    # 永不 done → 达 max_steps 停止
    llm = MockLLM(script=[lambda: json.dumps({"type": "click", "target_node_id": 0})])
    resp = run_decision_loop(llm=llm, session_id="s1", intent="无限点",
                             ui_xml=SIMPLE_XML, max_steps=3)
    assert "最大步数" in resp["reply"]


# ===== server =====

def test_server_health():
    srv = _fresh_server()
    assert srv.health().get("status") == "ok"
    assert srv.health().get("protocol_version") == "1.0"


def test_server_rejects_bad_request():
    srv = _fresh_server()
    client = TestClient(srv.app)
    bad = {"intent": "缺 protocol_version 和 session_id"}
    r = client.post("/agent/run", json=bad)
    assert r.status_code == 400
    assert "task_request 校验失败" in r.json()["detail"]


def test_server_unconfigured_runtime_fails_closed():
    srv = _fresh_server()
    response = srv.agent_run({
        "protocol_version": "1.0",
        "session_id": "unconfigured",
        "intent": "测试",
    })
    assert response["actions"] == []
    assert "未配置" in response["reply"]


def test_server_run_with_mock_decision():
    srv = _fresh_server()
    srv.decision_fn = lambda **kw: {
        "protocol_version": "1.0",
        "session_id": kw["session_id"],
        "done": True,
        "reply": "OK 已点击",
        "actions": [{"type": "click", "target_node_id": 1}],
    }
    client = TestClient(srv.app)
    req = {
        "protocol_version": "1.0",
        "session_id": "abc",
        "intent": "点击发送",
        "ui_xml": SIMPLE_XML,
    }
    r = client.post("/agent/run", json=req)
    assert r.status_code == 200
    body = r.json()
    assert body["done"] is True
    assert body["reply"] == "OK 已点击"


def test_server_configure_mock_runtime_runs_a_valid_response():
    srv = _fresh_server()
    srv.configure_runtime(mock=True)
    response = srv.agent_run({
        "protocol_version": "1.0",
        "session_id": "mock-runtime",
        "intent": "完成测试",
        "ui_xml": SIMPLE_XML,
        "history": [],
    })
    assert response["done"] is True
    assert response["actions"] == []
    assert response["reply"] == "协议联调完成（mock，未调用本地模型）"


def test_server_accepts_request_without_ui_tree_in_mock_mode():
    srv = _fresh_server()
    srv.configure_runtime(mock=True)
    response = srv.agent_run({
        "protocol_version": "1.0",
        "session_id": "mock-empty-ui",
        "intent": "完成测试",
    })
    assert response["done"] is True
    assert response["actions"] == []


def test_server_routes_allowlisted_open_app_skill_without_model():
    srv = _fresh_server()
    response = srv.agent_run({
        "protocol_version": "1.0",
        "session_id": "open-settings",
        "intent": "打开设置",
    })
    assert response["done"] is True
    assert response["actions"] == [{"type": "launch", "package_name": "com.android.settings"}]


def test_server_routes_english_open_settings_skill_without_model():
    srv = _fresh_server()
    response = srv.agent_run({
        "protocol_version": "1.0",
        "session_id": "open-settings-en",
        "intent": "open settings",
    })
    assert response["done"] is True
    assert response["actions"] == [{"type": "launch", "package_name": "com.android.settings"}]
