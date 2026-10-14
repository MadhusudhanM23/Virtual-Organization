package io.vorg.api.recommendations;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of generation and execution (Plan 8.3, ADR-63), {@code vo.recommendations.*}. Every optimizer value is
 * required and has no default in code (application.yml carries the documented values); a missing value fails startup
 * rather than being guessed.
 *
 * @param generator optimizer parameters and the trip-plan time scale
 * @param verification how long execution waits for the source systems to show the new docks
 */
@ConfigurationProperties(prefix = "vo.recommendations")
public record ExecutionProperties(Generator generator, Verification verification) {

    /**
     * @param minutesPerBatch plan minutes represented by one simulated adapter batch (ASSUMPTION, ADR-63: the simulated
     *     TMS runs compressed time; this converts its trip plan into the optimizer's minutes)
     * @param reassignmentPenaltyMin objective minutes charged per moved trip (ASSUMPTION: no cost data for a dock change)
     * @param maxSolveMs wall-clock limit of each CP-SAT solve
     * @param randomSeed CP-SAT random seed
     * @param solverThreads size of the bounded solver executor
     * @param solverQueue pending solves accepted beyond the running ones (more is 503 {@code optimizer_busy})
     */
    public record Generator(Integer minutesPerBatch, Integer reassignmentPenaltyMin, Integer maxSolveMs,
            Integer randomSeed, Integer solverThreads, Integer solverQueue) {}

    /**
     * @param timeoutBatches SIM-TMS batches after execution started within which every target must be observed at its
     *     new dock; afterwards the execution is FAILED (nothing observed) or PARTIAL (some observed)
     */
    public record Verification(Integer timeoutBatches) {}

    public ExecutionProperties {
        if (generator == null || generator.minutesPerBatch() == null || generator.reassignmentPenaltyMin() == null
                || generator.maxSolveMs() == null || generator.randomSeed() == null
                || generator.solverThreads() == null || generator.solverQueue() == null) {
            throw new IllegalStateException("vo.recommendations.generator.{minutes-per-batch, reassignment-penalty-min,"
                    + " max-solve-ms, random-seed, solver-threads, solver-queue} are all required (ADR-63)");
        }
        if (generator.minutesPerBatch() < 1 || generator.solverThreads() < 1 || generator.solverQueue() < 0) {
            throw new IllegalStateException("vo.recommendations.generator values are out of range");
        }
        if (verification == null || verification.timeoutBatches() == null || verification.timeoutBatches() < 1) {
            throw new IllegalStateException("vo.recommendations.verification.timeout-batches (>= 1) is required");
        }
    }
}
