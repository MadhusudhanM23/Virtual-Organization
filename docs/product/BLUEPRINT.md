# Virtual Organization — Industrial Digital Twin Platform
## Master Product, Architecture, Security and Build Blueprint

> Faithful markdown transcription of `Virtual_Organization_Digital_Twin_Master_Blueprint.pdf` (same folder).
> The PDF is the original; this file exists so agents can search it. Only list numbering was normalized
> (the PDF numbers some lists 29–55 due to a rendering quirk). If the two ever disagree, the PDF wins.
>
> **This blueprint is the project source of truth** (see §25). Requirements derived from it live in
> `.gsd/SPEC.md` and `.gsd/REQUIREMENTS.md`; decisions in `docs/adr/`.

**North Star.** Create a programmable virtual organization where operational entities, people, assets,
processes, policies and events behave like a real enterprise; the digital twin visualizes the world,
simulation tests the future, and intelligence helps people make safer, better decisions.

*Cover image: concept visualization only — illustrative UI; values shown are not real operational measurements.*
The concept UI (cover) shows: left sidebar (Overview, Organization, Operations, Digital Twin → Live View / Map View /
Simulation / Replay, Orders, Inventory, Assets, People, Fleet & Logistics, Analytics, AI Assistant, Reports, Settings,
Switch Organization); top KPI row (Total Revenue, Active Orders, On-Time Delivery, Warehouses, Vehicles, Employees);
center 3D warehouse "Bengaluru Warehouse - 01" with Live badge, weather/time, 2D/3D/Map toggles, floor selector
(3F/2F/1F/GF), zone labels (Zone A Receiving, Zone B Storage, Zone C Picking, Zone D Packing), docks, forklifts,
trucks, a red "Safety Alert" marker, minimap with legend (Forklift, Worker, Pallet, Truck, Alert) and camera mode bar
(Overview, Follow, First Person, Cinematic, Floor View); right column: Live Camera Feed (CAM-07 Packing Area, labeled
detections Worker/Forklift/Pallet), Live Events list (Safety Alert, Forklift entered zone, Pallet picked, Truck arrived,
Order marked ready), AI Operations Assistant ("Why is Zone B congested today?" with cited causes and actions View
Details / Show Affected Orders / Suggest Actions); bottom: Warehouse Performance (Throughput, Picking Accuracy, Avg Pick
Time, Dock Utilization + trend chart), Inventory Heatmap (Occupancy / Movement / Congestion), Order Flow (Created →
Picking → Packing → Dispatched → Delivered, On-Time vs Delayed donut).

**Important scope statement.** This document defines a serious enterprise-oriented platform architecture. The first
delivery proves one coherent warehouse slice; later capabilities expand the same world model rather than creating
unrelated demos.

---

## 1. Product thesis and positioning

The product is not a warehouse CRUD system, a 3D dashboard, a chatbot, or a game. It is a programmable virtual
organization: a software world in which organizational structure, operational state, people, assets, workflows,
policies and events can be observed, replayed, simulated and acted upon.

The warehouse is the first environment because it gives us a rich, visual and operational domain: inventory, zones,
forklifts, workers, docks, vehicles, orders, safety events and logistics flows all interact in a physically meaningful
space.

The 3D layer is the visualization of the world model. It is not the source of truth. The source of truth is
domain-owned operational state and its durable event history; the twin, simulation and AI layers consume that model.

`Observe -> Understand -> Simulate -> Recommend -> Approve -> Execute -> Verify -> Observe again`

### Business value
- See current operations in context rather than as disconnected tables and charts.
- Explain why an operational metric or incident changed by correlating state, events and dependencies.
- Replay past incidents and operational journeys without pretending historical data is live.
- Test warehouse changes, demand spikes and disruptions in a cloned simulation before executing them.
- Give decision makers an AI interface grounded in authorized organizational data and evidence.
- Create a reusable platform model that can later represent logistics, manufacturing, retail, ports, campuses or other
  operational domains.

### Industry alignment
Current Siemens and NVIDIA materials show a real industry convergence around high-fidelity digital twins, real-time
operational data, simulation, computer vision, optimization and AI. Siemens describes Digital Twin Composer as
combining digital twin data, simulation and physical real-time information to test and optimize industrial changes
virtually. NVIDIA describes digital-twin and vision-AI patterns for warehouses, factories and multi-camera operational
understanding. These references validate the direction, not the scope or implementation of this project.

---

## 2. Product principles and non-negotiables

| Principle | Decision |
|---|---|
| World model first | The 3D scene renders the world model; business logic never depends on visual geometry. |
| PostgreSQL state + outbox | Transactional business state is authoritative for platform-owned domains; durable events are emitted through an outbox. |
| Provenance everywhere | Every observation/event is labeled by source, data mode, time, actor and location where applicable. |
| Simulation is a separate world | Scenarios run against a snapshot/overlay; simulation cannot silently mutate live state. |
| Human gates for consequential actions | AI proposes; policy and human approval govern execution except narrowly defined emergency commands. |
| Safety is advisory | The platform is not a safety-rated control system and never replaces hardware E-stops or certified safety PLCs. |
| Overlay by default | External WMS/ERP/TMS systems may own orders/inventory; our platform owns twin, incidents, scenarios, approvals, audit and intelligence records unless ownership is explicitly delegated. |
| Tenant isolation by construction | Tenant scope is enforced in APIs, SQL, Redis, event streams, object storage, WebSockets and AI retrieval. |
| No fabricated truth | Synthetic/simulated/demo data must be visibly and programmatically labeled as such. |
| Measure before claiming | Latency, reliability, simulation accuracy and AI quality are measured with explicit test thresholds. |

### Three time lenses

| Lens | Question | Data behavior |
|---|---|---|
| LIVE | What is happening now? | Current state + live observations; real-time updates. |
| REPLAY | What happened? | Historical snapshots + ordered state-delta/integration events; no live labels. |
| SIMULATE | What could happen? | Cloned baseline + scenario inputs + deterministic clock/seed; isolated from live state. |

---

## 3. World model and ownership boundaries

The World Model is the central abstraction. It contains the normalized entities and relationships needed by operations,
visualization, replay, simulation and AI. It must be independent of any particular renderer, UI framework or external
vendor system.

`Organization -> People / Roles / Locations / Assets / Processes / Tasks / Orders / Inventory / Policies / Events / Dependencies / Spatial state`

