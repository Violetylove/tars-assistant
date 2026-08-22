"""llm_client — 对云端 OpenAI-compatible 推理端点的封装。

决策 D3：云端模型经私有配置的 base_url + model 切换，不改 Agent 代码。

可 mock 性：LLMClient 定义 `complete()` 接口；`MockLLM` 实现同接口用于开发/测试
（不依赖真实模型与网络）。默认走 `requests`，仅在产品模式实例化。
"""

from __future__ import annotations

import json
import re
from typing import Any, Callable, Optional


class LLMClient:
    """调用云端 OpenAI-compatible 推理端点。"""

    def __init__(self, base_url: str,
                 model: str,
                 api_key: str, timeout: float = 60.0):
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.api_key = api_key
        self.timeout = timeout
        try:
            import requests
        except ImportError as exc:  # pragma: no cover - 环境应装有 requests
            raise RuntimeError("需要 requests 依赖：pip install requests") from exc
        self._requests = requests

    def complete(self, messages: list[dict], temperature: float = 0.0) -> str:
        """返回补全的纯文本内容。"""
        resp = self._requests.post(
            f"{self.base_url}/chat/completions",
            headers={"Authorization": f"Bearer {self.api_key}"},
            json={
                "model": self.model,
                "messages": messages,
                "temperature": temperature,
            },
            timeout=self.timeout,
        )
        resp.raise_for_status()
        data = resp.json()
        return data["choices"][0]["message"]["content"]


# --- Mock，供开发/测试，不依赖真实模型 ---

class MockLLM:
    """脚本式 LLM 回复。script 为每轮回复的可调用列表；每调用推进一轮，
    耗尽后循环最后一项。用于无模型环境下验证决策链与多轮观察。"""

    def __init__(self, script: Optional[list[Callable[[], str]]] = None):
        self._script = list(script or [MockLLM.default_action])
        self._step = 0

    def complete(self, messages: list[dict], temperature: float = 0.0) -> str:
        idx = min(self._step, len(self._script) - 1)
        self._step += 1
        return self._script[idx]()

    @staticmethod
    def default_action() -> str:
        return json.dumps({
            "type": "click",
            "target_node_id": 0,
            "requires_confirmation": False,
        })


def extract_json(text: str) -> Optional[dict]:
    """从 LLM 回复中提取第一个 JSON 对象（容忍围栏```json...```与首尾杂质）。

    "LLM 输出不可信"——这是第一道净化：只取结构化片段，喂给 schema 校验。
    """
    if not text or not text.strip():
        return None
    # 去除 markdown 围栏
    m = re.search(r"```(?:json)?\s*(.*?)```", text, re.S)
    if m:
        text = m.group(1)
    # 找第一个 { ... }，用 json.JSONDecoder 精确定位
    decoder = json.JSONDecoder()
    for i, ch in enumerate(text):
        if ch == "{":
            try:
                obj, _ = decoder.raw_decode(text[i:])
                return obj if isinstance(obj, dict) else None
            except json.JSONDecodeError:
                continue
    return None
