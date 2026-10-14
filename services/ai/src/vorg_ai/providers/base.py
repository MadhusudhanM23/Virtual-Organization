"""The provider interface (ADR-17, ADR-64). Domain code never imports a vendor SDK outside ``providers``."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Protocol

from ..identity import Caller
from ..redaction import Redactor

if TYPE_CHECKING:
    from ..tools import ToolRuntime


@dataclass
class Turn:
    message: str
    site_id: str
    intents: frozenset[str]
    caller: Caller
    redactor: Redactor


@dataclass
class Usage:
    prompt_tokens: int = 0
    completion_tokens: int = 0
    #: ``provider`` (counted by the vendor) or ``regex_word_v1`` (counted locally; the offline provider has no model).
    method: str = "regex_word_v1"


@dataclass
class ProviderAnswer:
    text: str
    citation_ids: list[str] = field(default_factory=list)
    directives: list[dict[str, Any]] = field(default_factory=list)
    usage: Usage = field(default_factory=Usage)
    model: str = ""


class ProviderError(Exception):
    """The provider could not produce an answer (network, HTTP, malformed output); the gateway falls back."""


class Provider(Protocol):
    name: str
    external: bool
    model: str

    async def respond(self, turn: Turn, runtime: ToolRuntime) -> ProviderAnswer: ...


_TOKEN = re.compile(r"\w+|[^\w\s]")


def count_tokens(text: str) -> int:
    """Local token count (method ``regex_word_v1``): words and punctuation marks. Used when no vendor count exists."""
    return len(_TOKEN.findall(text or ""))
