"""Allowlisted assistant tools (REQ-102, REQ-104, ADR-66).

Each tool wraps exactly one existing platform endpoint, declares the permission-matrix action it needs and validates
its arguments with a strict pydantic model (unknown fields are rejected). ``site_id`` is never a tool argument: the
gateway binds it from the request, so neither a template nor a model can pick another site, and the tenant always
comes from the caller's token.

Kinds:

* ``read`` — GET only.
* ``propose`` — one of the two proposal commands (``POST /api/scenarios``, ``POST /api/recommendations``). Offered
  only when the caller's roles can use the action *and* the user's own message asked for it. There is no approve,
  reject or execute tool.
"""

from __future__ import annotations

import time
import uuid
from collections import Counter
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from . import guards
from .evidence import Evidence, EvidenceLedger
from .identity import Caller
from .platform_client import PlatformClient, PlatformError

MAX_CALLS_PER_EXCHANGE = 12

OPEN_INCIDENT_STATUSES = ["DETECTED", "UNCONFIRMED", "CONFIRMED"]


class _Args(BaseModel):
    model_config = ConfigDict(extra="forbid")


class NoArgs(_Args):
    pass


class EntityArgs(_Args):
    entity_id: uuid.UUID


class FindEntitiesArgs(_Args):
    code: str | None = Field(default=None, max_length=20, pattern=r"^[A-Za-z]{1,4}-\d{2,4}$")
    type: Literal["FORKLIFT", "TRUCK", "PALLET", "ROBOT", "WORKER", "CAMERA"] | None = None
    zone_id: uuid.UUID | None = None
    limit: int = Field(default=500, ge=1, le=500)


class ListIncidentsArgs(_Args):
    status: list[Literal["DETECTED", "UNCONFIRMED", "CONFIRMED", "REJECTED", "RESOLVED", "CLOSED"]] | None = None
    severity: list[Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]] | None = None
    limit: int = Field(default=20, ge=1, le=50)


class IncidentArgs(_Args):
    incident_id: uuid.UUID


class KpiArgs(_Args):
    window: Literal["15m", "1h", "6h", "24h", "7d"] = "24h"


class ListArgs(_Args):
    limit: int = Field(default=10, ge=1, le=20)


class ScenarioArgs(_Args):
    scenario_id: uuid.UUID


class ListRunsArgs(_Args):
    scenario_id: uuid.UUID | None = None
    limit: int = Field(default=10, ge=1, le=20)


class RunArgs(_Args):
    run_id: uuid.UUID


class ListRecommendationsArgs(_Args):
    status: (
        Literal["PROPOSED", "APPROVED", "REJECTED", "EXPIRED", "STALE", "EXECUTING", "VERIFIED", "PARTIAL", "FAILED"]
        | None
    ) = None
    limit: int = Field(default=5, ge=1, le=20)


class RecommendationArgs(_Args):
    recommendation_id: uuid.UUID


class CreateScenarioArgs(_Args):
    dock_code: str = Field(pattern=r"^(IN|OUT)-\d{2}$")
    start_min: int = Field(ge=0, le=10080)
    end_min: int = Field(ge=1, le=10080)
    name: str | None = Field(default=None, max_length=200)


class ProposeArgs(_Args):
    simulation_run_id: uuid.UUID


@dataclass
class ToolContext:
    client: PlatformClient
    site_id: str
    ledger: EvidenceLedger
    exchange_id: str
    flags: list[dict[str, Any]] = field(default_factory=list)

    def flag_untrusted(self, ev: Evidence, text: str | None, where: str) -> None:
        if guards.scan_untrusted(text):
            self.flags.append(
                {
                    "code": "untrusted_instruction_in_tool_output",
                    "citation_id": ev.id,
                    "field": where,
                    "handling": "treated as data; not followed",
                }
            )

    def idempotency_key(self, tool: str, args: dict[str, Any]) -> str:
        # Stable per exchange and intent: a retry of the same exchange never doubles a proposal.
        return str(uuid.uuid5(uuid.UUID(self.exchange_id), f"{tool}:{sorted(args.items())}"))


@dataclass
class ToolData:
    data: dict[str, Any]
    evidence: list[Evidence]


