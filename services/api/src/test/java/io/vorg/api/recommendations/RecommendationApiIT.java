package io.vorg.api.recommendations;

import static io.vorg.api.platform.identity.seed.DemoIds.REGIONAL_SOUTH;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.WM_BLR;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;

/**
 * Recommendation and approval flow on the real stack with the test-only generator/current-state reader
 * ({@link RecommendationTestConfig}): propose from a real SUCCEEDED dock-closure run, read, dry-run, approve by a
 * second eligible user; separation of duties, policy eligibility, reject, expiry and re-evaluation, tenant isolation,
 * a driver who cannot propose, idempotent replay of an approval, and the database guarantees (forced RLS, no DELETE).
 *
 * <p>Proposer: {@code wm.blr@demo.invalid} (Warehouse Manager, Bengaluru: ALLOW). Second approver:
 * {@code regional.south@demo.invalid} (Regional Manager, South region containing Bengaluru: ALLOW_SUBJECT_TO_POLICY,
 * accepted by the dock-reassignment policy).
 */
// Its own context (the fake beans); closed after the class so its pools do not eat the shared container's
// connection budget for the rest of the suite.
@Import(RecommendationTestConfig.class)
@DirtiesContext
class RecommendationApiIT extends AbstractApiIntegrationTest {

    @Autowired
    private FakeCurrentStateReader currentState;

    private int keySeq;

    private String key() {
        return "rec-it-" + System.nanoTime() + "-" + (keySeq++);
    }

    @AfterEach
    void resetFixtures() {
        currentState.reset();
    }

    private String wm() throws Exception {
        return token(TENANT_A_SLUG, "wm.blr@demo.invalid");
    }

    // ---- the happy path -------------------------------------------------------------------------------------------

