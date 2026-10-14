"""Deterministic offline provider (the default; ADR-17, ADR-66).

Rule-based intent routing over the user's message plus answer templates. Every value in an answer is read from a tool
result and cited; nothing is invented and nothing leaves the process. Same inputs, same answer.
"""

from __future__ import annotations

import re
from typing import Any

from .. import guards
from ..evidence import Evidence, focus, highlight
from ..tools import ToolOutcome, ToolRuntime
from .base import ProviderAnswer, Turn, Usage, count_tokens

MODEL = "offline-rules-v1"

_WINDOWS = [
    (re.compile(r"\b(15 ?min(ute)?s?|quarter[- ]hour)\b", re.I), "15m"),
    (re.compile(r"\b(last|past) hour\b|\b1 ?h(our)?\b", re.I), "1h"),
    (re.compile(r"\b6 ?h(ours)?\b|\bsix hours\b", re.I), "6h"),
    (re.compile(r"\b(7 ?d(ays)?|week|seven days)\b", re.I), "7d"),
]
_MINUTES = re.compile(
    r"(?:from\s+)?(?:minute\s+|min\s+)?(\d{1,5})\s*(?:min(?:utes?)?)?\s*(?:to|until|through|-|–)\s*"
    r"(?:minute\s+|min\s+)?(\d{1,5})",
    re.I,
)
_ZONE_LETTER = re.compile(r"\bzone\s+([a-z])\b", re.I)

REFUSAL = (
    "I can't approve, reject or execute recommendations, handle incidents or request emergency stops. "
    "The assistant is read-mostly: at most it can propose a recommendation or create a simulation scenario when you "
    "ask, and a person with the right role decides on the Recommendations page (incident handling is on the "
    "Incidents page). You can ask me to explain a recommendation, or to propose one from a simulation run."
)
HELP = (
    "I answer questions about this site from platform data you are allowed to see, and cite every source: why a "
    "bottleneck matters (from simulation runs), open safety incidents, KPIs, scenarios and runs, recommendations, a "
    "zone, a dock or an entity by its code (for example FL-03). When you ask, I can also create a dock-closure "
    "scenario or propose a recommendation from a simulation run. I never approve or execute anything."
)


def fmt(value: Any) -> str:
    if isinstance(value, bool) or value is None:
        return str(value)
    if isinstance(value, int) or (isinstance(value, float) and value.is_integer()):
        return str(int(value))
    if isinstance(value, float):
        return f"{value:.2f}"
    return str(value)


class _Builder:
    def __init__(self) -> None:
        self.lines: list[str] = []
        self.cited: list[str] = []
        self.directives: list[dict[str, Any]] = []

    def c(self, *evs: Evidence | None) -> str:
        marks = []
        for ev in evs:
            if ev is None:
                continue
            if ev.id not in self.cited:
                self.cited.append(ev.id)
            marks.append(f"[{ev.id}]")
        return "".join(marks)

    def add(self, line: str) -> None:
        self.lines.append(line)

    def show(self, items: list[Evidence], focus_on: Evidence | None) -> None:
        h = highlight([ev for ev in items if ev is not None])
        if h:
            for ev in items:
                self.c(ev)
            self.directives.append(h)
        f = focus(focus_on)
        if f:
            self.c(focus_on)
            self.directives.append(f)

    def text(self) -> str:
        return "\n".join(self.lines)


def _refused(out: ToolOutcome, what: str) -> str:
    err = out.error or {}
    code = err.get("code")
    if code == "not_permitted":
        return f"Your role can't {what} for this site, so I didn't look it up."
    status = err.get("http_status")
    if status == 403:
        return f"The platform refused to let you {what} for this site."
    if status == 404:
        return f"The platform found nothing to {what} here."
    return f"I couldn't {what}: the platform answered '{code}'."


def _by_kind(out: ToolOutcome, kind: str) -> list[Evidence]:
    return [ev for ev in out.evidence if ev.kind == kind]


