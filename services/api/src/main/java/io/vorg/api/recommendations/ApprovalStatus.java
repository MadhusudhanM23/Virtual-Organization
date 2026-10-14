package io.vorg.api.recommendations;

/** Lifecycle of an approval (ADR-61): only a {@code PENDING} approval can be decided; the others are terminal. */
public enum ApprovalStatus {
    PENDING, APPROVED, REJECTED, EXPIRED;

    public boolean canMoveTo(ApprovalStatus next) {
        return this == PENDING && next != PENDING;
    }

    /** The recommendation status that a decision on its approval implies. */
    public RecommendationStatus recommendationStatus() {
        return switch (this) {
            case PENDING -> RecommendationStatus.PROPOSED;
            case APPROVED -> RecommendationStatus.APPROVED;
            case REJECTED -> RecommendationStatus.REJECTED;
            case EXPIRED -> RecommendationStatus.EXPIRED;
        };
    }
}
