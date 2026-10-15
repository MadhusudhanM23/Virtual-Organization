"""FastAPI app of ``services/ai`` (port 8090, ADR-64).

``POST /api/assistant/messages`` — ask the assistant (bearer token required; it is forwarded, never stored).
``GET /api/assistant/health`` — liveness and which providers are configured (never the key).

Run: ``uvicorn vorg_ai.app:app --port 8090`` from ``services/ai`` (with ``src`` on the path).
"""

from __future__ import annotations

import time
import uuid
from contextlib import asynccontextmanager
from typing import Any

import httpx
from fastapi import FastAPI, Header, Request
from fastapi.responses import JSONResponse, PlainTextResponse
from pydantic import BaseModel, ConfigDict, Field

from .config import Settings
from .gateway import AssistantGateway
from .metrics import Metrics
from .platform_client import PlatformError


class MessageRequest(BaseModel):
    # extra=forbid: a tenant_id (or anything else) in the body is rejected, the tenant comes from the token.
    model_config = ConfigDict(extra="forbid")
    site_id: uuid.UUID
    message: str = Field(min_length=1, max_length=2000)
    conversation_id: uuid.UUID | None = None


def _problem(status: int, title: str, detail: str, correlation_id: str, code: str | None = None) -> JSONResponse:
    body: dict[str, Any] = {"type": "about:blank", "title": title, "status": status, "detail": detail}
    if code:
        body["code"] = code
    return JSONResponse(
        body, status_code=status, media_type="application/problem+json", headers={"X-Correlation-Id": correlation_id}
    )


def create_app(settings: Settings | None = None, gateway: AssistantGateway | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    state: dict[str, Any] = {"gateway": gateway}
    metrics = Metrics()

    @asynccontextmanager
    async def lifespan(_app: FastAPI):  # type: ignore[no-untyped-def]
        if state["gateway"] is not None:
            yield
            return
        api_http = httpx.AsyncClient(base_url=settings.api_base_url, timeout=settings.api_timeout_s)
        llm_http = httpx.AsyncClient() if settings.openai_configured else None
        state["gateway"] = AssistantGateway(settings, api_http, llm_http)
        try:
            yield
        finally:
            await api_http.aclose()
            if llm_http is not None:
                await llm_http.aclose()

    app = FastAPI(title="Virtual Organization AI assistant", version="0.1.0", lifespan=lifespan)

    @app.get("/api/assistant/health")
    async def health() -> dict[str, Any]:
        return {
            "status": "ok",
            "provider_mode": settings.provider_mode,
            "providers": {
                "offline": "available",
                "openai": "configured" if settings.openai_configured else "not_configured",
            },
            "openai_model": settings.openai_model if settings.openai_configured else None,
            "retention": settings.retention,
        }

    @app.get("/api/assistant/metrics", response_class=PlainTextResponse)
    async def prometheus_metrics() -> PlainTextResponse:
        """Prometheus text exposition: latency histogram and request counts (no tenant or user data)."""
        return PlainTextResponse(metrics.render(), media_type="text/plain; version=0.0.4")

    @app.post("/api/assistant/messages")
    async def messages(
        body: MessageRequest,
        request: Request,
        authorization: str | None = Header(default=None),
        x_correlation_id: str | None = Header(default=None),
    ) -> JSONResponse:
        cid = x_correlation_id or str(uuid.uuid4())
        if not authorization or not authorization.lower().startswith("bearer ") or len(authorization) < 12:
            return _problem(401, "Unauthorized", "A bearer token is required.", cid)
        token = authorization.split(" ", 1)[1].strip()
        started = time.perf_counter()
        try:
            result = await state["gateway"].handle(
                token, str(body.site_id), body.message, str(body.conversation_id) if body.conversation_id else None, cid
            )
        except PlatformError as exc:
            metrics.observe("platform_error", time.perf_counter() - started)
            if exc.status == 401:
                return _problem(401, "Unauthorized", "The token was not accepted by the platform.", cid)
            return _problem(502, "Platform error", f"The platform answered {exc.status}.", cid, "platform_error")
        except httpx.HTTPError:
            metrics.observe("platform_unavailable", time.perf_counter() - started)
            return _problem(
                503, "Platform unavailable", "The platform API could not be reached.", cid, "platform_unavailable"
            )
        metrics.observe("ok", time.perf_counter() - started)
        return JSONResponse(result, headers={"X-Correlation-Id": cid})

    return app


app = create_app()