### Data ownership matrix

| Domain | Production ownership | MCD / demo source | Notes |
|---|---|---|---|
| Organization, users, roles, permissions | Platform | Platform seed/admin | Identity and authorization are platform-owned. |
| Twin entities, positions and scene state | Platform | Simulation seed / vision adapter | Platform owns the normalized twin representation. |
| Incidents, alerts, investigations | Platform | Synthetic event generator | Includes evidence links and confirmation state. |
| Scenarios, snapshots, simulation runs | Platform | Platform | Simulation is isolated from live state. |
| Approvals, recommendations, commands | Platform | Platform | Always auditable; execution requires authorization. |
| Audit records | Platform | Platform | Append-only with privacy-safe actor references. |
| AI conversations, tool calls, evidence references | Platform | Platform | Tenant-scoped and permission-aware. |
| Orders | External WMS/OMS by default | Simulated WMS adapter | Our MCD must ingest these through a clearly labeled adapter. |
| Inventory quantities | External WMS by default | Simulated WMS adapter | Platform mirrors inventory projections; it does not silently become ERP. |
| Shipment / carrier state | External TMS by default | Simulated TMS adapter | Platform may enrich, analyze and simulate alternatives. |
| Driver workflow tasks | External TMS/WMS if delegated | Simulated task adapter | If our platform owns the workflow in a future deployment, ownership must be explicit. |

> **Source-system adapter rule.** The first end-to-end demo must never imply that orders or inventory came from a real
> WMS when they are actually generated by us. The demo uses a named Simulated WMS/TMS Adapter and labels the resulting
> feed as simulated.

### Canonical ID strategy
External identifiers are mapped into stable internal canonical IDs. Every integration record retains `source_system`,
`source_record_id` and `canonical_id` so the same business entity can be traced without leaking external system
assumptions into every service.

---

## 4. Users, roles and permission matrix

Authorization is modeled as **Subject + Action + Resource + Scope + Context**. RBAC selects the role; policy evaluation
applies organizational scope, resource attributes, policy constraints and current context.

| Role | Scope | Allowed | Restricted / conditions | Special note |
|---|---|---|---|---|
| Executive | Global org | View, Analyze, Simulate, Propose, Approve | Execute only through approval gates and policy-controlled commands. | No emergency-stop authority by default. |
| Regional Manager | Region | View, Analyze, Simulate, Propose, limited Approve | Regional operations and planning. | Cannot act outside region. |
| Warehouse Manager | Warehouse | View, Analyze, Simulate, Propose, Approve, eligible Execute | Tasks, staffing, zones, inventory workflow within assigned warehouse. | Cannot alter global policy. |
| Driver / Operator | Assigned tasks/assets | View, Execute assigned workflow, Capture evidence | Trip/task actions, scans, proof, issue reports. | No approval or broad data access. |
| Analyst | Assigned org scope | View, Analyze, Simulate | Read-only analytical and scenario access. | No operational execution. |
| Safety / Compliance | Assigned sites/org | View, Analyze, Propose, Approve safety workflows, Emergency Stop advisory command | Safety incident handling and shutdown requests/alerts. | Stop action is not a certified safety function. |
| Platform Administrator | Platform/tenant configuration | Manage technical configuration, users, integrations, policies | Technical administration. | No implicit business authority; cannot approve business actions merely by being admin. |

### Permission matrix baseline

| Action | Exec | Regional | Warehouse | Driver | Analyst | Safety | Platform Admin |
|---|---|---|---|---|---|---|---|
| View operational state | R | R | R | R* | R | R | Admin-tech |
| Analyze / query | R | R | R | No | R | R | No |
| Create simulation | R | R | R | No | R | R | No |
| Propose recommendation | R | R | R | No | R | R | No |
| Approve consequential action | R* | R* | R | No | No | R* | No |
| Execute standard command | Gate | Gate | R | Assigned only | No | Gate | No |
| Emergency stop request | No | No | Limited | No | No | R | No |
| Manage platform configuration | No | No | No | No | No | No | R |

Legend: **R** = allowed within scope; **R\*** = allowed subject to policy/approval; **Gate** = permitted only as part of
the formal approval workflow; **Assigned only** = current authenticated assignment; **No** = not allowed.

---

## 5. Operating modes and user experience

### Primary modes

| Mode | Purpose | Key interactions |
|---|---|---|
| Overview | Understand the organization at a glance. | KPIs, alerts, organization map, quick jump. |
| Explore | Navigate the 3D environment. | Orbit, pan, zoom, focus, floor changes, object inspector. |
| Follow | Track one entity. | Forklift/worker/truck camera follow with live status. |
| Physical Camera | Inspect real or recorded video. | Multi-camera view, detection overlays, evidence links. |
| Replay | Reconstruct a historical event. | Timeline scrub, step/segment playback, evidence inspection. |
| Simulate | Run a what-if scenario. | Clone baseline, change variables, compare outcomes, save scenario. |

### Experience-design rules
- Progressive disclosure: overview first, inspector second, deep evidence on demand.
- Selection is persistent: clicking an entity keeps its details visible until changed or dismissed.
- Every essential action works with keyboard and touch; hover cannot reveal the only control.
- The 3D scene has an accessible 2D/structured alternative for critical information and keyboard-only use.
- Respect reduced-motion preferences. Camera flights and continuous animation can be paused or replaced by static transitions.
- Small screens prioritize alerts, current task, essential entity details and 2D fallback over decorative 3D density.
- All user-facing date/time values respect the selected organization/site time zone; storage uses UTC timestamps.
- Units, locale, number formats and language are configurable; do not assume one country format.
- Colors are never the only status signal; pair with labels, icons, shape or text.

### Spatial subscriptions and performance
The client must never stream the entire world continuously. WebSocket subscriptions are scoped by organization, site,
floor, zone, object type and/or bounding region. The server sends coarse state first, then high-frequency updates only
for entities inside the active spatial subscription.

> **Camera principle.** The virtual camera is a navigation system for understanding the organization. Natural-language
> answers, alerts and selected entities may trigger camera focus, but the camera never defines or mutates business truth.

---

## 6. End-to-end architecture

```
Clients -> API Gateway / BFF -> Domain Services -> PostgreSQL
                                                     |
                                                   Outbox
                                                     |
                                         Event Publisher / Handlers
                                                     |
                                    Twin / Analytics / Notifications / AI
                                                     |
                                               WebSocket Hub
                                                     |
                                                   3D UI
```

