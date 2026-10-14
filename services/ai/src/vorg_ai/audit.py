"""Exchange audit records (REQ-101, REQ-106, ADR-65).

The record is built from **redacted** text only (or no text at all with ``VO_AI_RETENTION=none``): provider, model,
external flag and reason, token counts with their counting method, the tool calls (name, validated arguments, status,
citation ids, duration), the cited evidence refs, redaction counts per kind, flags, groundedness and latency. It never
contains the caller's token, the provider key or an unredacted secret. It is stored by the platform API
(``POST /api/ai/exchanges``, tenant-scoped with RLS) with the caller's own token; when that fails, a metadata-only line
is logged so the exchange is never silently unrecorded.
"""

from __future__ import annotations

import hashlib
import json
import logging
from typing import Any, Protocol

import httpx

from .platform_client import PlatformClient, PlatformError
from .redaction import Redactor, contains_secret

log = logging.getLogger("vorg_ai.audit")


def build_record(
    *,
    exchange_id: str,
    conversation_id: str,
    site_id: str,
    provider: str,
    model: str,
    external: bool,
    provider_reason: str,
    retention: str,
    question: str,
    answer: str,
    redactor: Redactor,
    prompt_tokens: int,
    completion_tokens: int,
    token_count_method: str,
    tool_calls: list[dict[str, Any]],
    citations: list[dict[str, Any]],
    flags: list[dict[str, Any]],
    grounded: bool,
    latency_ms: int,
) -> dict[str, Any]:
    redacted_q = redactor.redact(question)
    redacted_a = redactor.redact(answer)
    record = {
        "exchange_id": exchange_id,
        "site_id": site_id,
        "conversation_id": conversation_id,
        "provider": provider,
        "model": model,
        "external": external,
        "provider_reason": provider_reason,
        "retention": retention,
        "question_sha256": hashlib.sha256(question.encode("utf-8")).hexdigest(),
        "question_text": redacted_q if retention == "redacted" else None,
        "answer_text": redacted_a if retention == "redacted" else None,
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "token_count_method": token_count_method,
        "tool_calls": [
            redactor.redact_obj(
                {
                    k: c.get(k)
                    for k in ("tool", "origin", "args", "status", "error", "http_status", "citation_ids", "duration_ms")
                    if c.get(k) is not None
                }
            )
            for c in tool_calls
        ][:50],
        "citations": [{"id": c["id"], "kind": c["kind"], "ref": c["ref"]} for c in citations][:50],
        "redactions": redactor.summary(),
        "flags": [f.get("code") for f in flags][:50],
        "grounded": grounded,
        "latency_ms": latency_ms,
    }
    if contains_secret(json.dumps(record)):  # belt and braces: should be impossible after redaction
        raise ValueError("audit record would contain a secret")
    return record


class AuditSink(Protocol):
    async def record(self, record: dict[str, Any], client: PlatformClient) -> bool: ...


class ApiAuditSink:
    """Stores the record through the platform API with the caller's own token (idempotent on the exchange id)."""

    async def record(self, record: dict[str, Any], client: PlatformClient) -> bool:
        try:
            await client.post_command("/api/ai/exchanges", record, idempotency_key=record["exchange_id"])
            return True
        except (PlatformError, httpx.HTTPError) as exc:
            status = getattr(exc, "status", None)
            log.warning(
                "exchange %s not recorded by the API (status=%s); metadata: provider=%s model=%s "
                "external=%s prompt_tokens=%s completion_tokens=%s tools=%s",
                record["exchange_id"],
                status,
                record["provider"],
                record["model"],
                record["external"],
                record["prompt_tokens"],
                record["completion_tokens"],
                [c.get("tool") for c in record["tool_calls"]],
            )
            return False


class MemoryAuditSink:
    """Test and eval sink."""

    def __init__(self) -> None:
        self.records: list[dict[str, Any]] = []

    async def record(self, record: dict[str, Any], client: PlatformClient) -> bool:
        self.records.append(record)
        return True
