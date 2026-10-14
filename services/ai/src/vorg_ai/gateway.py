"""The assistant gateway: one exchange from question to grounded, cited, audited answer (ADR-64, ADR-66, ADR-65).

Flow (BLUEPRINT §13 "AI tool architecture"):
``caller token -> GET /api/me (identity, tenant LLM flag) -> policy decision -> intents from the user's message ->
provider with the allowlisted tool runtime (each call as the caller) -> grounding + directive validation ->
exchange record -> response``.
"""

from __future__ import annotations

import time
import uuid
from typing import Any

import httpx

from . import guards, policy
from .audit import ApiAuditSink, AuditSink, build_record
from .config import Settings
from .evidence import EvidenceLedger, check_grounding, validate_directives
from .identity import load_caller
from .platform_client import PlatformClient
from .providers.base import ProviderAnswer, ProviderError, Turn
from .providers.offline import OfflineProvider
from .providers.openai_provider import OpenAIProvider
from .redaction import Redactor
from .tools import ToolContext, ToolRuntime


class AssistantGateway:
    def __init__(
        self,
        settings: Settings,
        api_http: httpx.AsyncClient,
        llm_http: httpx.AsyncClient | None = None,
        audit_sink: AuditSink | None = None,
    ) -> None:
        self.settings = settings
        self.api_http = api_http
        self.llm_http = llm_http
        self.audit_sink = audit_sink or ApiAuditSink()
        self.offline = OfflineProvider()

    async def handle(
        self,
        token: str,
        site_id: str,
        message: str,
        conversation_id: str | None = None,
        correlation_id: str | None = None,
    ) -> dict[str, Any]:
        started = time.perf_counter()
        correlation_id = correlation_id or str(uuid.uuid4())
        client = PlatformClient(self.api_http, token, correlation_id)
        caller = await load_caller(client)  # 401 propagates: no identity, no answer
        decision = policy.decide(self.settings, caller.llm_external_allowed)
        exchange_id = str(uuid.uuid4())
        conversation_id = conversation_id or str(uuid.uuid4())
        intents = guards.detect_intents(message)
        redactor = Redactor()
        ledger = EvidenceLedger()
        ctx = ToolContext(client=client, site_id=site_id, ledger=ledger, exchange_id=exchange_id)
        runtime = ToolRuntime(ctx, caller, intents)
        turn = Turn(message=message, site_id=site_id, intents=intents, caller=caller, redactor=redactor)

        used = self.offline
        used_decision = decision
        answer: ProviderAnswer
        if decision.name == policy.OPENAI and guards.REFUSE not in intents and self.llm_http is not None:
            try:
                ext = OpenAIProvider(self.settings, self.llm_http)
                answer = await ext.respond(turn, runtime)
                report = check_grounding(answer.text, answer.citation_ids, ledger, message)
                if report.grounded:
                    used = ext
                else:
                    ctx.flags.append(
                        {
                            "code": "ungrounded_external_answer_replaced",
                            "ungrounded_numbers": report.ungrounded_numbers[:10],
                            "unknown_citations": report.unknown_citations[:10],
                        }
                    )
                    answer = await self.offline.respond(turn, runtime)
                    used_decision = policy.ProviderDecision(policy.OFFLINE, False, "external_answer_ungrounded")
            except ProviderError as exc:
                ctx.flags.append({"code": "external_provider_failed", "detail": str(exc)[:200]})
                answer = await self.offline.respond(turn, runtime)
                used_decision = policy.ProviderDecision(policy.OFFLINE, False, "external_provider_failed")
        else:
            if decision.name == policy.OPENAI:
                used_decision = policy.ProviderDecision(
                    policy.OFFLINE, False, "refusal_is_deterministic" if guards.REFUSE in intents else "no_llm_client"
                )
            answer = await self.offline.respond(turn, runtime)

        report = check_grounding(answer.text, answer.citation_ids, ledger, message)
        if not report.grounded:
            ctx.flags.append({"code": "ungrounded_numbers", "values": report.ungrounded_numbers[:10]})
        directives, dropped = validate_directives(answer.directives, ledger, report.cited_ids)
        if dropped:
            ctx.flags.append({"code": "directive_targets_dropped", "count": dropped})
        citations = [ev.public() for ev in (ledger.get(c) for c in report.cited_ids) if ev is not None]
        data_modes = sorted({c["data_mode"] for c in citations if c.get("data_mode")})
        latency_ms = int((time.perf_counter() - started) * 1000)
        provider_info = {
            "name": used.name,
            "model": answer.model or used.model,
            "external": used.external,
            "reason": used_decision.reason,
            "region": self.settings.provider_region if used.external else None,
        }
        record = build_record(
            exchange_id=exchange_id,
            conversation_id=conversation_id,
            site_id=site_id,
            provider=used.name,
            model=provider_info["model"],
            external=used.external,
            provider_reason=used_decision.reason,
            retention=self.settings.retention,
            question=message,
            answer=answer.text,
            redactor=redactor,
            prompt_tokens=answer.usage.prompt_tokens,
            completion_tokens=answer.usage.completion_tokens,
            token_count_method=answer.usage.method,
            tool_calls=runtime.records,
            citations=citations,
            flags=ctx.flags,
            grounded=report.grounded,
            latency_ms=latency_ms,
        )
        recorded = await self.audit_sink.record(record, client)
        return {
            "exchange_id": exchange_id,
            "conversation_id": conversation_id,
            "answer": answer.text,
            "citations": citations,
            "directives": directives,
            "provider": provider_info,
            "grounded": report.grounded,
            "data_modes": data_modes,
            "flags": ctx.flags,
            "tool_calls": [
                {k: r.get(k) for k in ("tool", "status", "error", "citation_ids") if r.get(k) is not None}
                for r in runtime.records
            ],
            "usage": {
                "prompt_tokens": answer.usage.prompt_tokens,
                "completion_tokens": answer.usage.completion_tokens,
                "method": answer.usage.method,
            },
            "audit": {"recorded": recorded},
            "latency_ms": latency_ms,
        }