### Core layers

| Layer | Responsibility | Initial technology |
|---|---|---|
| Presentation | React application, 3D scene, inspectors, timelines, analytics. | React + TypeScript + Tailwind + React Three Fiber + Three.js + Zustand |
| Operator client | Offline-capable task workflow. | PWA + service worker + IndexedDB |
| API / domain | Commands, queries, policy, domain transactions. | Spring Boot + REST + WebSocket |
| World model | Canonical entities, spatial/temporal state. | PostgreSQL + PostGIS optional later |
| Event durability | Transactional event production. | PostgreSQL outbox + poller |
| Async transport | Cross-process fan-out when required. | NATS JetStream when scale needs it; Kafka later if justified |
| Live cache | Hot state and session acceleration. | Redis |
| Vision | Detection, tracking and observations. | Python + OpenCV + YOLO + ByteTrack |
| Simulation | Discrete-event simulation with deterministic seed/clock. | Dedicated simulation module; OR-Tools for optimization adjuncts |
| AI | Permission-aware reasoning and tool use. | Provider-neutral LLM gateway + retrieval |
| Observability | Traces, metrics, logs, correlation. | OpenTelemetry + Prometheus/Grafana-compatible stack |

### Broker strategy
The Minimum Credible Demonstration does not require Kafka. A modular monolith can use PostgreSQL outbox + polling +
in-process handlers + WebSockets. When cross-process asynchronous delivery becomes necessary, NATS JetStream is the named
lightweight broker. Kafka remains a later option for very high-volume durable streams, replay infrastructure or
multi-team stream processing, not a day-one requirement.

### Separation of concerns
- Business services never call Three.js directly.
- The UI never writes database state through ad-hoc endpoints; all consequential mutations use commands.
- Simulation cannot call live execution paths except through explicitly generated proposals.
- AI can read through permissioned tools and produce proposals, but it never bypasses authorization.

---

## 7. Event model, provenance and replay

The platform is state-first for platform-owned business transactions, with a durable event history. The event history
is sufficient for replay of selected twin-owned domains because those domains emit explicit state-delta events and
maintain periodic snapshots.

### MVP replay-authoritative domains
- Entity positions and movement state for forklifts, trucks and selected people/robots in the twin.
- Task lifecycle state: assigned, accepted, started, paused, completed, failed or cancelled.
- Incident lifecycle state: detected, unconfirmed, confirmed, resolved, closed.
- Scenario lifecycle and simulation artifacts.
- Approvals and commands, including the decision path and outcome.

Mirrored orders and inventory are replayable through captured integration events and snapshots, but are labeled as
mirrored data. The MCD uses a Simulated WMS/TMS Adapter so replay has an honest source feed.

### Event contract

| Field | Purpose |
|---|---|
| event_id | Global unique ID; idempotency key. |
| tenant_id | Tenant boundary. |
| aggregate_type / aggregate_id | Business entity that the event changes or describes. |
| event_type | Stable semantic name, e.g. FORKLIFT_POSITION_UPDATED. |
| sequence | Per-aggregate monotonic sequence where applicable. |
| occurred_at / ingested_at | When it happened vs. when the platform received it. |
| source | API, CAMERA, DEVICE, SIMULATION, IMPORT, SYSTEM or HUMAN. |
| data_mode | LIVE, SIMULATED, SYNTHETIC, REPLAY or MIXED. |
| actor_type / actor_id | Human, service, device or agent; pseudonymized for privacy where needed. |
| location | Site/zone coordinates or logical location when applicable. |
| correlation_id / causation_id | Trace a workflow and causal chain. |
| schema_version | Contract evolution identifier. |
| payload | Validated event data. |
| confidence / observation_status | For observations: confidence and detected/unconfirmed/confirmed state. |

### Ordering and late-event rules
- Consumers are idempotent and safe to retry.
- Ordering is guaranteed per aggregate/entity where the source provides sequence information.
- Late events are accepted and marked with occurred_at earlier than ingested_at; projection logic may repair state or create a correction event.
- Cross-aggregate global ordering is not assumed.
- Duplicate events do not create duplicate side effects.

---

## 8. Simulation engine and calibration

Simulation is a separate computational world derived from an explicit baseline. The MVP uses discrete-event simulation
(DES) for warehouse operations; agent-based simulation can be added later when individual behavior materially affects
the model.

### Simulation contract

| Element | Rule |
|---|---|
| Baseline | Snapshot of the relevant world state and policy versions. |
| Clock | Simulation clock is independent from wall clock; every run stores start time and time scale. |
| Randomness | Seed is mandatory; seed and RNG algorithm are stored with the run. |
| Determinism | Same baseline + scenario + seed + engine parameters must reproduce the same result. |
| Tie-breaking | Stable ordering for equal timestamps; never rely on hash-map iteration order. |
| Parallelism | No nondeterministic parallel updates inside a deterministic run; parallelization may occur between independent runs. |
| Clone model | Snapshot + copy-on-write/overlay; never duplicate all production rows for small scenario changes. |
| Output | State trajectory, KPI deltas, constraints, infeasibilities and provenance. |
| Calibration | Compare historical simulation outcomes with historical real/mirrored outcomes and report error metrics. |

### Simulation scenarios supported
- Close a zone or dock for a defined duration.
- Increase demand by a percentage over a time window.
- Reduce worker availability or change shifts.
- Remove/disable forklifts or vehicles.
- Introduce route blockage or temporary congestion.
- Add a new dock, storage zone or handling resource.
- Change picking policy, priority rules or time windows.
- Simulate delayed inbound inventory or supplier disruption.
- Compare alternative staffing or fleet allocation plans.

### Monte Carlo path
For stochastic extensions, run multiple stored seeds and report distributions or confidence intervals only when the
model supports them. An optimizer does not get a fake "confidence" percentage; its outputs use feasibility, objective
values, constraint violations and optimality gap where available.

---

## 9. Safe command, approval and execution model

`Observe -> Analyze -> Simulate -> Propose -> Approve -> Execute -> Verify`

### Recommendation contract

