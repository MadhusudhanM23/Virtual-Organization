package io.vorg.api.platform.command;

import static io.vorg.api.platform.identity.seed.DemoIds.ADMIN_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.ResourceKind;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.events.DomainEvents;
import io.vorg.api.platform.events.EventDraft;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventSchemaValidator;
import io.vorg.api.platform.events.EventStore;
import io.vorg.api.platform.events.EnvelopeCodec;
import io.vorg.api.platform.events.outbox.OutboxPoller;
import io.vorg.api.platform.identity.Role;
import io.vorg.api.platform.identity.RoleAssignment;
import io.vorg.api.platform.identity.ScopeType;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class CommandGatewayIT extends AbstractApiIntegrationTest {

    @Autowired
    CommandGateway gateway;

    @Autowired
    DomainEvents events;

    @Autowired
    OutboxPoller poller;

    @Autowired
    EventStore store;

    @Autowired
    EnvelopeCodec codec;

    @Autowired
    EventSchemaValidator validator;

    private String admin;
    private UUID site;
    private String path;

    @BeforeEach
    void login() throws Exception {
        admin = token(TENANT_A_SLUG, "admin@demo.invalid");
        // A site of this test's own, so commands never alter the seeded sites other tests assert on.
        site = UUID.randomUUID();
        path = "/api/sites/" + site + "/settings";
                try (java.sql.Connection owner = POSTGRES.createConnection("");
                java.sql.PreparedStatement ps = owner.prepareStatement(
                        "insert into world.sites (id, tenant_id, region_id, name, time_zone, locale, currency, unit_system)"
                                + " values (?, ?, ?, 'Gateway IT Site', 'Asia/Kolkata', 'en-IN', 'INR', 'METRIC')")) {
            ps.setObject(1, site);
            ps.setObject(2, TENANT_A);
            ps.setObject(3, io.vorg.api.platform.identity.seed.DemoIds.REGION_SOUTH);
            ps.executeUpdate();
        }
    }

    @org.junit.jupiter.api.AfterEach
    void removeTestSite() throws Exception {
        // The database is shared by every IT in the JVM: leave the seeded hierarchy exactly as it was found.
        try (java.sql.Connection owner = POSTGRES.createConnection("");
                java.sql.PreparedStatement ps = owner.prepareStatement("delete from world.sites where id = ?")) {
            ps.setObject(1, site);
            ps.executeUpdate();
        }
    }

    private static String key() {
        return "it-" + UUID.randomUUID();
    }

    private long siteEvents() throws Exception {
        return ownerCount("select count(*) from evt.events where aggregate_id = ?", site);
    }

    @Test
    void successPathWritesStateEventOutboxAuditAndAcknowledges() throws Exception {
        String key = key();
        long eventsBefore = siteEvents();
        HttpResponse<String> res = post(path, admin, "{\"locale\":\"en-GB\",\"currency\":\"GBP\"}",
                "Idempotency-Key", key, "X-Correlation-Id", "0199f3a0-0000-7000-8000-0000000000aa");

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode ack = json(res);
        assertThat(ack.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(ack.get("correlation_id").asString()).isEqualTo("0199f3a0-0000-7000-8000-0000000000aa");
        UUID commandId = UUID.fromString(ack.get("command_id").asString());
        JsonNode result = ack.get("result");
        assertThat(result.get("site_id").asString()).isEqualTo(site.toString());
        assertThat(result.get("changed_fields")).hasSize(2);
        UUID eventId = UUID.fromString(result.get("event_id").asString());

        // State.
        Map<String, Object> siteRow = ownerQuery("select locale, currency, version from world.sites where id = ?", site).get(0);
        assertThat(siteRow.get("locale")).isEqualTo("en-GB");
        assertThat(siteRow.get("currency")).isEqualTo("GBP");
        assertThat(((Number) siteRow.get("version")).intValue()).isEqualTo(result.get("version").asInt());

        // Command record.
        Map<String, Object> command = ownerQuery("select status, idempotency_key, actor_ref from cmd.commands where command_id = ?", commandId).get(0);
        assertThat(command.get("status")).isEqualTo("SUCCEEDED");
        assertThat(command.get("idempotency_key")).isEqualTo(key);
        assertThat(command.get("actor_ref")).isEqualTo(ADMIN_A.toString());

        // Event (LIVE / HUMAN, actor is the admin) and outbox row, atomically with the state.
        assertThat(siteEvents()).isEqualTo(eventsBefore + 1);
        Map<String, Object> ev = ownerQuery("select event_type, source, data_mode, actor_type, actor_id, sequence, correlation_id from evt.events where event_id = ?", eventId).get(0);
        assertThat(ev.get("event_type")).isEqualTo("SITE_SETTINGS_UPDATED");
        assertThat(ev.get("source")).isEqualTo("HUMAN");
        assertThat(ev.get("data_mode")).isEqualTo("LIVE");
        assertThat(ev.get("actor_type")).isEqualTo("HUMAN");
        assertThat(ev.get("actor_id")).isEqualTo(ADMIN_A.toString());
        assertThat(ev.get("correlation_id").toString()).isEqualTo("0199f3a0-0000-7000-8000-0000000000aa");
        assertThat(ownerCount("select count(*) from evt.outbox where event_id = ? and status = 'PENDING'", eventId)).isEqualTo(1);

        // Audit ALLOWED with the command and correlation ids.
        Map<String, Object> audit = ownerQuery("select decision, actor_ref, action, command_id, correlation_id from audit.audit_log where command_id = ?", commandId).get(0);
        assertThat(audit.get("decision")).isEqualTo("ALLOWED");
        assertThat(audit.get("actor_ref")).isEqualTo(ADMIN_A.toString());
        assertThat(audit.get("action")).isEqualTo("MANAGE_PLATFORM_CONFIGURATION");
        assertThat(audit.get("correlation_id").toString()).isEqualTo("0199f3a0-0000-7000-8000-0000000000aa");

        // The recorded envelope is valid against the shared schema and the payload carries the change.
        EventEnvelope envelope = TenantContext.callWith(TENANT_A, () -> store.find(eventId).orElseThrow());
        assertThat(validator.validate(codec.toNode(envelope))).isEmpty();
        assertThat(envelope.payload().get("new_values").get("locale").asString()).isEqualTo("en-GB");

        // ... and the outbox delivers it to the demo handler exactly once.
        while (poller.pollOnce() > 0) {
            // drain
        }
        assertThat(ownerCount("select count(*) from evt.outbox where event_id = ? and status = 'PUBLISHED'", eventId)).isEqualTo(1);
        assertThat(ownerCount("select count(*) from evt.processed_events where event_id = ? and consumer = 'world.site-settings-projection'", eventId)).isEqualTo(1);
    }

    @Test
    void missingIdempotencyKeyIs400ProblemAndNothingIsStored() throws Exception {
        long commandsBefore = ownerCount("select count(*) from cmd.commands");
        HttpResponse<String> res = post(path, admin, "{\"locale\":\"en-IN\"}");
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode problem = json(res);
        assertThat(problem.get("title").asString()).isEqualTo("Idempotency-Key required");
        assertThat(problem.get("status").asInt()).isEqualTo(400);
        assertThat(problem.hasNonNull("correlation_id")).isTrue();
        assertThat(ownerCount("select count(*) from cmd.commands")).isEqualTo(commandsBefore);
    }

    @Test
    void replayWithTheSameKeyReturnsTheSameCommandAndNoSecondEvent() throws Exception {
        String key = key();
        String body = "{\"time_zone\":\"Asia/Dubai\"}";
        HttpResponse<String> first = post(path, admin, body, "Idempotency-Key", key);
        long afterFirst = siteEvents();
        HttpResponse<String> second = post(path, admin, body, "Idempotency-Key", key);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json(second).get("command_id").asString()).isEqualTo(json(first).get("command_id").asString());
        assertThat(json(second).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(json(second).get("result").get("event_id").asString())
                .isEqualTo(json(first).get("result").get("event_id").asString());
        assertThat(siteEvents()).isEqualTo(afterFirst);
        assertThat(ownerCount("select count(*) from cmd.commands where idempotency_key = ?", key)).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentPayloadIs409() throws Exception {
        String key = key();
        assertThat(post(path, admin, "{\"currency\":\"EUR\"}", "Idempotency-Key", key).statusCode()).isEqualTo(200);
        long events = siteEvents();
        HttpResponse<String> conflict = post(path, admin, "{\"currency\":\"USD\"}", "Idempotency-Key", key);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(json(conflict).get("title").asString()).isEqualTo("Idempotency-Key conflict");
        assertThat(siteEvents()).isEqualTo(events);
    }

    @Test
    void payloadKeyOrderDoesNotMatterForIdempotency() throws Exception {
        String key = key();
        HttpResponse<String> one = post(path, admin, "{\"locale\":\"en-IN\",\"currency\":\"INR\"}", "Idempotency-Key", key);
        HttpResponse<String> two = post(path, admin, "{\"currency\":\"INR\",\"locale\":\"en-IN\"}", "Idempotency-Key", key);
        assertThat(json(two).get("command_id").asString()).isEqualTo(json(one).get("command_id").asString());
    }

    @Test
    void executiveIsForbiddenAndTheDenialIsRejectedAndAudited() throws Exception {
        String exec = token(TENANT_A_SLUG, "exec@demo.invalid");
        String key = key();
        long events = siteEvents();
        String correlation = "0199f3a0-0000-7000-8000-0000000000bb";
        HttpResponse<String> res = post(path, exec, "{\"locale\":\"fr-FR\"}", "Idempotency-Key", key,
                "X-Correlation-Id", correlation);

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(json(res).get("correlation_id").asString()).isEqualTo(correlation);
        assertThat(siteEvents()).isEqualTo(events);
        assertThat(ownerQuery("select locale from world.sites where id = ?", site).get(0).get("locale")).isNotEqualTo("fr-FR");

        Map<String, Object> command = ownerQuery("select command_id, status from cmd.commands where idempotency_key = ?", key).get(0);
        assertThat(command.get("status")).isEqualTo("REJECTED");
        Map<String, Object> audit = ownerQuery("select decision, actor_ref, correlation_id, reason from audit.audit_log where command_id = ?", command.get("command_id")).get(0);
        assertThat(audit.get("decision")).isEqualTo("DENIED");
        assertThat(audit.get("actor_ref")).isEqualTo(io.vorg.api.platform.identity.seed.DemoIds.EXEC_A.toString());
        assertThat(audit.get("correlation_id").toString()).isEqualTo(correlation);

        // Replaying the rejected key is rejected again, not turned into a success.
        assertThat(post(path, exec, "{\"locale\":\"fr-FR\"}", "Idempotency-Key", key).statusCode()).isEqualTo(403);
    }

    @Test
    void handlerExceptionIsRecordedAsFailedAndNoEventIsPersisted() throws Exception {
        Subject adminSubject = new Subject(ADMIN_A, TENANT_A, "Demo Platform Administrator",
                List.of(new RoleAssignment(Role.PLATFORM_ADMIN, ScopeType.ORG, null)));
        UUID aggregate = UUID.randomUUID();
        String key = key();
        CommandSpec spec = new CommandSpec("TEST_FAILING_COMMAND", key, adminSubject,
                Action.MANAGE_PLATFORM_CONFIGURATION,
                new ResourceRef(ResourceKind.PLATFORM_CONFIG, TENANT_A, site, null), PolicyContext.none(),
                Map.of("n", 1));

        assertThatThrownBy(() -> gateway.execute(spec, () -> {
            events.record(EventDraft.of("SITE", aggregate, "SITE_SETTINGS_UPDATED",
                    Map.of("changed_fields", List.of("locale"), "new_values", Map.of("locale", "xx"))));
            throw new IllegalStateException("handler blew up");
        })).isInstanceOf(IllegalStateException.class).hasMessage("handler blew up");

        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ?", aggregate)).isZero();
        assertThat(ownerCount("select count(*) from evt.aggregate_sequences where aggregate_id = ?", aggregate)).isZero();
        Map<String, Object> command = ownerQuery("select command_id, status, result::text as result from cmd.commands where idempotency_key = ?", key).get(0);
        assertThat(command.get("status")).isEqualTo("FAILED");
        assertThat(command.get("result").toString()).contains("IllegalStateException");
        assertThat(ownerCount("select count(*) from audit.audit_log where command_id = ? and decision = 'ALLOWED'", command.get("command_id"))).isEqualTo(1);

        // The key stays bound to the outcome: a replay returns the stored FAILED ack without re-running the handler.
        CommandAck replay = gateway.execute(spec, () -> {
            throw new AssertionError("handler must not run again");
        });
        assertThat(replay.status()).isEqualTo("FAILED");
        assertThat(replay.commandId()).isEqualTo(command.get("command_id"));
    }

    @Test
    void invalidValuesAreRejectedWithFieldErrors() throws Exception {
        HttpResponse<String> res = post(path, admin,
                "{\"time_zone\":\"Mars/Olympus\",\"locale\":\"en_IN\",\"currency\":\"ZZZ1\",\"unit_system\":\"FURLONGS\"}",
                "Idempotency-Key", key());
        assertThat(res.statusCode()).isEqualTo(400);
        JsonNode errors = json(res).get("errors");
        assertThat(errors).hasSize(4);
        assertThat(post(path, admin, "{}", "Idempotency-Key", key()).statusCode()).isEqualTo(400);
    }

    @Test
    void unknownSiteIsDeniedWithoutLeakingExistence() throws Exception {
        HttpResponse<String> res = post("/api/sites/" + UUID.randomUUID() + "/settings", admin,
                "{\"locale\":\"en-IN\"}", "Idempotency-Key", key());
        assertThat(res.statusCode()).isEqualTo(403);
    }

    @Test
    void ackAndProblemBodiesFollowTheSharedContracts() throws Exception {
        JsonNode ack = json(post(path, admin, "{\"unit_system\":\"IMPERIAL\"}", "Idempotency-Key", key()));
        assertThat(ack.propertyNames()).isSubsetOf("command_id", "status", "correlation_id", "result");
        assertThat(UUID.fromString(ack.get("command_id").asString())).isNotNull();
    }

    @Test
    void unauthenticatedRequestIs401() throws Exception {
        assertThat(post(path, null, "{\"locale\":\"en-IN\"}", "Idempotency-Key", key()).statusCode()).isEqualTo(401);
    }

    @Test
    void ackLatencyHistogramIsExposed() throws Exception {
        post(path, admin, "{\"unit_system\":\"METRIC\"}", "Idempotency-Key", key());
        java.net.http.HttpResponse<String> metrics = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(metrics.body()).contains("vo_command_ack_seconds_bucket");
    }
}