Handler = Callable[[ToolContext, Any], Awaitable[ToolData]]


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    args: type[_Args]
    action: str
    kind: Literal["read", "propose"]
    handler: Handler
    intent: str | None = None

    def json_schema(self) -> dict[str, Any]:
        schema = self.args.model_json_schema()
        schema.pop("title", None)
        schema["additionalProperties"] = False
        return schema


# -- helpers --------------------------------------------------------------------------------------------------------


def _center(bounds: dict[str, Any] | None, z: float = 0.0) -> dict[str, float] | None:
    if not bounds:
        return None
    return {"x": (bounds["min_x"] + bounds["max_x"]) / 2, "y": (bounds["min_y"] + bounds["max_y"]) / 2, "z": z}


def _xyz(p: dict[str, Any] | None) -> dict[str, float] | None:
    if not p or p.get("x") is None or p.get("y") is None:
        return None
    return {"x": float(p["x"]), "y": float(p["y"]), "z": float(p.get("z") or 0.0)}


def _entity_evidence(ctx: ToolContext, tool: str, e: dict[str, Any]) -> Evidence:
    pose = e.get("pose") or {}
    return ctx.ledger.add(
        "ENTITY",
        e["id"],
        f"{e.get('entity_type', 'ENTITY')} {e.get('code', e['id'])}",
        f"/api/entities/{e['id']}",
        tool,
        data_mode=e.get("data_mode") or pose.get("data_mode"),
        values={
            k: v
            for k, v in {
                "code": e.get("code"),
                "entity_type": e.get("entity_type"),
                "status": e.get("status"),
                "zone_name": e.get("zone_name"),
                "floor_name": e.get("floor_name"),
                "x": pose.get("x"),
                "y": pose.get("y"),
                "pose_at": pose.get("occurred_at"),
                "pose_source": pose.get("source"),
            }.items()
            if v is not None
        },
        entity_ids=[e["id"]],
        location=_xyz(pose),
        floor_id=e.get("floor_id"),
        zone_ids=[e["zone_id"]] if e.get("zone_id") else [],
    )


def _incident_evidence(ctx: ToolContext, tool: str, inc: dict[str, Any]) -> Evidence:
    forklift = inc.get("forklift") or {}
    worker = inc.get("worker") or {}
    camera = inc.get("camera") or {}
    location = _xyz(camera.get("focus_target")) or _xyz(inc.get("location"))
    ev = ctx.ledger.add(
        "INCIDENT",
        inc["id"],
        str(inc.get("title") or f"Incident {inc['id']}"),
        f"/api/incidents/{inc['id']}",
        tool,
        data_mode=inc.get("data_mode"),
        values={
            k: v
            for k, v in {
                "status": inc.get("status"),
                "severity": inc.get("severity"),
                "zone_name": inc.get("zone_name"),
                "min_distance_m": inc.get("min_distance_m"),
                "alert_count": inc.get("alert_count"),
                "detected_at": inc.get("detected_at"),
                "forklift_code": forklift.get("code"),
                "worker_code": worker.get("code"),
                "advisory": inc.get("advisory"),
            }.items()
            if v is not None
        },
        entity_ids=[i for i in (forklift.get("entity_id"), worker.get("entity_id")) if i],
        zone_ids=[inc["zone_id"]] if inc.get("zone_id") else [],
        location=location,
        floor_id=inc.get("floor_id"),
    )
    ctx.flag_untrusted(ev, inc.get("title"), "title")
    ctx.flag_untrusted(ev, inc.get("status_reason"), "status_reason")
    for i, note in enumerate(inc.get("notes") or []):
        ctx.flag_untrusted(ev, note.get("text") if isinstance(note, dict) else str(note), f"notes[{i}]")
    return ev


def _scenario_evidence(ctx: ToolContext, tool: str, s: dict[str, Any], created: bool = False) -> Evidence:
    closures = [
        {k: c.get(k) for k in ("dock_id", "start_min", "end_min")}
        for c in ((s.get("overlay") or {}).get("dock_closures") or [])
    ]
    ev = ctx.ledger.add(
        "SCENARIO",
        s["id"],
        str(s.get("name") or s["id"]),
        f"/api/scenarios/{s['id']}",
        tool,
        data_mode="SIMULATED",
        values={
            "name": s.get("name"),
            "affected_entities": s.get("affected_entities") or [],
            "dock_closures": closures,
            "created_at": s.get("created_at"),
            **({"created_by_assistant_request": True} if created else {}),
        },
    )
    ctx.flag_untrusted(ev, s.get("description"), "description")
    return ev


