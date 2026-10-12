# Plan 2.3 - 3D twin scene (working plan)
Scope: REQ-020, REQ-024 (render), NFR-10. Data mode SYNTHETIC, counts only from API data.
Tasks
1. Twin data layer: typed API calls (world, paged entities), `useTwinData` hook, scene model (pure mapping: world->renderer, per-type/status color, rack/entity instance transforms), Vitest.
2. 3D scene: R3F Canvas, floor slabs + labels, zones, docks, instanced racks/pallets/entities, lighting, legend, floor switcher, minimap, loading/empty/error states, Live View route.
Verify: lint, typecheck, vitest, build.
