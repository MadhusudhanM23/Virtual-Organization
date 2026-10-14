"""Evidence model and groundedness checks (REQ-103, ADR-66).

Every tool result is turned into :class:`Evidence` items held by one :class:`EvidenceLedger` per exchange. Answers
cite them as ``[c1]``. Only cited items are returned as ``citations[]``. :func:`check_grounding` verifies that

* every cited id exists in the ledger;
* every number in the answer appears in the values of a cited item (or in the user's own question);
* an answer that states numbers cites at least one item.

:func:`validate_directives` drops any twin directive whose target is not part of the cited evidence, so neither a
template nor a model can point the camera at something it did not retrieve.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

CITATION_MARK = re.compile(r"\[(c\d+)\]")

# Spans that contain digits but are identifiers, not quantities.
_IDENTIFIERS = re.compile(
    r"\[c\d+\]"
    r"|\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"
    r"|\b\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?)?"
    r"|\b[A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)+\b"  # IN-02, FL-03, PAL-0018, A-03-03, gpt-4o-mini
    r"|\b[A-Za-z]+\d+[A-Za-z0-9]*\b"  # p95, c1, W001
    r"|\b\d+[A-Za-z]+\b"  # 24h, 15m (window names)
    r"|\[[A-Z]+_\d+\]"  # redaction placeholders
)
_NUMBER = re.compile(r"[-+]?\d+(?:\.\d+)?")


@dataclass
class Evidence:
    id: str
    kind: str
    ref: str
    label: str
    href: str
    tool: str
    data_mode: str | None = None
    values: dict[str, Any] = field(default_factory=dict)
    entity_ids: list[str] = field(default_factory=list)
    zone_ids: list[str] = field(default_factory=list)
    dock_ids: list[str] = field(default_factory=list)
    location: dict[str, float] | None = None
    floor_id: str | None = None

    def public(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "kind": self.kind,
            "ref": self.ref,
            "label": self.label,
            "href": self.href,
            "tool": self.tool,
            "data_mode": self.data_mode,
            "values": self.values,
        }

    def brief(self) -> dict[str, Any]:
        """What a model sees about the item (it cites by ``id``)."""
        return {
            "id": self.id,
            "kind": self.kind,
            "label": self.label,
            "data_mode": self.data_mode,
            "values": self.values,
        }


class EvidenceLedger:
    def __init__(self) -> None:
        self.items: list[Evidence] = []
        self._by_key: dict[tuple[str, str], Evidence] = {}

    def add(self, kind: str, ref: str, label: str, href: str, tool: str, **kw: Any) -> Evidence:
        key = (kind, str(ref))
        existing = self._by_key.get(key)
        if existing is not None:
            existing.values.update(kw.get("values") or {})
            return existing
        ev = Evidence(id=f"c{len(self.items) + 1}", kind=kind, ref=str(ref), label=label, href=href, tool=tool, **kw)
        self.items.append(ev)
        self._by_key[key] = ev
        return ev

    def get(self, cid: str) -> Evidence | None:
        for ev in self.items:
            if ev.id == cid:
                return ev
        return None

    def find(self, kind: str, ref: str) -> Evidence | None:
        return self._by_key.get((kind, str(ref)))


# -- groundedness ---------------------------------------------------------------------------------------------------


@dataclass
class GroundingReport:
    grounded: bool
    ungrounded_numbers: list[str]
    unknown_citations: list[str]
    cited_ids: list[str]


def cited_ids(text: str) -> list[str]:
    seen: list[str] = []
    for m in CITATION_MARK.finditer(text or ""):
        if m.group(1) not in seen:
            seen.append(m.group(1))
    return seen


def numbers_in(text: str) -> list[str]:
    stripped = _IDENTIFIERS.sub(" ", text or "")
    return _NUMBER.findall(stripped)


def _collect_numbers(value: Any, out: list[float]) -> None:
    if isinstance(value, bool):
        return
    if isinstance(value, int | float):
        out.append(float(value))
    elif isinstance(value, str):
        for n in numbers_in(value):
            out.append(float(n))
    elif isinstance(value, dict):
        for v in value.values():
            _collect_numbers(v, out)
    elif isinstance(value, list | tuple):
        for v in value:
            _collect_numbers(v, out)


def _matches(token: str, allowed: list[float]) -> bool:
    n = abs(float(token))
    decimals = len(token.split(".")[1]) if "." in token else 0
    return any(round(abs(a), decimals) == round(n, decimals) for a in allowed)


def check_grounding(answer: str, extra_cited: list[str], ledger: EvidenceLedger, user_text: str) -> GroundingReport:
    ids = cited_ids(answer)
    for cid in extra_cited:
        if cid not in ids:
            ids.append(cid)
    unknown = [cid for cid in ids if ledger.get(cid) is None]
    allowed: list[float] = []
    for cid in ids:
        ev = ledger.get(cid)
        if ev is not None:
            _collect_numbers(ev.values, allowed)
    _collect_numbers(user_text, allowed)
    ungrounded = [tok for tok in numbers_in(answer) if not _matches(tok, allowed)]
    return GroundingReport(
        grounded=not unknown and not ungrounded,
        ungrounded_numbers=ungrounded,
        unknown_citations=unknown,
        cited_ids=[c for c in ids if c not in unknown],
    )


# -- directives -----------------------------------------------------------------------------------------------------


def highlight(items: list[Evidence]) -> dict[str, Any] | None:
    entity_ids = _unique(i for ev in items for i in ev.entity_ids)
    zone_ids = _unique(i for ev in items for i in ev.zone_ids)
    dock_ids = _unique(i for ev in items for i in ev.dock_ids)
    if not (entity_ids or zone_ids or dock_ids):
        return None
    floor = next((ev.floor_id for ev in items if ev.floor_id), None)
    return {
        "type": "highlight",
        "entity_ids": entity_ids,
        "zone_ids": zone_ids,
        "dock_ids": dock_ids,
        "floor_id": floor,
        "citation_ids": [ev.id for ev in items],
    }


def focus(ev: Evidence | None) -> dict[str, Any] | None:
    if ev is None or not ev.location:
        return None
    return {"type": "focus_camera", "target": dict(ev.location), "floor_id": ev.floor_id, "citation_id": ev.id}


def validate_directives(
    directives: list[dict[str, Any]], ledger: EvidenceLedger, cited: list[str]
) -> tuple[list[dict[str, Any]], int]:
    """Keeps only directive targets that belong to cited evidence. Returns (directives, dropped target count)."""
    evs = [ev for ev in (ledger.get(c) for c in cited) if ev is not None]
    ok_entities = {i for ev in evs for i in ev.entity_ids}
    ok_zones = {i for ev in evs for i in ev.zone_ids}
    ok_docks = {i for ev in evs for i in ev.dock_ids}
    out: list[dict[str, Any]] = []
    dropped = 0
    for d in directives:
        if d.get("type") == "highlight":
            ents = [i for i in d.get("entity_ids", []) if i in ok_entities]
            zones = [i for i in d.get("zone_ids", []) if i in ok_zones]
            docks = [i for i in d.get("dock_ids", []) if i in ok_docks]
            dropped += (
                len(d.get("entity_ids", []))
                - len(ents)
                + len(d.get("zone_ids", []))
                - len(zones)
                + len(d.get("dock_ids", []))
                - len(docks)
            )
            if ents or zones or docks:
                out.append(
                    {
                        **d,
                        "entity_ids": ents,
                        "zone_ids": zones,
                        "dock_ids": docks,
                        "citation_ids": [c for c in d.get("citation_ids", []) if c in cited],
                    }
                )
        elif d.get("type") == "focus_camera":
            ev = ledger.get(str(d.get("citation_id")))
            if ev is not None and ev.id in cited and ev.location and ev.location == d.get("target"):
                out.append(d)
            else:
                dropped += 1
        else:
            dropped += 1
    return out, dropped


def _unique(values: Any) -> list[str]:
    seen: list[str] = []
    for v in values:
        if v and v not in seen:
            seen.append(v)
    return seen
