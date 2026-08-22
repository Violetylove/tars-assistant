"""Private cloud deployment configuration loader."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import yaml


@dataclass(frozen=True)
class CloudConfig:
    base_url: str
    model: str
    api_key: str
    timeout_seconds: float


def load_cloud_config(path: str | Path) -> CloudConfig:
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    llm = data.get("llm") or {}
    config = CloudConfig(
        base_url=str(llm.get("base_url", "")).rstrip("/"),
        model=str(llm.get("model", "")),
        api_key=str(llm.get("api_key", "")),
        timeout_seconds=float(llm.get("timeout_seconds", 60)),
    )
    if not config.base_url.startswith("https://"):
        raise ValueError("llm.base_url must use HTTPS")
    if not config.model or config.model.startswith("replace-with"):
        raise ValueError("llm.model must be configured")
    if len(config.api_key) < 8 or config.api_key.startswith("REPLACE_"):
        raise ValueError("llm.api_key must be configured")
    return config
