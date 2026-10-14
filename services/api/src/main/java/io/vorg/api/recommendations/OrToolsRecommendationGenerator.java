package io.vorg.api.recommendations;

import io.vorg.api.integration.simulated.SimTmsTripPlan;
import io.vorg.api.integration.simulated.SimTmsTripPlan.PlannedVisit;
import io.vorg.api.recommendations.AssignmentStore.DockRow;
import io.vorg.api.recommendations.AssignmentStore.ForkliftAssignment;
import io.vorg.api.recommendations.AssignmentStore.TripAssignment;
import io.vorg.api.recommendations.RecommendationDraft.Compensation;
import io.vorg.api.recommendations.RecommendationDraft.Constraint;
import io.vorg.api.recommendations.RecommendationDraft.ConstraintKind;
import io.vorg.api.recommendations.RecommendationDraft.EvidenceKind;
import io.vorg.api.recommendations.RecommendationDraft.EvidenceRef;
import io.vorg.api.recommendations.RecommendationDraft.ExecutionPlan;
import io.vorg.api.recommendations.RecommendationDraft.Feasibility;
import io.vorg.api.recommendations.RecommendationDraft.FeasibilityStatus;
import io.vorg.api.recommendations.RecommendationDraft.Objective;
import io.vorg.api.recommendations.RecommendationDraft.PlanStep;
import io.vorg.api.recommendations.RecommendationDraft.RecommendationType;
import io.vorg.optimization.DockKind;
import io.vorg.optimization.DockReassignmentProblem;
import io.vorg.optimization.DockReassignmentProblem.Closure;
import io.vorg.optimization.DockReassignmentProblem.Dock;
import io.vorg.optimization.DockReassignmentProblem.Forklift;
import io.vorg.optimization.DockReassignmentProblem.Params;
import io.vorg.optimization.DockReassignmentProblem.Trip;
import io.vorg.optimization.DockReassignmentSolver;
import io.vorg.optimization.FieldError;
import io.vorg.optimization.InvalidProblemException;
import io.vorg.optimization.OptimizationResult;
import io.vorg.optimization.OptimizationResult.ForkliftMove;
import io.vorg.optimization.OptimizationResult.TripMove;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The OR-Tools-backed {@link RecommendationGenerator} (Plan 8.3, REQ-091, ADR-62/63). It builds a
 * {@code vorg-optimization} dock re-assignment problem from observed state and the simulation run's scenario, solves it
 * on a bounded executor and maps the result onto a {@link RecommendationDraft}:
 * <ul>
 *   <li>docks: {@code world.docks} of the site; closures: the scenario overlay's {@code dock_closures}, plus a whole-
 *       horizon closure for a dock the twin shows {@code CLOSED}; horizon: the scenario's {@code run.horizon_min};</li>
 *   <li>trips: the SIM-TMS trips whose dock assignment has been observed ({@code ops.dock_assignments}); the current
 *       dock is the observed one; arrival and service are the trip's next dock visit in the SIM-TMS trip plan
 *       ({@link SimTmsTripPlan}, adapter batches) times {@code minutes-per-batch} (ASSUMPTION, ADR-63). A visit that
 *       cannot fit into the horizon is left out and said so in the rationale;</li>
 *   <li>forklifts: the observed forklift stations ({@code ops.forklift_assignments});</li>
 *   <li>params: {@code vo.recommendations.generator.*}, all required.</li>
 * </ul>
 * A missing input is a 422 {@code optimization_input_missing}, an input the optimizer rejects a 422
 * {@code optimization_input_invalid} with every field error; no value is ever invented. A result without a plan or
 * without any move is 422 {@code not_actionable}. The baseline versions are read in the propose transaction.
 */
@Component
public class OrToolsRecommendationGenerator implements RecommendationGenerator, DisposableBean {

    private final AssignmentStore assignments;
    private final SimTmsTripPlan tripPlan;
    private final ExecutionProperties.Generator config;
    private final ThreadPoolExecutor executor;

