package io.vorg.api.recommendations;

import io.vorg.api.recommendations.RecommendationStore.ApprovalView;
import io.vorg.api.recommendations.RecommendationStore.RecommendationView;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The documented wire shapes (docs/api/recommendations.md). The recommendation shape is the binding contract of
 * Plan 8.1: exactly the BLUEPRINT section 9 fields plus bookkeeping, in this order, with {@code supersedes_id} and
 * {@code execution} always present (null when not applicable). Built as ordered maps rather than serialized records
 * so the field set cannot drift with a refactoring of the internal views.
 */
public final class RecommendationJson {

    /** Every key of {@link #recommendation}, in order. */
    public static final List<String> RECOMMENDATION_FIELDS = List.of("recommendation_id", "site_id", "type",
            "status", "rationale", "evidence_refs", "feasibility", "objective_before", "objective_after",
            "constraints", "affected_entities", "baseline_entity_versions", "policy_versions", "execution_plan",
            "requires_approval", "expires_at", "approval_id", "proposed_by", "created_at", "updated_at", "version",
            "data_mode", "supersedes_id", "execution");

    private RecommendationJson() {}

    public static Map<String, Object> recommendation(RecommendationView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recommendation_id", v.id().toString());
        m.put("site_id", v.siteId().toString());
        m.put("type", v.type());
        m.put("status", v.status().name());
        m.put("rationale", v.rationale());
        m.put("evidence_refs", v.evidenceRefs());
        m.put("feasibility", v.feasibility());
        m.put("objective_before", v.objectiveBefore());
        m.put("objective_after", v.objectiveAfter());
        m.put("constraints", v.constraints());
        m.put("affected_entities", v.affectedEntities());
        m.put("baseline_entity_versions", v.baselineEntityVersions());
        m.put("policy_versions", v.policyVersions());
        m.put("execution_plan", v.executionPlan());
        m.put("requires_approval", v.requiresApproval());
        m.put("expires_at", ts(v.expiresAt()));
        m.put("approval_id", v.approvalId().toString());
        m.put("proposed_by", v.proposedBy().toString());
        m.put("created_at", ts(v.createdAt()));
        m.put("updated_at", ts(v.updatedAt()));
        m.put("version", v.version());
        m.put("data_mode", v.dataMode());
        m.put("supersedes_id", id(v.supersedesId()));
        m.put("execution", v.execution());
        return m;
    }

    public static Map<String, Object> approval(ApprovalView a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approval_id", a.id().toString());
        m.put("recommendation_id", a.recommendationId().toString());
        m.put("site_id", a.siteId().toString());
        m.put("status", a.status().name());
        m.put("approval_class", a.approvalClass().name());
        m.put("separation_of_duties", a.separationOfDuties());
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("name", a.policyName());
        policy.put("version", a.policyVersion());
        m.put("policy", policy);
        m.put("approver_roles", a.approverRoles());
        m.put("requested_by", a.requestedBy().toString());
        m.put("decided_by", id(a.decidedBy()));
        m.put("decided_at", ts(a.decidedAt()));
        m.put("reason", a.reason());
        m.put("expires_at", ts(a.expiresAt()));
        m.put("created_at", ts(a.createdAt()));
        m.put("updated_at", ts(a.updatedAt()));
        m.put("version", a.version());
        return m;
    }

    static String ts(Instant at) {
        return at == null ? null : at.toString();
    }

    static String id(UUID id) {
        return id == null ? null : id.toString();
    }
}
