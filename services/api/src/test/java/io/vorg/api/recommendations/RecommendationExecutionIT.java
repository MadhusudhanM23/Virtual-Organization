package io.vorg.api.recommendations;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.integration.core.IntegrationSyncService;
import io.vorg.api.integration.simulated.SimWorld;
import io.vorg.api.integration.simulated.SimWorldProvider;
import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;

/**
 * Execution of recommendations on the real stack (Plan 8.3 task 4, MCD 11-12): the OR-Tools generator, the simulated
 * TMS command port, the dock-assignment projection and the verifier. A dock-closure scenario is run, proposed,
 * dry-run, approved by a second user, executed and verified from what SIM-TMS reports after the next sync. Then the
 * stale paths (an external TMS change, a policy-version bump), execute without an approval, an idempotent replay, the
 * compensation path (an injected refusal of the last step) and the verification timeout (a source that acknowledges a
 * command but never applies it). {@link ExecutionTestConfig} provides the two test seams.
 *
 * <p>The SIM-TMS sync is driven by hand ({@link IntegrationSyncService#syncOnce}); the live loop is off in tests. The
 * database is shared with every other IT, so each test puts every overridden trip back on its seeded dock afterwards.
 */
@Import(ExecutionTestConfig.class)
@DirtiesContext
class RecommendationExecutionIT extends AbstractApiIntegrationTest {

    @Autowired
    private IntegrationSyncService sync;

    @Autowired
    private SimWorldProvider worlds;

    @Autowired
    private ExecutionTestConfig.BumpablePolicyVersions policyVersions;

    private int keySeq;

    private String key() {
        return "rec-exec-it-" + System.nanoTime() + "-" + (keySeq++);
    }

    @BeforeEach
    void restoreBefore() throws Exception {
        restoreSeededDocks();
    }

    @AfterEach
    void restoreAfter() throws Exception {
        ExecutionTestConfig.FAULTS.reset();
        policyVersions.reset();
        restoreSeededDocks();
    }

    // ---- the full path --------------------------------------------------------------------------------------------