    @Test
    void proposeReadAndDryRun() throws Exception {
        String wm = wm();
        String runId = succeededRun(wm);

        HttpResponse<String> proposed = propose(wm, runId);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(200);
        JsonNode ack = json(proposed);
        assertThat(ack.get("status").asString()).isEqualTo("SUCCEEDED");
        String recId = ack.get("result").get("recommendation_id").asString();
        String approvalId = ack.get("result").get("approval_id").asString();
        assertThat(ack.get("result").get("status").asString()).isEqualTo("PROPOSED");

        HttpResponse<String> got = get("/api/recommendations/" + recId, wm);
        assertThat(got.statusCode()).isEqualTo(200);
        assertThat(got.body()).doesNotContainIgnoringCase("confidence");
        JsonNode rec = json(got);
        List<String> keys = new ArrayList<>();
        rec.propertyNames().forEach(keys::add);
        assertThat(keys).containsExactlyElementsOf(RecommendationJson.RECOMMENDATION_FIELDS);
        assertThat(rec.get("recommendation_id").asString()).isEqualTo(recId);
        assertThat(rec.get("site_id").asString()).isEqualTo(SITE_BLR.toString());
        assertThat(rec.get("type").asString()).isEqualTo("DOCK_REASSIGNMENT");
        assertThat(rec.get("status").asString()).isEqualTo("PROPOSED");
        assertThat(rec.get("evidence_refs").get(0).get("kind").asString()).isEqualTo("SIMULATION_RUN");
        assertThat(rec.get("evidence_refs").get(0).get("ref").asString()).isEqualTo(runId);
        assertThat(rec.get("feasibility").get("status").asString()).isEqualTo("OPTIMAL");
        assertThat(rec.get("feasibility").get("solver").asString()).isEqualTo(FakeRecommendationGenerator.SOLVER);
        assertThat(rec.get("objective_before").get("unit").asString()).isEqualTo("min");
        assertThat(rec.get("objective_after").get("value").asDouble())
                .isEqualTo(FakeRecommendationGenerator.FIXTURE_AFTER);
        assertThat(rec.get("constraints").get(0).get("kind").asString()).isEqualTo("CAPACITY");
        assertThat(rec.get("affected_entities").get(0).asString()).isEqualTo("TRIP-00003");
        assertThat(rec.get("baseline_entity_versions").get("TRIP-00003").asInt()).isEqualTo(4);
        assertThat(rec.get("policy_versions").get("permission-matrix").asInt()).isEqualTo(1);
        assertThat(rec.get("policy_versions").get("dock-reassignment").asInt()).isEqualTo(1);
        JsonNode step = rec.get("execution_plan").get("steps").get(0);
        assertThat(step.get("seq").asInt()).isEqualTo(1);
        assertThat(step.get("command_type").asString()).isEqualTo("REASSIGN_TRIP_DOCK");
        assertThat(step.get("target").asString()).isEqualTo("TRIP-00003");
        assertThat(step.get("params").get("to_dock").asString()).isEqualTo("IN-03");
        assertThat(step.get("compensation").get("params").get("to_dock").asString()).isEqualTo("IN-02");
        assertThat(rec.get("requires_approval").get("required").asBoolean()).isTrue();
        assertThat(rec.get("requires_approval").get("approval_class").asString()).isEqualTo("SENSITIVE");
        assertThat(rec.get("requires_approval").get("separation_of_duties").asBoolean()).isTrue();
        assertThat(Instant.parse(rec.get("expires_at").asString())).isAfter(Instant.now());
        assertThat(rec.get("approval_id").asString()).isEqualTo(approvalId);
        assertThat(rec.get("proposed_by").asString()).isEqualTo(WM_BLR.toString());
        assertThat(rec.get("version").asInt()).isEqualTo(1);
        assertThat(rec.get("data_mode").asString()).isEqualTo("SIMULATED");
        assertThat(rec.get("supersedes_id").isNull()).isTrue();
        assertThat(rec.get("execution").isNull()).isTrue();

        HttpResponse<String> dryRun = get("/api/recommendations/" + recId + "/dry-run", wm);
        assertThat(dryRun.statusCode()).as(dryRun.body()).isEqualTo(200);
        JsonNode dr = json(dryRun);
        assertThat(dr.get("recommendation_id").asString()).isEqualTo(recId);
        assertThat(dr.get("computed_at").asString()).isNotBlank();
        assertThat(dr.get("diff")).hasSize(1);
        assertThat(dr.get("diff").get(0).get("entity").asString()).isEqualTo("TRIP-00003");
        assertThat(dr.get("diff").get(0).get("field").asString()).isEqualTo("dock_code");
        assertThat(dr.get("diff").get(0).get("before").asString()).isEqualTo("IN-02");
        assertThat(dr.get("diff").get(0).get("after").asString()).isEqualTo("IN-03");
        assertThat(dr.get("stale")).isEmpty();

        assertThat(json(get("/api/recommendations?site_id=" + SITE_BLR + "&status=PROPOSED", wm)).get("items"))
                .anyMatch(i -> i.get("recommendation_id").asString().equals(recId));
        assertThat(json(get("/api/recommendations?site_id=" + SITE_BLR + "&status=REJECTED", wm)).get("items"))
                .noneMatch(i -> i.get("recommendation_id").asString().equals(recId));
        assertThat(ownerQuery("select status, approval_class from rec.approvals where id = ?",
                UUID.fromString(approvalId)).get(0)).containsEntry("status", "PENDING")
                .containsEntry("approval_class", "SENSITIVE");

        List<Map<String, Object>> evts = ownerQuery("""
                select event_type, data_mode, actor_type, actor_id from evt.events
                where tenant_id = ? and aggregate_id in (?, ?) order by ingested_at, event_type
                """, TENANT_A, UUID.fromString(recId), UUID.fromString(approvalId));
        assertThat(evts).extracting(r -> r.get("event_type")).containsExactlyInAnyOrder("RECOMMENDATION_PROPOSED",
                "APPROVAL_REQUESTED");
        assertThat(evts).allMatch(r -> "SIMULATED".equals(r.get("data_mode")) && "HUMAN".equals(r.get("actor_type"))
                && WM_BLR.toString().equals(r.get("actor_id")));
    }

    @Test
    void aRunWithAPendingProposalCannotBeProposedAgain() throws Exception {
        String wm = wm();
        String runId = succeededRun(wm);
        String recId = proposeOk(wm, runId).get("recommendation_id").asString();

        HttpResponse<String> second = propose(wm, runId);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(409);
        assertThat(json(second).get("code").asString()).isEqualTo("proposal_pending");
        assertThat(json(second).get("recommendation_id").asString()).isEqualTo(recId);
    }

