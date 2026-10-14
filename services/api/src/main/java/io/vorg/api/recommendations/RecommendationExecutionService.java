package io.vorg.api.recommendations;

import io.vorg.api.integration.core.AdapterCommandPort;
import io.vorg.api.integration.simulated.SimulatedTmsAdapter;
import io.vorg.api.platform.command.CommandResult;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.DomainEvents;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.recommendations.CurrentStateReader.EntityState;
import io.vorg.api.recommendations.RecommendationStore.ApprovalView;
import io.vorg.api.recommendations.RecommendationStore.RecommendationView;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executing an approved recommendation (Plan 8.3, REQ-095, ADR-63). Runs inside the command gateway's transaction
 * (authorized as {@code EXECUTE_STANDARD_COMMAND} with {@code PolicyContext.approvedWorkflow()}). Under a row lock:
 * <ol>
 *   <li>state: the recommendation must be {@code APPROVED} with an {@code APPROVED} approval and not past
 *       {@code expires_at} ({@code 409 not_approved}, {@code 409 approval_expired});</li>
 *   <li>stale check, in this transaction, on the affected-entity versions and the policy versions only: on any
 *       mismatch the recommendation becomes {@code STALE}, {@code RECOMMENDATION_STALE} is recorded and committed, and
 *       the handler returns {@code outcome: STALE}; the controller answers {@code 409 stale_baseline};</li>
 *   <li>every step's command must go to a SIMULATED {@link AdapterCommandPort}; anything else is refused before a
 *       single step is applied ({@code 422 live_source_refused}, BLUEPRINT section 22);</li>
 *   <li>otherwise {@code EXECUTING}: the steps are applied in order. When a step fails, the applied steps are
 *       compensated in reverse; the outcome is {@code FAILED} when every compensation succeeded and {@code PARTIAL}
 *       when one did not, with the failure as evidence.</li>
 * </ol>
 * A successful execution stays {@code EXECUTING} until {@link RecommendationVerifier} observes every target at its
 * new dock (REQ-096).
 */
@Service
public class RecommendationExecutionService {

    public static final String STARTED_EVENT = "RECOMMENDATION_EXECUTION_STARTED";
    public static final String STEP_APPLIED_EVENT = "RECOMMENDATION_STEP_APPLIED";
    public static final String STEP_COMPENSATED_EVENT = "RECOMMENDATION_STEP_COMPENSATED";
    public static final String FAILED_EVENT = "RECOMMENDATION_EXECUTION_FAILED";
    public static final String STALE_EVENT = "RECOMMENDATION_STALE";
    public static final String VERIFIED_EVENT = "RECOMMENDATION_VERIFIED";

    public static final String OUTCOME_STALE = "STALE";

    /** Step statuses in {@code execution.steps[].status}. */
    public static final String APPLIED = "APPLIED";
    public static final String COMPENSATED = "COMPENSATED";
    public static final String FAILED = "FAILED";
    public static final String VERIFIED = "VERIFIED";
    /** A step that was never attempted because an earlier one failed. */
    public static final String NOT_APPLIED = "NOT_APPLIED";

    private final RecommendationStore store;
    private final CurrentStateReader currentState;
    private final PolicyVersions policyVersions;
    private final List<AdapterCommandPort> ports;
    private final DomainEvents events;
    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final Clock clock;

