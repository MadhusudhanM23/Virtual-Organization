package io.vorg.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vorg.api.platform.authz.Decision;
import io.vorg.api.platform.identity.Role;
import io.vorg.api.recommendations.ApprovalRules.Grant;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** State machines, approval rules (SoD, policy eligibility, expiry), policy config and version comparison. */
class RecommendationDomainTest {

    private static final Decision ALLOW = Decision.allow("ALLOW", "ok", false);
    private static final Decision ALLOW_R_STAR = Decision.allow("ALLOW_SUBJECT_TO_POLICY", "ok", true);
    private static final Decision DENY = Decision.deny("DENY", "no");

    @Test
    void recommendationStateMachine() {
        assertThat(RecommendationStatus.PROPOSED.canMoveTo(RecommendationStatus.APPROVED)).isTrue();
        assertThat(RecommendationStatus.PROPOSED.canMoveTo(RecommendationStatus.REJECTED)).isTrue();
        assertThat(RecommendationStatus.PROPOSED.canMoveTo(RecommendationStatus.EXPIRED)).isTrue();
        assertThat(RecommendationStatus.PROPOSED.canMoveTo(RecommendationStatus.EXECUTING)).isFalse();
        assertThat(RecommendationStatus.APPROVED.canMoveTo(RecommendationStatus.EXECUTING)).isTrue();
        assertThat(RecommendationStatus.APPROVED.canMoveTo(RecommendationStatus.REJECTED)).isFalse();
        assertThat(RecommendationStatus.EXECUTING.canMoveTo(RecommendationStatus.VERIFIED)).isTrue();
        assertThat(RecommendationStatus.EXECUTING.canMoveTo(RecommendationStatus.APPROVED)).isFalse();
        for (RecommendationStatus terminal : EnumSet.of(RecommendationStatus.REJECTED, RecommendationStatus.EXPIRED,
                RecommendationStatus.STALE, RecommendationStatus.VERIFIED, RecommendationStatus.PARTIAL,
                RecommendationStatus.FAILED)) {
            assertThat(terminal.terminal()).as(terminal.name()).isTrue();
            for (RecommendationStatus next : RecommendationStatus.values()) {
                assertThat(terminal.canMoveTo(next)).as(terminal + " -> " + next).isFalse();
            }
        }
    }

    @Test
    void approvalStateMachineOnlyDecidesPending() {
        assertThat(ApprovalStatus.PENDING.canMoveTo(ApprovalStatus.APPROVED)).isTrue();
        assertThat(ApprovalStatus.PENDING.canMoveTo(ApprovalStatus.REJECTED)).isTrue();
        assertThat(ApprovalStatus.PENDING.canMoveTo(ApprovalStatus.EXPIRED)).isTrue();
        assertThat(ApprovalStatus.PENDING.canMoveTo(ApprovalStatus.PENDING)).isFalse();
        for (ApprovalStatus decided : EnumSet.of(ApprovalStatus.APPROVED, ApprovalStatus.REJECTED,
                ApprovalStatus.EXPIRED)) {
            for (ApprovalStatus next : ApprovalStatus.values()) {
                assertThat(decided.canMoveTo(next)).as(decided + " -> " + next).isFalse();
            }
        }
        assertThat(ApprovalStatus.APPROVED.recommendationStatus()).isEqualTo(RecommendationStatus.APPROVED);
        assertThat(ApprovalStatus.EXPIRED.recommendationStatus()).isEqualTo(RecommendationStatus.EXPIRED);
    }

    @Test
    void separationOfDuties() {
        UUID proposer = UUID.randomUUID();
        assertThat(ApprovalRules.violatesSeparationOfDuties(true, proposer, proposer)).isTrue();
        assertThat(ApprovalRules.violatesSeparationOfDuties(true, proposer, UUID.randomUUID())).isFalse();
        assertThat(ApprovalRules.violatesSeparationOfDuties(false, proposer, proposer)).isFalse();
        assertThat(ApprovalClass.SENSITIVE.separationOfDuties()).isTrue();
        assertThat(ApprovalClass.STANDARD.separationOfDuties()).isFalse();
    }

