"""Security suite (Plan 11.3): AI tool permissions derived from the permission matrix, role by role.

The assistant has no special write API: it may only use the identity of the caller. These tests walk EVERY role of
``packages/contracts/authz/permission-matrix.v1.json`` against EVERY allowlisted tool and check that
  - a tool is offered only if the caller's roles can use its action,
  - proposals are never offered without the user's own explicit request,
  - no tool can approve, execute, stop equipment or change configuration,
  - read tools are GET-only and the only POST endpoints are the two proposal commands.
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest

from vorg_ai import guards
from vorg_ai.evidence import EvidenceLedger
from vorg_ai.identity import Caller
from vorg_ai.tools import COMMAND_ENDPOINTS, REGISTRY, ToolContext, ToolRuntime

MATRIX_PATH = Path(__file__).resolve().parents[3] / "packages" / "contracts" / "authz" / "permission-matrix.v1.json"
MATRIX = json.loads(MATRIX_PATH.read_text(encoding="utf-8"))
ROLES = [r["role"] for r in MATRIX["roles"]]
DECISION = {a["action"]: a["decisions"] for a in MATRIX["actions"]}
USABLE = {"ALLOW", "ALLOW_SUBJECT_TO_POLICY", "GATED", "ASSIGNED_ONLY", "LIMITED"}
FORBIDDEN_ACTIONS = {
    "APPROVE_CONSEQUENTIAL_ACTION",
    "EXECUTE_STANDARD_COMMAND",
    "EMERGENCY_STOP_REQUEST",
    "MANAGE_PLATFORM_CONFIGURATION",
    "HANDLE_SAFETY_INCIDENT",
}


def _caller(role: str) -> Caller:
    return Caller("actor", "tenant", "demo", False, (role,))


def _runtime(role: str, intents: frozenset[str]) -> ToolRuntime:
    ctx = ToolContext(
        client=None,  # type: ignore[arg-type]
        site_id="s",
        ledger=EvidenceLedger(),
        exchange_id="00000000-0000-4000-8000-000000000000",
    )
    return ToolRuntime(ctx, _caller(role), intents)


def test_the_matrix_file_is_the_one_the_gateway_reads() -> None:
    assert set(ROLES) == {
        "EXECUTIVE",
        "REGIONAL_MANAGER",
        "WAREHOUSE_MANAGER",
        "DRIVER_OPERATOR",
        "ANALYST",
        "SAFETY_COMPLIANCE",
        "PLATFORM_ADMIN",
    }
    for tool in REGISTRY.values():
        assert tool.action in DECISION, f"{tool.name} uses an action that is not in the matrix"


@pytest.mark.parametrize("role", ROLES)
def test_read_tools_are_offered_exactly_when_the_matrix_allows_the_action(role: str) -> None:
    offered = {t.name for t in _runtime(role, frozenset()).available()}
    for tool in REGISTRY.values():
        if tool.kind != "read":
            continue
        expected = DECISION[tool.action][role] in USABLE
        assert (tool.name in offered) == expected, f"{role} / {tool.name} ({tool.action}={DECISION[tool.action][role]})"


@pytest.mark.parametrize("role", ROLES)
def test_proposals_need_the_users_explicit_request_and_the_role(role: str) -> None:
    without = {t.name for t in _runtime(role, frozenset()).available()}
    assert not {t.name for t in REGISTRY.values() if t.kind == "propose"} & without
    for tool in (t for t in REGISTRY.values() if t.kind == "propose"):
        with_intent = {t.name for t in _runtime(role, frozenset({tool.intent})).available()}
        expected = DECISION[tool.action][role] in USABLE
        assert (tool.name in with_intent) == expected, f"{role} / {tool.name}"
        refused = {t.name for t in _runtime(role, frozenset({tool.intent, guards.REFUSE})).available()}
        assert tool.name not in refused, "a refused intent never offers a proposal"


@pytest.mark.parametrize("role", ROLES)
def test_a_denied_tool_call_is_rejected_by_the_runtime_before_any_platform_call(role: str) -> None:
    for tool in REGISTRY.values():
        if tool.kind == "read" and DECISION[tool.action][role] not in USABLE:
            out = asyncio.run(_runtime(role, frozenset()).call(tool.name, {}))
            assert not out.ok and out.error["code"] == "not_permitted", f"{role} / {tool.name}"


def test_no_tool_can_approve_execute_stop_or_configure() -> None:
    assert {t.action for t in REGISTRY.values()}.isdisjoint(FORBIDDEN_ACTIONS)
    for name in REGISTRY:
        assert not any(w in name for w in ("approve", "reject", "execute", "estop", "emergency", "setting", "config"))
    assert COMMAND_ENDPOINTS == {"/api/scenarios", "/api/recommendations"}
    assert {t.kind for t in REGISTRY.values()} == {"read", "propose"}
    assert {t.name for t in REGISTRY.values() if t.kind == "propose"} == {"create_scenario", "propose_recommendation"}


def test_tool_arguments_cannot_carry_a_tenant_or_site() -> None:
    for tool in REGISTRY.values():
        props = set(tool.json_schema().get("properties", {}))
        assert not props & {"tenant_id", "tenant", "site_id", "actor", "actor_ref", "token", "authorization"}, tool.name
        assert tool.json_schema()["additionalProperties"] is False, tool.name