    @Test
    void anotherTenantCannotSeeOrDecide() throws Exception {
        String wm = wm();
        JsonNode result = proposeOk(wm, succeededRun(wm));
        String recId = result.get("recommendation_id").asString();
        String approvalId = result.get("approval_id").asString();
        String tenantB = token(TENANT_B_SLUG, "exec@demo.invalid");

        assertThat(get("/api/recommendations/" + recId, tenantB).statusCode()).isEqualTo(404);
        assertThat(get("/api/recommendations/" + recId + "/dry-run", tenantB).statusCode()).isEqualTo(404);
        assertThat(json(get("/api/recommendations?site_id=" + SITE_BLR, wm)).get("items"))
                .anyMatch(i -> i.get("approval_id").asString().equals(approvalId));
    }

    @Test
    void aDriverCannotProposeOrRead() throws Exception {
        String wm = wm();
        String runId = succeededRun(wm);
        String driver = token(TENANT_A_SLUG, "driver.blr@demo.invalid");

        HttpResponse<String> res = propose(driver, runId);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(403);
        String recId = proposeOk(wm, runId).get("recommendation_id").asString();
        assertThat(get("/api/recommendations/" + recId, driver).statusCode()).isEqualTo(403);
        assertThat(get("/api/recommendations?site_id=" + SITE_BLR, driver).statusCode()).isEqualTo(403);
    }

    @Test
    void dryRunListsEntitiesThatChangedSinceTheProposal() throws Exception {
        String wm = wm();
        String recId = proposeOk(wm, succeededRun(wm)).get("recommendation_id").asString();
        currentState.bump("TRIP-00003");

        JsonNode dr = json(get("/api/recommendations/" + recId + "/dry-run", wm));
        assertThat(dr.get("stale")).hasSize(1);
        JsonNode stale = dr.get("stale").get(0);
        assertThat(stale.get("kind").asString()).isEqualTo("ENTITY");
        assertThat(stale.get("entity").asString()).isEqualTo("TRIP-00003");
        assertThat(stale.get("baseline_version").asInt()).isEqualTo(4);
        assertThat(stale.get("current_version").asInt()).isEqualTo(5);
    }

    @Test
    void inputValidationAndUnknownRun() throws Exception {
        String wm = wm();
        HttpResponse<String> noRun = post("/api/recommendations", wm, "{\"site_id\":\"" + SITE_BLR + "\"}",
                "Idempotency-Key", key());
        assertThat(noRun.statusCode()).isEqualTo(400);
        assertThat(json(noRun).get("errors").get(0).get("field").asString()).isEqualTo("simulation_run_id");

        HttpResponse<String> unknownRun = propose(wm, UUID.randomUUID().toString());
        assertThat(unknownRun.statusCode()).isEqualTo(404);

        HttpResponse<String> unknownSite = post("/api/recommendations", wm,
                "{\"site_id\":\"018f0000-0000-7000-8000-00000000ffff\",\"simulation_run_id\":\""
                        + UUID.randomUUID() + "\"}", "Idempotency-Key", key());
        assertThat(unknownSite.statusCode()).isEqualTo(404);

        HttpResponse<String> noKey = post("/api/recommendations", wm, "{\"site_id\":\"" + SITE_BLR
                + "\",\"simulation_run_id\":\"" + succeededRun(wm) + "\"}");
        assertThat(noKey.statusCode()).isEqualTo(400);

        assertThat(get("/api/recommendations?site_id=" + SITE_BLR + "&status=NOPE", wm).statusCode())
                .isEqualTo(400);
        assertThat(get("/api/recommendations", wm).statusCode()).isEqualTo(400);
    }

    @Test
    void tablesForceRlsAndCannotBeDeletedFrom() throws Exception {
        for (String table : List.of("rec.recommendations", "rec.approvals")) {
            assertThat(ownerQuery("select relrowsecurity and relforcerowsecurity as forced from pg_class"
                    + " where oid = ?::regclass", table).get(0).get("forced")).as(table).isEqualTo(true);
            assertThat(ownerQuery("select has_table_privilege('vo_app', ?, 'DELETE') as can_delete", table)
                    .get(0).get("can_delete")).as(table).isEqualTo(false);
        }
    }

    // ---- approvals ------------------------------------------------------------------------------------------------

