package io.vorg.api.ops;

import static io.vorg.api.platform.identity.seed.DemoIds.DRIVER_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.integration.core.AdapterContext;
import io.vorg.api.integration.core.MirrorService;
import io.vorg.api.integration.core.RecordKind;
import io.vorg.api.integration.core.SourceAdapter;
import io.vorg.api.integration.core.SourceBatch;
import io.vorg.api.integration.core.SourceRecord;
import io.vorg.api.ops.TaskService.NewTask;
import io.vorg.api.ops.TaskService.Place;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

class TaskApiIT extends AbstractApiIntegrationTest {

    @DynamicPropertySource
    static void operator(DynamicPropertyRegistry registry) {
        registry.add("vo.live.operator-actor-ref", DRIVER_BLR::toString);
    }

    @Autowired
    TaskService tasks;

    @Autowired
    MirrorService mirrors;

    @Autowired
    PlatformTransactionManager txManager;

    private int keySeq;

    private String key() {
        return "task-it-" + System.nanoTime() + "-" + (keySeq++);
    }

    private String wm() throws Exception {
        return token(TENANT_A_SLUG, "wm.blr@demo.invalid");
    }

    private String driver() throws Exception {
        return token(TENANT_A_SLUG, "driver.blr@demo.invalid");
    }

    /** Creates a PICK task directly (tx + tenant context, as a command handler would). */
    private UUID newTask(UUID assignee) {
        return TenantContext.callWith(TENANT_A, () -> new TransactionTemplate(txManager).execute(s -> tasks.create(
                new NewTask(TENANT_A, SITE_BLR, "PICK", 2, "Pick pallet for test", null, null, assignee, null, null,
                        new Place("Rack A-01-01", 7, 22), new Place("Staging OUT-01", 72, 74), DataMode.SIMULATED,
                        EventSource.SIMULATION, "svc:test")).orElseThrow()));
    }

    private HttpResponse<String> command(String token, UUID task, String action, String body) throws Exception {
        return post("/api/tasks/" + task + "/" + action, token, body, "Idempotency-Key", key());
    }

    private JsonNode okCommand(String token, UUID task, String action, String body) throws Exception {
        HttpResponse<String> res = command(token, task, action, body);
        assertThat(res.statusCode()).as("%s -> %s", action, res.body()).isEqualTo(200);
        return json(res);
    }

    private JsonNode task(UUID id, String token) throws Exception {
        HttpResponse<String> res = get("/api/tasks/" + id, token);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json(res);
    }

    private List<Map<String, Object>> taskEvents(UUID id) throws Exception {
        return ownerQuery("""
                select event_type, sequence, source, data_mode, actor_type, payload->>'from_status' as f,
                       payload->>'to_status' as t
                from evt.events where aggregate_id = ? and aggregate_type = 'TASK' order by sequence
                """, id);
    }

