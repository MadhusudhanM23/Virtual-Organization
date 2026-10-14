# Plan 9.3 — Assistant UI and twin directives

Working plan (brief). Source: REQ-103, REQ-108; BLUEPRINT §13, §15; concept UI. Owns `apps/web/` only. Wave C — after
9.2 (contract in `docs/api/assistant.md`).

## Tasks

1. **Client + model** — `features/assistant/assistantApi.ts` (wire types, `POST /api/assistant/messages`) and
   `assistantModel.ts` (pure helpers: split the answer into text and citation chips, the provider label, mapping
   directives to twin-store calls). Vite proxies `/api/assistant` to the AI service on 8090 (`VITE_AI_ORIGIN`).
2. **Store + directives** — `assistantStore.ts` (one Zustand store: messages, pending, error). The twin store gets
   `assistantHighlight {entityIds, zoneIds, dockIds, floorId}`, `showAssistantHighlight`, `clearAssistantHighlight`
   and `focusPoint` (reuses `focusRequest`). Scene3D and FloorMap2D outline the highlighted entities, zones and docks
   (outline plus label, not color alone).
3. **Panel + page** — `AssistantPanel` in the right-column panel (`panel.assistant`) and on `/assistant`:
   - suggestions and the message list;
   - the answer with numbered citations (kind, label and the evidence's truth label);
   - the provider label (`OFFLINE · deterministic` or `OPENAI · <model>`, plus why external is off);
   - actions "Show in twin" (applies the directives and opens `/twin/live`), "View details" (opens the cited record)
     and "Suggest actions" (asks the propose question).

   The nav entry stays gated on `PROPOSE_RECOMMENDATION`. i18n in `messages.en.ts`.
4. **Tests** — Vitest for the model helpers and the directive application. `npm run typecheck` and `npm run lint`
   green.

Commit: `feat(phase-9): assistant panel with citations, provider label and twin directives`.