    public RecommendationExecutionService(RecommendationStore store, AssignmentStateReader currentState,
            PolicyVersions policyVersions, List<AdapterCommandPort> ports, DomainEvents events, JdbcClient jdbc,
            JsonMapper mapper, Clock clock) {
        this.store = store;
        this.currentState = currentState;
        this.policyVersions = policyVersions;
        this.ports = ports;
        this.events = events;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The recommendation without an authorization check (to resolve the site the command is authorized on). */
    public RecommendationView locate(Subject subject, UUID id) {
        return store.findRecommendation(subject.tenantId(), id, false)
                .orElseThrow(() -> RecommendationProblems.notFound("recommendation"));
    }

    /** Whether the recommendation has an APPROVED approval (decides whether the gateway sees an approved workflow). */
    public boolean approved(Subject subject, RecommendationView rec) {
        return rec.status() == RecommendationStatus.APPROVED && store.findApproval(subject.tenantId(),
                rec.approvalId(), false).map(a -> a.status() == ApprovalStatus.APPROVED).orElse(false);
    }

    @SuppressWarnings("unchecked")
    public CommandResult execute(Subject subject, UUID recommendationId, String idempotencyKey) {
        UUID tenantId = subject.tenantId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        RecommendationView rec = store.findRecommendation(tenantId, recommendationId, true)
                .orElseThrow(() -> RecommendationProblems.notFound("recommendation"));
        ApprovalView approval = store.findApproval(tenantId, rec.approvalId(), true)
                .orElseThrow(() -> new IllegalStateException("Recommendation " + rec.id() + " has no approval"));
        if (rec.status() != RecommendationStatus.APPROVED || approval.status() != ApprovalStatus.APPROVED) {
            throw RecommendationProblems.of(HttpStatus.CONFLICT, "Not approved",
                    "Only an APPROVED recommendation with an APPROVED approval can be executed (status "
                            + rec.status() + ", approval " + approval.status() + ").",
                    RecommendationProblems.NOT_APPROVED,
                    Map.of("recommendation_status", rec.status().name(), "approval_status", approval.status().name()));
        }
        if (ApprovalRules.expired(now, rec.expiresAt())) {
            throw RecommendationProblems.of(HttpStatus.CONFLICT, "Approval expired",
                    "The approved plan expired at " + rec.expiresAt() + "; re-evaluate the simulation run.",
                    RecommendationProblems.APPROVAL_EXPIRED,
                    Map.of("recommendation_id", rec.id().toString(), "approval_id", approval.id().toString()));
        }

        List<Map<String, Object>> stale = staleEntries(tenantId, rec);
        if (!stale.isEmpty()) {
            return markStale(subject, rec, stale, now);
        }

        List<Map<String, Object>> steps = (List<Map<String, Object>>) rec.executionPlan().get("steps");
        List<AdapterCommandPort> stepPorts = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            stepPorts.add(port(String.valueOf(step.get("command_type"))));
        }

        UUID commandId = commandId(tenantId, idempotencyKey);
        List<Map<String, Object>> exSteps = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("seq", step.get("seq"));
            s.put("command_type", step.get("command_type"));
            s.put("target", step.get("target"));
            s.put("status", NOT_APPLIED);
            s.put("observed_event_id", null);
            exSteps.add(s);
        }
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("command_id", commandId == null ? null : commandId.toString());
        execution.put("started_at", now.toString());
        execution.put("finished_at", null);
        execution.put("steps", exSteps);
        execution.put("evidence", null);
        store.updateExecution(tenantId, rec, RecommendationStatus.EXECUTING, json(execution),
                tmsCursor(tenantId, rec.siteId()), now);
        RecommendationView current;

        Map<String, Object> started = new LinkedHashMap<>();
        started.put("status", RecommendationStatus.EXECUTING.name());
        started.put("command_id", execution.get("command_id"));
        started.put("approval_id", approval.id().toString());
        started.put("step_count", steps.size());
        started.put("baseline_entity_versions", rec.baselineEntityVersions());
        started.put("policy_versions", rec.policyVersions());
        EventEnvelope startedEvent = events.record(RecommendationService.event(subject,
                RecommendationService.AGGREGATE, rec.id(), STARTED_EVENT, rec.siteId(), withoutNulls(started)));