    @Test
    void aSecondEligibleUserApproves() throws Exception {
        String wm = wm();
        JsonNode proposed = proposeOk(wm, succeededRun(wm));
        String recId = proposed.get("recommendation_id").asString();
        String approvalId = proposed.get("approval_id").asString();
        String regional = regional();

        JsonNode pending = json(get("/api/approvals/" + approvalId, regional));
        assertThat(pending.get("status").asString()).isEqualTo("PENDING");
        assertThat(pending.get("requested_by").asString()).isEqualTo(WM_BLR.toString());
        assertThat(pending.get("policy").get("name").asString()).isEqualTo("dock-reassignment");
        assertThat(json(get("/api/approvals?site_id=" + SITE_BLR + "&status=PENDING", regional)).get("items"))
                .anyMatch(i -> i.get("approval_id").asString().equals(approvalId));

        HttpResponse<String> res = decide(regional, approvalId, "approve", "{\"reason\":\"IN-03 is free\"}", key());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        JsonNode result = json(res).get("result");
        assertThat(result.get("outcome").asString()).isEqualTo("DECIDED");
        assertThat(result.get("status").asString()).isEqualTo("APPROVED");
        assertThat(result.get("recommendation_status").asString()).isEqualTo("APPROVED");
        assertThat(result.get("version").asInt()).isEqualTo(2);
        assertThat(result.get("recommendation_version").asInt()).isEqualTo(2);

        JsonNode approval = json(get("/api/approvals/" + approvalId, wm));
        assertThat(approval.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approval.get("decided_by").asString()).isEqualTo(REGIONAL_SOUTH.toString());
        assertThat(approval.get("decided_at").asString()).isNotBlank();
        assertThat(approval.get("reason").asString()).isEqualTo("IN-03 is free");
        JsonNode rec = json(get("/api/recommendations/" + recId, wm));
        assertThat(rec.get("status").asString()).isEqualTo("APPROVED");
        assertThat(rec.get("version").asInt()).isEqualTo(2);

        List<Map<String, Object>> evts = ownerQuery("""
                select event_type, actor_id from evt.events where tenant_id = ? and aggregate_id = ?
                  and event_type = 'APPROVAL_GRANTED'
                """, TENANT_A, UUID.fromString(approvalId));
        assertThat(evts).hasSize(1);
        assertThat(evts.get(0)).containsEntry("actor_id", REGIONAL_SOUTH.toString());

        // Decided: a second decision is a conflict.
        HttpResponse<String> again = decide(regional, approvalId, "reject", "{\"reason\":\"changed my mind\"}", key());
        assertThat(again.statusCode()).as(again.body()).isEqualTo(409);
        assertThat(json(again).get("code").asString()).isEqualTo("invalid_state");
        assertThat(json(again).get("approval_status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void theProposerCannotApproveTheirOwnSensitiveRecommendation() throws Exception {
        String wm = wm();
        JsonNode proposed = proposeOk(wm, succeededRun(wm));
        String approvalId = proposed.get("approval_id").asString();

        HttpResponse<String> res = decide(wm, approvalId, "approve", null, key());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(403);
        assertThat(json(res).get("code").asString()).isEqualTo("separation_of_duties");
        HttpResponse<String> reject = decide(wm, approvalId, "reject", "{\"reason\":\"no\"}", key());
        assertThat(reject.statusCode()).as(reject.body()).isEqualTo(403);
        assertThat(json(reject).get("code").asString()).isEqualTo("separation_of_duties");

        assertThat(json(get("/api/approvals/" + approvalId, wm)).get("status").asString()).isEqualTo("PENDING");
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type in"
                + " ('APPROVAL_GRANTED', 'APPROVAL_REJECTED')", UUID.fromString(approvalId))).isZero();
    }

    @Test
    void aRoleThePolicyDoesNotListIsNotEligibleAndADriverIsForbidden() throws Exception {
        String wm = wm();
        String approvalId = proposeOk(wm, succeededRun(wm)).get("approval_id").asString();

        // Safety & Compliance has an R* cell, but the dock-reassignment policy does not list the role.
        HttpResponse<String> safety = decide(token(TENANT_A_SLUG, "safety@demo.invalid"), approvalId, "approve", null,
                key());
        assertThat(safety.statusCode()).as(safety.body()).isEqualTo(403);
        assertThat(json(safety).get("code").asString()).isEqualTo("approver_not_eligible");
        HttpResponse<String> driver = decide(token(TENANT_A_SLUG, "driver.blr@demo.invalid"), approvalId, "approve",
                null, key());
        assertThat(driver.statusCode()).as(driver.body()).isEqualTo(403);
        assertThat(json(get("/api/approvals/" + approvalId, wm)).get("status").asString()).isEqualTo("PENDING");
    }

