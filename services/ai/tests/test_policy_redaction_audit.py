from __future__ import annotations

import json

import pytest

from vorg_ai import policy
from vorg_ai.audit import build_record
from vorg_ai.config import Settings
from vorg_ai.redaction import Redactor, contains_secret

KEY = "sk-test-0000000000000000000000"  # a fake key shape for tests; never a real credential


@pytest.mark.parametrize(
    ("mode", "key", "tenant_flag", "expected", "reason"),
    [
        ("auto", None, True, "offline", "no_api_key"),
        ("auto", KEY, False, "offline", "tenant_external_disallowed"),
        ("auto", KEY, None, "offline", "tenant_external_disallowed"),
        ("offline", KEY, True, "offline", "offline_mode_configured"),
        ("auto", KEY, True, "openai", "tenant_external_allowed"),
    ],
)
def test_provider_policy(mode: str, key: str | None, tenant_flag: bool | None, expected: str, reason: str) -> None:
    d = policy.decide(Settings(provider_mode=mode, openai_api_key=key), tenant_flag)
    assert (d.name, d.reason) == (expected, reason)
    assert d.external is (expected == "openai")


def test_settings_never_show_the_key() -> None:
    s = Settings.from_env({"OPENAI_API_KEY": KEY})
    assert s.openai_configured
    assert KEY not in repr(s)
    assert not Settings.from_env({}).openai_configured


def test_redaction_tokenizes_pii_and_removes_secrets() -> None:
    r = Redactor()
    text = (
        "Call ravi.k@example.com or +91 98450 12345; token eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2lnbmF0dXJl "
        f"and {KEY}; PAN ABCDE1234F; site 018f0000-0000-7000-8000-00000000a021 at 2026-10-14T09:00:00Z"
    )
    out = r.redact(text)
    assert "ravi.k@example.com" not in out and "[EMAIL_1]" in out
    assert "98450" not in out and "[PHONE_1]" in out
    assert "ABCDE1234F" not in out
    assert "eyJ" not in out and KEY not in out and out.count("[SECRET_REDACTED]") == 2
    # Operational identifiers survive untouched.
    assert "018f0000-0000-7000-8000-00000000a021" in out and "2026-10-14T09:00:00Z" in out
    restored = r.restore(out)
    assert "ravi.k@example.com" in restored and KEY not in restored
    assert r.summary() == {"EMAIL": 1, "PAN": 1, "PHONE": 1, "SECRET": 2}


def test_same_value_same_placeholder() -> None:
    r = Redactor()
    assert r.redact("a@b.io and a@b.io") == "[EMAIL_1] and [EMAIL_1]"


def test_audit_record_has_no_secret_and_respects_retention() -> None:
    r = Redactor()
    question = f"why is IN-02 slow? my key is {KEY} and mail me at x@y.org"
    rec = build_record(
        exchange_id="e",
        conversation_id="c",
        site_id="s",
        provider="offline",
        model="m",
        external=False,
        provider_reason="no_api_key",
        retention="redacted",
        question=question,
        answer="ok",
        redactor=r,
        prompt_tokens=3,
        completion_tokens=1,
        token_count_method="regex_word_v1",
        tool_calls=[{"tool": "list_docks", "status": "ok"}],
        citations=[{"id": "c1", "kind": "DOCK", "ref": "d"}],
        flags=[],
        grounded=True,
        latency_ms=5,
    )
    blob = json.dumps(rec)
    assert KEY not in blob and "x@y.org" not in blob and not contains_secret(blob)
    assert len(rec["question_sha256"]) == 64
    none = build_record(**{**_args(r), "retention": "none"})
    assert none["question_text"] is None and none["answer_text"] is None


def _args(r: Redactor) -> dict:
    return dict(
        exchange_id="e",
        conversation_id="c",
        site_id="s",
        provider="offline",
        model="m",
        external=False,
        provider_reason="no_api_key",
        retention="redacted",
        question="q",
        answer="a",
        redactor=r,
        prompt_tokens=0,
        completion_tokens=0,
        token_count_method="regex_word_v1",
        tool_calls=[],
        citations=[],
        flags=[],
        grounded=True,
        latency_ms=0,
    )