| Field | Purpose |
|---|---|
| recommendation_id | Stable reference for the proposal. |
| type | Routing, staffing, zone change, recovery, safety, etc. |
| rationale | Human-readable explanation grounded in evidence. |
| evidence_refs | Incident/event/metric/camera references used. |
| feasibility | Solver feasibility status. |
| objective_before / objective_after | Measured objective values, not probabilities. |
| constraints | Rules, capacity, SLA and policy constraints. |
| affected_entities | Explicit entity IDs that the plan touches. |
| baseline_entity_versions | Versions for all affected entities; used for stale-plan detection. |
| policy_versions | Policies that were evaluated. |
| execution_plan | Ordered commands with compensation/rollback strategy. |
| requires_approval | Boolean + approval class. |
| expires_at | Approval/proposal expiry. |

### Stale-baseline rule
A proposal is considered stale only when the version of an entity it intends to modify has changed, or a relevant
policy version has changed. We do not compare the entire world state. This keeps approvals usable while preventing
execution against a materially different baseline.

### Execution safety
- Every consequential proposal shows a dry-run/diff before approval.
- Approvals expire; expired approvals require re-evaluation.
- Separation of duties can require approver != proposer for sensitive actions.
- Multi-step execution has compensation actions or explicit partial-failure handling.
- Execution is idempotent where feasible and tied to a command ID.
- Execution results are verified and emitted as events; a proposal is not marked successful merely because a command was sent.

### Emergency path and safety boundary
Safety alerts are advisory. Emergency Stop in this platform means a software request, notification or command to an
external control system. It is not a safety-rated control function and does not replace hardware emergency stops,
safety PLCs or certified safety-related control systems. Real deployments must be engineered and validated against
applicable machinery/functional-safety requirements; ISO 13849-1:2023 and IEC 61508 are relevant reference families,
not a certification claim by this project.

> **Alert behavior.** Safety events are surfaced immediately even when unconfirmed. State transitions are
> DETECTED -> UNCONFIRMED -> CONFIRMED/REJECTED -> RESOLVED. Confirmation changes confidence/status; it does not delay
> the initial alert. Demo thresholds are configuration examples, not real-world safety limits.

---

## 10. Digital twin, camera and spatial architecture

The digital twin has a spatial model independent from the browser camera. A user can navigate Overview, Explore,
Follow, First Person, Physical Camera and Replay views without changing domain state.

### Virtual camera capabilities
- Orbit/pan/zoom and floor switching.
- Focus on entity, zone, dock, camera or incident.
- Follow an entity such as forklift, truck, worker or robot.
- Cinematic incident focus: move camera to the relevant spatial evidence sequence.
- Replay camera path tied to historical events.
- 2D fallback for critical operational information.

### Physical camera chain
`Physical / recorded video -> ingestion -> CV inference -> tracking -> spatial transform -> observation event -> twin projection -> UI / AI evidence`

### Spatial state model

| Concept | Purpose |
|---|---|
| Site / building / floor / zone | Logical hierarchy. |
| Coordinate system | Consistent local coordinates; document units and orientation. |
| Entity pose | Position + orientation + timestamp + provenance. |
| Camera calibration | Mapping between image coordinates and world coordinates. |
| Spatial confidence | Confidence that an observation maps to the intended physical location. |
| Subscription region | Server-side filter for live updates. |

---

## 11. Computer vision and physical-to-virtual mapping

Computer vision is a perception layer, not an authority layer. A detection is an observation. The platform can derive
a twin state only after applying identity rules, confidence and temporal consistency. For safety scenarios, an
unconfirmed detection can still trigger an alert.

### Initial vision pipeline
`Frame -> YOLO detection -> object tracker (ByteTrack/BoT-SORT) -> identity association -> coordinate transform -> observation event`

- Objects: person, forklift, truck, pallet, box and optional safety equipment.
- Track continuity across frames; treat identity as provisional until sufficiently stable.
- Store confidence and observation timestamps separately from business truth timestamps.
- Do not persist faces or biometric identifiers by default.
- Support recorded videos first; physical RTSP/WebRTC integration is a later adapter.

### Demo spatial mapping
For the MCD, we can define a small calibrated zone where pixel positions map to warehouse coordinates. The map is
documented and tested. We do not pretend one camera configuration generalizes to every warehouse.

### Multi-camera evolution
Later, multiple cameras will be treated as overlapping sensors of one space. Camera handoff becomes a data-association
problem rather than simply switching video panels. The canonical entity ID must survive camera transitions while
preserving uncertainty and provenance.

> **Safety note.** Computer vision outputs are suitable for monitoring and advisory analysis in this project. They are
> not certified safety sensors and should not be represented as a safety-rated control path.

---

## 12. Offline and edge operating model

Warehouses can have intermittent connectivity. The operator client should continue to display assigned work and capture
offline evidence where safe, then synchronize when the connection returns.

### Client technology
Initial mobile/field client: installable PWA using React/TypeScript, service worker, IndexedDB and a small local
command/evidence queue. React Native is a later option if device APIs or rugged-device integration justify native
packaging.

### Conflict rules

| Conflict | Resolution |
|---|---|
| Task reassigned while operator is offline | Server assignment wins. Offline acceptance is marked stale; operator must refresh before continuing. |
| Inventory scan against changed inventory version | Do not overwrite authoritative quantity. Create an inventory-exception event with local evidence. |
| Proof of delivery captured offline | Store evidence locally; upload later. Mark as PENDING_SYNC until server confirms. |
| Two operators complete same task | First valid server command wins; later command becomes a conflict event and is not applied twice. |
| Approval while offline | Not allowed for consequential actions. |
| Emergency action while offline | No promise of execution; client may display last-known instructions and create an urgent local record for sync. |

### Edge evolution
- Local edge gateway for video inference.
- Local buffering when cloud link is interrupted.
- Store-and-forward events with sequence numbers.
- Site-level control plane that can continue operating within bounded autonomy.

---

## 13. AI and agent architecture

AI is a grounded intelligence layer, not the system of record. It can interpret evidence, explain trends, propose
scenarios and generate action plans, but all operational writes flow through the same command and authorization layer
used by humans.

### AI tool architecture
`User identity -> AI session -> authorized tool call -> policy check -> data/query or command -> schema validation -> response / proposal`

### AI capabilities
- Natural-language operational questions: why, what changed, what is affected.
- Incident summarization with evidence references.
- Scenario creation from natural language.
- Recommendation generation backed by simulation/optimization outputs.
- Camera/evidence search over authorized live or archived data.
- Root-cause hypotheses with explicit uncertainty and supporting events.