def _run_evidence(ctx: ToolContext, tool: str, run: dict[str, Any]) -> list[Evidence]:
    rid = run.get("run_id") or run.get("id")
    results = run.get("results") or {}
    deltas = results.get("deltas") or []
    run_ev = ctx.ledger.add(
        "SIMULATION_RUN",
        rid,
        f"Simulation run {rid}",
        f"/api/simulation-runs/{rid}",
        tool,
        data_mode="SIMULATED",
        values={
            k: v
            for k, v in {
                "status": run.get("status"),
                "scenario_id": run.get("scenario_id"),
                "finished_at": run.get("finished_at"),
                "results_hash": run.get("results_hash"),
                "affected_entities": results.get("affected_entities"),
                "n_pairs": deltas[0].get("n_pairs") if deltas else None,
                "engine": (run.get("engine") or {}).get("name"),
            }.items()
            if v is not None
        },
    )
    out = [run_ev]
    for d in deltas:
        out.append(
            ctx.ledger.add(
                "METRIC",
                f"{rid}#{d.get('key')}",
                str(d.get("label") or d.get("key")),
                f"/api/simulation-runs/{rid}",
                tool,
                data_mode="SIMULATED",
                values={
                    k: d.get(k)
                    for k in (
                        "key",
                        "unit",
                        "better",
                        "baseline_mean",
                        "scenario_mean",
                        "delta_mean",
                        "delta_ci95_low",
                        "delta_ci95_high",
                        "n_pairs",
                        "verdict",
                    )
                }
                | {"ci_level_pct": 95},
            )
        )
    return out


def _recommendation_evidence(ctx: ToolContext, tool: str, r: dict[str, Any]) -> Evidence:
    rid = r.get("recommendation_id") or r.get("id")
    before = r.get("objective_before") or {}
    after = r.get("objective_after") or {}
    ev = ctx.ledger.add(
        "RECOMMENDATION",
        rid,
        f"{r.get('type', 'RECOMMENDATION')} ({r.get('status')})",
        f"/api/recommendations/{rid}",
        tool,
        data_mode=r.get("data_mode") or "SIMULATED",
        values={
            k: v
            for k, v in {
                "status": r.get("status"),
                "type": r.get("type"),
                "objective": before.get("name"),
                "objective_unit": before.get("unit"),
                "objective_before": before.get("value"),
                "objective_after": after.get("value"),
                "affected_entities": r.get("affected_entities"),
                "approval_id": r.get("approval_id"),
                "expires_at": r.get("expires_at"),
                "steps": len(((r.get("execution_plan") or {}).get("steps")) or []) or None,
                "feasibility": (r.get("feasibility") or {}).get("status"),
            }.items()
            if v is not None
        },
    )
    ctx.flag_untrusted(ev, r.get("rationale"), "rationale")
    return ev


def _brief(evs: list[Evidence]) -> list[dict[str, Any]]:
    return [ev.brief() for ev in evs]


# -- read tools -----------------------------------------------------------------------------------------------------


async def _get_entity(ctx: ToolContext, a: EntityArgs) -> ToolData:
    e = await ctx.client.get(f"/api/entities/{a.entity_id}")
    if e.get("site_id") and str(e["site_id"]) != ctx.site_id:
        raise PlatformError(404, "Not found", "The entity is not on the selected site.")
    ev = _entity_evidence(ctx, "get_entity", e)
    return ToolData({"entity": ev.brief()}, [ev])


