# 阶段3 端到端：真实模型驱动完整决策链（decision -> schema 校验 -> action）
# 用 SIMPLE_XML 那样的 UI 场景，但喂一个真实可点击场景
import json, sys

from agent.llm_client import LLMClient
from agent.agent_loop import decide_once

XML = """<?xml version="1.0" encoding="utf-8"?>
<hierarchy>
  <node text="微信" class="android.widget.TextView" bounds="[50,80][350,180]" clickable="false"/>
  <node text="" class="android.widget.EditText" bounds="[40,200][760,300]" focusable="true" focused="true"/>
  <node text="发送" class="android.widget.Button" bounds="[240,900][600,990]" clickable="true"/>
</hierarchy>
"""

llm = LLMClient(base_url="http://127.0.0.1:11434/v1", model="qwen2.5-3b", timeout=180)
resp = decide_once(llm=llm, session_id="real-model-test", intent="点击屏幕上的发送按钮", ui_xml=XML)
print(json.dumps({"done": resp["done"],
                  "reply": resp["reply"],
                  "actions": resp["actions"],
                  "need_observation": resp["need_observation"]}, ensure_ascii=False, indent=2))
print("== 决策合法 ==" if not resp["reply"].startswith(("LLM", "非法")) else "== 决策被拒 ==")