    public OrToolsRecommendationGenerator(AssignmentStore assignments, SimTmsTripPlan tripPlan,
            ExecutionProperties properties) {
        this.assignments = assignments;
        this.tripPlan = tripPlan;
        this.config = properties.generator();
        int threads = config.solverThreads();
        BlockingQueue<Runnable> queue = config.solverQueue() == 0 ? new SynchronousQueue<>()
                : new ArrayBlockingQueue<>(config.solverQueue());
        AtomicInteger n = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, queue, r -> {
            Thread t = new Thread(r, "optimizer-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    /** One planned trip of the problem with where its numbers came from. */
    private record Planned(TripAssignment assignment, PlannedVisit visit) {}

    @Override
    public RecommendationDraft generate(GenerationRequest request) {
        UUID tenantId = request.tenantId();
        UUID siteId = request.siteId();
        SimulationRunSnapshot run = request.run();
        int mpb = config.minutesPerBatch();

        Integer horizon = intValue(run.scenarioRunSettings() == null ? null
                : run.scenarioRunSettings().get("horizon_min"));
        if (horizon == null) {
            throw missing("scenario.run.horizon_min", "The scenario has no run horizon.");
        }

        List<DockRow> dockRows = assignments.docks(tenantId, siteId);
        if (dockRows.isEmpty()) {
            throw missing("world.docks", "The site has no docks.");
        }
        Map<String, List<Closure>> closures = closures(run, dockRows, horizon);
        List<Dock> docks = new ArrayList<>();
        Map<String, Integer> dockVersions = new LinkedHashMap<>();
        for (DockRow d : dockRows) {
            docks.add(new Dock(d.name(), DockKind.valueOf(d.dockType()), closures.getOrDefault(d.name(), List.of())));
            dockVersions.put(d.name(), d.version());
        }

        SimTmsTripPlan.Plan plan = tripPlan.plan(tenantId, siteId).orElseThrow(() -> missing("sim_tms.trip_plan",
                "SIM-TMS has not delivered a batch for this site yet, so there is no trip plan."));
        Map<String, TripAssignment> observed = new LinkedHashMap<>();
        assignments.trips(tenantId, siteId).forEach(t -> observed.put(t.tripCode(), t));
        List<Planned> planned = new ArrayList<>();
        List<String> beyondHorizon = new ArrayList<>();
        for (PlannedVisit v : plan.visits()) {
            TripAssignment a = observed.get(v.tripCode());
            if (a == null) {
                continue;
            }
            if ((long) (v.batchesUntilDocked() + v.dockedBatches()) * mpb > horizon) {
                beyondHorizon.add(v.tripCode());
                continue;
            }
            planned.add(new Planned(a, v));
        }
        if (planned.isEmpty()) {
            throw missing("ops.dock_assignments",
                    "No SIM-TMS trip with an observed dock assignment can be planned within the horizon.");
        }
        List<Trip> trips = planned.stream().map(p -> new Trip(p.assignment().tripCode(), p.assignment().truckCode(),
                DockKind.valueOf(p.assignment().direction()), p.assignment().dockCode(),
                p.visit().batchesUntilDocked() * mpb, p.visit().dockedBatches() * mpb)).toList();
        Map<String, ForkliftAssignment> forkliftRows = new LinkedHashMap<>();
        assignments.forklifts(tenantId, siteId).forEach(f -> forkliftRows.put(f.forkliftCode(), f));
        List<Forklift> forklifts = forkliftRows.values().stream()
                .map(f -> new Forklift(f.forkliftCode(), f.dockCode())).toList();

        DockReassignmentProblem problem = new DockReassignmentProblem(docks, trips, forklifts, horizon,
                new Params(config.reassignmentPenaltyMin(), config.maxSolveMs(), config.randomSeed()));
        List<FieldError> errors = problem.validationErrors();
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        OptimizationResult result = solve(problem);

        FeasibilityStatus status = FeasibilityStatus.valueOf(result.feasibility().status().name());
        if (!status.actionable() || result.objectiveAfter() == null || result.objectiveAfter().value() == null
                || result.objectiveBefore() == null || result.objectiveBefore().value() == null) {
            throw RecommendationProblems.of(HttpStatus.UNPROCESSABLE_CONTENT, "No actionable plan",
                    "The optimizer found no plan to propose (feasibility " + status + ").",
                    RecommendationProblems.NOT_ACTIONABLE, Map.of("feasibility_status", status.name()));
        }
        if (result.tripMoves().isEmpty() && result.forkliftMoves().isEmpty()) {
            throw RecommendationProblems.of(HttpStatus.UNPROCESSABLE_CONTENT, "No actionable plan",
                    "The optimizer's best plan keeps every trip and forklift where it is, so there is nothing to"
                            + " propose (feasibility " + status + ").",
                    RecommendationProblems.NOT_ACTIONABLE, Map.of("feasibility_status", status.name()));
        }

        // Affected entities: moved trips, moved forklifts, then every dock a move leaves or enters.
        List<String> affected = new ArrayList<>();
        Map<String, Integer> baseline = new LinkedHashMap<>();
        Set<String> involvedDocks = new LinkedHashSet<>();
        List<PlanStep> steps = new ArrayList<>();
        int seq = 1;
        for (TripMove m : result.tripMoves()) {
            TripAssignment a = observed.get(m.tripCode());
            affected.add(m.tripCode());
            baseline.put(m.tripCode(), a.version());
            involvedDocks.add(m.fromDock());
            involvedDocks.add(m.toDock());
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("trip_code", m.tripCode());
            params.put("from_dock", m.fromDock());
            params.put("to_dock", m.toDock());
            params.put("planned_start_min", m.plannedStartMin());
            Map<String, Object> undo = new LinkedHashMap<>();
            undo.put("trip_code", m.tripCode());
            undo.put("to_dock", m.fromDock());
            steps.add(new PlanStep(seq++, StepEffects.REASSIGN_TRIP_DOCK, m.tripCode(), params,
                    new Compensation(StepEffects.REASSIGN_TRIP_DOCK, undo)));
        }
        for (ForkliftMove m : result.forkliftMoves()) {
            affected.add(m.code());
            baseline.put(m.code(), forkliftRows.get(m.code()).version());
            involvedDocks.add(m.fromDock());
            involvedDocks.add(m.toDock());
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("forklift_code", m.code());
            params.put("from_dock", m.fromDock());
            params.put("to_dock", m.toDock());
            Map<String, Object> undo = new LinkedHashMap<>();
            undo.put("forklift_code", m.code());
            undo.put("to_dock", m.fromDock());
            steps.add(new PlanStep(seq++, StepEffects.REASSIGN_FORKLIFT_DOCK, m.code(), params,
                    new Compensation(StepEffects.REASSIGN_FORKLIFT_DOCK, undo)));
        }
        for (String d : involvedDocks) {
            affected.add(d);
            baseline.put(d, dockVersions.get(d));
        }

        List<Constraint> constraints = result.constraints().stream().map(c -> new Constraint(c.name(),
                ConstraintKind.valueOf(c.kind().name()), c.satisfied(), c.detail())).toList();
        Feasibility feasibility = new Feasibility(status, result.feasibility().solver(),
                result.feasibility().optimalityGap(), result.feasibility().solveTimeMs());
        Objective before = new Objective(result.objectiveBefore().name(), result.objectiveBefore().unit(),
                result.objectiveBefore().value());
        Objective after = new Objective(result.objectiveAfter().name(), result.objectiveAfter().unit(),
                result.objectiveAfter().value());

        return new RecommendationDraft(RecommendationType.DOCK_REASSIGNMENT,
                rationale(run, closures, planned.size(), beyondHorizon, result, status, plan.lastBatch(), mpb),
                evidence(run, result), feasibility, before, after, constraints, affected, baseline,
                new ExecutionPlan(steps));
    }

    private OptimizationResult solve(DockReassignmentProblem problem) {
        Future<OptimizationResult> future;
        try {
            future = executor.submit(() -> DockReassignmentSolver.solve(problem));
        } catch (RejectedExecutionException ex) {
            throw RecommendationProblems.of(HttpStatus.SERVICE_UNAVAILABLE, "Optimizer busy",
                    "Every optimizer slot is in use; try again shortly.", RecommendationProblems.OPTIMIZER_BUSY);
        }
        long waitMs = 3L * config.maxSolveMs() + 10_000L;
        try {
            return future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable("The optimizer call was interrupted.");
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw unavailable("The optimizer did not return within " + waitMs + " ms.");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof InvalidProblemException ipe) {
                throw invalid(ipe.errors());
            }
            throw unavailable("The optimizer failed: " + ex.getCause().getClass().getSimpleName());
        }
    }

    /** Closures per dock name: the scenario overlay's, plus a whole-horizon one for a dock the twin shows CLOSED. */
    @SuppressWarnings("unchecked")
    private static Map<String, List<Closure>> closures(SimulationRunSnapshot run, List<DockRow> docks, int horizon) {
        Map<String, List<Closure>> out = new LinkedHashMap<>();
        Object raw = run.scenarioOverlay() == null ? null : run.scenarioOverlay().get("dock_closures");
        if (raw instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                String path = "scenario.overlay.dock_closures[" + i + "]";
                if (!(list.get(i) instanceof Map<?, ?> m)) {
                    throw invalid(List.of(new FieldError(path, "must be an object")));
                }
                Map<String, Object> c = (Map<String, Object>) m;
                String ref = c.get("dock_id") == null ? null : c.get("dock_id").toString();
                DockRow dock = docks.stream().filter(d -> d.name().equals(ref) || d.id().toString().equals(ref))
                        .findFirst().orElseThrow(() -> invalid(List.of(new FieldError(path + ".dock_id",
                                "is not a dock of the site"))));
                Integer start = intValue(c.get("start_min"));
                Integer end = intValue(c.get("end_min"));
                if (start == null || end == null) {
                    throw invalid(List.of(new FieldError(path, "needs integer start_min and end_min")));
                }
                Object reason = c.get("reason");
                out.computeIfAbsent(dock.name(), k -> new ArrayList<>()).add(new Closure(start, end,
                        reason == null || reason.toString().isBlank() ? "scenario dock closure" : reason.toString()));
            }
        }
        for (DockRow d : docks) {
            if ("CLOSED".equals(d.status())) {
                out.computeIfAbsent(d.name(), k -> new ArrayList<>())
                        .add(new Closure(0, horizon, "dock status CLOSED in the twin"));
            }
        }
        return out;
    }

    /** The run first (RecommendationService keeps it first), then each KPI delta of the comparison as a METRIC. */
    @SuppressWarnings("unchecked")
    private static List<EvidenceRef> evidence(SimulationRunSnapshot run, OptimizationResult result) {
        List<EvidenceRef> refs = new ArrayList<>();
        refs.add(new EvidenceRef(EvidenceKind.SIMULATION_RUN, run.runId().toString(),
                "Simulation run of scenario \"" + run.scenarioName() + "\""));
        Object deltas = run.results() == null ? null : run.results().get("deltas");
        if (deltas instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> d = (Map<String, Object>) m;
                Object key = d.get("key");
                if (key == null || !(d.get("delta_mean") instanceof Number mean)) {
                    continue;
                }
                String unit = d.get("unit") == null ? "" : " " + d.get("unit");
                StringBuilder label = new StringBuilder();
                label.append(d.get("label") == null ? key : d.get("label")).append(": scenario minus baseline ")
                        .append(num(mean)).append(unit);
                if (d.get("delta_ci95_low") instanceof Number lo && d.get("delta_ci95_high") instanceof Number hi) {
                    label.append(" (95% CI ").append(num(lo)).append(" to ").append(num(hi)).append(unit)
                            .append(")");
                }
                refs.add(new EvidenceRef(EvidenceKind.METRIC, run.runId() + "#deltas/" + key, label.toString()));
            }
        }
        refs.add(new EvidenceRef(EvidenceKind.METRIC, "optimization:" + result.resultHash(),
                "Optimizer result " + result.resultHash() + " for input " + result.inputHash()));
        return refs;
    }

