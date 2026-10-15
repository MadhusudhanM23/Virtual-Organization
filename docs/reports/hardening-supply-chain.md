# Hardening supply-chain report (Phase 11, plan 11.5)

CycloneDX SBOMs and vulnerability scans of the code dependencies and the container images. **Real findings only**, from
`scripts/supply-chain.ps1` run on 2026-10-15 (Trivy 0.58.1 with its vulnerability database as of that day, `npm audit`,
`@cyclonedx/cyclonedx-npm` 1.19.3, `cyclonedx-maven-plugin` 2.9.1, `cyclonedx-bom` for Python). The same steps run in CI as
`.github/workflows/supply-chain.yml`. Policy: ADR-74 (Proposed). Raw files are written to `sbom/` (gitignored); CI uploads
them as the `sbom` artifact.

## SBOMs

| SBOM | Components | Format |
|---|---|---|
| Maven, aggregate of `simulation`, `optimization`, `api` (`org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom`) | 178 | CycloneDX 1.6 JSON |
| npm workspace, production dependencies (`npx @cyclonedx/cyclonedx-npm --omit dev`) | 71 | CycloneDX 1.6 JSON |
| Python, `services/ai` environment (`cyclonedx-py environment`) | 27 | CycloneDX 1.6 JSON |

`services/vision` (Python) has no SBOM yet: its dependencies include `onnxruntime`/`opencv` wheels that I did not install
for this run.

## Code dependencies

| Source | First scan | After fixes |
|---|---|---|
| Maven SBOM (Trivy) | **3 CRITICAL, 5 HIGH** | 0 |
| npm SBOM (Trivy) | 0 | 0 |
| `npm audit` (all dependencies including dev) | 0 (0 low, 0 moderate, 0 high, 0 critical) | 0 |
| Python SBOM `services/ai` (Trivy) | 0 | 0 |
| Repository filesystem: secrets and misconfiguration (Trivy) | 1 HIGH `private-key` in `services/api/secrets/dev-jwt.pem` | 0 |

**Fixed (Maven, in the root `pom.xml`).** Spring Boot 4.1.1 manages versions that carry published CVEs, so two properties
override them until the parent catches up:

| Finding | Package | Installed | Fixed in | Override |
|---|---|---|---|---|
| CVE-2026-65182, CVE-2026-65905, CVE-2026-68525 (CRITICAL) | `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.24 | 11.0.25 | `<tomcat.version>11.0.25</tomcat.version>` |
| CVE-2026-89407, CVE-2026-89425 (HIGH) | `tools.jackson.core:jackson-core` | 3.1.5 | 3.1.7 | `<jackson-bom.version>3.1.7</jackson-bom.version>` |
| CVE-2026-68497, CVE-2026-91776, CVE-2026-91777 (HIGH) | `tools.jackson.core:jackson-databind` | 3.1.5 | 3.1.7 | same |

Verified with `dependency:list` (tomcat-embed-core 11.0.25, jackson-core and jackson-databind 3.1.7), a clean re-scan, and a
full `scripts/mvnw.ps1 -B verify` (see the commit): 85 + 25 module tests, 277 API unit tests and 617 integration tests, 0 failures.

**Secret finding was a false positive for the repository, true for the working tree.** `services/api/secrets/dev-jwt.pem`
is the local profile's generated development signing key (`vo.jwt.generate-if-absent`). It is gitignored and not tracked
(`scripts/security/secrets-scan.mjs` scans the 940-odd tracked files and is clean; it also fails on any tracked `.env`,
`.pem`, `.p12`, `.pfx`, `.jks`). The filesystem scan now skips `**/secrets`.

## Container images (compose stack and the load-test tool)

Trivy, HIGH and CRITICAL. These are third-party images: the findings are in the base operating system or in upstream
Go binaries and are removed by an upstream rebuild, not by our code. Each image is accepted as a whole, with its reason,
in `scripts/supply-chain-allowlist.json` (the script fails on anything not listed there), and the CI job reports them
without failing.

| Image | CRITICAL | HIGH | With a fix available | What it is and why it is accepted |
|---|---|---|---|---|
| `postgis/postgis:16-3.5` | 26 | 229 | 104 of 255 | Debian 11 (end of life) base; 151 findings (perl, libxml2, libcurl, libheif, libexpat and others) have no fix in any package source. The tags `16-3.5-bookworm` and `16-3.6` do not exist upstream (checked). Local development database only: bound to 127.0.0.1, dev placeholder credentials, SYNTHETIC data. A different libc would also change text collation of the existing development volume, so the tag was not changed. **Production must not use this image** (ADR-71: the production database is undecided). |
| `redis:7-alpine` | 0 | 2 | 2 | OpenSSL 3.3.7-r1 in the Alpine base; fixed by the `-r2` package in the next upstream rebuild of the floating tag. Holds no authoritative data, bound to 127.0.0.1. |
| `prom/prometheus:v3.15.0` | 0 | 10 | 10 | Go standard library and `golang.org/x/net` inside the Prometheus binaries. v3.15.0 is the newest published tag. Observability profile only. |
| `grafana/grafana:13.2.4` (was 13.2.3: 64 HIGH) | 0 | 56 | 56 | Go standard library, `x/net`, grpc, tempo inside Grafana and its bundled data-source plugins. Upgraded to the newest tag, 13.2.4, which removed 8. Observability profile only, anonymous viewer, 127.0.0.1. |
| `grafana/k6:1.8.2` (was 1.3.0: 4 CRITICAL, 67 HIGH) | 0 | 16 | 16 | Go standard library inside k6 and OpenSSL in the Alpine base. Developer load-test tool, `--rm` against the local stack, never deployed. Upgraded from 1.3.0 (a 10 s reads plus WebSocket smoke run with 1.8.2 passed its thresholds). |

Reading it plainly: **no HIGH or CRITICAL finding remains in code we ship**. 339 HIGH or CRITICAL findings remain in five
third-party images that are used for local development and observability only; all 26 CRITICAL ones are in the
Debian 11 PostGIS image.

## Pinning

- **Maven**: versions come from the Spring Boot 4.1.1 parent plus the two overrides above; plugin versions for the SBOM are
  pinned in the root `pom.xml`.
- **npm**: `package-lock.json` is committed and CI installs with `npm ci`.
- **Python**: `services/ai/pyproject.toml` declares lower bounds only (`fastapi>=0.115`, ...). The SBOM records the resolved
  versions, but they are not locked. A lock file (`pip-compile` or `uv.lock`) is the next step.
- **Images**: pinned by tag, not by digest, and `redis:7-alpine` floats. Digest pinning is the next step (ADR-74).
- **GitHub Actions**: pinned by major tag (`actions/checkout@v4`) and `aquasecurity/trivy-action@0.28.0`; commit SHAs are the next step.

## Not done

- OWASP dependency-check: Trivy against the SBOMs and `npm audit` cover the same advisories; dependency-check would need
  an NVD API key and a multi-hour first download, so it was not run.
- Signing of the SBOMs and of the images, provenance attestations, licence scanning.
- `services/vision` SBOM and scan (see above).