### AI security
- Retrieved text, camera OCR and incident notes are untrusted data, never instructions.
- Every tool call runs with the authenticated human identity and tenant scope.
- Tool schemas are allowlisted; unknown operations cannot be invented.
- Model output is validated before it can become a proposal or command.
- High-impact commands require human approval and stale-baseline checks.
- External LLM use is tenant-configurable; default policy for sensitive environments is no external transmission until approved.

### AI evaluation
- Groundedness tests: answers must cite retrievable evidence.
- Permission-leak tests: model must not reveal data outside authorization scope.
- Prompt-injection tests: malicious retrieved content must not become tool instructions.
- Action safety tests: model cannot bypass command approval paths.
- Regression set: fixed operational questions with expected evidence and acceptable answer structure.

---

## 14. Integration and source-system strategy

The platform is an intelligence/simulation/decision layer over existing operational systems by default. We avoid
pretending to replace the WMS/ERP/TMS unless a deployment explicitly delegates ownership of a domain.

### Integration pattern
`WMS / OMS / TMS / ERP / IoT / Camera -> Adapter -> Canonical event/data contract -> World Model -> Twin / Analytics / Simulation / AI`

### Adapter responsibilities
- Map external IDs to canonical IDs.
- Normalize timestamps and time zones.
- Attach source and data mode provenance.
- Handle retries and duplicate messages.
- Publish contract-compliant state-delta or integration events.
- Surface data-quality failures as observable integration incidents.

### Simulated source adapter for the MCD
A Simulated WMS/TMS Adapter generates realistic but clearly labeled orders, inventory updates, vehicle assignments and
delivery states. This allows the MCD to behave like a real integrated product without claiming access to a production
customer system.

### Integration safety
- Never silently overwrite source-owned records.
- Store source version / update timestamp when available.
- Use idempotency keys for inbound writes.
- Surface reconciliation differences rather than hiding them.
- Keep adapter code behind interfaces so future connectors can be swapped.

---

## 15. UX / visual design system

The product should feel like a premium enterprise operations platform with an immersive visual layer, not a game UI and
not a decorative 3D demo.

### Visual hierarchy
- Top: organization context + global status
- Center: digital twin / selected operational context
- Right: inspector + live camera / evidence
- Bottom: event stream + analytics + timeline

### Design rules
- Use the 3D environment for spatial understanding; use cards and charts for precise quantitative comparison.
- Selection panels are context-sensitive; avoid showing every metric at once.
- Provide 2D, list and table views for keyboard/small-screen workflows.
- Keep live state visibly distinct from replay and simulation state.
- Simulation results show baseline vs. scenario deltas and affected entities.
- Every synthetic demo metric is labeled or derived from the underlying seeded data.
- Camera transitions should support pause/reduced motion and never interrupt urgent alerts.
- Internationalization: locale, currency, number format, units, time zone and language are configuration, not assumptions.

### Signature interaction
A user asks an operational question, the AI retrieves authorized evidence, the answer highlights affected entities, and
the camera focuses the relevant place in the twin. The visual navigation is the explanation layer, not just spectacle.

---

## 16. API and event contract baseline

### Query resources

| Resource | Example endpoint | Purpose |
|---|---|---|
| Organizations | GET /api/organizations | Organization and scope metadata. |
| World state | GET /api/twin/world | Current normalized twin state within authorized scope. |
| Entities | GET /api/entities/{id} | Canonical entity inspector. |
| Events | GET /api/events | Filtered operational history. |
| Incidents | GET /api/incidents/{id} | Incident timeline and evidence. |
| Scenarios | GET /api/scenarios/{id} | Scenario definition and results. |
| Simulations | GET /api/simulations/{id} | Simulation outputs and metrics. |
| Recommendations | GET /api/recommendations/{id} | Decision package and evidence. |
| Cameras | GET /api/cameras | Camera registry and streams. |
| Analytics | GET /api/metrics/... | Governed KPI definitions and values. |

### Command resources

| Command | Endpoint | Rule |
|---|---|---|
| Complete task | POST /api/tasks/{id}/complete | Only assigned/authorized actor; idempotent. |
| Report issue | POST /api/tasks/{id}/issues | Creates incident/issue event; does not silently close task. |
| Create scenario | POST /api/scenarios | Clones baseline and accepts scenario changes. |
| Approve proposal | POST /api/approvals/{id}/approve | Checks scope, separation of duties and expiry. |
| Execute proposal | POST /api/recommendations/{id}/execute | Checks baseline entity versions and policy versions. |
| Emergency request | POST /api/safety/emergency-stop-request | Advisory software command; external safety system owns physical stop. |

### Command-vs-query rule
GET/queries describe state and history. POST/command endpoints request state changes. Consequential state changes must
create an auditable command record and resulting event(s). AI does not receive a special write API; it uses the same
commands and policies.

### KPI semantic layer
KPI definitions are governed entities. For each metric we store formula, business meaning, dimensions, time basis,
source fields, freshness and owner. This prevents two screens from silently calculating different meanings for
"On-Time Delivery" or "Throughput".

---

## 17. Observability, SLOs, reliability and disaster recovery

Targets below are initial engineering hypotheses for the MCD and hardening work. They must be revalidated after
workload modeling and load tests.

### Provisional SLOs

| Signal | Initial target | Measurement |
|---|---|---|
| Standard API p95 | < 500 ms | Authenticated representative read/query workload. |
| Command acknowledgement p95 | < 1 s | Request accepted with command ID; full execution may take longer. |
| Outbox publish p95 | < 2 s | Commit to event availability to downstream consumers. |
| Event-to-UI p95 | < 2 s | Observed event commit to relevant client update. |
| Replay navigation | Interactive for MCD history | No more than a few seconds between timeline interaction and rendered state for demo scale. |
| Simulation run | < 60 s for MCD scenario | Seeded warehouse scenario at documented scale. |
| AI response | < 10 s typical | Simple grounded operational queries; long analyses may take longer. |
| MCD availability | 99.5% | Monthly non-safety service objective. |

### Recovery objectives

| Item | Initial target | Note |
|---|---|---|
| RPO | <= 15 minutes | For non-safety platform data; depends on backup mechanism. |
| RTO | <= 60 minutes | For non-safety platform recovery in the MCD environment. |
| Critical audit integrity | No acknowledged loss | Audit path requires durable transactional persistence. |

