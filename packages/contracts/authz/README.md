# Authorization permission matrix (contract)

`permission-matrix.v1.json` is the single source of truth for the role x action matrix of
`docs/product/BLUEPRINT.md` section 4, plus the ADR-20 action `HANDLE_SAFETY_INCIDENT`
(every row carries a `source`: `§4` or `ADR-20`).

- **Java:** `services/api` copies this file onto its classpath as `authz/permission-matrix.v1.json` (Maven
  `<resources>` entry) and `PermissionMatrix` loads it; the policy engine has no second copy of the table.
- **UI:** may read it to hide/disable controls as a hint only. The server enforces every decision (ADR-06).
- **Drift guard:** `PermissionMatrixTest` hard-codes section 4 independently and fails the build if this file differs.

## Decision codes

| Code | Blueprint | Meaning |
|---|---|---|
| `ALLOW` | R | Allowed within the role's scope |
| `ALLOW_SUBJECT_TO_POLICY` | R* | Allowed within scope; callers must also apply approval-class policy (Phase 8) |
| `GATED` | Gate | Only as part of the formal approval workflow |
| `ASSIGNED_ONLY` | Assigned only | Only for the caller's own current assignment |
| `LIMITED` | Limited | Warehouse Manager: own warehouse only (ADR-21) |
| `DENY` | No | Never |
| `ADMIN_TECH` | Admin-tech | Platform/technical resources only; never business data |

Changing a cell requires a blueprint/ADR change first; add a new file version (`v2`) for breaking shape changes.