    private static String rationale(SimulationRunSnapshot run, Map<String, List<Closure>> closures, int tripCount,
            List<String> beyondHorizon, OptimizationResult result, FeasibilityStatus status, long lastBatch,
            int mpb) {
        StringBuilder s = new StringBuilder();
        s.append("Scenario \"").append(run.scenarioName()).append("\" (simulation run ").append(run.runId())
                .append(")");
        List<String> closed = new ArrayList<>();
        closures.forEach((dock, list) -> list.forEach(c -> closed.add(dock + " from minute " + c.startMin()
                + " to " + c.endMin() + " (" + c.reason() + ")")));
        s.append(closed.isEmpty() ? " closes no dock" : " closes " + String.join(", ", closed)).append(". ");
        s.append("Solving the dock plan of ").append(tripCount).append(" observed SIM-TMS trip(s) with ")
                .append(result.feasibility().solver()).append(" gives a ").append(status).append(" plan that moves ");
        List<String> moves = new ArrayList<>();
        result.tripMoves().forEach(m -> moves.add(m.tripCode() + " " + m.fromDock() + " -> " + m.toDock()
                + " (start minute " + m.plannedStartMin() + ")"));
        result.forkliftMoves().forEach(m -> moves.add(m.code() + " " + m.fromDock() + " -> " + m.toDock()));
        s.append(String.join("; ", moves)).append(". ");
        s.append("Total inbound wait: ").append(result.objectiveBefore().value()).append(' ')
                .append(result.objectiveBefore().unit()).append(" with the current assignment, ")
                .append(result.objectiveAfter().value()).append(' ').append(result.objectiveAfter().unit())
                .append(" with the plan");
        if (result.feasibility().optimalityGap() != null) {
            s.append(" (optimality gap ").append(num(result.feasibility().optimalityGap())).append(")");
        }
        s.append(". Trip times are the SIM-TMS trip plan after batch ").append(lastBatch).append(" at ").append(mpb)
                .append(" plan minutes per batch (ASSUMPTION, ADR-63)");
        if (!beyondHorizon.isEmpty()) {
            s.append("; left out because their next visit ends beyond the horizon: ")
                    .append(String.join(", ", beyondHorizon));
        }
        s.append('.');
        String text = s.toString();
        return text.length() <= RecommendationDraft.MAX_RATIONALE ? text
                : text.substring(0, RecommendationDraft.MAX_RATIONALE - 3) + "...";
    }