        List<AdapterCommandPort.Result> applied = new ArrayList<>();
        Map<String, Object> failure = null;
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            AdapterCommandPort port = stepPorts.get(i);
            Map<String, Object> params = (Map<String, Object>) step.getOrDefault("params", Map.of());
            try {
                AdapterCommandPort.Result r = port.apply(tenantId, rec.siteId(),
                        new AdapterCommandPort.Command(String.valueOf(step.get("command_type")), params));
                applied.add(r);
                exSteps.get(i).put("status", APPLIED);
                Map<String, Object> payload = stepPayload(step, port);
                payload.put("previous_value", r.previousValue());
                payload.put("new_value", r.newValue());
                payload.put("source_version", r.sourceVersion());
                events.record(RecommendationService.event(subject, RecommendationService.AGGREGATE, rec.id(),
                        STEP_APPLIED_EVENT, rec.siteId(), withoutNulls(payload)));
            } catch (AdapterCommandPort.CommandRefusedException ex) {
                exSteps.get(i).put("status", FAILED);
                failure = new LinkedHashMap<>();
                failure.put("seq", step.get("seq"));
                failure.put("target", step.get("target"));
                failure.put("source_system", port.sourceSystem());
                failure.put("code", ex.code());
                failure.put("message", ex.getMessage());
                break;
            }
        }

        if (failure == null) {
            current = reload(tenantId, rec.id());
            int version = store.updateExecution(tenantId, current, RecommendationStatus.EXECUTING, json(execution),
                    null, now);
            return result(rec.id(), RecommendationStatus.EXECUTING, version, execution, startedEvent);
        }

        // Compensate the applied steps in reverse order.
        List<Map<String, Object>> compensationFailures = new ArrayList<>();
        for (int i = applied.size() - 1; i >= 0; i--) {
            AdapterCommandPort.Result r = applied.get(i);
            Map<String, Object> step = steps.get(i);
            AdapterCommandPort port = stepPorts.get(i);
            try {
                AdapterCommandPort.Result undone = port.apply(tenantId, rec.siteId(), r.compensation());
                exSteps.get(i).put("status", COMPENSATED);
                Map<String, Object> payload = stepPayload(step, port);
                payload.put("restored_value", undone.newValue());
                payload.put("source_version", undone.sourceVersion());
                events.record(RecommendationService.event(subject, RecommendationService.AGGREGATE, rec.id(),
                        STEP_COMPENSATED_EVENT, rec.siteId(), withoutNulls(payload)));
            } catch (AdapterCommandPort.CommandRefusedException ex) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("seq", step.get("seq"));
                f.put("target", step.get("target"));
                f.put("code", ex.code());
                f.put("message", ex.getMessage());
                compensationFailures.add(f);
            }
        }
        RecommendationStatus outcome = compensationFailures.isEmpty() ? RecommendationStatus.FAILED
                : RecommendationStatus.PARTIAL;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("reason", "STEP_FAILED");
        evidence.put("failed_step", failure);
        evidence.put("compensation_failures", compensationFailures);
        execution.put("finished_at", now.toString());
        execution.put("evidence", evidence);
        current = reload(tenantId, rec.id());
        int finalVersion = store.updateExecution(tenantId, current, outcome, json(execution), null, now);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", outcome.name());
        payload.put("command_id", execution.get("command_id"));
        payload.put("evidence", evidence);
        events.record(RecommendationService.event(subject, RecommendationService.AGGREGATE, rec.id(), FAILED_EVENT,
                rec.siteId(), withoutNulls(payload)));
        return result(rec.id(), outcome, finalVersion, execution, startedEvent);
    }

    /** Affected entities whose version moved (or that are gone) and policies whose version changed. */
    private List<Map<String, Object>> staleEntries(UUID tenantId, RecommendationView rec) {
        Map<String, EntityState> states = currentState.read(tenantId, rec.siteId(), rec.affectedEntities());
        List<Map<String, Object>> stale = new ArrayList<>();
        rec.baselineEntityVersions().forEach((entity, baseline) -> {
            EntityState now = states.get(entity);
            Integer current = now == null ? null : now.version();
            Integer recorded = baseline instanceof Number n ? n.intValue() : null;
            if (!Objects.equals(recorded, current)) {
                stale.add(staleRow("ENTITY", entity, recorded, current));
            }
        });
        Map<String, Integer> recordedPolicies = new LinkedHashMap<>();
        rec.policyVersions().forEach((name, v) -> recordedPolicies.put(name, v instanceof Number n ? n.intValue()
                : null));
        for (PolicyVersions.Change c : policyVersions.changed(recordedPolicies)) {
            stale.add(staleRow("POLICY", c.policy(), c.baselineVersion(), c.currentVersion()));
        }
        return stale;
    }

    private CommandResult markStale(Subject subject, RecommendationView rec, List<Map<String, Object>> stale,
            Instant now) {
        int version = store.updateStatus(subject.tenantId(), rec, RecommendationStatus.STALE, now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", RecommendationStatus.STALE.name());
        payload.put("previous_status", rec.status().name());
        payload.put("stale", stale);
        payload.put("scenario_id", rec.scenarioId().toString());
        payload.put("simulation_run_id", rec.simulationRunId().toString());
        EventEnvelope event = events.record(RecommendationService.event(subject, RecommendationService.AGGREGATE,
                rec.id(), STALE_EVENT, rec.siteId(), payload));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", OUTCOME_STALE);
        result.put("recommendation_id", rec.id().toString());
        result.put("status", RecommendationStatus.STALE.name());
        result.put("version", version);
        result.put("stale", stale);
        result.put("scenario_id", rec.scenarioId().toString());
        result.put("event_id", event.eventId().toString());
        return CommandResult.of(result);
    }

    private static CommandResult result(UUID id, RecommendationStatus status, int version,
            Map<String, Object> execution, EventEnvelope started) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", status.name());
        result.put("recommendation_id", id.toString());
        result.put("status", status.name());
        result.put("version", version);
        result.put("execution", execution);
        result.put("event_id", started.eventId().toString());
        return CommandResult.of(result);
    }

    /** The SIMULATED port that executes a command type; a missing or LIVE port refuses the whole plan. */
    private AdapterCommandPort port(String commandType) {
        AdapterCommandPort port = ports.stream().filter(p -> p.commandTypes().contains(commandType)).findFirst()
                .orElseThrow(() -> RecommendationProblems.of(HttpStatus.UNPROCESSABLE_CONTENT, "Command unsupported",
                        "No source system executes " + commandType + ".", RecommendationProblems.LIVE_SOURCE_REFUSED,
                        Map.of("command_type", commandType)));
        if (port.dataMode() != DataMode.SIMULATED) {
            throw RecommendationProblems.of(HttpStatus.UNPROCESSABLE_CONTENT, "Live source refused",
                    "Recommendation plans execute only against simulated source systems; " + port.sourceSystem()
                            + " is " + port.dataMode() + ".",
                    RecommendationProblems.LIVE_SOURCE_REFUSED,
                    Map.of("command_type", commandType, "source_system", port.sourceSystem()));
        }
        return port;
    }

    private static Map<String, Object> stepPayload(Map<String, Object> step, AdapterCommandPort port) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seq", step.get("seq"));
        payload.put("command_type", step.get("command_type"));
        payload.put("target", step.get("target"));
        payload.put("source_system", port.sourceSystem());
        return payload;
    }

    private static Map<String, Object> staleRow(String kind, String entity, Integer baseline, Integer current) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("entity", entity);
        row.put("baseline_version", baseline);
        row.put("current_version", current);
        return row;
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> m) {
        m.values().removeIf(Objects::isNull);
        return m;
    }

    private RecommendationView reload(UUID tenantId, UUID id) {
        return store.findRecommendation(tenantId, id, true)
                .orElseThrow(() -> new IllegalStateException("Recommendation " + id + " vanished"));
    }

    /** The gateway's command id for this call (its row was inserted in this transaction under the key). */
    private UUID commandId(UUID tenantId, String key) {
        if (key == null) {
            return null;
        }
        return jdbc.sql("select command_id from cmd.commands where tenant_id = :t and idempotency_key = :k")
                .param("t", tenantId).param("k", key.trim()).query(UUID.class).optional().orElse(null);
    }

    /** The SIM-TMS cursor (next batch to deliver) for the site: verification counts batches from here. */
    private Long tmsCursor(UUID tenantId, UUID siteId) {
        return jdbc.sql("""
                select next_batch from integ.sync_cursors
                where tenant_id = :t and site_id = :s and source_system = :sys
                """).param("t", tenantId).param("s", siteId).param("sys", SimulatedTmsAdapter.SOURCE_SYSTEM)
                .query(Long.class).optional().orElse(0L);
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }
}
