# Plan 11.5 - Supply chain, docs and ADRs

Working plan (brief). Source: REQ-130 (SBOM, pinned deps, image scan).

## Shape
`scripts/supply-chain.ps1`: CycloneDX SBOMs (Maven via `cyclonedx-maven-plugin`, npm via `@cyclonedx/cyclonedx-npm`,
Python via `cyclonedx-py` when installable), Trivy via Docker for the filesystem and the compose images, `npm audit`.
SBOMs go to `sbom/` (gitignored; generated in CI as artifacts). CI job `supply-chain` in `.github/workflows/`.
`docs/reports/hardening-supply-chain.md` lists real findings; each high/critical is fixed (upgrade) or documented with
its reason. Docs: README Phase 11 row and run steps; `docs/api/` if endpoints change; Proposed ADR-71..ADR-74 only.

## Tasks
1. Scripts + CI job. 2. Run scans, fix, report. 3. README, ADRs.