    @Test
    void managerWalksATaskThroughItsWholeLifecycleWithEvents() throws Exception {
        UUID id = newTask(DRIVER_BLR);
        String wm = wm();
        assertThat(task(id, wm).get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(task(id, wm).get("allowed_transitions").toString()).contains("ACCEPT", "CANCEL");

        assertThat(okCommand(wm, id, "accept", null).get("result").get("status").asString()).isEqualTo("ACCEPTED");
        okCommand(wm, id, "start", null);
        okCommand(wm, id, "pause", "{\"reason\":\"waiting for dock\"}");
        okCommand(wm, id, "resume", null);
        JsonNode done = okCommand(wm, id, "complete", null);
        assertThat(done.get("result").get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("result").get("changed").asBoolean()).isTrue();

        JsonNode view = task(id, wm);
        assertThat(view.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(view.get("version").asInt()).isEqualTo(6);
        assertThat(view.get("started_at").isNull()).isFalse();
        assertThat(view.get("completed_at").isNull()).isFalse();
        assertThat(view.get("allowed_transitions")).isEmpty();
        assertThat(view.get("data_mode").asString()).isEqualTo("SIMULATED");

        List<Map<String, Object>> events = taskEvents(id);
        assertThat(events).extracting(e -> e.get("event_type")).containsExactly("TASK_ASSIGNED", "TASK_ACCEPTED",
                "TASK_STARTED", "TASK_PAUSED", "TASK_RESUMED", "TASK_COMPLETED");
        assertThat(events).extracting(e -> ((Number) e.get("sequence")).longValue()).containsExactly(1L, 2L, 3L, 4L,
                5L, 6L);
        assertThat(events.get(0).get("source")).isEqualTo("SIMULATION");
        assertThat(events.get(1).get("source")).isEqualTo("API");
        assertThat(events.get(1).get("actor_type")).isEqualTo("HUMAN");
        assertThat(events.get(5).get("f")).isEqualTo("STARTED");
        assertThat(events.get(5).get("t")).isEqualTo("COMPLETED");
        assertThat(events).allSatisfy(e -> assertThat(e.get("data_mode")).isEqualTo("SIMULATED"));
    }

    @Test
    void completingIsIdempotentByKeyAndByState() throws Exception {
        UUID id = newTask(DRIVER_BLR);
        String wm = wm();
        okCommand(wm, id, "accept", null);
        okCommand(wm, id, "start", null);

        String k = key();
        HttpResponse<String> one = post("/api/tasks/" + id + "/complete", wm, null, "Idempotency-Key", k);
        HttpResponse<String> two = post("/api/tasks/" + id + "/complete", wm, null, "Idempotency-Key", k);
        assertThat(one.statusCode()).isEqualTo(200);
        assertThat(two.statusCode()).isEqualTo(200);
        assertThat(json(two).get("command_id").asString()).isEqualTo(json(one).get("command_id").asString());
        assertThat(taskEvents(id)).hasSize(4);

        // A new key for a task that is already completed is a no-op, not an error and not a second event.
        JsonNode again = okCommand(wm, id, "complete", null);
        assertThat(again.get("result").get("changed").asBoolean()).isFalse();
        assertThat(again.get("result").get("status").asString()).isEqualTo("COMPLETED");
        assertThat(taskEvents(id)).hasSize(4);
        assertThat(task(id, wm).get("version").asInt()).isEqualTo(4);

        // Same key, different request: conflict.
        assertThat(post("/api/tasks/" + id + "/complete", wm, "{\"reason\":\"other\"}", "Idempotency-Key", k)
                .statusCode()).isEqualTo(409);
        // Missing key: 400.
        assertThat(post("/api/tasks/" + id + "/complete", wm, null).statusCode()).isEqualTo(400);
    }

    @Test
    void illegalTransitionsAreConflicts() throws Exception {
        UUID id = newTask(DRIVER_BLR);
        String wm = wm();
        assertThat(command(wm, id, "complete", null).statusCode()).isEqualTo(409);
        assertThat(command(wm, id, "start", null).statusCode()).isEqualTo(409);
        okCommand(wm, id, "cancel", "{\"reason\":\"order withdrawn\"}");
        assertThat(command(wm, id, "accept", null).statusCode()).isEqualTo(409);
        assertThat(task(id, wm).get("status_reason").asString()).isEqualTo("order withdrawn");
        assertThat(taskEvents(id)).hasSize(2);
        // failing needs a reason
        UUID other = newTask(DRIVER_BLR);
        okCommand(wm, other, "accept", null);
        assertThat(command(wm, other, "fail", null).statusCode()).isEqualTo(400);
        assertThat(okCommand(wm, other, "fail", "{\"reason\":\"pallet not at location\"}").get("result")
                .get("status").asString()).isEqualTo("FAILED");
    }

    @Test
    void anAssignedDriverWorksOnlyTheirOwnTasks() throws Exception {
        UUID mine = newTask(DRIVER_BLR);
        UUID theirs = newTask(UUID.randomUUID());
        UUID unassigned = newTask(null);
        String driver = driver();

        okCommand(driver, mine, "accept", null);
        okCommand(driver, mine, "start", null);
        okCommand(driver, mine, "complete", null);
        assertThat(task(mine, driver).get("status").asString()).isEqualTo("COMPLETED");

        assertThat(command(driver, theirs, "accept", null).statusCode()).isEqualTo(403);
        assertThat(command(driver, unassigned, "accept", null).statusCode()).isEqualTo(403);
        assertThat(get("/api/tasks/" + theirs, driver).statusCode()).isEqualTo(403);
        // Cancelling is a manager decision: a driver is denied even for their own task.
        UUID mine2 = newTask(DRIVER_BLR);
        assertThat(command(driver, mine2, "cancel", null).statusCode()).isEqualTo(403);
        // The denial was audited.
        assertThat(ownerCount("select count(*) from audit.audit_log where decision = 'DENIED' and action = "
                + "'EXECUTE_STANDARD_COMMAND' and actor_ref = ?", DRIVER_BLR.toString())).isGreaterThanOrEqualTo(3);

        // The driver's list holds exactly their own tasks.
        JsonNode list = json(get("/api/tasks?site_id=" + SITE_BLR + "&limit=500", driver));
        assertThat(list.get("items").size()).isGreaterThanOrEqualTo(2);
        for (JsonNode t : list.get("items")) {
            assertThat(t.get("assigned_actor_ref").asString()).isEqualTo(DRIVER_BLR.toString());
        }
        // The manager sees all of them.
        JsonNode all = json(get("/api/tasks?site_id=" + SITE_BLR + "&limit=500", wm()));
        Set<String> ids = new java.util.HashSet<>();
        all.get("items").forEach(t -> ids.add(t.get("id").asString()));
        assertThat(ids).contains(mine.toString(), theirs.toString(), unassigned.toString());
    }

    @Test
    void otherRolesAndTenantsCannotOperateOnTasks() throws Exception {
        UUID id = newTask(DRIVER_BLR);
        for (String user : List.of("analyst", "exec", "safety", "admin", "regional.south")) {
            String token = token(TENANT_A_SLUG, user + "@demo.invalid");
            assertThat(command(token, id, "accept", null).statusCode()).as(user).isEqualTo(403);
        }
        assertThat(command(token(TENANT_B_SLUG, "exec@demo.invalid"), id, "accept", null).statusCode())
                .isEqualTo(404);
        assertThat(get("/api/tasks/" + id, token(TENANT_B_SLUG, "exec@demo.invalid")).statusCode()).isEqualTo(404);
        assertThat(get("/api/tasks/" + id, null).statusCode()).isEqualTo(401);
        assertThat(command(null, id, "accept", null).statusCode()).isEqualTo(401);
        // Read-only roles can view.
        assertThat(get("/api/tasks/" + id, token(TENANT_A_SLUG, "analyst@demo.invalid")).statusCode()).isEqualTo(200);
        assertThat(task(id, wm()).get("status").asString()).isEqualTo("ASSIGNED");
    }

    @Test
    void reportingAnIssueRecordsAnEventButNeverClosesTheTask() throws Exception {
        UUID id = newTask(DRIVER_BLR);
        String driver = driver();
        okCommand(driver, id, "accept", null);
        okCommand(driver, id, "start", null);

        JsonNode issue = okCommand(driver, id, "issues",
                "{\"issue_type\":\"damaged_goods\",\"description\":\"Pallet wrap torn, load shifting\"}");
        assertThat(issue.get("result").get("task_status").asString()).isEqualTo("STARTED");
        JsonNode view = task(id, driver);
        assertThat(view.get("status").asString()).isEqualTo("STARTED");
        assertThat(view.get("issues")).hasSize(1);
        assertThat(view.get("issues").get(0).get("issue_type").asString()).isEqualTo("DAMAGED_GOODS");
        assertThat(view.get("issues").get(0).get("reported_by").asString()).isEqualTo(DRIVER_BLR.toString());

        List<Map<String, Object>> events = taskEvents(id);
        assertThat(events.get(events.size() - 1).get("event_type")).isEqualTo("TASK_ISSUE_REPORTED");
        // The task can still be completed afterwards.
        okCommand(driver, id, "complete", null);
        // But a closed task takes no more issues.
        assertThat(command(driver, id, "issues", "{\"issue_type\":\"OTHER\",\"description\":\"late\"}").statusCode())
                .isEqualTo(409);
        assertThat(command(driver, newTask(DRIVER_BLR), "issues", "{\"issue_type\":\"NOPE\",\"description\":\"x\"}")
                .statusCode()).isEqualTo(400);
        assertThat(command(driver, newTask(DRIVER_BLR), "issues", "{\"issue_type\":\"BLOCKED\",\"description\":\" \"}")
                .statusCode()).isEqualTo(400);
        // A different driver-less caller (analyst) cannot report.
        assertThat(command(token(TENANT_A_SLUG, "analyst@demo.invalid"), newTask(DRIVER_BLR), "issues",
                "{\"issue_type\":\"BLOCKED\",\"description\":\"x\"}").statusCode()).isEqualTo(403);
    }

    @Test
    void listFiltersByStatusTypeAndPaginates() throws Exception {
        UUID a = newTask(DRIVER_BLR);
        UUID b = newTask(DRIVER_BLR);
        String wm = wm();
        okCommand(wm, a, "accept", null);
        JsonNode accepted = json(get("/api/tasks?site_id=" + SITE_BLR + "&status=accepted&limit=500", wm));
        Set<String> ids = new java.util.HashSet<>();
        accepted.get("items").forEach(t -> ids.add(t.get("id").asString()));
        assertThat(ids).contains(a.toString()).doesNotContain(b.toString());
        assertThat(get("/api/tasks?site_id=" + SITE_BLR + "&status=BOGUS", wm).statusCode()).isEqualTo(400);
        assertThat(get("/api/tasks?site_id=" + SITE_BLR + "&type=BOGUS", wm).statusCode()).isEqualTo(400);

        JsonNode p1 = json(get("/api/tasks?site_id=" + SITE_BLR + "&limit=1", wm));
        assertThat(p1.get("items")).hasSize(1);
        JsonNode p2 = json(get("/api/tasks?site_id=" + SITE_BLR + "&limit=1&cursor=" + p1.get("next_cursor").asString(),
                wm));
        assertThat(p2.get("items").get(0).get("id").asString()).isNotEqualTo(p1.get("items").get(0).get("id").asString());
        assertThat(get("/api/tasks?site_id=" + SITE_BLR + "&cursor=@@", wm).statusCode()).isEqualTo(400);
    }

    @Test
    void sourceTasksFromTheMirrorBecomeAssignedTasksExactlyOnce() throws Exception {
        String system = "T-" + UUID.randomUUID().toString().substring(0, 8);
        SourceAdapter adapter = new SourceAdapter() {
            @Override
            public String sourceSystem() {
                return system;
            }

            @Override
            public DataMode dataMode() {
                return DataMode.SIMULATED;
            }

            @Override
            public Set<RecordKind> kinds() {
                return Set.of(RecordKind.TASK);
            }

            @Override
            public SourceBatch fetch(AdapterContext context) {
                throw new UnsupportedOperationException();
            }
        };
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_type", "PUTAWAY");
        payload.put("priority", 1);
        payload.put("from", Map.of("label", "Staging IN-01", "x", 12.0, "y", 6.0));
        payload.put("to", Map.of("label", "Rack A-02-02", "x", 19.0, "y", 28.0));
        payload.put("assignee_entity_code", "FL-07");
        payload.put("pallet_code", "PAL-0100");
        payload.put("instruction", "Put away pallet from IN-01 to A-02-02");
        SourceRecord record = new SourceRecord(RecordKind.TASK, "TASK-X1", 1, Instant.now(), payload);
        var batch = new SourceBatch(system, 0, List.of(record));
        mirrors.ingest(TENANT_A, SITE_BLR, adapter, batch);
        mirrors.ingest(TENANT_A, SITE_BLR, adapter, batch); // re-delivery

        assertThat(ownerCount("select count(*) from ops.tasks where source_system = ?", system)).isEqualTo(1);
        Map<String, Object> row = ownerQuery("""
                select t.id, t.status, t.task_type, t.assigned_actor_ref, e.code, p.code as pcode, t.data_mode
                from ops.tasks t join world.entities e on e.id = t.assigned_entity_id
                join world.entities p on p.id = t.pallet_entity_id where t.source_system = ?
                """, system).get(0);
        assertThat(row.get("status")).isEqualTo("ASSIGNED");
        assertThat(row.get("code")).isEqualTo("FL-07");
        assertThat(row.get("pcode")).isEqualTo("PAL-0100");
        assertThat(row.get("data_mode")).isEqualTo("SIMULATED");
        assertThat(row.get("assigned_actor_ref").toString()).isEqualTo(DRIVER_BLR.toString());
        JsonNode view = task((UUID) row.get("id"), wm());
        assertThat(view.get("source_system").asString()).isEqualTo(system);
        assertThat(view.get("assigned_entity_code").asString()).isEqualTo("FL-07");
        assertThat(view.get("from").get("label").asString()).isEqualTo("Staging IN-01");
        List<Map<String, Object>> events = taskEvents((UUID) row.get("id"));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("event_type")).isEqualTo("TASK_ASSIGNED");
        assertThat(events.get(0).get("source")).isEqualTo("SIMULATION");
    }
}
