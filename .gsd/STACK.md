# Technology Stack

> Planned stack per blueprint §19 and ADR-15. Executors **must record the exact versions they install** in the
> tables below (replace "latest stable at scaffold").

## Runtime

| Technology | Version | Purpose |
|------------|---------|---------|
| Java (Eclipse Temurin) | 21.0.12.1 LTS | API + simulation |
| Spring Boot | latest stable at scaffold | API framework |
| PostgreSQL (postgis/postgis image) | 16.x | State, events, RLS |
| Redis | 7.x | Hot state, rate limits, WS cache |
| Node.js | 24.18.0 (host) | Web tooling |
| React / TypeScript / Vite | latest stable at scaffold | Web app |
| Tailwind CSS | v4 latest | Styling |
| three / @react-three/fiber / @react-three/drei | latest stable at scaffold | 3D twin |
| Zustand | v5 latest | Client state |
| Python | 3.12 (containers); 3.14.6 host | Vision, AI |
| OR-Tools (Java) | latest stable (Phase 8) | Optimization |

## Tooling

| Tool | Purpose |
|------|---------|
| Maven wrapper (`mvnw`) via `scripts/mvnw.ps1` / `scripts/mvnw.sh` | Java builds without global Maven |
| npm workspaces | TS monorepo |
| JUnit 5, Testcontainers, ArchUnit | Java tests |
| Vitest, Testing Library, Playwright, axe-core | Web tests |
| pytest, ruff | Python tests/lint |
| Flyway | DB migrations |
| OpenTelemetry + Micrometer/Prometheus | Observability |
| GitHub Actions | CI |

## Local environment (reference dev machine)

| Item | Value |
|------|-------|
| OS / shell | Windows 11 Pro; PowerShell 5.1 (no `&&`), Git Bash available |
| JDK path | `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` |
| Docker | Desktop 29.7, Compose v5.5, 8 GB RAM, 8 CPUs |
| GPU | Intel UHD 730 (integrated) — vision on CPU |
| Git | 2.55, credential helper `manager` |

## Configuration

| Variable | Purpose | Location |
|----------|---------|----------|
| `POSTGRES_*` | DB connection | `.env` (from `.env.example`) |
| `REDIS_URL` | Redis connection | `.env` |
| `VO_JWT_KEY_PATH` | Dev issuer RSA key path (gitignored) | `.env` |
| `DEMO_USER_PASSWORD` | Password for seeded demo users (dev only) | `.env` |
| `OPENAI_API_KEY` | LLM provider key (Phase 9; never committed) | `.env` |

**Repository:** https://github.com/MadhusudhanM23/Virtual-Organization

---

*Last updated: 2026-10-09*