### Observability
- Distributed trace: correlation_id and causation_id flow through commands, events and AI tool calls.
- Metrics: API latency, outbox age, event lag, WebSocket fanout, simulation duration, CV latency, AI tool latency.
- Logs: structured, tenant-aware, privacy-safe; never log secrets or raw sensitive camera payloads by default.
- Alerts: data-quality, integration lag, event backlog, stale subscriptions, repeated authorization failures and simulation drift.

---

## 18. Security, privacy and governance

### Security baseline
- Tenant isolation: shared PostgreSQL database with tenant_id and row-level security where practical; tenant namespaces in Redis, object storage and event routing.
- Authentication: centralized identity provider integration; short-lived access tokens; secure refresh strategy.
- Authorization: policy evaluated at API, command, WebSocket subscription and AI tool boundaries.
- Secrets: external secret manager for deployed environments; never commit credentials.
- Audit: immutable logical audit history with privacy-preserving actor references.
- Supply chain: pinned dependencies, image scanning, SBOM and signed build artifacts as the project matures.

### Privacy-by-design
- Camera/person tracking is purpose-limited and configurable per tenant/site.
- Face/biometric identifiers are not required for the core product and should be disabled by default.
- Retention periods are configurable by data class and jurisdiction.
- Worker movement data is not automatically turned into individual performance scoring.
- Access to sensitive footage and person-linked events is separately audited.
- Global deployments require jurisdiction-specific privacy, labor and workplace review.

### Erasure vs immutable audit
Append-only business/audit history may need to survive while personal identity obligations require deletion or
minimization. The preferred design is to store pseudonymous actor references in events and keep identifying profile data
in a separable identity store. When lawful deletion is required, identity records can be deleted or cryptographically
shredded while the event history retains a non-identifying reference and business integrity.

### External LLM data policy

| Policy | Default |
|---|---|
| External LLM transmission | Disabled for sensitive tenants until explicitly enabled. |
| PII minimization | Redact or tokenize before external transmission where permitted. |
| Tenant residency | Configuration must specify approved model/provider regions. |
| Tool authorization | Always use the human user identity + tenant policy. |
| Prompt/response retention | Configurable; minimize raw retention where not required for evaluation/audit. |

---

## 19. Technology choices and rationale

| Area | Choice | Why | Later upgrade path |
|---|---|---|---|
| Web app | React + TypeScript | Strong ecosystem for enterprise UI and 3D integration. | React Server Components or alternative shell if needed. |
| 3D | Three.js + React Three Fiber | Web-native 3D with flexible scene/camera control. | More advanced rendering/engine later without changing the world model. |
| State | Zustand | Small, explicit client state for live twin interactions. | Domain-specific stores or server-state tooling if scale demands. |
| Backend | Spring Boot | Strong transactional, security and enterprise API support. | Split selected domains when justified. |
| Database | PostgreSQL | Reliable transactional state, rich querying and extensibility. | PostGIS / partitioning / read replicas as workloads grow. |
| Cache | Redis | Hot state, subscriptions, rate limits and ephemeral coordination. | Clustered Redis when needed. |
| Async | Postgres outbox -> NATS JetStream | Reliable initial flow without day-one broker complexity. | Kafka for high-volume multi-team streaming if justified. |
| Vision | Python + OpenCV + YOLO + tracker | Fast iteration for perception workloads. | GPU inference services / DeepStream if scale warrants. |
| Optimization | OR-Tools | Open, mature and vendor-neutral starting point. | Specialized solvers or NVIDIA cuOpt adapter later. |
| Mobile | PWA + IndexedDB | Low-friction offline workflow, shared web stack. | React Native for rugged/native device needs. |
| AI | Provider-neutral gateway | Avoid coupling domain logic to one model vendor. | Multiple providers or local models by policy. |
| Observability | OpenTelemetry | Portable instrumentation across services. | Vendor-specific backends later. |

### Three-language trade-off
TypeScript is optimized for the product UI and real-time user experience; Java is optimized for transactional enterprise
services and policy-heavy workflows; Python is optimized for computer vision, experimentation and AI. This split is
deliberate but increases repository and deployment complexity. The architecture must keep contracts language-neutral
through HTTP/event schemas.

---

## 20. Repository structure and engineering workflow

```
virtual-organization/
  apps/        web/  operator-pwa/
  services/    api/  vision/  ai/  simulation/
  packages/    contracts/  twin-schema/  ui/
  infra/       docker/  monitoring/  db/
  docs/        adr/  product/  api/  scenarios/
  tests/       contract/  integration/  simulation/  security/  ai-eval/
```

### Engineering workflow
1. Write or update the relevant ADR before introducing a cross-cutting architecture change.
2. Define domain state and invariants before building the UI around it.
3. Implement a vertical slice: command -> state -> outbox -> event -> projection -> UI.
4. Add tests for happy path, failure path, idempotency and authorization.
5. Instrument the slice before calling it complete.
6. Document demo data provenance and how to reproduce the scenario.

### Definition of done
- Function works through the intended UI and API path.
- Authorization is enforced at the server boundary.
- State mutation is transactional and event behavior is idempotent.
- Live/replay/simulation data modes are correctly labeled.
- Logs/metrics/traces exist for important workflows.
- Tests cover the core invariant and at least one failure case.
- AI features include evidence and cannot bypass commands.
- UI remains usable on keyboard and small screens for critical workflows.

---

## 21. Build strategy and effort bands

The effort figures below are rough engineering-size bands, not promises or deadlines. They exclude major unfamiliarity
spikes, customer integration work and production compliance certification.

| Capability | Effort band (engineer-weeks) | Key output |
|---|---|---|
| Foundation | 2-3 | Auth, tenancy, core schema, outbox, baseline contracts, CI. |
| Digital Twin | 3-5 | 3D warehouse, world model, object inspector, camera system. |
| Live Operations | 3-4 | WebSocket updates, live events, spatial subscriptions, simulated source adapter. |
| Replay | 2-3 | Snapshots, state-delta replay for twin-owned domains, timeline UI. |
| Simulation | 4-6 | Deterministic DES, scenario clone/overlay, baseline comparison. |
| Computer Vision | 5-8 | Recorded video, detection, tracking, spatial mapping, safety observation path. |
| Optimization | 3-5 | Routing/resource allocation scenarios and recommendation contracts. |
| AI operations | 3-5 | Grounded assistant, tools, evidence, evaluation set. |
| Hardening | 4-6 | Load tests, chaos tests, security, privacy, SLOs, DR, UX/accessibility. |

