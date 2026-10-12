# Demo tenants (SYNTHETIC)

> **Provenance:** every tenant, organization, region, site and user below is **SYNTHETIC demo data** created by
> `DemoSeeder` (`services/api/.../identity/seed/DemoSeeder.java`). Nothing here is real customer data. Identifiers are
> fixed (`DemoIds`) so seeding is reproducible; user e-mails use the reserved, non-routable domain `demo.invalid`.

The seeder runs only in the `local`, `demo` and `test` Spring profiles, is idempotent (existing rows are left
untouched, including erased profiles) and refuses to start without a password.

## Password source

All demo users share one password, read from the environment variable `DEMO_USER_PASSWORD`
(`vo.demo.user-password`). It is never committed; `.env.example` carries a placeholder only.

## Tenant A: `marutiflex-demo` ("MarutiFlex Traders (Demo)")

`demo_mode = true`, `llm_external_allowed = false` (Phase 9 flips external LLM use explicitly).

| Level | Name | Notes |
|---|---|---|
| Organization | MarutiFlex Traders (Demo) | |
| Region | South | |
| Region | West | |
| Site (South) | Bengaluru Warehouse 01 | Asia/Kolkata, en-IN, INR, METRIC |
| Site (West) | Mumbai Warehouse 02 | Asia/Kolkata, en-IN, INR, METRIC |

| E-mail | Role | Scope | Sees in `GET /api/organizations` |
|---|---|---|---|
| exec@demo.invalid | Executive | Organization | both sites |
| regional.south@demo.invalid | Regional Manager | Region South | Bengaluru only |
| wm.blr@demo.invalid | Warehouse Manager | Site Bengaluru | Bengaluru only |
| driver.blr@demo.invalid | Driver / Operator | Assigned tasks/assets | nothing (no broad data access) |
| analyst@demo.invalid | Analyst | Organization | both sites |
| safety@demo.invalid | Safety / Compliance | Organization | both sites |
| admin@demo.invalid | Platform Administrator | Platform/tenant configuration | ids only (technical metadata, no names or locale data) |

## Tenant B: `tenant-b-demo` ("Tenant B Logistics (Demo)")

One region ("Central") and one site ("Pune Warehouse 01"), used to prove tenant isolation.

| E-mail | Role | Scope |
|---|---|---|
| exec@demo.invalid | Executive | Organization |
| wm@demo.invalid | Warehouse Manager | Site Pune |

The same e-mail local part can exist in both tenants: login is tenant-scoped (`tenant` slug + e-mail + password).

## Try it

```
curl -s -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"tenant":"marutiflex-demo","email":"exec@demo.invalid","password":"<DEMO_USER_PASSWORD>"}'
curl -s localhost:8080/api/organizations -H "Authorization: Bearer <access_token>"
```
