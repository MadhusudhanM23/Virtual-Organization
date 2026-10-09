---
phase: 1
plan: 4
wave: 2
gap_closure: false
depends_on: [1.1, 1.2]
owns: [services/api/src/main/java/io/vorg/api/platform/tenancy/, services/api/src/main/java/io/vorg/api/platform/identity/, services/api/src/main/java/io/vorg/api/platform/authz/, services/api/src/main/java/io/vorg/api/world/, packages/contracts/authz/]
requirements: [REQ-003, REQ-004, REQ-005, REQ-006, REQ-007, REQ-008, REQ-009]
---

# Plan 1.4: Tenancy (RLS), identity (dev JWT issuer) & authorization matrix

## Objective
Make tenant isolation and authorization true **by construction** before any business data exists: forced RLS with a
fail-closed tenant binding, the built-in dev identity issuer (ADR-16) with pseudonymous actor references, the minimal
organization → region → site hierarchy that scopes are evaluated against, and a policy engine that implements the
blueprint §4 matrix exactly, proven by an exhaustive test.

## Context
- docs/product/BLUEPRINT.md §4 (roles + matrix — the authority), §18 (security, erasure vs audit)
- docs/adr/ADR-REGISTER.md: ADR-06, ADR-07, ADR-16, ADR-20, ADR-21, ADR-28
- .gsd/ARCHITECTURE.md (conventions; migration naming)
- services/api (Plan 1.2 output): `AbstractIntegrationTest`, `SecurityConfig`, `V1_2_01__baseline_schemas_and_roles.sql`
- packages/contracts (Plan 1.1 output)
- Build command: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B verify`

## Decision escalation
If anything in §4 is ambiguous beyond what ADR-20/ADR-21 already settle, stop and write a *Proposed* ADR in
`docs/adr/ADR-REGISTER.md`; report it as a checkpoint instead of guessing.

## Tasks

<task type="auto" effort="high">
  <name>Tenancy schema, RLS and fail-closed tenant binding</name>
  <files>
    services/api/src/main/resources/db/migration/V1_4_01__tenants_identity.sql
    services/api/src/main/resources/db/migration/V1_4_02__org_hierarchy.sql
    services/api/src/main/java/io/vorg/api/platform/tenancy/TenantContext.java
    services/api/src/main/java/io/vorg/api/platform/tenancy/TenantBindingDataSource.java
    services/api/src/main/java/io/vorg/api/platform/tenancy/TenantKeys.java
    services/api/src/test/java/io/vorg/api/platform/tenancy/TenantIsolationIT.java
    services/api/src/test/java/io/vorg/api/platform/tenancy/TenantKeysTest.java
  </files>
  <action>
    1. `V1_4_01`: `iam.tenants` (id uuid pk, slug unique, name, demo_mode bool default false,
       llm_external_allowed bool default false, created_at) — readable by vo_app WITHOUT RLS for slug lookup only
       (no secrets in it); `iam.identity_profiles` (actor_ref uuid pk, tenant_id, email citext/lower unique per tenant,
       display_name, password_hash, status ACTIVE|DISABLED|ERASED, erased_at, created_at, updated_at) — this is the
       separable identity store (§18); `iam.role_assignments` (id, tenant_id, actor_ref, role, scope_type
       ORG|REGION|SITE|ASSIGNED, scope_id uuid null, created_at); `iam.refresh_tokens` (id, tenant_id, actor_ref,
       token_hash, family_id, expires_at, revoked_at, replaced_by, created_at).
    2. `V1_4_02`: `world.organizations` (id, tenant_id, name, version), `world.regions` (id, tenant_id,
       organization_id, name, version), `world.sites` (id, tenant_id, region_id, name, kind WAREHOUSE, time_zone,
       locale, currency, unit_system METRIC|IMPERIAL, version).
    3. RLS on every tenant table above (ENABLE + FORCE + `tenant_isolation` policy per .gsd/ARCHITECTURE.md).
    4. `TenantContext`: holds the current tenant id (ThreadLocal; set by the security layer from the JWT `tid` claim,
       cleared in `finally`). `TenantBindingDataSource` wraps the runtime DataSource: on connection checkout runs
       `select set_config('app.tenant_id', ?, false)` with the current tenant or with the nil UUID
       `00000000-0000-0000-0000-000000000000` when absent (fail closed ⇒ zero rows), and `RESET app.tenant_id` on close.
    5. `TenantKeys`: helpers `redisKey(tenantId, parts...)` → `t:{tenant}:…`, `storagePrefix`, `wsChannel`,
       `eventRoute`; reject blank/invalid tenant ids.
    6. `TenantIsolationIT`: seed two tenants as owner; as vo_app with tenant A bound, querying tenant B rows by id
       returns nothing; INSERT with tenant B id fails the RLS WITH CHECK; without tenant context all tables read empty.
  </action>
  <verify>scripts/mvnw.ps1 -B verify (TenantIsolationIT, TenantKeysTest)</verify>
  <done>Cross-tenant reads/writes are impossible at the SQL layer; missing context fails closed.</done>
</task>

<task type="auto" effort="high">
  <name>Dev identity issuer: login, refresh rotation, logout, JWKS, resource server</name>
  <files>
    services/api/src/main/java/io/vorg/api/platform/identity/**
    services/api/src/main/java/io/vorg/api/platform/web/SecurityConfig.java
    services/api/src/test/java/io/vorg/api/platform/identity/AuthFlowIT.java
  </files>
  <action>
    1. Key management: load RSA private key from `VO_JWT_KEY_PATH`; in `local`/`test` profiles generate a 2048+ bit key
       into that (gitignored) path if absent; publish the public key as JWKS at `GET /.well-known/jwks.json` with a
       `kid`.
    2. `POST /api/auth/login` body `{tenant, email, password}` → resolve tenant by slug, bind tenant context, verify
       password (BCrypt or Argon2 via Spring Security `PasswordEncoder`), status ACTIVE; respond
       `{access_token, token_type:"Bearer", expires_in:900}` and set refresh cookie `vo_refresh` (HttpOnly,
       SameSite=Strict, Path=/api/auth, Secure configurable — false only in local). Access JWT RS256 claims:
       `iss` (config), `sub` = actor_ref, `tid` = tenant_id, `iat`, `exp` (15 min), `jti`. No roles, names or emails in
       the token. Generic 401 problem for any failure (no user enumeration). Simple in-memory per-IP+email attempt
       limiter (5/min) — document Redis-backed limiter as Phase 11 debt.
    3. `POST /api/auth/refresh`: validate cookie hash, rotate (new token, old marked replaced), reuse of a replaced
       token revokes the whole family and returns 401. `POST /api/auth/logout`: revoke family, clear cookie.
       Refresh/logout require a custom header `X-Requested-With: vorg-web` as CSRF mitigation for the cookie path.
    4. Resource server: validate access tokens via `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`
       (pointing at our own JWKS; swapping IdP = config). A filter binds `TenantContext` from `tid` and loads the
       `Subject` (actor_ref, tenant, role assignments) per request with a short cache (≤ 30 s).
    5. `GET /api/me` → `{actor_ref, display_name, tenant:{id,slug,name,demo_mode}, roles:[{role, scope_type, scope_id}]}`.
    6. `AuthFlowIT`: login success/failure, wrong tenant slug, expired token → 401, refresh rotation, refresh reuse
       detection revokes family, logout, `/api/me` content, token contains no PII claims.
  </action>
  <verify>scripts/mvnw.ps1 -B verify (AuthFlowIT)</verify>
  <done>End-to-end auth works with rotation and reuse detection; tokens carry only pseudonymous ids.</done>
</task>

<task type="auto" effort="max">
  <name>Permission matrix as a contract and the policy engine</name>
  <files>
    packages/contracts/authz/permission-matrix.v1.json
    packages/contracts/authz/README.md
    services/api/src/main/java/io/vorg/api/platform/authz/**
    services/api/src/test/java/io/vorg/api/platform/authz/PermissionMatrixTest.java
    services/api/src/test/java/io/vorg/api/platform/authz/PolicyServiceTest.java
  </files>
  <action>
    1. `permission-matrix.v1.json`: the §4 table verbatim as data — roles [EXECUTIVE, REGIONAL_MANAGER,
       WAREHOUSE_MANAGER, DRIVER_OPERATOR, ANALYST, SAFETY_COMPLIANCE, PLATFORM_ADMIN] × actions
       [VIEW_OPERATIONAL_STATE, ANALYZE_QUERY, CREATE_SIMULATION, PROPOSE_RECOMMENDATION, APPROVE_CONSEQUENTIAL_ACTION,
       EXECUTE_STANDARD_COMMAND, EMERGENCY_STOP_REQUEST, MANAGE_PLATFORM_CONFIGURATION] → decision codes
       [ALLOW (R), ALLOW_SUBJECT_TO_POLICY (R*), GATED (Gate), ASSIGNED_ONLY, LIMITED, DENY (No), ADMIN_TECH].
       Add the ADR-20 incident-handling action `HANDLE_SAFETY_INCIDENT` (SAFETY_COMPLIANCE=ALLOW,
       WAREHOUSE_MANAGER=ALLOW, others DENY) clearly marked `"source": "ADR-20"` (all others `"source": "§4"`).
       Include each role's scope type (§4 Scope column). The Java build includes this file as a classpath resource
       (Maven `<resources>` entry pointing at `../../packages/contracts/authz`) — it is the single source.
    2. Policy engine: `PolicyService.authorize(Subject subject, Action action, ResourceRef resource, PolicyContext ctx)
       → Decision {allowed, code, reason}`. Rules:
       - matrix cell DENY → deny; ADMIN_TECH → allow only for technical/platform resources (resource kind PLATFORM_CONFIG,
         TECHNICAL_STATE), never business data;
       - scope: EXECUTIVE org-wide within tenant; REGIONAL_MANAGER only sites in assigned region(s);
         WAREHOUSE_MANAGER only assigned site(s); SAFETY_COMPLIANCE assigned sites or org; ANALYST assigned org scope;
         DRIVER_OPERATOR only resources assigned to them (`ctx.assignedActorRef == subject.actorRef`);
       - GATED → allowed only when `ctx.viaApprovedWorkflow == true` (formal approval workflow, Phase 8);
       - ALLOW_SUBJECT_TO_POLICY → allowed but returns `requiresPolicyCheck=true` so callers apply approval-class
         policy (Phase 8); LIMITED (emergency stop, Warehouse Manager) → ADR-21: own site only;
       - deny by default for anything unknown.
       Resolve a resource's site/region/org through `world` lookups (cached), never from client input.
    3. `PermissionMatrixTest`: hard-codes the blueprint §4 table independently and asserts the JSON equals it, so an
       accidental edit of the JSON fails the build.
    4. `PolicyServiceTest`: parameterized over every role × action × {in scope, out of scope} (≥ 7×9×2 cases) with
       explicit expected outcomes; extra cases: Platform Admin cannot approve/execute/propose (REQ-009), Regional
       Manager denied for a site in another region, Driver allowed only for own assignment, Gate requires approved
       workflow, Limited e-stop own site only.
  </action>
  <verify>scripts/mvnw.ps1 -B verify (PermissionMatrixTest, PolicyServiceTest)</verify>
  <done>Every matrix cell is enforced and tested in and out of scope; JSON contract is the single source.</done>
</task>

<task type="auto" effort="medium">
  <name>Deterministic demo seed and scoped organizations query</name>
  <files>
    services/api/src/main/java/io/vorg/api/world/**
    services/api/src/main/java/io/vorg/api/platform/identity/seed/DemoSeeder.java
    services/api/src/test/java/io/vorg/api/world/OrganizationsQueryIT.java
    services/api/src/test/java/io/vorg/api/platform/identity/ProfileErasureIT.java
    docs/scenarios/demo-tenants.md
  </files>
  <action>
    1. `DemoSeeder` (active only in `local`, `demo`, `test` profiles; idempotent; fixed UUIDs so runs are reproducible):
       - Tenant A `marutiflex-demo` "MarutiFlex Traders (Demo)", demo_mode=true, llm_external_allowed=false (Phase 9
         flips it explicitly); organization; regions "South" and "West"; sites "Bengaluru Warehouse 01"
         (Asia/Kolkata, en-IN, INR, METRIC) in South and "Mumbai Warehouse 02" in West.
       - One user per role: exec@, regional.south@, wm.blr@, driver.blr@, analyst@, safety@, admin@ — domain
         `demo.invalid` (reserved, non-routable); password from `DEMO_USER_PASSWORD`.
       - Tenant B `tenant-b-demo` "Tenant B Logistics (Demo)" with one site and exec@/wm@ users.
       All seeded rows are SYNTHETIC demo data; document it.
    2. `GET /api/organizations` → org + regions + sites visible to the caller's scope (policy VIEW_OPERATIONAL_STATE;
       Platform Admin gets technical metadata only per ADMIN_TECH). Response includes each site's time_zone, locale,
       currency, unit_system.
    3. `OrganizationsQueryIT`: exec sees both sites; regional.south sees only Bengaluru; wm.blr sees only Bengaluru;
       tenant B exec never sees tenant A data.
    4. `ProfileErasureIT` (REQ-006): erase a profile (set status ERASED, null out email/display_name/password_hash)
       — role assignments and any rows referencing actor_ref remain valid; `/api/me` for that actor returns 401.
    5. `docs/scenarios/demo-tenants.md`: tenants, sites, users/roles table, password source, provenance statement.
  </action>
  <verify>
    scripts/mvnw.ps1 -B verify
    Run the app (profile local, compose stack up) and capture:
    curl -s -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"tenant":"marutiflex-demo","email":"exec@demo.invalid","password":"<DEMO_USER_PASSWORD>"}'
    curl -s localhost:8080/api/organizations -H "Authorization: Bearer <token>"   (exec vs wm.blr differ)
  </verify>
  <done>Seeded tenants work through real HTTP; scope filtering proven by tests and curl evidence.</done>
</task>

## Must-Haves
- [ ] Forced RLS on every tenant table; fail-closed binding; isolation test passes
- [ ] Login/refresh(rotation+reuse detection)/logout/JWKS/me working; no PII in tokens
- [ ] Matrix JSON = blueprint §4 (+ ADR-20 row), exhaustive policy test passes
- [ ] Platform Admin has no business authority (test)
- [ ] Deterministic demo seed for two tenants

## Success Criteria
- [ ] All tasks verified with captured output in 4-SUMMARY.md
- [ ] One commit per task, authored by Madhusudhan
