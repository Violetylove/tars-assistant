"""Verify a running TARS Agent HTTP endpoint without requiring a real model."""

from __future__ import annotations

import argparse
import uuid

import requests


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    health = requests.get(f"{base_url}/health", timeout=10)
    health.raise_for_status()
    print("health:", health.json())

    request = {
        "protocol_version": "1.0",
        "session_id": str(uuid.uuid4()),
        "intent": "完成协议冒烟测试",
        "ui_xml": "<hierarchy><node text=\"测试\" clickable=\"true\" bounds=\"[0,0][100,100]\"/></hierarchy>",
        "history": [],
    }
    response = requests.post(f"{base_url}/agent/run", json=request, timeout=30)
    response.raise_for_status()
    payload = response.json()
    assert payload["protocol_version"] == "1.0"
    assert payload["session_id"] == request["session_id"]
    print("agent_response:", payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
