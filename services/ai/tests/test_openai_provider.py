"""The OpenAI provider against a mocked endpoint: never the network, never a real key."""

from __future__ import annotations

import json

import httpx

from conftest import Harness
from vorg_ai.config import Settings

FAKE_KEY = "sk-test-not-a-real-key-000000000000"


class FakeOpenAI:
    def __init__(self, final: dict | None = None, leak_check: bool = True) -> None:
        self.bodies: list[dict] = []
        self.final = final
        self.headers: list[str] = []

    def handler(self, req: httpx.Request) -> httpx.Response:
        body = json.loads(req.content)
        self.bodies.append(body)
        self.headers.append(req.headers.get("authorization", ""))
        tool_msgs = [m for m in body["messages"] if m["role"] == "tool"]
        if not tool_msgs:
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "message": {
                                "role": "assistant",
                                "content": None,
                                "tool_calls": [
                                    {
                                        "id": "t1",
                                        "type": "function",
                                        "function": {"name": "list_docks", "arguments": "{}"},
                                    }
                                ],
                            }
                        }
                    ],
                    "usage": {"prompt_tokens": 120, "completion_tokens": 9},
                },
            )
        content = self.final or {"answer": "Dock IN-02 is OCCUPIED [c1].", "highlight": ["c1"], "focus": "c1"}
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"role": "assistant", "content": json.dumps(content)}}],
                "usage": {"prompt_tokens": 200, "completion_tokens": 20},
            },
        )


def _harness(fake: FakeOpenAI) -> Harness:
    return Harness(Settings(openai_api_key=FAKE_KEY), httpx.MockTransport(fake.handler))


def test_external_provider_only_for_allowed_tenant() -> None:
    fake = FakeOpenAI()
    h = _harness(fake)
    res = h.ask("What is the status of dock IN-02?")  # demo tenant: llm_external_allowed = false
    assert res["provider"]["name"] == "offline" and res["provider"]["reason"] == "tenant_external_disallowed"
    assert fake.bodies == []


def test_openai_tool_loop_with_redaction_and_usage() -> None:
    fake = FakeOpenAI()
    h = _harness(fake)
    res = h.ask("Status of dock IN-02? Reply to ops.lead@example.com", token="tok-manager-ext")
    assert res["provider"]["name"] == "openai" and res["provider"]["external"] is True
    assert res["usage"] == {"prompt_tokens": 320, "completion_tokens": 29, "method": "provider"}
    sent = json.dumps(fake.bodies)
    assert "ops.lead@example.com" not in sent and "[EMAIL_1]" in sent
    assert "tok-manager-ext" not in sent  # the caller's token never goes to the provider
    assert all(h_ == f"Bearer {FAKE_KEY}" for h_ in fake.headers)
    assert "untrusted_data" in sent
    assert {d["type"] for d in res["directives"]} == {"highlight", "focus_camera"}
    assert h.sink.records[0]["external"] is True and FAKE_KEY not in json.dumps(h.sink.records)
    # write tools are not offered without an explicit request
    offered = {t["function"]["name"] for t in fake.bodies[0].get("tools", [])}
    assert "propose_recommendation" not in offered and "list_docks" in offered


def test_ungrounded_external_answer_is_replaced() -> None:
    fake = FakeOpenAI(final={"answer": "Dock IN-02 handles 57 trucks per hour [c1].", "highlight": [], "focus": None})
    h = _harness(fake)
    res = h.ask("What is the status of dock IN-02?", token="tok-manager-ext")
    assert res["provider"]["name"] == "offline" and res["provider"]["reason"] == "external_answer_ungrounded"
    assert any(f["code"] == "ungrounded_external_answer_replaced" for f in res["flags"])
    assert "57" not in res["answer"]


def test_provider_failure_falls_back_offline() -> None:
    h = Harness(Settings(openai_api_key=FAKE_KEY), httpx.MockTransport(lambda r: httpx.Response(500)))
    res = h.ask("Any incidents?", token="tok-manager-ext")
    assert res["provider"]["reason"] == "external_provider_failed" and res["grounded"]
