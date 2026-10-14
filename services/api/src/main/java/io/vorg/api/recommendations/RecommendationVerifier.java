package io.vorg.api.recommendations;

import io.vorg.api.integration.core.SyncCompletedListener;
import io.vorg.api.integration.simulated.SimulatedTmsAdapter;
import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.DomainEvents;
import io.vorg.api.platform.events.EventDraft;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.recommendations.AssignmentStore.ForkliftAssignment;
import io.vorg.api.recommendations.AssignmentStore.TripAssignment;
import io.vorg.api.recommendations.RecommendationStore.Executing;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verification of executed recommendations (Plan 8.3, REQ-096, ADR-63). After every integration sync of a site it
 * looks at the site's {@code EXECUTING} recommendations and compares each applied step's target with what the source
 * systems now report through the mirror (the dock and forklift assignment projections): a step is {@code VERIFIED}
 * only when its target is <b>observed</b> at the step's {@code to_dock}, with the {@code DOCK_ASSIGNMENT_CHANGED} /
 * {@code FORKLIFT_ASSIGNMENT_CHANGED} event as {@code observed_event_id}. When every step is verified the
 * recommendation becomes {@code VERIFIED} ({@code RECOMMENDATION_VERIFIED}). When
 * {@code vo.recommendations.verification.timeout-batches} SIM-TMS batches have been applied since execution started
 * and a step is still unobserved, it becomes {@code FAILED} (nothing observed) or {@code PARTIAL} (some steps
 * observed), with the observed state as evidence ({@code RECOMMENDATION_EXECUTION_FAILED}).
 */
@Component
public class RecommendationVerifier implements SyncCompletedListener {

    static final String ACTOR = "svc:recommendation-verifier";

    private final RecommendationStore store;
    private final AssignmentStore assignments;
    private final DomainEvents events;
    private final JsonMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final int timeoutBatches;

    public RecommendationVerifier(RecommendationStore store, AssignmentStore assignments, DomainEvents events,
            JsonMapper mapper, Clock clock, PlatformTransactionManager txManager, ExecutionProperties properties) {
        this.store = store;
        this.assignments = assignments;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.timeoutBatches = properties.verification().timeoutBatches();
    }

    @Override
    public void onSyncCompleted(UUID tenantId, UUID siteId, Map<String, Long> batches) {
        Long tmsBatch = batches.get(SimulatedTmsAdapter.SOURCE_SYSTEM);
        TenantContext.callWith(tenantId, () -> tx.execute(status -> {
            for (Executing e : store.executing(tenantId, siteId)) {
                verify(tenantId, siteId, e, tmsBatch);
            }
            return null;
        }));
    }

    @SuppressWarnings("unchecked")
    private void verify(UUID tenantId, UUID siteId, Executing e, Long tmsBatch) {
        RecommendationView rec = e.recommendation();
        Map<String, Object> execution = rec.execution() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(rec.execution());
        List<Map<String, Object>> exSteps = new ArrayList<>();
        Object raw = execution.get("steps");
        if (raw instanceof List<?> list) {
            list.forEach(o -> exSteps.add(new LinkedHashMap<>((Map<String, Object>) o)));
        }
        List<Map<String, Object>> planSteps = (List<Map<String, Object>>) rec.executionPlan().get("steps");
        Map<String, TripAssignment> trips = new LinkedHashMap<>();
        assignments.trips(tenantId, siteId).forEach(t -> trips.put(t.tripCode(), t));
        Map<String, ForkliftAssignment> forklifts = new LinkedHashMap<>();
        assignments.forklifts(tenantId, siteId).forEach(f -> forklifts.put(f.forkliftCode(), f));

        boolean changed = false;
        int verified = 0;
        List<Map<String, Object>> observed = new ArrayList<>();
        for (int i = 0; i < exSteps.size() && i < planSteps.size(); i++) {
            Map<String, Object> s = exSteps.get(i);
            Map<String, Object> params = (Map<String, Object>) planSteps.get(i).getOrDefault("params", Map.of());
            String target = String.valueOf(s.get("target"));
            String expected = params.get("to_dock") == null ? null : params.get("to_dock").toString();
            String actual = null;
            UUID eventId = null;
            if (trips.containsKey(target)) {
                actual = trips.get(target).dockCode();
                eventId = trips.get(target).lastEventId();
            } else if (forklifts.containsKey(target)) {
                actual = forklifts.get(target).dockCode();
                eventId = forklifts.get(target).lastEventId();
            }
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("seq", s.get("seq"));
            o.put("target", target);
            o.put("expected_dock", expected);
            o.put("observed_dock", actual);
            observed.add(o);
            if (RecommendationExecutionService.VERIFIED.equals(s.get("status"))) {
                verified++;
                continue;
            }
            if (RecommendationExecutionService.APPLIED.equals(s.get("status")) && expected != null
                    && expected.equals(actual) && eventId != null) {
                s.put("status", RecommendationExecutionService.VERIFIED);
                s.put("observed_event_id", eventId.toString());
                verified++;
                changed = true;
            }
        }
        execution.put("steps", exSteps);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        if (verified == exSteps.size() && !exSteps.isEmpty()) {
            execution.put("finished_at", now.toString());
            store.updateExecution(tenantId, rec, RecommendationStatus.VERIFIED, json(execution), null, now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", RecommendationStatus.VERIFIED.name());
            payload.put("command_id", execution.get("command_id"));
            payload.put("observed", observed);
            payload.put("observed_event_ids", exSteps.stream().map(s -> s.get("observed_event_id")).toList());
            record(rec, RecommendationExecutionService.VERIFIED_EVENT, payload);
            return;
        }
        long waited = tmsBatch == null || e.verifyFromBatch() == null ? 0 : tmsBatch - e.verifyFromBatch() + 1;
        if (waited >= timeoutBatches) {
            RecommendationStatus outcome = verified == 0 ? RecommendationStatus.FAILED : RecommendationStatus.PARTIAL;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("reason", "VERIFICATION_TIMEOUT");
            evidence.put("timeout_batches", timeoutBatches);
            evidence.put("batches_observed", waited);
            evidence.put("observed", observed);
            execution.put("finished_at", now.toString());
            execution.put("evidence", evidence);
            store.updateExecution(tenantId, rec, outcome, json(execution), null, now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", outcome.name());
            payload.put("command_id", execution.get("command_id"));
            payload.put("evidence", evidence);
            record(rec, RecommendationExecutionService.FAILED_EVENT, payload);
            return;
        }
        if (changed) {
            store.updateExecution(tenantId, rec, RecommendationStatus.EXECUTING, json(execution), null, now);
        }
    }

    private void record(RecommendationView rec, String type, Map<String, Object> payload) {
        payload.values().removeIf(Objects::isNull);
        events.record(EventDraft.of(RecommendationService.AGGREGATE, rec.id(), type, payload)
                .source(EventSource.SYSTEM).dataMode(DataMode.SIMULATED).actor(ActorType.SERVICE, ACTOR)
                .location(EventLocation.site(rec.siteId())));
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }
}