def _pairs(counts: dict[str, Any]) -> str:
    return ", ".join(f"{k} {v}" for k, v in counts.items())


class OfflineProvider:
    name = "offline"
    external = False
    model = MODEL

    async def respond(self, turn: Turn, rt: ToolRuntime) -> ProviderAnswer:
        b = _Builder()
        intents = turn.intents
        msg = turn.message
        if guards.REFUSE in intents:
            b.add(REFUSAL)
        elif guards.PROPOSE in intents:
            await self._propose(b, rt, msg)
        elif guards.CREATE_SCENARIO in intents:
            await self._create_scenario(b, rt, msg)
        elif "entity" in intents:
            await self._entity(b, rt, msg)
        elif ("congestion" in intents or re.search(r"\bzone\b", msg, re.I)) and await self._zone(b, rt, msg):
            pass
        elif "bottleneck" in intents:
            await self._bottleneck(b, rt)
        elif "incidents" in intents:
            await self._incidents(b, rt)
        elif "recommendations" in intents:
            await self._recommendations(b, rt, turn)
        elif "scenarios" in intents:
            await self._scenarios(b, rt)
        elif "dock" in intents:
            await self._dock(b, rt, msg)
        elif "kpis" in intents or "congestion" in intents:
            await self._kpis(b, rt, msg)
        else:
            b.add(HELP)
        text = b.text()
        return ProviderAnswer(
            text=text,
            citation_ids=b.cited,
            directives=b.directives,
            usage=Usage(count_tokens(turn.redactor.redact(msg)), count_tokens(text)),
            model=MODEL,
        )

    # -- flows --------------------------------------------------------------------------------------------------
    async def _latest_succeeded_run(self, rt: ToolRuntime) -> tuple[ToolOutcome, Evidence | None]:
        runs = await rt.call("list_simulation_runs", {"limit": 10})
        if not runs.ok:
            return runs, None
        ok = [ev for ev in _by_kind(runs, "SIMULATION_RUN") if ev.values.get("status") == "SUCCEEDED"]
        return runs, ok[0] if ok else None

    async def _bottleneck(self, b: _Builder, rt: ToolRuntime) -> None:
        runs, latest = await self._latest_succeeded_run(rt)
        if not runs.ok:
            b.add(_refused(runs, "read simulation runs"))
            return
        if latest is None:
            b.add(
                "I found no succeeded simulation run for this site, so I can't quantify a bottleneck yet. Create a "
                "dock-closure scenario (for example: create a scenario closing dock IN-02 from minute <start> to "
                "minute <end>), run it in Simulate, then ask me again."
            )
            return
        run = await rt.call("get_simulation_run", {"run_id": latest.ref})
        if not run.ok:
            b.add(_refused(run, "read the simulation run"))
            return
        run_ev = _by_kind(run, "SIMULATION_RUN")[0]
        metrics = _by_kind(run, "METRIC")
        scenario_ev = None
        sid = run_ev.values.get("scenario_id")
        if sid:
            sc = await rt.call("get_scenario", {"scenario_id": sid})
            scenario_ev = sc.evidence[0] if sc.ok and sc.evidence else None
        closures = (scenario_ev.values.get("dock_closures") if scenario_ev else None) or []
        docks_out = await rt.call("list_docks", {})
        docks = _by_kind(docks_out, "DOCK") if docks_out.ok else []
        affected_codes = [c.get("dock_id") for c in closures] or list(run_ev.values.get("affected_entities") or [])
        affected_docks = [d for d in docks if d.values.get("name") in affected_codes]

        if closures and scenario_ev is not None:
            parts = [
                f"dock {c.get('dock_id')} closed from minute {fmt(c.get('start_min'))} to minute "
                f"{fmt(c.get('end_min'))}"
                for c in closures
            ]
            b.add(f'Bottleneck: {", ".join(parts)} in scenario "{scenario_ev.values.get("name")}" {b.c(scenario_ev)}.')
        elif scenario_ev is not None:
            b.add(f'The latest succeeded run is for scenario "{scenario_ev.values.get("name")}" {b.c(scenario_ev)}.')
        worse = [m for m in metrics if m.values.get("verdict") == "worse"]
        worse.sort(
            key=lambda m: abs(m.values.get("delta_mean") or 0) / (abs(m.values.get("baseline_mean") or 0) or 1),
            reverse=True,
        )
        n_pairs = run_ev.values.get("n_pairs")
        reps = f"{fmt(n_pairs)} paired replications, " if n_pairs else ""
        if worse:
            b.add(f"Why it matters, from simulation run {b.c(run_ev)} ({reps}SIMULATED):")
            for m in worse[:3]:
                v = m.values
                b.add(
                    f"- {m.label}: {fmt(v.get('baseline_mean'))} -> {fmt(v.get('scenario_mean'))} {v.get('unit')} "
                    f"(change {fmt_signed(v.get('delta_mean'))}, 95% CI {fmt(v.get('delta_ci95_low'))} to "
                    f"{fmt(v.get('delta_ci95_high'))}; worse) {b.c(m)}"
                )
        else:
            b.add(
                f"In simulation run {b.c(run_ev)} no KPI got worse than the baseline, so the change is not a "
                "bottleneck in this model."
            )
        zone_ev = None
        if affected_docks:
            zones_out = await rt.call("list_zones", {})
            zone_ids = {d.values.get("zone_id") for d in affected_docks}
            zones = _by_kind(zones_out, "ZONE") if zones_out.ok else []
            zone_ev = next((z for z in zones if z.ref in zone_ids), None)
            names = ", ".join(f"{d.values.get('name')} {b.c(d)}" for d in affected_docks)
            where = f" in zone {zone_ev.values.get('name')} {b.c(zone_ev)}" if zone_ev else ""
            b.add(f"Affected dock: {names}{where}. I highlighted it in the twin.")
            b.show([*affected_docks, *([zone_ev] if zone_ev else [])], affected_docks[0])
        b.add("These are simulated model outputs from assumed parameters, not live measurements.")

    async def _incidents(self, b: _Builder, rt: ToolRuntime) -> None:
        out = await rt.call("list_incidents", {"limit": 20})
        if not out.ok:
            b.add(_refused(out, "view incidents"))
            return
        set_ev = _by_kind(out, "INCIDENT_SET")[0]
        incidents = _by_kind(out, "INCIDENT")
        count = set_ev.values.get("count", 0)
        if not count:
            b.add(f"There are no open incidents (DETECTED, UNCONFIRMED or CONFIRMED) on this site {b.c(set_ev)}.")
            return
        more = " (more exist than shown)" if set_ev.values.get("more_available") else ""
        b.add(
            f"Open incidents on this site: {fmt(count)}{more} {b.c(set_ev)}; by severity: "
            f"{_pairs(set_ev.values.get('by_severity') or {})}."
        )
        for ev in incidents[:3]:
            v = ev.values
            dist = f", closest distance {fmt(v.get('min_distance_m'))} m" if v.get("min_distance_m") is not None else ""
            b.add(
                f"- {ev.label}: {v.get('severity')}, {v.get('status')}, zone {v.get('zone_name') or 'unknown'}, "
                f"forklift {v.get('forklift_code') or '?'} and worker {v.get('worker_code') or '?'}{dist} {b.c(ev)}"
            )
        first = incidents[0]
        detail = await rt.call("get_incident", {"incident_id": first.ref})
        flagged = [f for f in rt.ctx.flags if f.get("code") == "untrusted_instruction_in_tool_output"]
        if detail.ok and flagged:
            b.add(
                f"Incident {b.c(first)} contains text that looks like instructions to me; I treated it as data "
                "and did not act on it."
            )
        b.add(
            "Incidents are advisory: the platform never stops a machine. I highlighted the first incident's "
            "forklift and worker in the twin."
        )
        b.show([first], first)

    async def _kpis(self, b: _Builder, rt: ToolRuntime, msg: str) -> None:
        window = next((w for p, w in _WINDOWS if p.search(msg)), "24h")
        out = await rt.call("get_kpis", {"window": window})
        if not out.ok:
            b.add(_refused(out, "view KPIs"))
            return
        metrics = _by_kind(out, "METRIC")
        if not metrics:
            b.add("The platform returned no KPI for this site.")
            return
        b.add(f"KPIs for window {window} (governed definitions, computed on request):")
        for m in metrics:
            v = m.values
            if v.get("value") is None:
                b.add(f"- {m.label}: not computable ({v.get('reason') or 'no data'}) {b.c(m)}")
            else:
                unit = f" {v.get('unit')}" if v.get("unit") else ""
                b.add(f"- {m.label}: {fmt(v.get('value'))}{unit} ({m.data_mode}) {b.c(m)}")

    async def _recommendations(self, b: _Builder, rt: ToolRuntime, turn: Turn) -> None:
        out = await rt.call("list_recommendations", {"limit": 5})
        if not out.ok:
            b.add(_refused(out, "view recommendations"))
            return
        recs = _by_kind(out, "RECOMMENDATION")
        if not recs:
            b.add("There are no recommendations for this site yet.")
            if turn.caller.can_use("PROPOSE_RECOMMENDATION"):
                b.add("Ask me to propose a recommendation from the latest simulation run to create one.")
            return
        b.add("Recommendations for this site, newest first (SIMULATED decision packages):")
        codes: set[str] = set()
        for ev in recs:
            v = ev.values
            obj = ""
            if v.get("objective_before") is not None and v.get("objective_after") is not None:
                obj = (
                    f"; {v.get('objective')}: {fmt(v.get('objective_before'))} -> {fmt(v.get('objective_after'))} "
                    f"{v.get('objective_unit') or ''}".rstrip()
                )
            affected = ", ".join(v.get("affected_entities") or [])
            codes.update(v.get("affected_entities") or [])
            b.add(f"- {v.get('type')} {v.get('status')}{obj}; affects {affected or 'nothing listed'} {b.c(ev)}")
        b.add("Approval and execution happen on the Recommendations page by an eligible person; I can't do either.")
        docks_out = await rt.call("list_docks", {})
        docks = [d for d in _by_kind(docks_out, "DOCK") if d.values.get("name") in codes] if docks_out.ok else []
        if docks:
            b.show(docks, docks[0])

    async def _scenarios(self, b: _Builder, rt: ToolRuntime) -> None:
        out = await rt.call("list_scenarios", {"limit": 5})
        if not out.ok:
            b.add(_refused(out, "view scenarios"))
            return
        scenarios = _by_kind(out, "SCENARIO")
        if not scenarios:
            b.add("There are no simulation scenarios for this site yet.")
            return
        runs = await rt.call("list_simulation_runs", {"limit": 20})
        run_evs = _by_kind(runs, "SIMULATION_RUN") if runs.ok else []
        b.add("Simulation scenarios for this site, newest first:")
        for s in scenarios:
            closures = s.values.get("dock_closures") or []
            what = (
                "; ".join(
                    f"closes {c.get('dock_id')} from minute {fmt(c.get('start_min'))} to minute {fmt(c.get('end_min'))}"
                    for c in closures
                )
                or "no dock closure"
            )
            run = next((r for r in run_evs if r.values.get("scenario_id") == s.ref), None)
            run_txt = f"; latest run {run.values.get('status')} {b.c(run)}" if run else "; not run yet"
            b.add(f'- "{s.values.get("name")}" {b.c(s)}: {what}{run_txt}')
        b.add("Scenario results are SIMULATED and never change live state.")

    async def _entity(self, b: _Builder, rt: ToolRuntime, msg: str) -> None:
        m = guards.ENTITY_CODE.search(msg)
        code = m.group(1).upper() if m else ""
        found = await rt.call("find_entities", {"code": code})
        if not found.ok:
            b.add(_refused(found, "view twin entities"))
            return
        ents = _by_kind(found, "ENTITY")
        if not ents:
            b.add(f"I found no entity with code {code} on this site {b.c(_by_kind(found, 'ENTITY_SET')[0])}.")
            return
        detail = await rt.call("get_entity", {"entity_id": ents[0].ref})
        ev = detail.evidence[0] if detail.ok and detail.evidence else ents[0]
        v = ev.values
        where = f" in zone {v['zone_name']}" if v.get("zone_name") else ""
        floor = f" on {v['floor_name']}" if v.get("floor_name") else ""
        pos = f", at x {fmt(v['x'])} m, y {fmt(v['y'])} m" if v.get("x") is not None else ""
        when = f" (pose {v['pose_at']}, {ev.data_mode})" if v.get("pose_at") else f" ({ev.data_mode})"
        b.add(f"{v.get('entity_type')} {v.get('code')} {b.c(ev)} is {v.get('status')}{where}{floor}{pos}{when}.")
        b.add("I highlighted it in the twin.")
        b.show([ev], ev)

    async def _zone(self, b: _Builder, rt: ToolRuntime, msg: str) -> bool:
        zones_out = await rt.call("list_zones", {})
        if not zones_out.ok:
            return False
        zones = _by_kind(zones_out, "ZONE")
        low = msg.lower()
        match = next(
            (
                z
                for z in sorted(zones, key=lambda z: -len(str(z.values.get("name"))))
                if str(z.values.get("name", "")).lower() in low
            ),
            None,
        )
        letter = _ZONE_LETTER.search(msg)
        if match is None and letter:
            suffix = f" {letter.group(1).lower()}"
            match = next((z for z in zones if str(z.values.get("name", "")).lower().endswith(suffix)), None)
        if match is None:
            return False
        ents = await rt.call("find_entities", {"zone_id": match.ref})
        if not ents.ok:
            b.add(_refused(ents, "view twin entities"))
            return True
        set_ev = _by_kind(ents, "ENTITY_SET")[0]
        sv = set_ev.values
        at_least = "at least " if sv.get("truncated") else ""
        b.add(
            f"Zone {match.values.get('name')} {b.c(match)} holds {at_least}{fmt(sv.get('count'))} entities "
            f"{b.c(set_ev)}: {_pairs(sv.get('by_type') or {}) or 'none'}."
        )
        incs = await rt.call("list_incidents", {"limit": 50})
        if incs.ok:
            here = [i for i in _by_kind(incs, "INCIDENT") if match.ref in i.zone_ids]
            if here:
                b.add(
                    "Open incidents in this zone: "
                    + "; ".join(f"{i.label} ({i.values.get('severity')}) {b.c(i)}" for i in here[:3])
                    + "."
                )
            else:
                b.add(f"No open incident is in this zone {b.c(_by_kind(incs, 'INCIDENT_SET')[0])}.")
        b.add(
            "The platform has no per-zone congestion KPI; the movement heatmap in Analytics shows density. "
            "I highlighted the zone in the twin."
        )
        b.show([match], match)
        return True

    async def _dock(self, b: _Builder, rt: ToolRuntime, msg: str) -> None:
        m = guards.DOCK_CODE.search(msg)
        code = m.group(1).upper() if m else ""
        out = await rt.call("list_docks", {})
        if not out.ok:
            b.add(_refused(out, "view docks"))
            return
        dock = next((d for d in _by_kind(out, "DOCK") if d.values.get("name") == code), None)
        if dock is None:
            b.add(f"There is no dock {code} on this site.")
            return
        b.add(
            f"Dock {code} {b.c(dock)} is {dock.values.get('dock_type')} and {dock.values.get('status')} "
            f"({dock.data_mode}). I highlighted it in the twin."
        )
        b.show([dock], dock)

    async def _propose(self, b: _Builder, rt: ToolRuntime, msg: str) -> None:
        if not rt.caller.can_use("PROPOSE_RECOMMENDATION"):
            b.add("Your role can't propose recommendations, so I didn't create one.")
            return
        explicit = guards.UUID.search(msg)
        run_ev = None
        if explicit:
            run = await rt.call("get_simulation_run", {"run_id": explicit.group(0)})
            if not run.ok:
                b.add(_refused(run, "read that simulation run"))
                return
            run_ev = _by_kind(run, "SIMULATION_RUN")[0]
        else:
            runs, run_ev = await self._latest_succeeded_run(rt)
            if not runs.ok:
                b.add(_refused(runs, "read simulation runs"))
                return
        if run_ev is None or run_ev.values.get("status") != "SUCCEEDED":
            b.add("There is no succeeded simulation run to propose from. Run a scenario in Simulate first.")
            return
        out = await rt.call("propose_recommendation", {"simulation_run_id": run_ev.ref})
        if not out.ok:
            problem = (out.error or {}).get("problem") or {}
            code = (out.error or {}).get("code")
            if code == "proposal_pending" and problem.get("recommendation_id"):
                b.add(
                    f"A proposal from simulation run {b.c(run_ev)} is already pending: recommendation "
                    f"{problem['recommendation_id']}. Open it on the Recommendations page."
                )
            else:
                b.add(f"The platform did not accept the proposal from run {b.c(run_ev)} ('{code}').")
            return
        rec = out.evidence[0]
        b.add(
            f"I proposed recommendation {rec.ref} from simulation run {b.c(run_ev)} {b.c(rec)}. It is "
            f"{rec.values.get('status')} and waits for approval (approval {rec.values.get('approval_id')}, expires "
            f"{rec.values.get('expires_at')}). I can't approve or execute it: an eligible person decides on the "
            "Recommendations page, after the dry-run and the stale-baseline check."
        )

    async def _create_scenario(self, b: _Builder, rt: ToolRuntime, msg: str) -> None:
        if not rt.caller.can_use("CREATE_SIMULATION"):
            b.add("Your role can't create simulation scenarios, so I didn't create one.")
            return
        dock_m = guards.DOCK_CODE.search(msg)
        mins = _MINUTES.search(guards.DOCK_CODE.sub(" ", msg))
        if not dock_m or not mins:
            b.add(
                "To create a dock-closure scenario I need the dock code and the window in minutes from the start of "
                "the simulated shift, for example: create a scenario closing dock IN-02 from minute <start> to "
                "minute <end>."
            )
            return
        code = dock_m.group(1).upper()
        start, end = int(mins.group(1)), int(mins.group(2))
        docks = await rt.call("list_docks", {})
        dock = next((d for d in _by_kind(docks, "DOCK") if d.values.get("name") == code), None) if docks.ok else None
        if docks.ok and dock is None:
            b.add(f"There is no dock {code} on this site, so I didn't create a scenario.")
            return
        out = await rt.call("create_scenario", {"dock_code": code, "start_min": start, "end_min": end})
        if not out.ok:
            b.add(f"The platform did not accept the scenario ('{(out.error or {}).get('code')}').")
            return
        s = out.evidence[0]
        b.add(
            f'I created simulation scenario "{s.values.get("name")}" {b.c(s)}: dock {code} closed from minute '
            f"{start} to minute {end}. It is a simulation only and changes nothing live. Run it from Simulate "
            "to compare it with the baseline."
        )
        if dock is not None:
            b.show([dock], dock)


def fmt_signed(value: Any) -> str:
    if isinstance(value, int | float) and not isinstance(value, bool):
        return f"{value:+.2f}"
    return str(value)