    @Test
    void allowSubjectToPolicyNeedsAPolicyListedRole() {
        Set<Role> approvers = Set.of(Role.WAREHOUSE_MANAGER, Role.REGIONAL_MANAGER);
        assertThat(ApprovalRules.eligible(List.of(new Grant(Role.WAREHOUSE_MANAGER, ALLOW)), approvers)).isTrue();
        assertThat(ApprovalRules.eligible(List.of(new Grant(Role.REGIONAL_MANAGER, ALLOW_R_STAR)), approvers))
                .isTrue();
        assertThat(ApprovalRules.eligible(List.of(new Grant(Role.SAFETY_COMPLIANCE, ALLOW_R_STAR)), approvers))
                .isFalse();
        assertThat(ApprovalRules.eligible(List.of(new Grant(Role.EXECUTIVE, ALLOW_R_STAR)), approvers)).isFalse();
        // A plain ALLOW cell needs no policy listing.
        assertThat(ApprovalRules.eligible(List.of(new Grant(Role.WAREHOUSE_MANAGER, ALLOW)), Set.of(Role.EXECUTIVE)))
                .isTrue();
        // A denied assignment never qualifies, even for a listed role (e.g. a regional manager of another region).
        assertThat(ApprovalRules.eligible(List.of(new Grant(Role.REGIONAL_MANAGER, DENY),
                new Grant(Role.SAFETY_COMPLIANCE, ALLOW_R_STAR)), approvers)).isFalse();
        assertThat(ApprovalRules.eligible(List.of(), approvers)).isFalse();
    }

    @Test
    void expiryIsInclusiveOfTheDeadline() {
        Instant deadline = Instant.parse("2026-10-13T10:00:00Z");
        assertThat(ApprovalRules.expired(deadline.minusMillis(1), deadline)).isFalse();
        assertThat(ApprovalRules.expired(deadline, deadline)).isTrue();
        assertThat(ApprovalRules.expired(deadline.plusSeconds(1), deadline)).isTrue();
    }

    @Test
    void dockReassignmentPolicyDefaultsAndValidation() {
        DockReassignmentPolicy policy = new DockReassignmentPolicy(1, ApprovalClass.SENSITIVE,
                List.of(Role.WAREHOUSE_MANAGER, Role.REGIONAL_MANAGER), 30);
        assertThat(policy.separationOfDuties()).isTrue();
        assertThat(policy.expiry()).hasMinutes(30);
        assertThatThrownBy(() -> new DockReassignmentPolicy(0, ApprovalClass.SENSITIVE,
                List.of(Role.WAREHOUSE_MANAGER), 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DockReassignmentPolicy(1, ApprovalClass.SENSITIVE,
                List.of(Role.PLATFORM_ADMIN), 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DockReassignmentPolicy(1, ApprovalClass.SENSITIVE, List.of(), 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DockReassignmentPolicy(1, ApprovalClass.SENSITIVE,
                List.of(Role.WAREHOUSE_MANAGER), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyVersionChangesAreListed() {
        Map<String, Integer> baseline = Map.of("permission-matrix", 1, "dock-reassignment", 1);
        assertThat(PolicyVersions.changed(baseline, Map.of("permission-matrix", 1, "dock-reassignment", 1)))
                .isEmpty();
        assertThat(PolicyVersions.changed(baseline, Map.of("permission-matrix", 1, "dock-reassignment", 2)))
                .containsExactly(new PolicyVersions.Change("dock-reassignment", 1, 2));
        assertThat(PolicyVersions.changed(Map.of("retired", 3), Map.of()))
                .containsExactly(new PolicyVersions.Change("retired", 3, null));
    }
}
