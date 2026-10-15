# Plan 11.3 - Security suite

Working plan (brief). Source: REQ-130, NFR-14; BLUEPRINT section 4 (roles), section 18 (security). ADR-06, ADR-20, ADR-28.

## Shape
Java ITs in `services/api/src/test/java/io/vorg/api/security/` (Testcontainers like the existing ITs):
- tenant isolation across every table with a `tenant_id` column (discovered from `information_schema`: RLS enabled and
  forced, policy present, and a cross-tenant query as `vo_app` returns zero rows) and across every tenant-scoped
  endpoint (a tenant-B token against tenant-A ids gives 404, never data);
- authz matrix: every role against every `GET`/`POST` endpoint, expected status derived from
  `permission-matrix.v1.json` (the action each endpoint authorizes is mapped in one table in the test);
- AI tool permissions: tool results are identity-scoped (a role denied an action cannot obtain its data via the tools);
- audit-log integrity: append-only (UPDATE/DELETE refused for the app role), denials audited;
- security headers and CORS: HSTS-appropriate headers, `X-Content-Type-Options`, frame options, no wildcard CORS with
  credentials, errors without stack traces;
- secrets scan: tracked-file scan (script + test) for key patterns and committed `.env`.
Real vulnerabilities found are fixed in production code with a regression test, and listed in the report.

## Tasks
1. Authz matrix + tenant isolation ITs. 2. Audit, headers/CORS, AI tools, secrets scan. 3. Fixes found.
