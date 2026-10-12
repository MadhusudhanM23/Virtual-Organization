# Live WebSocket protocol (`/ws/live`)

Status: implemented in Phase 3 (Plan 3.4), decision record ADR-37 (Proposed). JSON text frames, snake_case, ISO-8601 UTC times.

## Connect and authenticate
`ws(s)://<host>/ws/live` (the dev server proxies `/ws`). The upgrade carries **no credentials and no query parameters**. The first
client message must be, within 5 seconds:

```json
{"type":"auth","token":"<access JWT>"}
```
Reply `{"type":"ready","heartbeat_s":15,"expires_at":"...","server_time":"..."}`. Any other first message, a bad or expired token,
an erased/disabled actor: the socket is closed with **4401**. Tokens are never logged.

To keep a long session, send `{"type":"reauth","token":"<fresh JWT>"}` before `expires_at` (same actor and tenant, else 4401);
reply `reauthenticated`. When the token expires without a `reauth` the server closes with **4401 `token_expired`**; the client
refreshes its token and reconnects.

## Subscribe
```json
{"type":"subscribe","id":"twin","site_id":"<uuid>","floor_id":"<uuid, optional>",
 "bbox":{"min_x":0,"min_y":0,"max_x":60,"max_y":40},"entity_types":["FORKLIFT","WORKER"],
 "event_types":["TASK_COMPLETED"],"poses":true,"events":true}
```
`id` (client chosen, max 64 chars) names the subscription; sending it again replaces it. At most 8 per socket. The caller needs
`VIEW_OPERATIONAL_STATE` on the site. Replies: `{"type":"subscribed","id":..,"site_id":..}` or
`{"type":"error","id":..,"code":"forbidden|not_found|bad_request|too_many_subscriptions","message":..}` (the socket stays open;
an unknown site and a site of another tenant are both `not_found`). `{"type":"unsubscribe","id":..}` replies
`{"type":"unsubscribed","id":..,"reason":"client"}`; the server also sends `unsubscribed` with `reason` `forbidden|not_found` when
a periodic re-check (30 s) finds the subject may no longer view the site.

Filters: `floor_id` and `bbox` (site frame, meters) apply to position events; `entity_types` is the pose event prefix
(`FORKLIFT`, `WORKER`, `TRUCK`). Pose events (`*_POSITION_UPDATED`) are delivered as `poses`; all other events as `event`
(a pose event type is delivered as `event` only when listed explicitly in `event_types`). `event_types` limits the `event` feed.

## Server messages
* `poses`: `{"type":"poses","sub":"twin","items":[{"entity_id","entity_type","event_id","occurred_at","floor_id","zone_id","x","y","z","heading_deg","status","source","data_mode","confidence"}]}`
  Coalesced: at most one item per entity per flush (100 ms), always the newest. `source`/`data_mode` are provenance (today
  `SIMULATION`/`SIMULATED`) and must be shown to the user.
* `event`: `{"type":"event","sub":"events","event":<event envelope, as GET /api/events items>}`
* `gap`: `{"type":"gap","sub":..,"dropped":n}` events were dropped for a slow client: resync with `GET /api/events`.
* `ping` (every 15 s, with `expires_at`): reply `{"type":"pong"}` (any client message resets the 45 s idle timer).
* `error`, `subscribed`, `unsubscribed`, `ready`, `reauthenticated` as above.

## Close codes
`4400` protocol error (bad JSON, oversize frame >8 KiB), `4401` authentication failed/expired, `4408` no auth within 5 s,
`4409` idle, `4429` slow consumer / send failure. Delivery is at-most-once per connection; after any reconnect or `gap` the
client must re-fetch state over REST (`/api/sites/{id}/entities`, `/api/events`).

## Security
Tenant isolation: an event is offered to a socket only if its tenant equals the socket subject's tenant, then if a subscription
matches. Origins must match `vo.live.ws.allowed-origins` (default localhost). Fan-out is from the outbox, so clients only see
committed events, about 200-300 ms after the write.

## Metrics
`vo.live.ws.connections` (gauge), `vo.live.ws.opened`, `vo.live.ws.closed{code}`, `vo.live.ws.messages{direction,type}`,
`vo.live.ws.poses.coalesced`, `vo.live.ws.lag` (ingest to socket write).
