package io.vorg.optimization;

import com.google.ortools.sat.CpSolverStatus;

/**
 * {@code feasibility.status}. Mapped 1:1 from CP-SAT, never upgraded: a solve stopped by the time limit with a
 * solution is {@link #FEASIBLE} (with its real gap), never {@link #OPTIMAL}; one stopped without a solution is
 * {@link #UNKNOWN}, never {@link #INFEASIBLE}.
 */
public enum SolveStatus {
    /** Proven optimal: objective equals the best bound. */
    OPTIMAL,
    /** A solution satisfying every hard constraint, optimality not proven. */
    FEASIBLE,
    /** Proven that no solution satisfies every hard constraint. */
    INFEASIBLE,
    /** The model was rejected by the solver (a bug in this module, not in the input). */
    MODEL_INVALID,
    /** Stopped (time limit) before finding a solution or proving infeasibility. */
    UNKNOWN;

    static SolveStatus of(CpSolverStatus s) {
        return switch (s) {
            case OPTIMAL -> OPTIMAL;
            case FEASIBLE -> FEASIBLE;
            case INFEASIBLE -> INFEASIBLE;
            case MODEL_INVALID -> MODEL_INVALID;
            default -> UNKNOWN;
        };
    }

    /** {@code true} when a solution exists (OPTIMAL or FEASIBLE). */
    public boolean hasSolution() {
        return this == OPTIMAL || this == FEASIBLE;
    }

    /** The less favourable of two statuses, in the order OPTIMAL, FEASIBLE, UNKNOWN, INFEASIBLE, MODEL_INVALID. */
    static SolveStatus worst(SolveStatus a, SolveStatus b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(SolveStatus s) {
        return switch (s) {
            case OPTIMAL -> 0;
            case FEASIBLE -> 1;
            case UNKNOWN -> 2;
            case INFEASIBLE -> 3;
            case MODEL_INVALID -> 4;
        };
    }
}
