"""Tenant LLM policy (REQ-101, ADR-12, ADR-17).

External transmission is off unless *all* of these hold:

1. the gateway runs in ``auto`` mode (``VO_AI_PROVIDER``), not ``offline``;
2. ``OPENAI_API_KEY`` is set in the environment;
3. the caller's tenant has ``llm_external_allowed = true`` (read from ``GET /api/me`` with the caller's own token).

A missing or unreadable flag counts as ``false`` (fail closed). The decision and its reason travel with every answer
and every exchange record.
"""

from __future__ import annotations

from dataclasses import dataclass

from .config import Settings

OFFLINE = "offline"
OPENAI = "openai"


@dataclass(frozen=True)
class ProviderDecision:
    name: str
    external: bool
    reason: str


def decide(settings: Settings, tenant_llm_external_allowed: bool | None) -> ProviderDecision:
    if settings.provider_mode == "offline":
        return ProviderDecision(OFFLINE, False, "offline_mode_configured")
    if not settings.openai_configured:
        return ProviderDecision(OFFLINE, False, "no_api_key")
    if tenant_llm_external_allowed is not True:
        return ProviderDecision(OFFLINE, False, "tenant_external_disallowed")
    return ProviderDecision(OPENAI, True, "tenant_external_allowed")
