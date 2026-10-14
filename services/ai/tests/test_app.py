from __future__ import annotations

from fastapi.testclient import TestClient

from vorg_ai.app import create_app
from vorg_ai.audit import MemoryAuditSink
from vorg_ai.config import Settings
from vorg_ai.fake_platform import SITE_A, FakePlatform
from vorg_ai.gateway import AssistantGateway


def _client() -> TestClient:
    settings = Settings()
    gw = AssistantGateway(settings, FakePlatform().client(), None, MemoryAuditSink())
    return TestClient(create_app(settings, gw))


def test_health_never_shows_a_key() -> None:
    with _client() as c:
        body = c.get("/api/assistant/health").json()
    assert body["providers"] == {"offline": "available", "openai": "not_configured"}


def test_message_requires_token_and_rejects_tenant_in_body() -> None:
    with _client() as c:
        assert c.post("/api/assistant/messages", json={"site_id": SITE_A, "message": "hi"}).status_code == 401
        r = c.post(
            "/api/assistant/messages",
            headers={"Authorization": "Bearer tok-manager-a"},
            json={"site_id": SITE_A, "message": "hi", "tenant_id": SITE_A},
        )
        assert r.status_code == 422
        bad = c.post(
            "/api/assistant/messages",
            headers={"Authorization": "Bearer nope-nope-nope"},
            json={"site_id": SITE_A, "message": "hi"},
        )
        assert bad.status_code == 401
        ok = c.post(
            "/api/assistant/messages",
            headers={"Authorization": "Bearer tok-manager-a"},
            json={"site_id": SITE_A, "message": "Why does the bottleneck matter?"},
        )
        assert ok.status_code == 200
        body = ok.json()
        assert set(body) >= {"answer", "citations", "directives", "provider", "grounded", "exchange_id"}
