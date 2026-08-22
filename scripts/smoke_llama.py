# 阶段3 验收：验证本地 llama-server 真实推理 + LLMClient 接入
import requests

# 1) 直接调用本地 OpenAI 兼容端点
resp = requests.post(
    "http://127.0.0.1:11434/v1/chat/completions",
    json={"model": "qwen2.5-3b",
          "messages": [{"role": "user", "content": "你好，请只用两个字回复"}],
          "temperature": 0},
    timeout=180,
)
resp.raise_for_status()
d = resp.json()
content = d["choices"][0]["message"]["content"]
print("HTTP", resp.status_code)
print("本地模型回复:", content)
print("== 原生LLMClient调用 ==")
from agent.llm_client import LLMClient
client = LLMClient(base_url="http://127.0.0.1:11434/v1", model="qwen2.5-3b")
out = client.complete([{"role": "user", "content": "1+1=?，只答数字"}], temperature=0)
print("LLMClient 回复:", out)
