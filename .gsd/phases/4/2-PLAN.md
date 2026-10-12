---
phase: 4
plan: 2
requirements: [REQ-052, REQ-055, REQ-056]
---

# Plan 4.2: Incident APIs and demo controls (backend working doc)

## Tasks
3. Query APIs: `GET /api/incidents` (filters, keyset cursor), detail, timeline, evidence; docs/api/README.md.
4. Demo control `POST /api/demo/incidents/proximity` (demo tenants only, ADR-43), movement-source hold/place, end-to-end
   IT (staged situation -> alert -> incident -> WS), real-stack curl run, docs.