> **Scope discipline.** The Minimum Credible Demonstration intentionally stops before the full physical-AI vision. The
> project becomes stronger by proving one complete slice than by starting ten advanced subsystems and leaving them
> disconnected.

### Recommended build gates
1. Foundation: identity, tenancy, world schema, ownership, permissions and outbox.
2. Twin: 3D warehouse and deterministic simulated world state.
3. Live Ops: event flow and real-time spatial subscriptions.
4. Replay: past incident reconstruction.
5. Simulation: one what-if scenario with deterministic output.
6. Vision: one recorded-camera safety or movement scenario.
7. Optimization: one constrained routing/resource scenario.
8. AI: grounded query + recommendation proposal.
9. Hardening: security, observability, test and accessibility gates.

---

## 22. Minimum Credible Demonstration

The MCD must tell one coherent story from organizational state to decision support. It must be possible to demo without
pretending that data came from an external production system.

### MCD scenario
1. Open the organization overview as an Executive or Warehouse Manager.
2. Navigate into a simulated Bengaluru warehouse.
3. Explore the live 3D twin with forklifts, workers, pallets and docks.
4. Open a live simulated camera feed showing labeled detections.
5. Trigger a worker/forklift proximity observation; the alert appears immediately as DETECTED/UNCONFIRMED.
6. Open the incident; the camera focuses the spatial evidence and the event timeline explains the sequence.
7. Use Replay to step through the incident from the last snapshot plus state-delta events.
8. Create a scenario that closes a dock for a defined interval.
9. Run a deterministic simulation and compare baseline vs. scenario throughput/queue outcomes.
10. Ask the AI Operations Assistant why the bottleneck matters; it retrieves authorized evidence and points to the affected zone.
11. Generate a recommendation. Approve only when the touched-entity baseline still matches; otherwise re-simulate.
12. Execute only a safe demo command against the simulated source adapter and verify the resulting state/event chain.

### MCD truth labels

| UI state | Label |
|---|---|
| Real customer integration | LIVE / SOURCE: WMS/TMS |
| Simulated adapter data | SIMULATED |
| Generated test scenario | SYNTHETIC |
| Historical playback | REPLAY |
| Scenario result | SIMULATION |
| Mixed environment | MIXED - source labels shown per entity/event |

---

## 23. Signature scenarios and future upgrades

### Near-term signatures

| Scenario | What it demonstrates |
|---|---|
| Warehouse bottleneck | Live state + analytics + camera focus + AI explanation. |
| Safety proximity | Vision observation + alerting + spatial context + incident replay. |
| Dock closure | Deterministic simulation + business KPI comparison. |
| Demand surge | Capacity planning + simulation + optimization. |
| Forklift outage | Resource reallocation + impact analysis. |
| Route blockage | Dynamic routing + affected-order visualization. |
| Shift shortage | Staffing simulation + schedule alternatives. |

### Future upgrades
- Multi-camera identity tracking and cross-camera spatial association.
- Edge inference gateways and store-and-forward site operation.
- Robotics/AMR simulation and human-robot interaction modeling.
- Advanced physics / photorealistic simulation adapters where justified.
- Optimization service abstraction with multiple solvers.
- Agentic planning with strict tool authorization and evaluation.
- Scenario libraries and reusable industry templates.
- Cross-facility network simulation: warehouses, plants, ports and transportation legs.
- Digital twin APIs that external applications can consume.
- Multi-tenant marketplace of world-model templates and policy packs.

### Industry expansion path
`Warehouse -> Logistics -> Manufacturing -> Retail -> Ports/Airports -> Campuses/Facilities -> Other operational domains`

The key is that expansion happens by adding domain schemas, policies, simulation modules and adapters on top of the same
platform primitives. We do not rebuild the product for each industry.

---

## 24. Risk register and validation strategy

| Risk | Likelihood | Impact | Control |
|---|---|---|---|
| Visual-first drift | Medium | High | Force world-model-first design and vertical slices. |
| Event/state divergence | Low | Very high | Transactional outbox; explicit replay-authoritative domains. |
| Simulation mismatch | Medium | High | Calibration against historical data and error reporting. |
| AI hallucination | Medium | High | Evidence requirements, tool boundaries, evaluation suite. |
| Permission leakage | Low | Very high | Server-side policy, tenant tests, AI tool identity enforcement. |
| Camera privacy exposure | Medium | Very high | Privacy-by-design, minimization, retention and access audit. |
| False safety confidence | Medium | Very high | Advisory-only labeling; no safety-rated claims. |
| Architecture overload | High | High | Gate-based scope; broker and microservices deferred until justified. |
| 3D performance | Medium | Medium | LOD, spatial subscriptions, object pooling and 2D fallback. |
| Integration complexity | High | High | Adapter boundary + simulated source adapter. |

### Validation strategy
- Replay determinism: same snapshot + same event sequence -> same state.
- Simulation determinism: same seed/clock/scenario -> same output.
- Chaos: kill event publisher, cache, WebSocket path or simulation worker and verify recovery behavior.
- Security: tenant isolation, authorization matrix, tool permissions, audit integrity.
- Performance: load test representative entity counts and event rates.
- CV evaluation: detection/tracking metrics on a fixed demo video set.
- AI evaluation: groundedness, permission leakage, prompt injection and action safety.
- UX/accessibility: keyboard, reduced motion, narrow screen, locale/time-zone tests.

### Readiness checklist before each gate
`Schema defined -> contracts defined -> authorization defined -> observability added -> tests added -> demo path verified -> provenance labels verified`

---

## 25. AI coding assistant operating contract

> **Primary instruction.** Treat this document as the project source of truth. Do not optimize for speed by bypassing
> architecture, security, provenance or test requirements. When requirements conflict, preserve the higher-level product
> principles and propose an ADR rather than silently inventing behavior.

