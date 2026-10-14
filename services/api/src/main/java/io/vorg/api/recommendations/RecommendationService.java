package io.vorg.api.recommendations;

import io.vorg.api.platform.Ids;
import io.vorg.api.platform.command.CommandResult;
import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.DomainEvents;
import io.vorg.api.platform.events.EventDraft;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.recommendations.RecommendationDraft.EvidenceKind;
import io.vorg.api.recommendations.RecommendationDraft.EvidenceRef;
import io.vorg.api.recommendations.RecommendationGenerator.GenerationRequest;
import io.vorg.api.recommendations.RecommendationStore.RecommendationView;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proposing a recommendation (REQ-090, REQ-092): runs inside the command gateway's transaction, so the recommendation,
 * its PENDING approval and the {@code RECOMMENDATION_PROPOSED} / {@code APPROVAL_REQUESTED} events commit together.
 * The draft comes from the {@link RecommendationGenerator} port; this service adds what the platform owns: the
 * {@code policy_versions} evaluated now, the approval class from the type's policy, {@code expires_at}, the proposer,
 * and the simulation run as the first evidence reference. Everything about the plan is SIMULATED data (ADR-61).
 *
 * <p>Proposals from one run are serialized. A run whose latest proposal is still awaiting a decision (PENDING and not
 * expired) cannot be proposed again ({@code 409 proposal_pending}); otherwise a new proposal records the previous one
 * in {@code supersedes_id} (this is how an expired or rejected plan is re-evaluated).
 */
@Service
public class RecommendationService {

    public static final String AGGREGATE = "RECOMMENDATION";
    public static final String APPROVAL_AGGREGATE = "APPROVAL";
    public static final String PROPOSED_EVENT = "RECOMMENDATION_PROPOSED";
    public static final String APPROVAL_REQUESTED_EVENT = "APPROVAL_REQUESTED";

    private final RecommendationStore store;
    private final DomainEvents events;
    private final PolicyVersions policyVersions;
    private final DockReassignmentPolicy policy;
    private final JsonMapper mapper;
    private final Clock clock;

    public RecommendationService(RecommendationStore store, DomainEvents events, PolicyVersions policyVersions,
            DockReassignmentPolicy policy, JsonMapper mapper, Clock clock) {
        this.store = store;
        this.events = events;
        this.policyVersions = policyVersions;
        this.policy = policy;
        this.mapper = mapper;
        this.clock = clock;
    }

    public CommandResult propose(Subject subject, SimulationRunSnapshot run, RecommendationGenerator generator) {
        UUID tenantId = subject.tenantId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        store.lockRun(tenantId, run.runId());
        Optional<RecommendationView> prior = store.latestForRun(tenantId, run.runId());
        if (prior.isPresent()) {
            requireDecidedOrLapsed(tenantId, prior.get(), now);
        }

        RecommendationDraft draft = generator.generate(new GenerationRequest(tenantId, run.siteId(), run));
        if (draft == null) {
            throw new IllegalStateException("Recommendation generator returned no draft");
        }
        List<String> problems = draft.problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Recommendation generator produced an invalid draft: " + problems);
        }
        if (!draft.feasibility().status().actionable() || draft.executionPlan().steps().isEmpty()) {
            throw RecommendationProblems.of(HttpStatus.UNPROCESSABLE_CONTENT, "No actionable plan",
                    "The optimizer found no plan to propose (feasibility " + draft.feasibility().status() + ").",
                    RecommendationProblems.NOT_ACTIONABLE,
                    Map.of("feasibility_status", draft.feasibility().status().name()));
        }

        UUID recommendationId = Ids.newId();
        UUID approvalId = Ids.newId();
        Instant expiresAt = now.plus(policy.expiry());
        Map<String, Integer> versions = policyVersions.current();
        Map<String, Object> requiresApproval = new LinkedHashMap<>();
        requiresApproval.put("required", true);
        requiresApproval.put("approval_class", policy.approvalClass().name());
        requiresApproval.put("separation_of_duties", policy.separationOfDuties());
        UUID supersedesId = prior.map(RecommendationView::id).orElse(null);
        List<String> approverRoles = policy.approverRoles().stream().map(Enum::name).toList();

        // The recommendation first: its approval_id foreign key is deferred to commit, the approval's is not.
        store.insert(new RecommendationStore.NewRecommendation(recommendationId, tenantId, run.siteId(),
                draft.type().name(), draft.rationale(), json(evidence(run, draft.evidenceRefs())),
                json(draft.feasibility()), json(draft.objectiveBefore()), json(draft.objectiveAfter()),
                json(draft.constraints()), json(draft.affectedEntities()), json(draft.baselineEntityVersions()),
                json(versions), json(draft.executionPlan()), json(requiresApproval), expiresAt, approvalId,
                run.runId(), run.scenarioId(), subject.actorRef(), supersedesId, DataMode.SIMULATED.name(), now));
        store.insert(new RecommendationStore.NewApproval(approvalId, tenantId, run.siteId(), recommendationId,
                policy.approvalClass(), policy.separationOfDuties(), DockReassignmentPolicy.NAME, policy.version(),
                json(approverRoles), subject.actorRef(), expiresAt, now));

