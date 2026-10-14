package io.vorg.api.recommendations;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of a recommendation (ADR-61). A proposal is {@code PROPOSED} until its approval is decided
 * ({@code APPROVED}/{@code REJECTED}) or lapses ({@code EXPIRED}); execution (Plan 8.3) moves an approved plan to
 * {@code EXECUTING} and then {@code VERIFIED}, {@code PARTIAL} or {@code FAILED}, or marks it {@code STALE} when a
 * baseline entity version or policy version changed. Every state other than PROPOSED, APPROVED and EXECUTING is
 * terminal: a new proposal (which may supersede the old one) is the only way forward.
 */
public enum RecommendationStatus {
    PROPOSED, APPROVED, REJECTED, EXPIRED, STALE, EXECUTING, VERIFIED, PARTIAL, FAILED;

    private static final Map<RecommendationStatus, Set<RecommendationStatus>> NEXT = Map.of(
            PROPOSED, EnumSet.of(APPROVED, REJECTED, EXPIRED, STALE),
            APPROVED, EnumSet.of(EXECUTING, EXPIRED, STALE),
            EXECUTING, EnumSet.of(VERIFIED, PARTIAL, FAILED));

    public boolean canMoveTo(RecommendationStatus next) {
        return NEXT.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean terminal() {
        return !NEXT.containsKey(this);
    }
}