async def _find_entities(ctx: ToolContext, a: FindEntitiesArgs) -> ToolData:
    page = await ctx.client.get(
        "/api/twin/entities",
        {
            "site_id": ctx.site_id,
            "type": a.type,
            "zone_id": str(a.zone_id) if a.zone_id else None,
            "limit": a.limit,
        },
    )
    items = page.get("items") or []
    if a.code:
        items = [e for e in items if str(e.get("code", "")).upper() == a.code.upper()]
    by_type = Counter(str(e.get("entity_type")) for e in items)
    truncated = bool(page.get("next_cursor")) and not a.code
    evs: list[Evidence] = []
    if a.code or len(items) <= 3:
        evs = [_entity_evidence(ctx, "find_entities", e) for e in items[:3]]
    filt = ",".join(f"{k}={v}" for k, v in (("type", a.type), ("zone_id", a.zone_id), ("code", a.code)) if v)
    data_modes = sorted({str(e.get("data_mode")) for e in items if e.get("data_mode")})
    set_ev = ctx.ledger.add(
        "ENTITY_SET",
        f"entities?{filt}",
        f"Entities ({filt or 'all'})",
        "/api/twin/entities",
        "find_entities",
        data_mode=data_modes[0] if len(data_modes) == 1 else ("MIXED" if data_modes else None),
        values={
            "count": len(items),
            "by_type": dict(sorted(by_type.items())),
            "truncated": truncated,
            "page_limit": a.limit,
        },
        entity_ids=[e["id"] for e in items[:50]],
        zone_ids=[str(a.zone_id)] if a.zone_id else [],
    )
    return ToolData({"set": set_ev.brief(), "entities": _brief(evs)}, [set_ev, *evs])


async def _list_zones(ctx: ToolContext, _a: NoArgs) -> ToolData:
    page = await ctx.client.get("/api/twin/zones", {"site_id": ctx.site_id})
    evs = [
        ctx.ledger.add(
            "ZONE",
            z["id"],
            str(z.get("name")),
            f"/api/twin/zones?site_id={ctx.site_id}",
            "list_zones",
            data_mode=z.get("data_mode"),
            values={"name": z.get("name"), "zone_type": z.get("zone_type"), **(z.get("bounds") or {})},
            zone_ids=[z["id"]],
            location=_center(z.get("bounds")),
            floor_id=z.get("floor_id"),
        )
        for z in page.get("items") or []
    ]
    return ToolData({"zones": _brief(evs)}, evs)


async def _list_docks(ctx: ToolContext, _a: NoArgs) -> ToolData:
    page = await ctx.client.get("/api/twin/docks", {"site_id": ctx.site_id})
    evs = [
        ctx.ledger.add(
            "DOCK",
            d["id"],
            str(d.get("name")),
            f"/api/twin/docks?site_id={ctx.site_id}",
            "list_docks",
            data_mode=d.get("data_mode"),
            values={
                "name": d.get("name"),
                "dock_type": d.get("dock_type"),
                "status": d.get("status"),
                "zone_id": d.get("zone_id"),
            },
            dock_ids=[d["id"]],
            location=_xyz(d.get("position")),
            floor_id=d.get("floor_id"),
        )
        for d in page.get("items") or []
    ]
    return ToolData({"docks": _brief(evs)}, evs)


async def _list_incidents(ctx: ToolContext, a: ListIncidentsArgs) -> ToolData:
    statuses = a.status or OPEN_INCIDENT_STATUSES
    page = await ctx.client.get(
        "/api/incidents",
        {
            "site_id": ctx.site_id,
            "status": ",".join(statuses),
            "severity": ",".join(a.severity) if a.severity else None,
            "limit": a.limit,
        },
    )
    items = page.get("items") or []
    evs = [_incident_evidence(ctx, "list_incidents", i) for i in items]
    set_ev = ctx.ledger.add(
        "INCIDENT_SET",
        f"incidents?status={','.join(statuses)}",
        f"Incidents ({', '.join(statuses)})",
        f"/api/incidents?site_id={ctx.site_id}",
        "list_incidents",
        data_mode=None,
        values={
            "count": len(items),
            "statuses": statuses,
            "more_available": bool(page.get("next_cursor")),
            "by_severity": dict(sorted(Counter(str(i.get("severity")) for i in items).items())),
            "by_status": dict(sorted(Counter(str(i.get("status")) for i in items).items())),
        },
    )
    return ToolData({"set": set_ev.brief(), "incidents": _brief(evs)}, [set_ev, *evs])


