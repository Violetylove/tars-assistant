"""阶段 2 单测：ui_summarizer / llm_client / agent_loop / server。"""

import json

import pytest
import requests
from fastapi.testclient import TestClient

from agent.ui_summarizer import summarize_xml, to_llm_context, to_llm_prompt
from agent.llm_client import CloudRequestError, LLMClient, MockLLM, extract_json
from agent.agent_loop import _build_user_message, decide_once, run_decision_loop
from agent import server
from agent.cloud_config import load_cloud_config

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

COMPOSITE_CONTROL_XML = """<?xml version="1.0" encoding="utf-8"?>
<hierarchy>
  <node text="" content-desc="" class="android.view.ViewGroup"
        bounds="[20,100][800,240]" clickable="true">
    <node text="Project Atlas" content-desc="" class="android.widget.TextView"
          bounds="[40,120][500,170]" clickable="false"/>
    <node text="" content-desc="Synchronized" class="android.widget.TextView"
          bounds="[40,180][500,220]" clickable="false"/>
  </node>
  <node text="Continue" content-desc="" class="android.widget.Button"
        bounds="[20,300][400,380]" clickable="true"/>
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


def test_summarize_preserves_descendant_labels_for_composite_control():
    nodes = summarize_xml(COMPOSITE_CONTROL_XML)
    # 只保留原有两个可操作节点；后代文本仅增强父节点语义，不生成新动作 ID。
    assert [(node["id"], node["bounds"]) for node in nodes] == [
        (0, [20, 100, 800, 240]),
        (1, [20, 300, 400, 380]),
    ]
    assert nodes[0]["text"] == "Project Atlas / Synchronized"
    assert nodes[1]["text"] == "Continue"


def test_summarize_accepts_missing_ui_tree_as_empty_nodes():
    assert summarize_xml("") == []
    assert summarize_xml("   ") == []


def test_summarize_occludes_lower_layer_fully_covered_nodes():
    # 顶层 window layer=0：按钮 A 占据 [0,0][500,500]；底层 window layer=1：
    # 按钮 B 位于 [100,100][300,300]（被 A 完全覆盖，应剔除），按钮 C 位于
    # [600,100][900,300]（未被覆盖，应保留）。
    xml = (
        '<hierarchy>'
        '<window layer="0">'
        '<node text="A" class="android.widget.Button" bounds="[0,0][500,500]" clickable="true"/>'
        '</window>'
        '<window layer="1">'
        '<node text="B" class="android.widget.Button" bounds="[100,100][300,300]" clickable="true"/>'
        '<node text="C" class="android.widget.Button" bounds="[600,100][900,300]" clickable="true"/>'
        '</window>'
        '</hierarchy>'
    )
    nodes = summarize_xml(xml)
    labels = [n["text"] for n in nodes]
    assert "A" in labels
    assert "B" not in labels  # 被上层 A 完全覆盖，视觉不可见
    assert "C" in labels


def test_to_llm_prompt_compact():
    nodes = summarize_xml(SIMPLE_XML)
    prompt = to_llm_prompt(nodes)
    assert "[0] input" in prompt
    assert "(400,250)" in prompt  # EditText 中心 x=(40+760)/2=400 y=(200+300)/2=250
    assert len(prompt.split()) <= 500


def test_decision_prompt_includes_optional_foreground_context():
    message = _build_user_message(
        "查看设置", summarize_xml(SIMPLE_XML), [],
        app="com.android.settings", activity="com.android.settings.Settings",
    )
    assert "当前前台应用包名：com.android.settings" in message
    assert "当前前台窗口类名：com.android.settings.Settings" in message


def test_structural_context_preserves_raw_window_and_node_facts():
    xml = (
        '<hierarchy><window layer="0"><node package="com.google.android.gm" '
        'resource-id="peoplekit_autocomplete_results_recyclerview" '
        'class="android.support.v7.widget.RecyclerView" bounds="[0,610][1080,1496]">'
        '<node text="violetylove@163.com" class="android.widget.TextView" '
        'clickable="true" bounds="[198,778][1036,894]"/></node></window></hierarchy>'
    )
    context = to_llm_context(xml)
    assert "window#0" in context
    assert "peoplekit_autocomplete_results_recyclerview" in context
    assert "violetylove@163.com" in context
    assert "bounds='[198,778][1036,894]'" in context


# ===== llm_client.extract_json =====

def test_extract_json_from_fence():
    raw = '好的，请执行：\n```json\n{"type":"click","target_node_id":0}\n```'
    assert extract_json(raw) == {"type": "click", "target_node_id": 0}


def test_extract_json_from_plain():
    assert extract_json('{"type":"back"}') == {"type": "back"}


def test_summarizer_keeps_zero_size_interactive_nodes_for_id_alignment():
    xml = (
        '<hierarchy><node text="零尺寸" class="android.widget.TextView" '
        'clickable="true" bounds="[0,0][0,0]"/>'
        '<node text="后续按钮" class="android.widget.Button" '
        'clickable="true" bounds="[10,10][110,110]"/></hierarchy>'
    )
    nodes = summarize_xml(xml)
    assert [node["text"] for node in nodes] == ["零尺寸", "后续按钮"]
    assert [node["id"] for node in nodes] == [0, 1]


def test_extract_json_invalid():
    assert extract_json("抱歉，我无法操作。") is None
    assert extract_json("nothing here") is None


class _FakeResponse:
    def __init__(self, status_code: int = 200, content: str = '{"type":"done"}'):
        self.status_code = status_code
        self._content = content

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.exceptions.HTTPError(response=self)

    def json(self):
        return {"choices": [{"message": {"content": self._content}}]}


class _ScriptedRequests:
    exceptions = requests.exceptions

    def __init__(self, outcomes):
        self.outcomes = iter(outcomes)
        self.calls = []

    def post(self, *args, **kwargs):
        self.calls.append((args, kwargs))
        outcome = next(self.outcomes)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


def _client_for_retry_test(outcomes, *, max_retries=2, backoff=0.25):
    waits = []
    client = LLMClient(
        base_url="https://api.example.com/v1", model="test-model", api_key="test-secret-key",
        timeout=30, max_retries=max_retries, retry_backoff_seconds=backoff, sleep_fn=waits.append,
    )
    scripted = _ScriptedRequests(outcomes)
    client._requests = scripted
    return client, scripted, waits


def test_llm_client_retries_timeout_then_returns_completion():
    client, scripted, waits = _client_for_retry_test([
        requests.exceptions.Timeout("timed out"), _FakeResponse(content='{"type":"done"}'),
    ])
    assert client.complete([{"role": "user", "content": "test"}]) == '{"type":"done"}'
    assert len(scripted.calls) == 2
    assert waits == [0.25]


def test_llm_client_retries_rate_limit_with_exponential_backoff():
    client, scripted, waits = _client_for_retry_test([
        _FakeResponse(status_code=429), _FakeResponse(status_code=503), _FakeResponse(content="ok"),
    ])
    assert client.complete([]) == "ok"
    assert len(scripted.calls) == 3
    assert waits == [0.25, 0.5]


def test_llm_client_does_not_retry_authentication_error():
    client, scripted, waits = _client_for_retry_test([_FakeResponse(status_code=401)])
    with pytest.raises(CloudRequestError, match="HTTP 401"):
        client.complete([])
    assert len(scripted.calls) == 1
    assert waits == []


def test_llm_client_reports_exhausted_transient_retries_without_secret():
    client, scripted, waits = _client_for_retry_test([
        requests.exceptions.ConnectionError("offline"),
        requests.exceptions.ConnectionError("offline"),
        requests.exceptions.ConnectionError("offline"),
    ])
    with pytest.raises(CloudRequestError, match="已重试 2 次") as exc_info:
        client.complete([])
    assert "test-secret-key" not in str(exc_info.value)
    assert len(scripted.calls) == 3
    assert waits == [0.25, 0.5]


# ===== agent_loop =====

def test_decide_once_valid_action():
    llm = MockLLM(script=[lambda: json.dumps({"type": "click", "target_node_id": 1})])
    resp = decide_once(llm=llm, session_id="s1", intent="点击发送", ui_xml=SIMPLE_XML)
    assert resp["done"] is False
    assert resp["actions"][0]["type"] == "click"
    assert resp["actions"][0]["target_node_id"] == 1
    assert resp["need_observation"] is True


def test_decide_once_normalizes_a_known_quoted_node_id():
    llm = MockLLM(script=[lambda: json.dumps({"type": "click", "target_node_id": "1"})])
    resp = decide_once(llm=llm, session_id="s1", intent="点击发送", ui_xml=SIMPLE_XML)
    assert resp["actions"] == [{"type": "click", "target_node_id": 1, "requires_confirmation": True}]


@pytest.mark.parametrize("target_node_id", ["button", " 1", "1.0", "999"])
def test_decide_once_rejects_ambiguous_or_unknown_quoted_node_id(target_node_id):
    llm = MockLLM(script=[lambda: json.dumps({"type": "click", "target_node_id": target_node_id})])
    resp = decide_once(
        llm=llm, session_id="s1", intent="点击发送", ui_xml=SIMPLE_XML, max_retries=0,
    )
    assert resp["actions"] == []
    assert "schema 校验" in resp["reply"]


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


def test_decide_once_turns_single_reply_into_a_terminal_response():
    llm = MockLLM(script=[lambda: json.dumps({"type": "reply", "text": "需要你手动确认"})])
    resp = decide_once(llm=llm, session_id="s1", intent="问", ui_xml=SIMPLE_XML)
    assert resp["done"] is True
    assert resp["reply"] == "需要你手动确认"
    assert resp["actions"] == []
    assert resp["need_observation"] is False


def test_run_decision_loop_stops_on_single_reply():
    llm = MockLLM(script=[lambda: json.dumps({"type": "reply", "text": "需要你手动确认"})])
    resp = run_decision_loop(llm=llm, session_id="s1", intent="问", ui_xml=SIMPLE_XML)
    assert resp["done"] is True
    assert resp["reply"] == "需要你手动确认"


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


def test_server_rejects_invalid_nonempty_ui_xml():
    srv = _fresh_server()
    client = TestClient(srv.app)
    r = client.post("/agent/run", json={
        "protocol_version": "1.0",
        "session_id": "bad-ui",
        "intent": "测试",
        "ui_xml": "<hierarchy>",
    })
    assert r.status_code == 400
    assert "不是合法 XML" in r.json()["detail"]


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


def test_server_rejects_invalid_decision_response_before_returning_to_android():
    srv = _fresh_server()
    srv.decision_fn = lambda **kwargs: {
        "protocol_version": "1.0", "session_id": kwargs["session_id"], "done": False,
        "actions": [{"type": "inject", "payload": "untrusted"}],
    }
    client = TestClient(srv.app, raise_server_exceptions=False)
    r = client.post("/agent/run", json={
        "protocol_version": "1.0", "session_id": "invalid-response", "intent": "测试",
    })
    assert r.status_code == 502
    assert "agent_response 校验失败" in r.json()["detail"]


def test_server_rejects_decision_response_for_a_different_session():
    srv = _fresh_server()
    srv.decision_fn = lambda **_kwargs: {
        "protocol_version": "1.0", "session_id": "other-session", "done": True,
        "actions": [],
    }
    client = TestClient(srv.app, raise_server_exceptions=False)
    r = client.post("/agent/run", json={
        "protocol_version": "1.0", "session_id": "expected-session", "intent": "测试",
    })
    assert r.status_code == 502
    assert "session_id 与请求不一致" in r.json()["detail"]


def test_server_passes_foreground_context_to_decision_backend():
    srv = _fresh_server()
    captured = {}
    srv.decision_fn = lambda **kwargs: captured.update(kwargs) or {
        "protocol_version": "1.0", "session_id": kwargs["session_id"], "done": True,
        "reply": "OK", "actions": [], "need_observation": False,
    }
    response = srv.agent_run({
        "protocol_version": "1.0", "session_id": "foreground-context", "intent": "查看设置",
        "app": "com.android.settings", "activity": "com.android.settings.Settings",
    })
    assert response["done"] is True
    assert captured["app"] == "com.android.settings"
    assert captured["activity"] == "com.android.settings.Settings"


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
    assert response["reply"] == "协议联调完成（mock，未调用云端模型）"


def test_server_accepts_loopback_agent_request_without_cloud_model_token():
    srv = _fresh_server()
    srv.configure_runtime(mock=True)
    client = TestClient(srv.app)
    r = client.post("/agent/run", json={
        "protocol_version": "1.0",
        "session_id": "model-not-ready",
        "intent": "普通任务",
    })
    assert r.status_code == 200


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


def test_server_routes_open_gmail_skill_without_model():
    srv = _fresh_server()
    response = srv.agent_run({
        "protocol_version": "1.0",
        "session_id": "open-gmail",
        "intent": "open gmail",
    })
    assert response["done"] is True
    assert response["actions"] == [{"type": "launch", "package_name": "com.google.android.gm"}]


def test_server_routes_open_tars_skill_to_current_application_id():
    srv = _fresh_server()
    response = srv.agent_run({
        "protocol_version": "1.0",
        "session_id": "open-tars",
        "intent": "open tars",
    })
    assert response["done"] is True
    assert response["actions"] == [{"type": "launch", "package_name": "org.atovio.tars"}]


def test_cloud_config_rejects_placeholder_values(tmp_path):
    config = tmp_path / "cloud.yaml"
    config.write_text("llm: {base_url: 'https://api.example.com/v1'}\n", encoding="utf-8")
    with pytest.raises(ValueError):
        load_cloud_config(config)


def test_cloud_config_loads_https_provider_settings(tmp_path):
    config = tmp_path / "cloud.yaml"
    config.write_text(
        "llm:\n  base_url: 'https://api.example.com/v1'\n  model: 'provider-model'\n"
        "  api_key: 'provider-secret-key'\n  timeout_seconds: 90\n"
        "  max_retries: 3\n  retry_backoff_seconds: 2\n",
        encoding="utf-8",
    )
    loaded = load_cloud_config(config)
    assert loaded.base_url == "https://api.example.com/v1"
    assert loaded.model == "provider-model"
    assert loaded.timeout_seconds == 90
    assert loaded.max_retries == 3
    assert loaded.retry_backoff_seconds == 2


@pytest.mark.parametrize("extra", ["max_retries: 4", "retry_backoff_seconds: 11", "timeout_seconds: 0"])
def test_cloud_config_rejects_out_of_bound_resilience_settings(tmp_path, extra):
    config = tmp_path / "cloud.yaml"
    config.write_text(
        "llm:\n  base_url: 'https://api.example.com/v1'\n  model: 'provider-model'\n"
        "  api_key: 'provider-secret-key'\n  " + extra + "\n",
        encoding="utf-8",
    )
    with pytest.raises(ValueError):
        load_cloud_config(config)