    @Test
    void rejectNeedsAReasonAndRejectsTheRecommendation() throws Exception {
        String wm = wm();
        JsonNode proposed = proposeOk(wm, succeededRun(wm));
        String recId = proposed.get("recommendation_id").asString();
        String approvalId = proposed.get("approval_id").asString();
        String regional = regional();

        HttpResponse<String> noReason = decide(regional, approvalId, "reject", "{}", key());
        assertThat(noReason.statusCode()).as(noReason.body()).isEqualTo(400);
        assertThat(json(noReason).get("errors").get(0).get("field").asString()).isEqualTo("reason");
        HttpResponse<String> tooLong = decide(regional, approvalId, "reject",
                "{\"reason\":\"" + "x".repeat(501) + "\"}", key());
        assertThat(tooLong.statusCode()).as(tooLong.body()).isEqualTo(400);

        HttpResponse<String> res = decide(regional, approvalId, "reject", "{\"reason\":\"IN-03 is reserved\"}",
                key());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        assertThat(json(res).get("result").get("status").asString()).isEqualTo("REJECTED");
        assertThat(json(res).get("result").get("recommendation_status").asString()).isEqualTo("REJECTED");
        JsonNode approval = json(get("/api/approvals/" + approvalId, wm));
        assertThat(approval.get("status").asString()).isEqualTo("REJECTED");
        assertThat(approval.get("reason").asString()).isEqualTo("IN-03 is reserved");
        assertThat(json(get("/api/recommendations/" + recId, wm)).get("status").asString()).isEqualTo("REJECTED");
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type ="
                + " 'APPROVAL_REJECTED'", UUID.fromString(approvalId))).isEqualTo(1);

        HttpResponse<String> approve = decide(regional, approvalId, "approve", null, key());
        assertThat(approve.statusCode()).as(approve.body()).isEqualTo(409);
        assertThat(json(approve).get("code").asString()).isEqualTo("invalid_state");
    }

    @Test
    void anExpiredApprovalIs409WithAReEvaluateLinkAndReProposingSupersedesIt() throws Exception {
        String wm = wm();
        String runId = succeededRun(wm);
        JsonNode proposed = proposeOk(wm, runId);
        String recId = proposed.get("recommendation_id").asString();
        String approvalId = proposed.get("approval_id").asString();
        expire(recId, approvalId);
        String regional = regional();

        String k = key();
        HttpResponse<String> res = decide(regional, approvalId, "approve", null, k);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(409);
        JsonNode problem = json(res);
        assertThat(problem.get("code").asString()).isEqualTo("approval_expired");
        assertThat(problem.get("approval_id").asString()).isEqualTo(approvalId);
        assertThat(problem.get("recommendation_id").asString()).isEqualTo(recId);
        assertThat(problem.get("re_evaluate").get("href").asString()).isEqualTo("/api/recommendations");
        assertThat(problem.get("re_evaluate").get("body").get("site_id").asString()).isEqualTo(SITE_BLR.toString());
        assertThat(problem.get("re_evaluate").get("body").get("simulation_run_id").asString()).isEqualTo(runId);

        // Committed: the approval and the recommendation are EXPIRED, with one APPROVAL_EXPIRED event.
        assertThat(json(get("/api/approvals/" + approvalId, wm)).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(json(get("/api/recommendations/" + recId, wm)).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type ="
                + " 'APPROVAL_EXPIRED'", UUID.fromString(approvalId))).isEqualTo(1);

        // A replay of the same key answers the same way and records nothing new.
        HttpResponse<String> replay = decide(regional, approvalId, "approve", null, k);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(409);
        assertThat(json(replay).get("code").asString()).isEqualTo("approval_expired");
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type ="
                + " 'APPROVAL_EXPIRED'", UUID.fromString(approvalId))).isEqualTo(1);

        // Re-evaluate: post the body to the href.
        HttpResponse<String> again = post(problem.get("re_evaluate").get("href").asString(), wm,
                mapper.writeValueAsString(problem.get("re_evaluate").get("body")), "Idempotency-Key", key());
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        String newId = json(again).get("result").get("recommendation_id").asString();
        assertThat(json(again).get("result").get("supersedes_id").asString()).isEqualTo(recId);
        assertThat(json(get("/api/recommendations/" + newId, wm)).get("supersedes_id").asString()).isEqualTo(recId);
    }

    @Test
    void anApprovalReplayReturnsTheStoredAckAndDecidesOnce() throws Exception {
        String wm = wm();
        String approvalId = proposeOk(wm, succeededRun(wm)).get("approval_id").asString();
        String regional = regional();
        String k = key();

        HttpResponse<String> first = decide(regional, approvalId, "approve", null, k);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        HttpResponse<String> second = decide(regional, approvalId, "approve", null, k);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(json(second).get("command_id").asString()).isEqualTo(json(first).get("command_id").asString());
        assertThat(json(second).get("result").get("event_id").asString())
                .isEqualTo(json(first).get("result").get("event_id").asString());
        assertThat(json(get("/api/approvals/" + approvalId, wm)).get("version").asInt()).isEqualTo(2);
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type ="
                + " 'APPROVAL_GRANTED'", UUID.fromString(approvalId))).isEqualTo(1);

        // The same key with a different payload is a conflict.
        HttpResponse<String> different = decide(regional, approvalId, "approve", "{\"reason\":\"other\"}", k);
        assertThat(different.statusCode()).as(different.body()).isEqualTo(409);
    }

    @Test
    void anotherTenantCannotReadOrDecideAnApproval() throws Exception {
        String wm = wm();
        String approvalId = proposeOk(wm, succeededRun(wm)).get("approval_id").asString();
        String tenantB = token(TENANT_B_SLUG, "wm@demo.invalid");
        assertThat(get("/api/approvals/" + approvalId, tenantB).statusCode()).isEqualTo(404);
        assertThat(decide(tenantB, approvalId, "approve", null, key()).statusCode()).isEqualTo(404);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private String regional() throws Exception {
        return token(TENANT_A_SLUG, "regional.south@demo.invalid");
    }

    private HttpResponse<String> decide(String token, String approvalId, String verb, String body, String k)
            throws Exception {
        return post("/api/approvals/" + approvalId + "/" + verb, token, body, "Idempotency-Key", k);
    }

    /** Moves the deadline of a proposal into the past (the clock is real, so the data is aged instead). */
    private void expire(String recId, String approvalId) throws Exception {
        ownerUpdate("update rec.approvals set expires_at = now() - interval '1 minute' where id = ?",
                UUID.fromString(approvalId));
        ownerUpdate("update rec.recommendations set expires_at = now() - interval '1 minute' where id = ?",
                UUID.fromString(recId));
    }

    private HttpResponse<String> propose(String token, String runId) throws Exception {
        return post("/api/recommendations", token,
                "{\"site_id\":\"" + SITE_BLR + "\",\"simulation_run_id\":\"" + runId + "\"}", "Idempotency-Key",
                key());
    }

    private JsonNode proposeOk(String token, String runId) throws Exception {
        HttpResponse<String> res = propose(token, runId);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json(res).get("result");
    }

    /** A real dock-closure scenario and a SUCCEEDED run of it, through the scenarios API. */
    private String succeededRun(String token) throws Exception {
        String body = """
                {"site_id":"%s","name":"Recommendation IT dock closure","description":"IN-02 closed",
                 "run":{"master_seed":20261013,"replications":2,"horizon_min":240,"warmup_min":30},
                 "overlay":{"dock_closures":[{"dock_id":"IN-02","start_min":60,"end_min":180,"reason":"leveller repair"}]}}
                """.formatted(SITE_BLR);
        HttpResponse<String> created = post("/api/scenarios", token, body, "Idempotency-Key", key());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String scenarioId = json(created).get("result").get("id").asString();
        HttpResponse<String> run = post("/api/scenarios/" + scenarioId + "/runs", token, null, "Idempotency-Key",
                key());
        assertThat(run.statusCode()).as(run.body()).isEqualTo(200);
        String runId = json(run).get("result").get("run_id").asString();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
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