    @Test
    void dockClosureRunProposeDryRunApproveExecuteVerified() throws Exception {
        String wm = wm();
        String runId = succeededRun(wm);

        HttpResponse<String> proposed = propose(wm, runId);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(200);
        String recId = json(proposed).get("result").get("recommendation_id").asString();
        String approvalId = json(proposed).get("result").get("approval_id").asString();

        JsonNode rec = json(get("/api/recommendations/" + recId, wm));
        assertThat(rec.get("status").asString()).isEqualTo("PROPOSED");
        assertThat(rec.get("feasibility").get("status").asString()).isIn("OPTIMAL", "FEASIBLE");
        assertThat(rec.get("evidence_refs").get(0).get("ref").asString()).isEqualTo(runId);
        assertThat(rec.get("objective_after").get("value").asDouble())
                .isLessThan(rec.get("objective_before").get("value").asDouble());
        List<JsonNode> steps = steps(rec);
        assertThat(steps).isNotEmpty();
        for (JsonNode step : steps) {
            assertThat(step.get("command_type").asString()).isEqualTo("REASSIGN_TRIP_DOCK");
            assertThat(rec.get("baseline_entity_versions").has(step.get("target").asString())).isTrue();
        }
        // The closed docks are left.
        assertThat(steps).allMatch(s -> !List.of("IN-02", "OUT-01").contains(s.get("params").get("to_dock")
                .asString()));

        // Dry-run: one diff row per step, from the observed dock to the planned one; nothing stale.
        JsonNode dryRun = json(get("/api/recommendations/" + recId + "/dry-run", wm));
        assertThat(dryRun.get("stale")).isEmpty();
        assertThat(dryRun.get("diff")).hasSize(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            JsonNode d = dryRun.get("diff").get(i);
            assertThat(d.get("entity").asString()).isEqualTo(steps.get(i).get("target").asString());
            assertThat(d.get("field").asString()).isEqualTo("dock_code");
            assertThat(d.get("before").asString()).isEqualTo(steps.get(i).get("params").get("from_dock").asString());
            assertThat(d.get("after").asString()).isEqualTo(steps.get(i).get("params").get("to_dock").asString());
        }

        approve(approvalId);
        // A routine sync in between (trucks progress through their states) must not make the plan stale: only a
        // material change of a target bumps a baseline version (ADR-63).
        sync.syncOnce(TENANT_A, SITE_BLR);
        sync.syncOnce(TENANT_A, SITE_BLR);

        HttpResponse<String> executed = execute(wm, recId, key());
        assertThat(executed.statusCode()).as(executed.body()).isEqualTo(200);
        JsonNode result = json(executed).get("result");
        assertThat(result.get("outcome").asString()).isEqualTo("EXECUTING");
        assertThat(result.get("status").asString()).isEqualTo("EXECUTING");
        assertThat(result.get("execution").get("command_id").asString())
                .isEqualTo(json(executed).get("command_id").asString());
        assertThat(result.get("execution").get("steps")).allMatch(s -> "APPLIED".equals(s.get("status").asString()));
        for (JsonNode step : steps) {
            assertThat(overrideDock(step.get("target").asString()))
                    .isEqualTo(step.get("params").get("to_dock").asString());
        }
        JsonNode executing = json(get("/api/recommendations/" + recId, wm));
        assertThat(executing.get("status").asString()).isEqualTo("EXECUTING");
        assertThat(executing.get("execution").get("finished_at").isNull()).isTrue();

        // The next sync: SIM-TMS reports the new docks, the projection records the change, the verifier observes it.
        sync.syncOnce(TENANT_A, SITE_BLR);

        JsonNode verified = json(get("/api/recommendations/" + recId, wm));
        assertThat(verified.get("status").asString()).as(verified.toString()).isEqualTo("VERIFIED");
        JsonNode execution = verified.get("execution");
        assertThat(execution.get("finished_at").asString()).isNotBlank();
        assertThat(execution.get("evidence").isNull()).isTrue();
        for (JsonNode s : execution.get("steps")) {
            assertThat(s.get("status").asString()).isEqualTo("VERIFIED");
            String eventId = s.get("observed_event_id").asString();
            List<Map<String, Object>> observed = ownerQuery("""
                    select event_type, payload->>'trip_code' as trip, payload->>'to_dock' as to_dock
                    from evt.events where event_id = ?
                    """, UUID.fromString(eventId));
            assertThat(observed).hasSize(1);
            assertThat(observed.get(0)).containsEntry("event_type", "DOCK_ASSIGNMENT_CHANGED")
                    .containsEntry("trip", s.get("target").asString());
        }
        assertThat(eventTypes(recId)).containsSubsequence("RECOMMENDATION_PROPOSED",
                "RECOMMENDATION_EXECUTION_STARTED", "RECOMMENDATION_STEP_APPLIED", "RECOMMENDATION_VERIFIED");
        List<Map<String, Object>> verifiedEvt = ownerQuery("""
                select source, actor_type, actor_id, data_mode from evt.events
                where aggregate_id = ? and event_type = 'RECOMMENDATION_VERIFIED'
                """, UUID.fromString(recId));
        assertThat(verifiedEvt).hasSize(1);
        assertThat(verifiedEvt.get(0)).containsEntry("actor_type", "SERVICE")
                .containsEntry("actor_id", "svc:recommendation-verifier").containsEntry("data_mode", "SIMULATED");
    }

    // ---- stale ----------------------------------------------------------------------------------------------------

