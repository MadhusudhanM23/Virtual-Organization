# Event catalog

Every event type must be listed here **before** any code emits it. The envelope shape is defined once, in
[`../schemas/event-envelope.v1.schema.json`](../schemas/event-envelope.v1.schema.json) (blueprint section 7).

## Naming rule

- `event_type` is `UPPER_SNAKE`, matching `^[A-Z][A-Z0-9_]*$`.
- Form: `<AGGREGATE>_<PAST_TENSE_VERB>`, for example `FORKLIFT_POSITION_UPDATED` or `INCIDENT_CONFIRMED`.
- The prefix matches the event's `aggregate_type`.
- Names are stable. A breaking payload change bumps `schema_version`; it does not rename the event.
- Payload schemas per event type are added later, alongside the phase that first emits the event.

## Catalog

| event_type | aggregate_type | Emitted by | Payload summary | Replay-authoritative | Phase |
|---|---|---|---|---|---|
| `SITE_SETTINGS_UPDATED` | `SITE` | API, site settings command (Plan 1.5) | `{ changed_fields: string[], new_values: { <field>: value } }`; fields are `time_zone`, `locale`, `currency`, `unit_system`; `source=HUMAN`, `data_mode=LIVE`, sequenced per site | n | 1 |

"Replay-authoritative" follows blueprint section 7: only the listed MVP domains (entity positions, task and incident
lifecycle, scenario lifecycle, approvals and commands) are rebuilt from events during replay.
