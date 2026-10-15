package io.vorg.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.recommendations.RecommendationDraft.FeasibilityStatus;
import org.junit.jupiter.api.Test;

/**
 * The 422 {@code not_actionable} text says why (plan 10.1, ADR-70): a whole-shift closure of the booked dock gives an
 * OPTIMAL re-assignment but no feasible current schedule, which used to be reported as "found no plan".
 */
class NotActionableDetailTest {

    @Test
    void actionableWhenThereIsAPlanAndABaseline() {
        assertThat(OrToolsRecommendationGenerator.notActionableDetail(FeasibilityStatus.OPTIMAL, true, true)).isNull();
        assertThat(OrToolsRecommendationGenerator.notActionableDetail(FeasibilityStatus.FEASIBLE, true, true)).isNull();
    }

    @Test
    void noPlanSaysSo() {
        assertThat(OrToolsRecommendationGenerator.notActionableDetail(FeasibilityStatus.INFEASIBLE, true, false))
                .isEqualTo("The optimizer found no plan to propose (feasibility INFEASIBLE).");
        assertThat(OrToolsRecommendationGenerator.notActionableDetail(FeasibilityStatus.OPTIMAL, true, false))
                .contains("found no plan");
    }

    @Test
    void aPlanWithoutAMeasuredBaselineSaysTheCurrentAssignmentCannotBeScheduled() {
        String detail = OrToolsRecommendationGenerator.notActionableDetail(FeasibilityStatus.OPTIMAL, false, true);
        assertThat(detail).contains("current assignment cannot be scheduled within the horizon")
                .contains("ADR-70")
                .doesNotContain("found no plan");
    }
}
