"""PII and secret redaction before any external call and before any exchange is recorded (REQ-101, ADR-65).

* **Secrets** (JWTs, bearer tokens, ``sk-`` keys, ``password=...``) are replaced by ``[SECRET_REDACTED]`` and are
  never restored: they must not leave the process, in either direction.
* **PII** (e-mail, phone, Aadhaar-like and PAN-like ids, card-like numbers) is *tokenized*: each distinct value gets a
  stable placeholder such as ``[EMAIL_1]``. The mapping lives only in this ``Redactor`` instance (one per exchange),
  so a provider answer can be restored locally for the user while the provider only ever saw the placeholder.

Operational identifiers are protected first so they are never mistaken for PII: UUIDs, long hex strings (hashes) and
ISO-8601 timestamps. Only *counts* per kind are reported, never values.
"""

from __future__ import annotations

import re
from collections import Counter
from typing import Any

SECRET_PLACEHOLDER = "[SECRET_REDACTED]"  # noqa: S105 (a placeholder, not a credential)

# Same patterns as io.vorg.api.ai.SecretPatterns (the API refuses a record that still matches one).
_SECRET_PATTERNS = [
    re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"),
    re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{16,}"),
    re.compile(r"\bsk-[A-Za-z0-9_-]{16,}"),
    re.compile(r"(?i)\b(?:password|passwd|api[_-]?key|secret)\s*[=:]\s*[^\s\"',]{4,}"),
]

_PROTECTED = re.compile(
    r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"  # UUID
    r"|\b[0-9a-fA-F]{32,}\b"  # hashes
    r"|\b\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?"  # ISO-8601 timestamps
)

# Order matters: the more specific kinds first.
_PII_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("EMAIL", re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")),
    ("CARD", re.compile(r"\b\d{4}[ -]\d{4}[ -]\d{4}[ -]\d{4}\b|\b\d{16}\b")),
    ("AADHAAR", re.compile(r"\b\d{4}[ -]\d{4}[ -]\d{4}\b|\b\d{12}\b")),
    ("PAN", re.compile(r"\b[A-Z]{5}\d{4}[A-Z]\b")),
    ("PHONE", re.compile(r"(?<![\w.])\+\d{1,3}[ -]?\d{4,5}[ -]?\d{4,6}\b|\b[6-9]\d{9}\b")),
]


class Redactor:
    """One instance per exchange: holds the placeholder mapping and the per-kind counts."""

    def __init__(self) -> None:
        self._by_value: dict[str, str] = {}
        self._by_placeholder: dict[str, str] = {}
        self._seq: Counter[str] = Counter()
        self.counts: Counter[str] = Counter()

    # -- text ---------------------------------------------------------------------------------------------------
    def redact(self, text: str) -> str:
        if not text:
            return text
        for pattern in _SECRET_PATTERNS:
            text, n = pattern.subn(SECRET_PLACEHOLDER, text)
            if n:
                self.counts["SECRET"] += n
        protected: list[str] = []

        def _protect(m: re.Match[str]) -> str:
            protected.append(m.group(0))
            return f"\x00{len(protected) - 1}\x00"

        text = _PROTECTED.sub(_protect, text)
        for kind, pattern in _PII_PATTERNS:
            text = pattern.sub(lambda m, k=kind: self._token(k, m.group(0)), text)
        return re.sub(r"\x00(\d+)\x00", lambda m: protected[int(m.group(1))], text)

    def restore(self, text: str) -> str:
        """Puts tokenized PII back (local display only). Secrets stay redacted."""
        if not text or not self._by_placeholder:
            return text
        return re.sub(r"\[[A-Z]+_\d+\]", lambda m: self._by_placeholder.get(m.group(0), m.group(0)), text)

    # -- structures ---------------------------------------------------------------------------------------------
    def redact_obj(self, value: Any) -> Any:
        """Redacts every string inside dicts/lists (keys are schema names, left as they are)."""
        if isinstance(value, str):
            return self.redact(value)
        if isinstance(value, dict):
            return {k: self.redact_obj(v) for k, v in value.items()}
        if isinstance(value, list | tuple):
            return [self.redact_obj(v) for v in value]
        return value

    def summary(self) -> dict[str, int]:
        return dict(sorted(self.counts.items()))

    def _token(self, kind: str, value: str) -> str:
        existing = self._by_value.get(value)
        if existing:
            self.counts[kind] += 1
            return existing
        self._seq[kind] += 1
        placeholder = f"[{kind}_{self._seq[kind]}]"
        self._by_value[value] = placeholder
        self._by_placeholder[placeholder] = value
        self.counts[kind] += 1
        return placeholder


def contains_secret(text: str) -> bool:
    return any(p.search(text or "") for p in _SECRET_PATTERNS)
