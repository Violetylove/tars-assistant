"""Private cloud deployment configuration loader.

允许接入自部署 OpenAI-compatible 模型：默认强制 HTTPS 并校验证书；显式开启
``llm.allow_insecure_http`` 后允许明文 HTTP 端点，``llm.verify_ssl=false`` 跳过
TLS 证书校验（自签名证书用）。两者都只在可信网络/自托管场景开启。
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

import yaml


@dataclass(frozen=True)
class CloudConfig:
    base_url: str
    model: str
    api_key: str
    timeout_seconds: float
    max_retries: int
    retry_backoff_seconds: float
    allow_insecure_http: bool
    verify_ssl: bool


def _bool(value, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


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
        allow_insecure_http=_bool(llm.get("allow_insecure_http"), False),
        verify_ssl=_bool(llm.get("verify_ssl"), True),
    )
    scheme = urlsplit(config.base_url).scheme.lower()
    if scheme not in {"http", "https"}:
        raise ValueError("llm.base_url must use http or https")
    if scheme == "http" and not config.allow_insecure_http:
        raise ValueError(
            "llm.base_url uses plain HTTP; set llm.allow_insecure_http=true only for a "
            "trusted self-hosted endpoint"
        )
    if not config.model or config.model.startswith("replace-with"):
        raise ValueError("llm.model must be configured")
    if config.api_key.startswith("REPLACE_"):
        raise ValueError("llm.api_key must be configured")
    self_hosted = config.allow_insecure_http or not config.verify_ssl
    if not self_hosted and len(config.api_key) < 8:
        raise ValueError("llm.api_key must be at least 8 characters")
    if config.timeout_seconds <= 0:
        raise ValueError("llm.timeout_seconds must be positive")
    if not 0 <= config.max_retries <= 3:
        raise ValueError("llm.max_retries must be between 0 and 3")
    if not 0 <= config.retry_backoff_seconds <= 10:
        raise ValueError("llm.retry_backoff_seconds must be between 0 and 10")
    return config
