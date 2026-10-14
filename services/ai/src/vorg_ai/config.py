"""Gateway configuration, from the environment only (ADR-17, ADR-64).

Nothing here is required: without ``OPENAI_API_KEY`` the gateway runs the deterministic offline provider. The key is
never logged, never put in a repr and never written to any file by this service.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass, field

PROVIDER_MODES = ("auto", "offline")
RETENTIONS = ("redacted", "none")


@dataclass(frozen=True)
class Settings:
    api_base_url: str = "http://localhost:8080"
    #: ``auto``: use the external provider when the key is set AND the tenant allows it; ``offline``: never.
    provider_mode: str = "auto"
    openai_api_key: str | None = field(default=None, repr=False)
    #: Configurable (REQ-100); the default is only a starting point the owner may override.
    openai_model: str = "gpt-4o-mini"
    openai_base_url: str = "https://api.openai.com/v1"
    #: Approved provider region (BLUEPRINT §18 tenant residency); recorded with every external exchange.
    provider_region: str = "unspecified"
    #: ``redacted``: store redacted question/answer text with the exchange; ``none``: store hashes and metadata only.
    retention: str = "redacted"
    api_timeout_s: float = 8.0
    llm_timeout_s: float = 20.0
    max_tool_rounds: int = 4

    @property
    def openai_configured(self) -> bool:
        return bool(self.openai_api_key)

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> Settings:
        e = os.environ if env is None else env
        mode = (e.get("VO_AI_PROVIDER") or "auto").strip().lower()
        if mode not in PROVIDER_MODES:
            raise ValueError(f"VO_AI_PROVIDER must be one of {PROVIDER_MODES}")
        retention = (e.get("VO_AI_RETENTION") or "redacted").strip().lower()
        if retention not in RETENTIONS:
            raise ValueError(f"VO_AI_RETENTION must be one of {RETENTIONS}")
        key = (e.get("OPENAI_API_KEY") or "").strip() or None
        return cls(
            api_base_url=(e.get("VO_API_BASE_URL") or cls.api_base_url).rstrip("/"),
            provider_mode=mode,
            openai_api_key=key,
            openai_model=(e.get("VO_AI_OPENAI_MODEL") or cls.openai_model).strip(),
            openai_base_url=(e.get("VO_AI_OPENAI_BASE_URL") or cls.openai_base_url).rstrip("/"),
            provider_region=(e.get("VO_AI_PROVIDER_REGION") or cls.provider_region).strip(),
            retention=retention,
            api_timeout_s=float(e.get("VO_AI_API_TIMEOUT_S") or cls.api_timeout_s),
            llm_timeout_s=float(e.get("VO_AI_LLM_TIMEOUT_S") or cls.llm_timeout_s),
            max_tool_rounds=int(e.get("VO_AI_MAX_TOOL_ROUNDS") or cls.max_tool_rounds),
        )
