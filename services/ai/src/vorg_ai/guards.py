"""User-intent detection and prompt-injection guards (REQ-104, REQ-105, ADR-12, ADR-66).

* **Intent comes only from the user's own message.** Write tools (create a scenario, propose a recommendation) are
  offered only when the user asked for that, and never because of anything a tool returned.
* **Approve, reject, execute and incident/e-stop commands are refused deterministically**, before any provider runs.
  The assistant has no tool for them at all.
* **Tool output is data.** Free text inside tool results (incident notes and titles, scenario descriptions,
  recommendation rationales) is scanned for instruction-like content; matches are flagged in the response and the text
  is never acted on.
"""

from __future__ import annotations

import re

REFUSE = "refuse_action"
PROPOSE = "propose"
CREATE_SCENARIO = "create_scenario"
WRITE_INTENTS = frozenset({PROPOSE, CREATE_SCENARIO})

_REFUSE_PATTERNS = [
    re.compile(r"\b(approve|approving|approval of|reject|rejecting|execute|executing|execution of)\b", re.I),
    re.compile(r"\b(apply|implement|carry out|run)\b[^.?!]{0,30}\b(recommendation|plan)\b", re.I),
    re.compile(r"\b(acknowledge|resolve|close|assign|dismiss)\b[^.?!]{0,30}\bincidents?\b", re.I),
    re.compile(r"\b(false[- ]positive)\b", re.I),
    re.compile(r"\b(e-?stop|emergency[- ]stop|stop the (forklift|machine|truck))\b", re.I),
]
_PROPOSE = re.compile(
    r"\b(propose|generate|create|make|draft)\b[^.?!]{0,40}\brecommendations?\b"
    r"|\brecommendations?\b[^.?!]{0,20}\b(from|for) (the )?(latest|last|this) (simulation )?run\b",
    re.I,
)
_CREATE_SCENARIO = re.compile(
    r"\b(create|build|make|set up|add|new)\b[^.?!]{0,40}\bscenario\b"
    r"|\b(simulate|what if|what happens if)\b[^.?!]{0,40}\bclos(e|ed|ing|ure)\b[^.?!]{0,20}\bdock\b",
    re.I,
)

TOPIC_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    (
        "bottleneck",
        re.compile(
            r"\b(bottleneck|matter|matters|queue|queues|queuing|wait|waiting|delay|delays|"
            r"why|impact|throughput loss|backlog)\b",
            re.I,
        ),
    ),
    ("incidents", re.compile(r"\b(incidents?|safety|proximity|near[- ]miss|alerts?|hazard)\b", re.I)),
    (
        "recommendations",
        re.compile(
            r"\b(recommendations?|suggest(ed)? actions?|what should (we|i) do|"
            r"approvals?)\b",
            re.I,
        ),
    ),
    ("scenarios", re.compile(r"\b(scenarios?|simulations?|simulated|runs?|what[- ]if)\b", re.I)),
    (
        "kpis",
        re.compile(
            r"\b(kpis?|metrics?|throughput|performance|utili[sz]ation|how are we doing|"
            r"status|productivity|cycle time|dwell)\b",
            re.I,
        ),
    ),
    ("congestion", re.compile(r"\b(congest(ed|ion)?|crowded|busy|busiest|hotspots?)\b", re.I)),
]

ENTITY_CODE = re.compile(r"\b(FL-\d{2}|W-\d{3}|TR-\d{2}|PAL-\d{4}|CAM-\d{2}|RB-\d{2})\b", re.I)
DOCK_CODE = re.compile(r"\b((?:IN|OUT)-\d{2})\b", re.I)
UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")

_INJECTION = re.compile(
    r"ignore (all |any |the )?(previous|prior|above|earlier|your)? ?(instructions|rules|prompts?)"
    r"|disregard (all |any |the )?(previous|prior|above)?"
    r"|\b(system|developer) (prompt|message|instructions?)\b"
    r"|\byou are now\b|\bact as\b|\bnew instructions\b"
    r"|<\s*/?\s*(system|assistant|tool)\s*>"
    r"|\b(call|use|invoke|run) the \w+ (tool|function)\b"
    r"|\b(approve|execute|reject)\b[^.?!]{0,40}\b(recommendation|approval|plan|immediately|now)\b"
    r"|\breveal\b[^.?!]{0,30}\b(token|key|secret|password|prompt)\b",
    re.I,
)


def detect_intents(message: str) -> frozenset[str]:
    text = message or ""
    intents: set[str] = set()
    if any(p.search(text) for p in _REFUSE_PATTERNS):
        intents.add(REFUSE)
    else:
        if _PROPOSE.search(text):
            intents.add(PROPOSE)
        if _CREATE_SCENARIO.search(text):
            intents.add(CREATE_SCENARIO)
    for name, pattern in TOPIC_PATTERNS:
        if pattern.search(text):
            intents.add(name)
    if ENTITY_CODE.search(text):
        intents.add("entity")
    if DOCK_CODE.search(text):
        intents.add("dock")
    return frozenset(intents)


def scan_untrusted(text: str | None) -> bool:
    """True when retrieved free text looks like an instruction aimed at the assistant."""
    return bool(text) and bool(_INJECTION.search(text))