    @Test
    void anExternalTmsChangeOfAnAffectedTripMakesExecuteStale() throws Exception {
        String wm = wm();
        JsonNode rec = approvedRecommendation(wm);
        String recId = rec.get("recommendation_id").asString();
        JsonNode step = steps(rec).get(0);
        String trip = step.get("target").asString();
        String elsewhere = otherDock(trip, step.get("params").get("from_dock").asString(),
                step.get("params").get("to_dock").asString());

        HttpResponse<String> change = post("/api/demo/integration/tms-change", wm, """
                {"site_id":"%s","trip_code":"%s","to_dock":"%s"}""".formatted(SITE_BLR, trip, elsewhere),
                "Idempotency-Key", key());
        assertThat(change.statusCode()).as(change.body()).isEqualTo(200);
        assertThat(json(change).get("result").get("previous_dock").asString())
                .isEqualTo(step.get("params").get("from_dock").asString());
        assertThat(json(change).get("result").get("to_dock").asString()).isEqualTo(elsewhere);
        sync.syncOnce(TENANT_A, SITE_BLR);

        String k = key();
        HttpResponse<String> res = execute(wm, recId, k);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(409);
        JsonNode problem = json(res);
        assertThat(problem.get("code").asString()).isEqualTo("stale_baseline");
        assertThat(problem.get("recommendation_id").asString()).isEqualTo(recId);
        int baseline = rec.get("baseline_entity_versions").get(trip).asInt();
        assertThat(problem.get("stale")).anyMatch(s -> "ENTITY".equals(s.get("kind").asString())
                && trip.equals(s.get("entity").asString()) && s.get("baseline_version").asInt() == baseline
                && s.get("current_version").asInt() == baseline + 1);
        String scenarioId = problem.get("resimulate").get("scenario_id").asString();
        assertThat(problem.get("resimulate").get("href").asString())
                .isEqualTo("/api/scenarios/" + scenarioId + "/runs");

        assertThat(json(get("/api/recommendations/" + recId, wm)).get("status").asString()).isEqualTo("STALE");
        assertThat(eventTypes(recId)).contains("RECOMMENDATION_STALE").doesNotContain(
                "RECOMMENDATION_EXECUTION_STARTED");
        // Nothing was applied: the trip stays where the external TMS put it.
        assertThat(overrideDock(trip)).isEqualTo(elsewhere);

        // A replay answers the same way; a new key finds the recommendation no longer approved.
        HttpResponse<String> replay = execute(wm, recId, k);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(409);
        assertThat(json(replay).get("code").asString()).isEqualTo("stale_baseline");
        HttpResponse<String> fresh = execute(wm, recId, key());
        assertThat(fresh.statusCode()).as(fresh.body()).isEqualTo(409);
        assertThat(json(fresh).get("code").asString()).isEqualTo("not_approved");
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type ="
                + " 'RECOMMENDATION_STALE'", UUID.fromString(recId))).isEqualTo(1);

        // Re-simulate: the link starts a new run of the same scenario.
        HttpResponse<String> rerun = post(problem.get("resimulate").get("href").asString(), wm, null,
                "Idempotency-Key", key());
        assertThat(rerun.statusCode()).as(rerun.body()).isEqualTo(200);
        assertThat(json(rerun).get("result").get("run_id").asString()).isNotBlank();
    }

    @Test
    void aPolicyVersionBumpMakesExecuteStale() throws Exception {
        String wm = wm();
        JsonNode rec = approvedRecommendation(wm);
        String recId = rec.get("recommendation_id").asString();
        int baseline = rec.get("policy_versions").get("dock-reassignment").asInt();
        policyVersions.bump(DockReassignmentPolicy.NAME);

        HttpResponse<String> res = execute(wm, recId, key());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(409);
        JsonNode problem = json(res);
        assertThat(problem.get("code").asString()).isEqualTo("stale_baseline");
        assertThat(problem.get("stale")).hasSize(1);
        JsonNode row = problem.get("stale").get(0);
        assertThat(row.get("kind").asString()).isEqualTo("POLICY");
        assertThat(row.get("entity").asString()).isEqualTo("dock-reassignment");
        assertThat(row.get("baseline_version").asInt()).isEqualTo(baseline);
        assertThat(row.get("current_version").asInt()).isEqualTo(baseline + 1);
        assertThat(json(get("/api/recommendations/" + recId, wm)).get("status").asString()).isEqualTo("STALE");
        for (JsonNode step : steps(rec)) {
            assertThat(overrideDock(step.get("target").asString()))
                    .isNotEqualTo(step.get("params").get("to_dock").asString());
        }
    }

    // ---- approval required ----------------------------------------------------------------------------------------

    @Test
    void executeWithoutAnApprovalIsRefused() throws Exception {
        String wm = wm();
        HttpResponse<String> proposed = propose(wm, succeededRun(wm));
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(200);
        String recId = json(proposed).get("result").get("recommendation_id").asString();

        // A warehouse manager may execute standard commands: the handler refuses an unapproved plan.
        HttpResponse<String> res = execute(wm, recId, key());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(409);
        assertThat(json(res).get("code").asString()).isEqualTo("not_approved");
        assertThat(json(res).get("recommendation_status").asString()).isEqualTo("PROPOSED");
        assertThat(json(res).get("approval_status").asString()).isEqualTo("PENDING");
        // A regional manager's cell is GATED: without an approved workflow the gateway denies it.
        HttpResponse<String> gated = execute(token(TENANT_A_SLUG, "regional.south@demo.invalid"), recId, key());
        assertThat(gated.statusCode()).as(gated.body()).isEqualTo(403);
        // A driver never may.
        assertThat(execute(token(TENANT_A_SLUG, "driver.blr@demo.invalid"), recId, key()).statusCode())
                .isEqualTo(403);

        JsonNode after = json(get("/api/recommendations/" + recId, wm));
        assertThat(after.get("status").asString()).isEqualTo("PROPOSED");
        assertThat(after.get("execution").isNull()).isTrue();
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type like"
                + " 'RECOMMENDATION_EXECUTION_%'", UUID.fromString(recId))).isZero();
    }

    @Test
    void aGatedRoleMayExecuteOnceTheWorkflowIsApproved() throws Exception {
        String wm = wm();
        JsonNode rec = approvedRecommendation(wm);
        // The regional manager's EXECUTE_STANDARD_COMMAND cell is GATED: the approval makes it an approved workflow.
        HttpResponse<String> res = execute(token(TENANT_A_SLUG, "regional.south@demo.invalid"),
                rec.get("recommendation_id").asString(), key());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        assertThat(json(res).get("result").get("status").asString()).isEqualTo("EXECUTING");
    }

    @Test
    void executeAfterTheDeadlineIs409ApprovalExpiredAndAnotherTenantSeesNothing() throws Exception {
        String wm = wm();
        JsonNode rec = approvedRecommendation(wm);
        String recId = rec.get("recommendation_id").asString();

        assertThat(execute(token(TENANT_B_SLUG, "wm@demo.invalid"), recId, key()).statusCode()).isEqualTo(404);

        ownerUpdate("update rec.recommendations set expires_at = now() - interval '1 minute' where id = ?",
                UUID.fromString(recId));
        HttpResponse<String> res = execute(wm, recId, key());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(409);
        assertThat(json(res).get("code").asString()).isEqualTo("approval_expired");
        assertThat(json(res).get("recommendation_id").asString()).isEqualTo(recId);
        assertThat(json(res).get("approval_id").asString()).isEqualTo(rec.get("approval_id").asString());
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type like"
                + " 'RECOMMENDATION_EXECUTION_%'", UUID.fromString(recId))).isZero();
    }

    // ---- idempotency ----------------------------------------------------------------------------------------------

    @Test
    void anExecuteReplayReturnsTheStoredAckAndAppliesNothingTwice() throws Exception {
        String wm = wm();
        JsonNode rec = approvedRecommendation(wm);
        String recId = rec.get("recommendation_id").asString();
        List<JsonNode> steps = steps(rec);
        String k = key();

        HttpResponse<String> first = execute(wm, recId, k);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (JsonNode step : steps) {
            versions.put(step.get("target").asString(), overrideVersion(step.get("target").asString()));
        }

        HttpResponse<String> second = execute(wm, recId, k);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(json(second).get("command_id").asString()).isEqualTo(json(first).get("command_id").asString());
        assertThat(json(second).get("result").get("event_id").asString())
                .isEqualTo(json(first).get("result").get("event_id").asString());
        versions.forEach((trip, v) -> {
            try {
                assertThat(overrideVersion(trip)).as(trip).isEqualTo(v);
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        });
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type ="
                + " 'RECOMMENDATION_STEP_APPLIED'", UUID.fromString(recId))).isEqualTo(steps.size());
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type ="
                + " 'RECOMMENDATION_EXECUTION_STARTED'", UUID.fromString(recId))).isEqualTo(1);

        // Another key finds it already executing.
        HttpResponse<String> other = execute(wm, recId, key());
        assertThat(other.statusCode()).as(other.body()).isEqualTo(409);
        assertThat(json(other).get("code").asString()).isEqualTo("not_approved");
    }

    // ---- failure paths --------------------------------------------------------------------------------------------

    @Test
    void aRefusedStepCompensatesTheAppliedStepsInReverseAndFails() throws Exception {
        String wm = wm();
        JsonNode rec = approvedRecommendation(wm);
        String recId = rec.get("recommendation_id").asString();
        List<JsonNode> steps = steps(rec);
        assertThat(steps).as("the scenario closes two docks, so the plan moves at least two trips").hasSizeGreaterThan(1);
        ExecutionTestConfig.FAULTS.failOnCall(steps.size());

        HttpResponse<String> res = execute(wm, recId, key());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        JsonNode result = json(res).get("result");
        assertThat(result.get("outcome").asString()).isEqualTo("FAILED");
        JsonNode execution = result.get("execution");
        JsonNode exSteps = execution.get("steps");
        for (int i = 0; i < steps.size() - 1; i++) {
            assertThat(exSteps.get(i).get("status").asString()).isEqualTo("COMPENSATED");
        }
        assertThat(exSteps.get(steps.size() - 1).get("status").asString()).isEqualTo("FAILED");
        JsonNode evidence = execution.get("evidence");
        assertThat(evidence.get("reason").asString()).isEqualTo("STEP_FAILED");
        assertThat(evidence.get("failed_step").get("seq").asInt()).isEqualTo(steps.size());
        assertThat(evidence.get("failed_step").get("code").asString()).isEqualTo("injected_fault");
        assertThat(evidence.get("failed_step").get("source_system").asString()).isEqualTo("SIM-TMS");
        assertThat(evidence.get("compensation_failures")).isEmpty();
        assertThat(execution.get("finished_at").asString()).isNotBlank();

        JsonNode stored = json(get("/api/recommendations/" + recId, wm));
        assertThat(stored.get("status").asString()).isEqualTo("FAILED");
        // Every trip is back where it was (the compensations told SIM-TMS the previous dock).
        for (JsonNode step : steps) {
            String trip = step.get("target").asString();
            String now = overrideDock(trip);
            if (step.get("seq").asInt() < steps.size()) {
                assertThat(now).as(trip).isEqualTo(step.get("params").get("from_dock").asString());
            } else {
                // The refused step never reached SIM-TMS.
                assertThat(now).as(trip).isNotEqualTo(step.get("params").get("to_dock").asString());
            }
        }
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type ="
                + " 'RECOMMENDATION_STEP_COMPENSATED'", UUID.fromString(recId))).isEqualTo(steps.size() - 1);
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type ="
                + " 'RECOMMENDATION_EXECUTION_FAILED'", UUID.fromString(recId))).isEqualTo(1);

        // The verifier leaves a finished execution alone.
        sync.syncOnce(TENANT_A, SITE_BLR);
        assertThat(json(get("/api/recommendations/" + recId, wm)).get("status").asString()).isEqualTo("FAILED");
    }

    @Test
    void aChangeTheSourceNeverShowsTimesOutAsFailed() throws Exception {
        String wm = wm();
        JsonNode rec = approvedRecommendation(wm);
        String recId = rec.get("recommendation_id").asString();
        List<JsonNode> steps = steps(rec);
        ExecutionTestConfig.FAULTS.dropAll();

        HttpResponse<String> res = execute(wm, recId, key());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        assertThat(json(res).get("result").get("outcome").asString()).isEqualTo("EXECUTING");

        sync.syncOnce(TENANT_A, SITE_BLR);
        assertThat(json(get("/api/recommendations/" + recId, wm)).get("status").asString()).isEqualTo("EXECUTING");
        sync.syncOnce(TENANT_A, SITE_BLR);

        JsonNode failed = json(get("/api/recommendations/" + recId, wm));
        assertThat(failed.get("status").asString()).as(failed.toString()).isEqualTo("FAILED");
        JsonNode evidence = failed.get("execution").get("evidence");
        assertThat(evidence.get("reason").asString()).isEqualTo("VERIFICATION_TIMEOUT");
        assertThat(evidence.get("timeout_batches").asInt()).isEqualTo(2);
        assertThat(evidence.get("batches_observed").asInt()).isEqualTo(2);
        assertThat(evidence.get("observed")).hasSize(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            JsonNode o = evidence.get("observed").get(i);
            assertThat(o.get("target").asString()).isEqualTo(steps.get(i).get("target").asString());
            assertThat(o.get("expected_dock").asString())
                    .isEqualTo(steps.get(i).get("params").get("to_dock").asString());
            assertThat(o.get("observed_dock").asString())
                    .isEqualTo(steps.get(i).get("params").get("from_dock").asString());
        }
        assertThat(failed.get("execution").get("steps")).allMatch(s -> "APPLIED".equals(s.get("status").asString()));
        List<Map<String, Object>> evt = ownerQuery("""
                select actor_id from evt.events where aggregate_id = ? and event_type = 'RECOMMENDATION_EXECUTION_FAILED'
                """, UUID.fromString(recId));
        assertThat(evt).hasSize(1);
        assertThat(evt.get(0)).containsEntry("actor_id", "svc:recommendation-verifier");
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private String wm() throws Exception {
        return token(TENANT_A_SLUG, "wm.blr@demo.invalid");
    }

    private HttpResponse<String> propose(String token, String runId) throws Exception {
        return post("/api/recommendations", token,
                "{\"site_id\":\"" + SITE_BLR + "\",\"simulation_run_id\":\"" + runId + "\"}", "Idempotency-Key",
                key());
    }

    private HttpResponse<String> execute(String token, String recId, String k) throws Exception {
        return post("/api/recommendations/" + recId + "/execute", token, null, "Idempotency-Key", k);
    }

    private void approve(String approvalId) throws Exception {
        HttpResponse<String> res = post("/api/approvals/" + approvalId + "/approve",
                token(TENANT_A_SLUG, "regional.south@demo.invalid"), null, "Idempotency-Key", key());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    }

    /** A dock-closure scenario run, proposed by the warehouse manager and approved by the regional manager. */
    private JsonNode approvedRecommendation(String wm) throws Exception {
        HttpResponse<String> proposed = propose(wm, succeededRun(wm));
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(200);
        String recId = json(proposed).get("result").get("recommendation_id").asString();
        approve(json(proposed).get("result").get("approval_id").asString());
        JsonNode rec = json(get("/api/recommendations/" + recId, wm));
        assertThat(rec.get("status").asString()).isEqualTo("APPROVED");
        return rec;
    }

    private static List<JsonNode> steps(JsonNode rec) {
        List<JsonNode> out = new ArrayList<>();
        rec.get("execution_plan").get("steps").forEach(out::add);
        return out;
    }

    private List<String> eventTypes(String recId) throws Exception {
        return ownerQuery("select event_type from evt.events where aggregate_id = ? order by ingested_at, event_id",
                UUID.fromString(recId)).stream().map(r -> (String) r.get("event_type")).toList();
    }

    private String overrideDock(String trip) throws Exception {
        List<Map<String, Object>> rows = ownerQuery("""
                select dock_code from integ.sim_tms_overrides where tenant_id = ? and site_id = ? and trip_code = ?
                """, TENANT_A, SITE_BLR, trip);
        return rows.isEmpty() ? null : (String) rows.get(0).get("dock_code");
    }

    private int overrideVersion(String trip) throws Exception {
        List<Map<String, Object>> rows = ownerQuery("""
                select version from integ.sim_tms_overrides where tenant_id = ? and site_id = ? and trip_code = ?
                """, TENANT_A, SITE_BLR, trip);
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("version")).intValue();
    }

    /** A dock of the trip's direction that is neither of the given ones. */
    private String otherDock(String trip, String... not) {
        SimWorld world = worlds.get(TENANT_A, SITE_BLR);
        String direction = world.trucks().stream().filter(t -> t.tripId().equals(trip)).findFirst().orElseThrow()
                .direction();
        List<String> excluded = List.of(not);
        return world.docks(direction).stream().map(SimWorld.Dock::name).filter(d -> !excluded.contains(d))
                .findFirst().orElseThrow();
    }

    /**
     * Puts every overridden trip back on the dock the seeded SIM-TMS plans it for (a new override version, so the next
     * batch is a strictly newer record) and syncs once, so the projection shows the seeded docks again.
     */
    private void restoreSeededDocks() throws Exception {
        for (SimWorld.Truck t : worlds.get(TENANT_A, SITE_BLR).trucks()) {
            ownerUpdate("""
                    update integ.sim_tms_overrides set dock_code = ?, version = version + 1, updated_at = now()
                    where tenant_id = ? and site_id = ? and trip_code = ? and dock_code <> ?
                    """, t.dockName(), TENANT_A, SITE_BLR, t.tripId(), t.dockName());
        }
        sync.syncOnce(TENANT_A, SITE_BLR);
    }

    /**
     * A real dock-closure scenario and a SUCCEEDED run of it: IN-02 and OUT-01 are closed for the first 300 minutes
     * of a 480-minute horizon, so the trips planned there must either wait or move.
     */
    private String succeededRun(String token) throws Exception {
        String body = """
                {"site_id":"%s","name":"Execution IT dock closure","description":"IN-02 and OUT-01 closed",
                 "run":{"master_seed":20261014,"replications":2,"horizon_min":480,"warmup_min":30},
                 "overlay":{"dock_closures":[
                   {"dock_id":"IN-02","start_min":0,"end_min":300,"reason":"leveller repair"},
                   {"dock_id":"OUT-01","start_min":0,"end_min":300,"reason":"door repair"}]}}
                """.formatted(SITE_BLR);
        HttpResponse<String> created = post("/api/scenarios", token, body, "Idempotency-Key", key());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String scenarioId = json(created).get("result").get("id").asString();
        HttpResponse<String> run = post("/api/scenarios/" + scenarioId + "/runs", token, null, "Idempotency-Key",
                key());
        assertThat(run.statusCode()).as(run.body()).isEqualTo(200);
        String runId = json(run).get("result").get("run_id").asString();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            String status = json(get("/api/simulation-runs/" + runId, token)).get("status").asString();
            if ("SUCCEEDED".equals(status)) {
                return runId;
            }
            assertThat(status).isNotEqualTo("FAILED");
            Thread.sleep(50);
        }
        throw new AssertionError("run " + runId + " did not succeed in time");
    }
}
