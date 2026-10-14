"""The caller as the platform sees them, loaded with their own token from ``GET /api/me`` (ADR-16).

Roles are only used to decide which tools to *offer* (UX and defense in depth); the API still authorizes every call.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from .platform_client import PlatformClient

# Decisions under which a role can use an action in some form (same rule as apps/web permissionMatrix.ts).
_USABLE = {"ALLOW", "ALLOW_SUBJECT_TO_POLICY", "GATED", "ASSIGNED_ONLY", "LIMITED"}


@dataclass(frozen=True)
class Caller:
    actor_ref: str
    tenant_id: str
    tenant_slug: str
    llm_external_allowed: bool
    roles: tuple[str, ...]

    def can_use(self, action: str) -> bool:
        decisions = _matrix().get(action)
        if decisions is None:
            return False  # unknown action: fail closed
        return any(decisions.get(role, "DENY") in _USABLE for role in self.roles)


async def load_caller(client: PlatformClient) -> Caller:
    me = await client.get("/api/me")
    tenant = me.get("tenant") or {}
    return Caller(
        actor_ref=str(me.get("actor_ref")),
        tenant_id=str(tenant.get("id")),
        tenant_slug=str(tenant.get("slug") or ""),
        # Fail closed: a missing flag means external transmission is not allowed.
        llm_external_allowed=tenant.get("llm_external_allowed") is True,
        roles=tuple(sorted({str(r.get("role")) for r in me.get("roles") or []})),
    )


def _matrix_path() -> Path:
    configured = os.environ.get("VO_AI_PERMISSION_MATRIX")
    if configured:
        return Path(configured)
    # services/ai/src/vorg_ai/identity.py -> repository root
    return Path(__file__).resolve().parents[4] / "packages" / "contracts" / "authz" / "permission-matrix.v1.json"


@lru_cache(maxsize=1)
def _matrix() -> dict[str, dict[str, str]]:
    path = _matrix_path()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}  # no contract available: every write tool stays hidden (fail closed)
    return {a["action"]: a["decisions"] for a in data.get("actions", [])}
