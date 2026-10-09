---
phase: 1
plan: 7
wave: 4
gap_closure: false
depends_on: [1.1, 1.2, 1.3, 1.4, 1.5, 1.6]
owns: [.github/workflows/, scripts/verify-all.ps1, scripts/verify-all.sh, .gsd/phases/1/VERIFICATION.md]
requirements: [REQ-019]
---

# Plan 1.7: CI pipeline & Phase 1 verification

## Objective
Automate every Phase 1 check in GitHub Actions and locally, then verify the phase must-haves with captured evidence.

## Tasks

<task type="auto" effort="medium">
  <name>GitHub Actions CI</name>
  <files>.github/workflows/ci.yml</files>
  <action>
    Triggers: push and pull_request on main. Jobs: `java` (ubuntu-latest, setup-java temurin 21 with maven cache,
    `./mvnw -B verify` — Testcontainers works on hosted runners); `web` (setup-node 24, `npm ci`, lint, typecheck,
    test, build for all workspaces incl. contracts); `contracts` covered by web job. Pin actions to major versions.
    No secrets required.
  </action>
  <verify>Push and confirm the run is green: gh is not installed, so check https://github.com/MadhusudhanM23/Virtual-Organization/actions via `git ls-remote` + ask orchestrator, or use the GitHub API with curl (public repo).</verify>
  <done>CI green on main.</done>
</task>

<task type="auto" effort="low">
  <name>Local verify-all scripts</name>
  <files>scripts/verify-all.ps1, scripts/verify-all.sh</files>
  <action>Run Java verify (via scripts/mvnw.*), then npm ci/lint/typecheck/test/build; stop on first failure with a clear message; ASCII-only PowerShell.</action>
  <verify>powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-all.ps1</verify>
  <done>Script passes locally.</done>
</task>

<task type="auto" effort="medium">
  <name>Phase 1 verification report</name>
  <files>.gsd/phases/1/VERIFICATION.md</files>
  <action>
    For each REQ-001..REQ-019 and Phase 1 must-have in ROADMAP: command run, captured output excerpt, PASS/FAIL.
    Include the blueprint §20 Definition-of-Done checklist for the slice (command → state → outbox → event → handler).
  </action>
  <verify>Every row has evidence.</verify>
  <done>VERIFICATION.md complete.</done>
</task>