async def _get_incident(ctx: ToolContext, a: IncidentArgs) -> ToolData:
    inc = await ctx.client.get(f"/api/incidents/{a.incident_id}")
    ev = _incident_evidence(ctx, "get_incident", inc)
    ev.values["notes_count"] = len(inc.get("notes") or [])
    notes = [n.get("text") if isinstance(n, dict) else str(n) for n in inc.get("notes") or []]
    # Notes are untrusted free text: handed over as quoted data, never as instructions.
    return ToolData({"incident": ev.brief(), "untrusted_notes": notes}, [ev])


async def _get_kpis(ctx: ToolContext, a: KpiArgs) -> ToolData:
    res = await ctx.client.get("/api/kpis", {"site_id": ctx.site_id, "window": a.window})
    evs = [
        ctx.ledger.add(
            "METRIC",
            f"kpi:{k['id']}@{a.window}",
            str(k.get("name") or k["id"]),
            f"/api/kpis?site_id={ctx.site_id}&window={a.window}",
            "get_kpis",
            data_mode=k.get("data_mode"),
            values={key: k.get(key) for key in ("id", "value", "unit", "reason", "sample_size", "as_of")}
            | {"window": a.window},
        )
        for k in res.get("items") or []
    ]
    return ToolData({"computed_at": res.get("computed_at"), "kpis": _brief(evs)}, evs)


async def _list_scenarios(ctx: ToolContext, a: ListArgs) -> ToolData:
    page = await ctx.client.get("/api/scenarios", {"site_id": ctx.site_id, "limit": a.limit})
    evs = [_scenario_evidence(ctx, "list_scenarios", s) for s in page.get("items") or []]
    return ToolData({"scenarios": _brief(evs)}, evs)


async def _get_scenario(ctx: ToolContext, a: ScenarioArgs) -> ToolData:
    s = await ctx.client.get(f"/api/scenarios/{a.scenario_id}")
    ev = _scenario_evidence(ctx, "get_scenario", s)
    return ToolData({"scenario": ev.brief()}, [ev])


async def _list_runs(ctx: ToolContext, a: ListRunsArgs) -> ToolData:
    params: dict[str, Any] = {"limit": a.limit}
    if a.scenario_id:
        params["scenario_id"] = str(a.scenario_id)
    else:
        params["site_id"] = ctx.site_id
    page = await ctx.client.get("/api/simulation-runs", params)
    evs: list[Evidence] = []
    for run in page.get("items") or []:
        if str(run.get("site_id") or ctx.site_id) != ctx.site_id:
            continue  # a scenario of another site never leaks into this site's answer
        evs.append(_run_evidence(ctx, "list_simulation_runs", {**run, "results": None})[0])
    return ToolData({"runs": _brief(evs)}, evs)


async def _get_run(ctx: ToolContext, a: RunArgs) -> ToolData:
    run = await ctx.client.get(f"/api/simulation-runs/{a.run_id}")
    if run.get("site_id") and str(run["site_id"]) != ctx.site_id:
        raise PlatformError(404, "Not found", "The run is not on the selected site.")
    evs = _run_evidence(ctx, "get_simulation_run", run)
    return ToolData({"run": evs[0].brief(), "kpi_deltas": _brief(evs[1:])}, evs)


async def _list_recommendations(ctx: ToolContext, a: ListRecommendationsArgs) -> ToolData:
    page = await ctx.client.get("/api/recommendations", {"site_id": ctx.site_id, "status": a.status, "limit": a.limit})
    evs = [_recommendation_evidence(ctx, "list_recommendations", r) for r in page.get("items") or []]
    return ToolData({"recommendations": _brief(evs)}, evs)


async def _get_recommendation(ctx: ToolContext, a: RecommendationArgs) -> ToolData:
    r = await ctx.client.get(f"/api/recommendations/{a.recommendation_id}")
    if r.get("site_id") and str(r["site_id"]) != ctx.site_id:
        raise PlatformError(404, "Not found", "The recommendation is not on the selected site.")
    ev = _recommendation_evidence(ctx, "get_recommendation", r)
    return ToolData({"recommendation": ev.brief(), "untrusted_rationale": r.get("rationale")}, [ev])


# -- proposal tools (the only writes, through the existing commands) ------------------------------------------------