        Map<String, Object> proposed = new LinkedHashMap<>();
        proposed.put("recommendation_type", draft.type().name());
        proposed.put("status", RecommendationStatus.PROPOSED.name());
        proposed.put("simulation_run_id", run.runId().toString());
        proposed.put("scenario_id", run.scenarioId().toString());
        proposed.put("approval_id", approvalId.toString());
        proposed.put("feasibility_status", draft.feasibility().status().name());
        Map<String, Object> objective = new LinkedHashMap<>();
        objective.put("name", draft.objectiveBefore().name());
        objective.put("unit", draft.objectiveBefore().unit());
        objective.put("before", draft.objectiveBefore().value());
        objective.put("after", draft.objectiveAfter().value());
        proposed.put("objective", objective);
        proposed.put("affected_entities", draft.affectedEntities());
        proposed.put("baseline_entity_versions", draft.baselineEntityVersions());
        proposed.put("policy_versions", versions);
        proposed.put("step_count", draft.executionPlan().steps().size());
        proposed.put("expires_at", expiresAt.toString());
        if (supersedesId != null) {
            proposed.put("supersedes_id", supersedesId.toString());
        }
        EventEnvelope proposedEvent = events.record(event(subject, AGGREGATE, recommendationId, PROPOSED_EVENT,
                run.siteId(), proposed));

        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("recommendation_id", recommendationId.toString());
        requested.put("status", ApprovalStatus.PENDING.name());
        requested.put("approval_class", policy.approvalClass().name());
        requested.put("separation_of_duties", policy.separationOfDuties());
        requested.put("policy", Map.of("name", DockReassignmentPolicy.NAME, "version", policy.version()));
        requested.put("approver_roles", approverRoles);
        requested.put("expires_at", expiresAt.toString());
        events.record(event(subject, APPROVAL_AGGREGATE, approvalId, APPROVAL_REQUESTED_EVENT, run.siteId(),
                requested));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recommendation_id", recommendationId.toString());
        result.put("approval_id", approvalId.toString());
        result.put("status", RecommendationStatus.PROPOSED.name());
        result.put("version", 1);
        result.put("expires_at", expiresAt.toString());
        if (supersedesId != null) {
            result.put("supersedes_id", supersedesId.toString());
        }
        result.put("event_id", proposedEvent.eventId().toString());
        return CommandResult.of(result);
    }

    /** A run's latest proposal must be decided (or have lapsed) before the run is proposed again. */
    private void requireDecidedOrLapsed(UUID tenantId, RecommendationView prior, Instant now) {
        if (prior.status() != RecommendationStatus.PROPOSED) {
            return;
        }
        boolean pending = store.findApproval(tenantId, prior.approvalId(), true)
                .map(a -> a.status() == ApprovalStatus.PENDING).orElse(false);
        if (pending && !ApprovalRules.expired(now, prior.expiresAt())) {
            throw RecommendationProblems.of(HttpStatus.CONFLICT, "Proposal pending",
                    "The latest recommendation from this simulation run is still awaiting a decision.",
                    RecommendationProblems.PROPOSAL_PENDING,
                    Map.of("recommendation_id", prior.id().toString(), "approval_id", prior.approvalId().toString()));
        }
    }

    /** The run is always the first evidence reference (it is what the proposal is grounded in). */
    private static List<EvidenceRef> evidence(SimulationRunSnapshot run, List<EvidenceRef> fromDraft) {
        List<EvidenceRef> refs = new ArrayList<>();
        String runRef = run.runId().toString();
        EvidenceRef runEvidence = fromDraft.stream()
                .filter(e -> e.kind() == EvidenceKind.SIMULATION_RUN && runRef.equals(e.ref())).findFirst()
                .orElse(new EvidenceRef(EvidenceKind.SIMULATION_RUN, runRef,
                        "Simulation run of scenario \"" + run.scenarioName() + "\""));
        refs.add(runEvidence);
        fromDraft.stream().filter(e -> e != runEvidence).forEach(refs::add);
        return refs;
    }

    static EventDraft event(Subject subject, String aggregate, UUID id, String type, UUID siteId,
            Map<String, Object> payload) {
        return EventDraft.of(aggregate, id, type, payload).source(EventSource.API).dataMode(DataMode.SIMULATED)
                .actor(ActorType.HUMAN, subject.actorRef().toString()).location(EventLocation.site(siteId));
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }
}
