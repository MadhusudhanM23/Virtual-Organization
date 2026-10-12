# @vo/twin-schema

World-model contract of the digital twin, shared by the web client and documented for the API.

- `src/coordinates.ts`: the site-local coordinate system and tested world to renderer helpers (REQ-022).
- `src/types.ts`: TypeScript wire types of `GET /api/twin/*` and `GET /api/entities/{id}` (snake_case, as served).
- `src/truth.ts`: `data_mode` to truth-label mapping (ADR-27).
- `schema/world.v1.schema.json`: language-neutral JSON Schema of the same responses. Real Spring responses are committed
  in `tests/contract/world-samples` and validated by `test/java-samples.test.ts`.

## Coordinate system (meters, right-handed)

| | |
|---|---|
| Origin | south-west corner of the ground floor, at ground level |
| Axes | +x east, +y north, +z up (z is an absolute elevation; a floor's `elevation_m` is its slab z) |
| Heading | degrees, 0 = east (+x), counter-clockwise positive seen from above; wire values in [0, 360) |
| three.js (Y-up) | `three.x = x`, `three.y = z`, `three.z = -y`; `rotation.y` (radians) = heading in radians |

```ts
import { worldToRenderer, headingToRendererYaw } from '@vo/twin-schema';

const p = worldToRenderer({ x: 12.5, y: 40, z: 5 }); // { x: 12.5, y: 5, z: -40 }
mesh.position.set(p.x, p.y, p.z);
mesh.rotation.y = headingToRendererYaw(pose.heading_deg);
```

Scripts: `npm run lint|typecheck|test -w @vo/twin-schema`.