async def _create_scenario(ctx: ToolContext, a: CreateScenarioArgs) -> ToolData:
    if a.end_min <= a.start_min:
        raise PlatformError(400, "Validation failed", "end_min must be after start_min.")
    body = {
        "site_id": ctx.site_id,
        "name": a.name or f"Close dock {a.dock_code} from minute {a.start_min} to {a.end_min} (assistant request)",
        "overlay": {
            "dock_closures": [
                {
                    "dock_id": a.dock_code,
                    "start_min": a.start_min,
                    "end_min": a.end_min,
                    "reason": "requested through the AI assistant",
                }
            ]
        },
    }
    ack = await ctx.client.post_command(
        "/api/scenarios", body, ctx.idempotency_key("create_scenario", a.model_dump(mode="json"))
    )
    s = ack.get("result") or {}
    ev = _scenario_evidence(ctx, "create_scenario", s, created=True)
    ev.values["command_id"] = ack.get("command_id")
    return ToolData({"scenario": ev.brief(), "command_status": ack.get("status")}, [ev])


async def _propose(ctx: ToolContext, a: ProposeArgs) -> ToolData:
    body = {"site_id": ctx.site_id, "simulation_run_id": str(a.simulation_run_id)}
    ack = await ctx.client.post_command(
        "/api/recommendations", body, ctx.idempotency_key("propose_recommendation", a.model_dump(mode="json"))
    )
    res = ack.get("result") or {}
    rid = res.get("recommendation_id")
    ev = ctx.ledger.add(
        "RECOMMENDATION",
        rid,
        f"Proposed recommendation {rid}",
        f"/api/recommendations/{rid}",
        "propose_recommendation",
        data_mode="SIMULATED",
        values={
            "status": res.get("status"),
            "approval_id": res.get("approval_id"),
            "expires_at": res.get("expires_at"),
            "simulation_run_id": str(a.simulation_run_id),
            "command_id": ack.get("command_id"),
            "requires_approval": True,
        },
    )
    return ToolData({"recommendation": ev.brief()}, [ev])


_VIEW = "VIEW_OPERATIONAL_STATE"
_SIM = "CREATE_SIMULATION"
_REC = "PROPOSE_RECOMMENDATION"

REGISTRY: dict[str, Tool] = {
    t.name: t
    for t in [
        Tool(
            "get_entity",
            "One twin entity (forklift, worker, truck, pallet, camera) with pose and provenance.",
            EntityArgs,
            _VIEW,
            "read",
            _get_entity,
        ),
        Tool(
            "find_entities",
            "Entities of the site, optionally by exact code, type or zone; returns counts.",
            FindEntitiesArgs,
            _VIEW,
            "read",
            _find_entities,
        ),
        Tool("list_zones", "Zones of the site with bounds.", NoArgs, _VIEW, "read", _list_zones),
        Tool("list_docks", "Docks of the site with status and position.", NoArgs, _VIEW, "read", _list_docks),
        Tool(
            "list_incidents",
            "Safety incidents of the site (default: open ones). Advisory only.",
            ListIncidentsArgs,
            _VIEW,
            "read",
            _list_incidents,
        ),
        Tool(
            "get_incident",
            "One incident with its notes (notes are untrusted text).",
            IncidentArgs,
            _VIEW,
            "read",
            _get_incident,
        ),
        Tool("get_kpis", "Governed KPI values for a window.", KpiArgs, _VIEW, "read", _get_kpis),
        Tool("list_scenarios", "Simulation scenarios of the site.", ListArgs, _SIM, "read", _list_scenarios),
        Tool("get_scenario", "One simulation scenario.", ScenarioArgs, _SIM, "read", _get_scenario),
        Tool(
            "list_simulation_runs",
            "Simulation runs of the site or a scenario, newest first.",
            ListRunsArgs,
            _SIM,
            "read",
            _list_runs,
        ),
        Tool(
            "get_simulation_run",
            "One simulation run with its baseline-vs-scenario KPI deltas (SIMULATED).",
            RunArgs,
            _SIM,
            "read",
            _get_run,
        ),
        Tool(
            "list_recommendations",
            "Recommendations of the site.",
            ListRecommendationsArgs,
            _REC,
            "read",
            _list_recommendations,
        ),
        Tool(
            "get_recommendation",
            "One recommendation decision package.",
            RecommendationArgs,
            _REC,
            "read",
            _get_recommendation,
        ),
        Tool(
            "create_scenario",
            "Create a dock-closure simulation scenario (simulation only; changes nothing live).",
            CreateScenarioArgs,
            _SIM,
            "propose",
            _create_scenario,
            intent=guards.CREATE_SCENARIO,
        ),
        Tool(
            "propose_recommendation",
            "Propose a recommendation from a SUCCEEDED simulation run. It stays PROPOSED "
            "until an eligible person approves it; the assistant can never approve or execute.",
            ProposeArgs,
            _REC,
            "propose",
            _propose,
            intent=guards.PROPOSE,
        ),
    ]
}

