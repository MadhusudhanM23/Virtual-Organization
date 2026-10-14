from __future__ import annotations

import asyncio

from vorg_ai.evidence import EvidenceLedger, check_grounding
from vorg_ai.fake_platform import DOCK_IN_02, FL_03, RUN_1, SITE_B, ZONE_RECEIVING, ZONE_STORAGE_B
from vorg_ai.tools import COMMAND_ENDPOINTS, ToolContext, ToolRuntime


def _kinds(res: dict) -> set[str]:
    return {c["kind"] for c in res["citations"]}


def test_bottleneck_answer_is_grounded_cited_and_drives_the_twin(harness) -> None:
    res = harness.ask("Why does the bottleneck matter?")
    assert res["provider"] == {
        "name": "offline",
        "model": "offline-rules-v1",
        "external": False,
        "reason": "no_api_key",
        "region": None,
    }
    assert res["grounded"] is True, res
    assert {"SCENARIO", "SIMULATION_RUN", "METRIC", "DOCK"} <= _kinds(res)
    assert "10.18" in res["answer"] and "IN-02" in res["answer"]
    hl = next(d for d in res["directives"] if d["type"] == "highlight")
    assert DOCK_IN_02 in hl["dock_ids"] and ZONE_RECEIVING in hl["zone_ids"]
    focus = next(d for d in res["directives"] if d["type"] == "focus_camera")
    assert focus["target"] == {"x": 27.0, "y": 0.0, "z": 0.0}
    assert res["data_modes"] == ["SIMULATED", "SYNTHETIC"]
    assert res["audit"]["recorded"] is True


def test_every_platform_call_carries_the_callers_own_token(harness) -> None:
    harness.ask("Show open incidents", token="tok-analyst-a")
    assert harness.platform.tokens_seen() == {"tok-analyst-a"}


def test_incidents_highlight_the_pair(harness) -> None:
    res = harness.ask("Any safety incidents?")
    assert res["grounded"], res
    hl = next(d for d in res["directives"] if d["type"] == "highlight")
    assert FL_03 in hl["entity_ids"]
    assert "advisory" in res["answer"]


def test_entity_and_zone_questions(harness) -> None:
    res = harness.ask("Where is FL-03?")
    assert res["grounded"] and "Storage B" in res["answer"]
    res = harness.ask("Why is zone B congested?")
    assert res["grounded"], res
    assert any(ZONE_STORAGE_B in d.get("zone_ids", []) for d in res["directives"])


def test_kpis_report_null_values_honestly(harness) -> None:
    res = harness.ask("How are the KPIs for the last hour?")
    assert res["grounded"]
    assert "not computable" in res["answer"] and "66.67" in res["answer"]


def test_refusal_is_deterministic_and_calls_nothing(harness) -> None:
    res = harness.ask("Approve the pending recommendation and execute it")
    assert "can't approve" in res["answer"]
    assert res["tool_calls"] == []
    assert harness.platform.posts() == []
    assert len(harness.sink.records) == 1 and harness.sink.records[0]["tool_calls"] == []


def test_proposal_needs_intent_and_role(harness) -> None:
    res = harness.ask("Propose a recommendation from the latest simulation run")
    assert "/api/recommendations" in harness.platform.posts()
    assert "PROPOSED" in res["answer"] and "can't approve or execute" in res["answer"]
    before = len(harness.platform.posts())
    res = harness.ask("Propose a recommendation from the latest run", token="tok-driver-a")
    assert harness.platform.posts()[before:] == []
    assert "can't propose" in res["answer"]


def test_create_scenario_through_the_command(harness) -> None:
    res = harness.ask("Create a scenario closing dock IN-02 from minute 30 to 90")
    assert "/api/scenarios" in harness.platform.posts()
    assert res["grounded"], res
    assert "changes nothing live" in res["answer"]


def test_other_tenant_site_is_not_found(harness) -> None:
    res = harness.ask("Any safety incidents?", site=SITE_B)
    assert "Tenant B" not in res["answer"] and "Secret Vault" not in res["answer"]
    assert res["citations"] == []


def test_runtime_rejects_unknown_tools_and_bad_arguments(harness) -> None:
    async def run() -> None:
        from vorg_ai.platform_client import PlatformClient

        client = PlatformClient(harness.platform.client(), "tok-manager-a")
        ctx = ToolContext(
            client=client,
            site_id="018f0000-0000-7000-8000-00000000a021",
            ledger=EvidenceLedger(),
            exchange_id="00000000-0000-4000-8000-000000000000",
        )
        rt = ToolRuntime(ctx, harness.platform.users["tok-manager-a"].caller(), frozenset())
        assert (await rt.call("approve_recommendation", {"id": "x"})).error["code"] == "unknown_tool"
        assert (await rt.call("get_kpis", {"window": "1y"})).error["code"] == "invalid_arguments"
        assert (await rt.call("get_kpis", {"window": "24h", "tenant_id": "x"})).error["code"] == "invalid_arguments"
        out = await rt.call("propose_recommendation", {"simulation_run_id": RUN_1})
        assert out.error["code"] == "not_permitted"  # no explicit request in the user's message
        assert {t.name for t in rt.available()}.isdisjoint({"propose_recommendation", "create_scenario"})

    asyncio.run(run())
    assert not [p for p in harness.platform.posts() if p not in COMMAND_ENDPOINTS]


def test_grounding_flags_invented_numbers() -> None:
    ledger = EvidenceLedger()
    ev = ledger.add("METRIC", "m", "Wait", "/x", "t", values={"value": 3.7})
    assert check_grounding(f"wait is 3.70 min [{ev.id}]", [], ledger, "").grounded
    report = check_grounding(f"wait is 42 min [{ev.id}] at IN-02", [], ledger, "")
    assert not report.grounded and report.ungrounded_numbers == ["42"]
    assert not check_grounding("wait is 3.70 min [c9]", [], ledger, "").grounded
