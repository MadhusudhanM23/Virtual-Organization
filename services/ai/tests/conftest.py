from __future__ import annotations

import asyncio
from typing import Any

import pytest

from vorg_ai.audit import MemoryAuditSink
from vorg_ai.config import Settings
from vorg_ai.fake_platform import SITE_A, FakePlatform
from vorg_ai.gateway import AssistantGateway


class Harness:
    def __init__(self, settings: Settings | None = None, llm_transport: Any = None) -> None:
        import httpx

        self.platform = FakePlatform()
        self.sink = MemoryAuditSink()
        self.settings = settings or Settings()
        llm = httpx.AsyncClient(transport=llm_transport) if llm_transport is not None else None
        self.gateway = AssistantGateway(self.settings, self.platform.client(), llm, self.sink)

    def ask(self, message: str, token: str = "tok-manager-a", site: str = SITE_A) -> dict[str, Any]:
        return asyncio.run(self.gateway.handle(token, site, message))


@pytest.fixture
def harness() -> Harness:
    return Harness()