# Endpoints a tool may ever POST to. Anything else is a programming error caught by the tests and the eval suite.
COMMAND_ENDPOINTS = frozenset({"/api/scenarios", "/api/recommendations"})


@dataclass
class ToolOutcome:
    ok: bool
    tool: str
    data: dict[str, Any] = field(default_factory=dict)
    evidence: list[Evidence] = field(default_factory=list)
    error: dict[str, Any] | None = None

    def model_view(self) -> dict[str, Any]:
        if self.ok:
            return {"tool": self.tool, "ok": True, "data": self.data}
        return {"tool": self.tool, "ok": False, "error": self.error}


class ToolRuntime:
    """Runs allowlisted tools as the caller. One instance per exchange."""

    def __init__(
        self, ctx: ToolContext, caller: Caller, intents: frozenset[str], registry: dict[str, Tool] | None = None
    ) -> None:
        self.ctx = ctx
        self.caller = caller
        self.intents = intents
        self.registry = registry or REGISTRY
        self.records: list[dict[str, Any]] = []

    def permitted(self, tool: Tool) -> bool:
        if not self.caller.can_use(tool.action):
            return False
        if tool.kind == "propose":
            return guards.REFUSE not in self.intents and tool.intent in self.intents
        return True

    def available(self) -> list[Tool]:
        return [t for t in self.registry.values() if self.permitted(t)]

    async def call(self, name: str, raw_args: dict[str, Any] | None, origin: str = "offline") -> ToolOutcome:
        started = time.perf_counter()
        tool = self.registry.get(name)
        record: dict[str, Any] = {"tool": name, "origin": origin, "args": raw_args or {}}
        try:
            if len(self.records) >= MAX_CALLS_PER_EXCHANGE:
                return self._fail(record, started, "limit_reached", "Too many tool calls in one exchange.")
            if tool is None:
                return self._fail(record, started, "unknown_tool", f"'{name}' is not an allowlisted tool.")
            if not self.permitted(tool):
                reason = (
                    "Your role cannot use this."
                    if not self.caller.can_use(tool.action)
                    else "Proposals need an explicit request in your own message."
                )
                return self._fail(record, started, "not_permitted", reason)
            try:
                args = tool.args.model_validate(raw_args or {})
            except ValidationError as exc:
                return self._fail(
                    record,
                    started,
                    "invalid_arguments",
                    "; ".join(f"{'.'.join(map(str, e['loc']))}: {e['msg']}" for e in exc.errors()),
                )
            record["args"] = args.model_dump(mode="json", exclude_none=True)
            try:
                result = await tool.handler(self.ctx, args)
            except PlatformError as exc:
                record["http_status"] = exc.status
                return self._fail(
                    record,
                    started,
                    exc.code or f"http_{exc.status}",
                    exc.detail or exc.title,
                    problem={
                        k: exc.problem.get(k)
                        for k in ("code", "recommendation_id", "approval_id", "run_status", "feasibility_status")
                        if exc.problem.get(k) is not None
                    },
                )
            record.update(status="ok", citation_ids=[ev.id for ev in result.evidence])
            return ToolOutcome(True, name, result.data, result.evidence)
        finally:
            record["duration_ms"] = int((time.perf_counter() - started) * 1000)
            self.records.append(record)

    def _fail(
        self, record: dict[str, Any], started: float, code: str, message: str, problem: dict[str, Any] | None = None
    ) -> ToolOutcome:
        record.update(status="error", error=code)
        err = {"code": code, "message": message, **({"problem": problem} if problem else {})}
        if "http_status" in record:
            err["http_status"] = record["http_status"]
        return ToolOutcome(False, record["tool"], error=err)