### The coding agent must
- Read the relevant ADRs and domain contract before changing a cross-cutting component.
- Never invent permissions, roles, ownership or event fields that are not defined.
- Preserve tenant boundaries in every query, event, cache key, storage prefix, WebSocket subscription and AI tool.
- Never call external systems or LLMs with sensitive data unless tenant policy permits it.
- Keep LIVE, REPLAY, SIMULATION, SYNTHETIC and MIXED data modes explicit.
- Use commands for consequential mutations; never create hidden mutation endpoints for convenience.
- Use transactional outbox for transactional events and idempotent consumers for downstream handling.
- Keep simulation isolated; no direct mutation of live state from a scenario run.
- Never claim a software safety control is a certified or hardware safety function.
- Build accessibility and reduced-motion behavior into critical workflows.
- Add tests for new invariants and failure cases, not just happy paths.
- Prefer the smallest complete vertical slice over broad unfinished features.

### Decision escalation rule
When the agent encounters an ambiguous choice that affects data ownership, replay, permissions, event semantics,
simulation determinism, safety, tenancy or integration boundaries, it must stop and propose a decision with alternatives,
trade-offs and an ADR entry. It must not quietly choose a behavior that could make the platform internally inconsistent.

### Build command principle
Every feature should answer: What business capability is being added? Which world-model entities change? Which
commands/events exist? Who can perform them? How is the change replayed? How is it simulated? How is it observed? What
happens when it fails?

---

## Appendix A. ADR register

| ADR | Decision | Baseline |
|---|---|---|
| ADR-01 | State authority | PostgreSQL is authoritative for platform-owned transactional domains. |
| ADR-02 | Transactional outbox | Business state and outbox event are committed in one transaction. |
| ADR-03 | Ordering and idempotency | Per-entity ordering where available; consumers are idempotent; no global ordering assumption. |
| ADR-04 | Deterministic simulation | Seeded DES, explicit clock, stable tie-breaking, snapshot + overlay. |
| ADR-05 | Safety boundary | Software alerts/requests are advisory; not a safety-rated control path. |
| ADR-06 | Authorization | RBAC + scoped/contextual policies; server enforced at API/event/twin/AI boundaries. |
| ADR-07 | Tenancy | Shared Postgres + tenant_id + RLS, extended to caches/storage/event routing. |
| ADR-08 | Integration ownership | Overlay by default; explicit ownership matrix and source adapters. |
| ADR-09 | Replay authority | Twin-owned positions, task state and incident state emit state-delta events and snapshots for replay. |
| ADR-10 | Broker strategy | MCD uses Postgres outbox/poller; NATS JetStream when cross-process async is needed; Kafka later by evidence. |
| ADR-11 | Offline client | PWA + IndexedDB first; deterministic conflict rules; no offline approvals. |
| ADR-12 | AI data handling | External LLM use is policy-controlled, identity-scoped and validated; retrieved content is untrusted. |
| ADR-13 | Provenance | Every event records source and data_mode, with location and observation confidence where applicable. |
| ADR-14 | KPI semantics | Metrics are governed definitions with formula, source, dimensions and owner. |

## Appendix B. Glossary

| Term | Meaning |
|---|---|
| World Model | Canonical representation of organizational entities, state, relationships and spatial context. |
| Digital Twin | A visual/operational representation of the world model synchronized with selected live or historical data. |
| Observation | A statement inferred from a sensor, camera, device, import or other source, with provenance and uncertainty. |
| Derived identity | A provisional linkage of an observation to a canonical entity. |
| Event | A durable semantic record describing a state change or significant observation. |
| State-delta event | An event containing enough information to apply or reverse a bounded state change in a replay-authoritative domain. |
| Replay | Historical reconstruction from snapshots and ordered events without treating the playback as live. |
| Simulation | Execution of a cloned world state under explicit scenario inputs, clock and random seed. |
| Overlay integration | Architecture in which the platform analyzes/simulates external source systems without automatically owning their core transactions. |
| Transactional outbox | Pattern that stores state mutation and outbound event intent in the same database transaction. |
| Recommendation | A structured proposed change with evidence, feasibility, affected entities and an execution plan. |
| Canonical ID | Stable internal identifier mapped to external system identifiers. |
| Spatial subscription | A filtered real-time feed for only the relevant region/entity set. |
| Data mode | A label such as LIVE, SIMULATED, SYNTHETIC, REPLAY or MIXED. |
| MCD | Minimum Credible Demonstration: the smallest coherent end-to-end product proof. |

## Appendix C. Research references

Vendor-reported performance figures are treated as claims of the source organizations, not as guarantees or benchmarks
for this project.

| Ref | Source | URL | Use in project |
|---|---|---|---|
| R1 | Siemens - Digital Twin Composer | https://www.siemens.com/en-us/company/digital-transformation/industrial-metaverse/introducing-digital-twin-composer/ | Combines 2D/3D digital twin information with real-time physical data and simulation; announced at CES 2026. |
| R2 | Siemens - Digital Twin Composer press release | https://news.siemens.com/en-gb/digital-twin-composer-ces-2026/ | Industrial metaverse environments, simulation, real-time data and PepsiCo facility use. |
| R3 | Siemens - Logistics digital twin | https://www.siemens.com/en-gb/products/digital-logistics/consulting/logistics-digital-twin/ | Supply-chain and intralogistics digital twins, scenario analysis. |
| R4 | Siemens - India launch | https://press.siemens.com/in/en/pressrelease/siemens-launches-teamcenter-digital-reality-viewer-and-digital-twin-composer-india | Digital twin data, simulation and real-time operational signals. |
| R5 | NVIDIA - Metropolis | https://www.nvidia.com/en-us/autonomous-machines/intelligent-video-analytics-platform/ | Video analytics AI agents, multi-camera tracking, warehouse use cases. |
| R6 | NVIDIA - Multi-camera tracking | https://www.nvidia.com/en-in/use-cases/ai-powered-multi-camera-tracking/ | Unified multi-camera tracking across large spaces. |
| R7 | NVIDIA - Warehouse logistics | https://www.nvidia.com/en-in/industries/retail/warehouse-logistics/ | Digital twins, simulation and optimization for warehouse/logistics. |
| R8 | ISO - ISO 13849-1:2023 | https://www.iso.org/standard/73481.html | Machinery safety-related control systems; boundary for software safety claims. |
| R9 | IEC - Functional safety / IEC 61508 | https://webstore.iec.ch/en/publication/5515 | Functional safety reference for E/E/PE safety-related systems. |

> **Public-document rule.** Before publishing claims about industry adoption, market size or vendor performance,
> re-check the linked official source and quote the scope of the claim accurately.
