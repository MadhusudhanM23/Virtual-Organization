package io.vorg.api.recommendations;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.PolicyService;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.command.CommandResult;
import io.vorg.api.platform.events.DomainEvents;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.identity.Role;
import io.vorg.api.platform.identity.RoleAssignment;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.recommendations.RecommendationStore.ApprovalView;
import io.vorg.api.recommendations.RecommendationStore.RecommendationView;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Deciding an approval (REQ-092, ADR-61). Runs inside the command gateway's transaction after the gateway authorized
 * {@code APPROVE_CONSEQUENTIAL_ACTION} on the site. Under a row lock it then applies, in order:
 * <ol>
 *   <li>state: only a {@code PENDING} approval can be decided ({@code 409 invalid_state});</li>
 *   <li>expiry: at or after {@code expires_at} the approval and its recommendation become {@code EXPIRED} and
 *       {@code APPROVAL_EXPIRED} is recorded. This is committed (the handler returns normally with
 *       {@code outcome: EXPIRED}); the controller turns that outcome into {@code 409 approval_expired} with a
 *       {@code re_evaluate} link, so an idempotent replay answers the same way;</li>
 *   <li>separation of duties: for a class that requires it, the decider must not be the proposer
 *       ({@code 403 separation_of_duties});</li>
 *   <li>eligibility: {@link ApprovalRules#eligible} gives {@code Decision.requiresPolicyCheck} its meaning: an R* cell
 *       qualifies only for a role listed by the approval's policy ({@code 403 approver_not_eligible}).</li>
 * </ol>
 * Approve and reject apply the same rules. The approval and the recommendation are versioned and never deleted.
 */
@Service
public class ApprovalService {

    public static final String GRANTED_EVENT = "APPROVAL_GRANTED";
    public static final String REJECTED_EVENT = "APPROVAL_REJECTED";
    public static final String EXPIRED_EVENT = "APPROVAL_EXPIRED";

    /** Result {@code outcome} of a decision command. */
    public static final String OUTCOME_DECIDED = "DECIDED";
    public static final String OUTCOME_EXPIRED = "EXPIRED";

    private final RecommendationStore store;
    private final DomainEvents events;
    private final PolicyService policy;
    private final Clock clock;

    public ApprovalService(RecommendationStore store, DomainEvents events, PolicyService policy, Clock clock) {
        this.store = store;
        this.events = events;
        this.policy = policy;
        this.clock = clock;
    }

    /** The approval without an authorization check (to resolve the site the command is authorized on); 404 if absent. */
    public ApprovalView locate(Subject subject, UUID approvalId) {
        return store.findApproval(subject.tenantId(), approvalId, false)
                .orElseThrow(() -> RecommendationProblems.notFound("approval"));
    }

    public CommandResult approve(Subject subject, UUID approvalId, String reason) {
        return decide(subject, approvalId, ApprovalStatus.APPROVED, reason);
    }

    public CommandResult reject(Subject subject, UUID approvalId, String reason) {
        return decide(subject, approvalId, ApprovalStatus.REJECTED, reason);
    }

    private CommandResult decide(Subject subject, UUID approvalId, ApprovalStatus next, String reason) {
        UUID tenantId = subject.tenantId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ApprovalView approval = store.findApproval(tenantId, approvalId, true)
                .orElseThrow(() -> RecommendationProblems.notFound("approval"));
        RecommendationView rec = store.findRecommendation(tenantId, approval.recommendationId(), true)
                .orElseThrow(() -> new IllegalStateException("Approval " + approvalId + " has no recommendation"));

        if (approval.status() != ApprovalStatus.PENDING) {
            throw RecommendationProblems.of(HttpStatus.CONFLICT, "Invalid state",
                    "Only a PENDING approval can be decided (status " + approval.status() + ").",
                    RecommendationProblems.INVALID_STATE, Map.of("approval_status", approval.status().name()));
        }
        if (ApprovalRules.expired(now, approval.expiresAt())) {
            return expire(subject, approval, rec, now);
        }
        if (ApprovalRules.violatesSeparationOfDuties(approval.separationOfDuties(), approval.requestedBy(),
                subject.actorRef())) {
            throw RecommendationProblems.of(HttpStatus.FORBIDDEN, "Separation of duties",
                    "The proposer of a " + approval.approvalClass() + " recommendation cannot decide its approval.",
                    RecommendationProblems.SEPARATION_OF_DUTIES);
        }
        if (!ApprovalRules.eligible(grants(subject, approval.siteId()), roles(approval.approverRoles()))) {
            throw RecommendationProblems.of(HttpStatus.FORBIDDEN, "Approver not eligible",
                    "None of your roles on this site may decide approvals under the " + approval.policyName()
                            + " policy.",
                    RecommendationProblems.APPROVER_NOT_ELIGIBLE);
        }

        int approvalVersion = store.decide(tenantId, approval, next, subject.actorRef(), reason, now);
        RecommendationStatus recStatus = next.recommendationStatus();
        int recVersion = store.updateStatus(tenantId, rec, recStatus, now);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recommendation_id", rec.id().toString());
        payload.put("status", next.name());
        payload.put("recommendation_status", recStatus.name());
        payload.put("approval_class", approval.approvalClass().name());
        payload.put("separation_of_duties", approval.separationOfDuties());
        payload.put("policy", Map.of("name", approval.policyName(), "version", approval.policyVersion()));
        payload.put("decided_by", subject.actorRef().toString());
        payload.put("decided_at", now.toString());
        if (reason != null) {
            payload.put("reason", reason);
        }
        EventEnvelope event = events.record(RecommendationService.event(subject,
                RecommendationService.APPROVAL_AGGREGATE, approval.id(),
                next == ApprovalStatus.APPROVED ? GRANTED_EVENT : REJECTED_EVENT, approval.siteId(), payload));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", OUTCOME_DECIDED);
        result.put("approval_id", approval.id().toString());
        result.put("recommendation_id", rec.id().toString());
        result.put("status", next.name());
        result.put("recommendation_status", recStatus.name());
        result.put("version", approvalVersion);
        result.put("recommendation_version", recVersion);
        result.put("event_id", event.eventId().toString());
        return CommandResult.of(result);
    }

    /** Marks the approval and its recommendation EXPIRED (committed with the command) and reports it. */
    private CommandResult expire(Subject subject, ApprovalView approval, RecommendationView rec, Instant now) {
        UUID tenantId = subject.tenantId();
        int approvalVersion = store.decide(tenantId, approval, ApprovalStatus.EXPIRED, null, null, now);
        int recVersion = rec.status().canMoveTo(RecommendationStatus.EXPIRED)
                ? store.updateStatus(tenantId, rec, RecommendationStatus.EXPIRED, now)
                : rec.version();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recommendation_id", rec.id().toString());
        payload.put("status", ApprovalStatus.EXPIRED.name());
        payload.put("recommendation_status", RecommendationStatus.EXPIRED.name());
        payload.put("expires_at", approval.expiresAt().toString());
        payload.put("detected_at", now.toString());
        EventEnvelope event = events.record(RecommendationService.event(subject,
                RecommendationService.APPROVAL_AGGREGATE, approval.id(), EXPIRED_EVENT, approval.siteId(), payload));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", OUTCOME_EXPIRED);
        result.put("approval_id", approval.id().toString());
        result.put("recommendation_id", rec.id().toString());
        result.put("status", ApprovalStatus.EXPIRED.name());
        result.put("recommendation_status", RecommendationStatus.EXPIRED.name());
        result.put("version", approvalVersion);
        result.put("recommendation_version", recVersion);
        result.put("site_id", rec.siteId().toString());
        result.put("simulation_run_id", rec.simulationRunId().toString());
        result.put("event_id", event.eventId().toString());
        return CommandResult.of(result);
    }

    /** The decision of each of the caller's role assignments, evaluated on its own (see {@link ApprovalRules}). */
    private List<ApprovalRules.Grant> grants(Subject subject, UUID siteId) {
        List<ApprovalRules.Grant> grants = new ArrayList<>();
        ResourceRef site = ResourceRef.site(subject.tenantId(), siteId);
        for (RoleAssignment a : subject.roles()) {
            Subject single = new Subject(subject.actorRef(), subject.tenantId(), subject.displayName(), List.of(a));
            grants.add(new ApprovalRules.Grant(a.role(),
                    policy.authorize(single, Action.APPROVE_CONSEQUENTIAL_ACTION, site, PolicyContext.none())));
        }
        return grants;
    }

    private static Set<Role> roles(List<String> names) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String n : names) {
            try {
                roles.add(Role.valueOf(n));
            } catch (IllegalArgumentException ignored) {
                // A role name no longer known grants nothing.
            }
        }
        return roles;
    }
}