    private static String num(Number n) {
        return String.format(Locale.ROOT, "%.2f", n.doubleValue());
    }

    private static Integer intValue(Object v) {
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                && Math.abs(n.doubleValue()) <= Integer.MAX_VALUE) {
            return n.intValue();
        }
        return null;
    }

    private static io.vorg.api.platform.web.ApiProblemException missing(String input, String detail) {
        return RecommendationProblems.of(HttpStatus.UNPROCESSABLE_CONTENT, "Optimization input missing", detail,
                RecommendationProblems.OPTIMIZATION_INPUT_MISSING, Map.of("missing", input));
    }

    private static io.vorg.api.platform.web.ApiProblemException invalid(List<FieldError> errors) {
        List<Map<String, Object>> list = errors.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", e.field());
            m.put("message", e.message());
            return m;
        }).toList();
        return RecommendationProblems.of(HttpStatus.UNPROCESSABLE_CONTENT, "Optimization input invalid",
                "The optimizer rejected the problem built from the current state and the scenario.",
                RecommendationProblems.OPTIMIZATION_INPUT_INVALID, Map.of("errors", list));
    }

    private static io.vorg.api.platform.web.ApiProblemException unavailable(String detail) {
        return RecommendationProblems.of(HttpStatus.SERVICE_UNAVAILABLE, "Optimizer unavailable", detail,
                RecommendationProblems.OPTIMIZER_UNAVAILABLE);
    }
}
