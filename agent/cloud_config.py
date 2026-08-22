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
    max_retries: int
    retry_backoff_seconds: float


def load_cloud_config(path: str | Path) -> CloudConfig:
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    llm = data.get("llm") or {}
    config = CloudConfig(
        base_url=str(llm.get("base_url", "")).rstrip("/"),
        model=str(llm.get("model", "")),
        api_key=str(llm.get("api_key", "")),
        timeout_seconds=float(llm.get("timeout_seconds", 60)),
        max_retries=int(llm.get("max_retries", 2)),
        retry_backoff_seconds=float(llm.get("retry_backoff_seconds", 1)),
    )
    if not config.base_url.startswith("https://"):
        raise ValueError("llm.base_url must use HTTPS")
    if not config.model or config.model.startswith("replace-with"):
        raise ValueError("llm.model must be configured")
    if len(config.api_key) < 8 or config.api_key.startswith("REPLACE_"):
        raise ValueError("llm.api_key must be configured")
    if config.timeout_seconds <= 0:
        raise ValueError("llm.timeout_seconds must be positive")
    if not 0 <= config.max_retries <= 3:
        raise ValueError("llm.max_retries must be between 0 and 3")
    if not 0 <= config.retry_backoff_seconds <= 10:
        raise ValueError("llm.retry_backoff_seconds must be between 0 and 10")
    return config
