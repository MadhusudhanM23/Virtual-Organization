package io.vorg.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.recommendations.RecommendationStore.ApprovalView;
import io.vorg.api.recommendations.RecommendationStore.RecommendationView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The documented wire shapes (docs/api/recommendations.md): field set, order, nulls and no confidence anywhere. */
class RecommendationJsonTest {

    private static final Instant T0 = Instant.parse("2026-10-13T10:00:00Z");
    private static final UUID REC = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID SITE = UUID.fromString("018f0000-0000-7000-8000-000000000002");
    private static final UUID APPROVAL = UUID.fromString("018f0000-0000-7000-8000-000000000003");
    private static final UUID RUN = UUID.fromString("018f0000-0000-7000-8000-000000000004");
    private static final UUID SCENARIO = UUID.fromString("018f0000-0000-7000-8000-000000000005");
    private static final UUID PROPOSER = UUID.fromString("018f0000-0000-7000-8000-000000000006");

    private static RecommendationView view(UUID supersedes, Map<String, Object> execution) {
        return new RecommendationView(REC, SITE, "DOCK_REASSIGNMENT", RecommendationStatus.PROPOSED, "Move a trip.",
                List.of(Map.of("kind", "SIMULATION_RUN", "ref", RUN.toString(), "label", "run")),
                Map.of("status", "OPTIMAL", "solver", "CP-SAT"), Map.of("name", "wait", "unit", "min", "value", 10.0),
                Map.of("name", "wait", "unit", "min", "value", 4.0), List.of(), List.of("TRIP-00002", "IN-02"),
                Map.of("TRIP-00002", 1, "IN-02", 1), Map.of("permission-matrix", 1, "dock-reassignment", 1),
                Map.of("steps", List.of()), Map.of("required", true), T0.plusSeconds(1800), APPROVAL, RUN, SCENARIO,
                PROPOSER, supersedes, execution, "SIMULATED", 1, T0, T0);
    }

    @Test
    void theRecommendationHasExactlyTheContractFieldsInOrder() {
        Map<String, Object> json = RecommendationJson.recommendation(view(null, null));
        assertThat(json.keySet()).containsExactlyElementsOf(RecommendationJson.RECOMMENDATION_FIELDS);
        assertThat(json).containsEntry("recommendation_id", REC.toString()).containsEntry("site_id", SITE.toString())
                .containsEntry("status", "PROPOSED").containsEntry("approval_id", APPROVAL.toString())
                .containsEntry("proposed_by", PROPOSER.toString()).containsEntry("expires_at", "2026-10-13T10:30:00Z")
                .containsEntry("created_at", "2026-10-13T10:00:00Z").containsEntry("version", 1)
                .containsEntry("data_mode", "SIMULATED");
        // Always present, null when not applicable.
        assertThat(json).containsKey("supersedes_id").containsKey("execution");
        assertThat(json.get("supersedes_id")).isNull();
        assertThat(json.get("execution")).isNull();
        // Internal bookkeeping never leaks onto the wire.
        assertThat(json).doesNotContainKeys("simulation_run_id", "scenario_id", "verify_from_batch", "tenant_id");
    }

    @Test
    void supersedesAndExecutionAreSerializedWhenSet() {
        UUID old = UUID.randomUUID();
        Map<String, Object> execution = Map.of("command_id", "c", "steps", List.of());
        Map<String, Object> json = RecommendationJson.recommendation(view(old, execution));
        assertThat(json).containsEntry("supersedes_id", old.toString()).containsEntry("execution", execution);
    }

    @Test
    void theSerializedDocumentIsSnakeCaseAndCarriesNoConfidence() {
        String text = JsonMapper.builder().build().writeValueAsString(RecommendationJson.recommendation(view(null,
                null)));
        assertThat(text).doesNotContainIgnoringCase("confidence").contains("\"supersedes_id\":null")
                .contains("\"execution\":null");
    }

    @Test
    void theApprovalShape() {
        ApprovalView a = new ApprovalView(APPROVAL, SITE, REC, ApprovalStatus.PENDING, ApprovalClass.SENSITIVE, true,
                DockReassignmentPolicy.NAME, 1, List.of("WAREHOUSE_MANAGER", "REGIONAL_MANAGER"), PROPOSER, null,
                null, null, T0.plusSeconds(1800), 1, T0, T0);
        Map<String, Object> json = RecommendationJson.approval(a);
        assertThat(json.keySet()).containsExactly("approval_id", "recommendation_id", "site_id", "status",
                "approval_class", "separation_of_duties", "policy", "approver_roles", "requested_by", "decided_by",
                "decided_at", "reason", "expires_at", "created_at", "updated_at", "version");
        assertThat(json).containsEntry("status", "PENDING").containsEntry("approval_class", "SENSITIVE")
                .containsEntry("separation_of_duties", true)
                .containsEntry("policy", Map.of("name", "dock-reassignment", "version", 1))
                .containsEntry("requested_by", PROPOSER.toString());
        assertThat(json.get("decided_by")).isNull();
        assertThat(json.get("decided_at")).isNull();
        assertThat(json.get("reason")).isNull();
    }
}
