package io.vorg.api.platform.live;

import static io.vorg.api.platform.identity.seed.DemoIds.ANALYST_A;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_B;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_MUM;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.events.outbox.OutboxPoller;
import io.vorg.api.platform.identity.AccessTokenService;
import io.vorg.api.support.AbstractLiveWorldIT;
import io.vorg.api.twin.ObservationIngestService;
import io.vorg.api.twin.ObservationIngestService.IngestSource;
import io.vorg.api.twin.ObservationIngestService.Observation;
import io.vorg.api.world.seed.BengaluruWarehouse01;
import io.vorg.api.world.seed.WorldSeedPlan;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Handshake authentication, authorization, filtering, tenant isolation, coalescing and expiry of {@code /ws/live}. */
class LiveSocketIT extends AbstractLiveWorldIT {

    private static final IngestSource SRC = new IngestSource("SIM-MOVEMENT", EventSource.SIMULATION,
            DataMode.SIMULATED, ActorType.SERVICE, "svc:movement-source");

    @Autowired
    ObservationIngestService ingest;
    @Autowired
    OutboxPoller poller;
    @Autowired
    AccessTokenService tokens;

    /** A WebSocket client that queues every text message and remembers the close code. */
    private final class Client implements WebSocket.Listener {
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final AtomicInteger count = new AtomicInteger();
        final StringBuilder partial = new StringBuilder();
        WebSocket socket;

        Client open() {
            socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(
                    URI.create("ws://localhost:" + port + "/ws/live"), this).join();
            return this;
        }

        void send(String json) {
            socket.sendText(json, true).join();
        }

        void auth(String token) {
            send(mapper.writeValueAsString(java.util.Map.of("type", "auth", "token", token)));
        }

        void subscribe(String id, UUID site, String extra) {
            send("{\"type\":\"subscribe\",\"id\":\"" + id + "\",\"site_id\":\"" + site + "\"" + (extra == null ? ""
                    : "," + extra) + "}");
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(mapper.readTree(partial.toString()));
                partial.setLength(0);
                count.incrementAndGet();
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closed.complete(code);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed.complete(-1);
        }

        /** Next message of the given type within the timeout (other types, e.g. pings, are skipped). */
        JsonNode next(String type, long seconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline) {
                JsonNode m = messages.poll(200, TimeUnit.MILLISECONDS);
                if (m != null && type.equals(m.get("type").asString())) {
                    return m;
                }
            }
            return null;
        }

        void close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    private void drainOutbox() {
        while (poller.pollOnce() > 0) {
            // keep draining
        }
    }

    private static UUID forklift() {
        return BengaluruWarehouse01.build(TENANT_A, SITE_BLR).entities().stream()
                .filter(e -> e.code().equals("FL-08")).findFirst().orElseThrow().id();
    }

    private void move(UUID entity, double x, double y, String id) {
        ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(new Observation(id, entity, Instant.now(), x, y, 0, 90, 1.5,
                "MOVING", 1.0)));
    }

    private Client ready(String tenant, String email) throws Exception {
        Client c = new Client().open();
        c.auth(token(tenant, email));
        assertThat(c.next("ready", 5)).as("ready").isNotNull();
        return c;
    }

    @Test
    void firstMessageMustAuthenticate() throws Exception {
        Client c = new Client().open();
        c.subscribe("a", SITE_BLR, null);
        assertThat(c.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4401);
    }

    @Test
    void badOrExpiredTokenClosesWith4401() throws Exception {
        Client bad = new Client().open();
        bad.auth("not-a-jwt");
        assertThat(bad.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4401);

        String expired = tokens.issue(ANALYST_A, TENANT_A, Instant.now().minus(Duration.ofHours(1)),
                Duration.ofMinutes(15));
        Client old = new Client().open();
        old.auth(expired);
        assertThat(old.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4401);
    }

    @Test
    void connectionIsClosedWhenTheTokenExpires() throws Exception {
        String shortLived = tokens.issue(ANALYST_A, TENANT_A, Instant.now(), Duration.ofSeconds(2));
        Client c = new Client().open();
        c.auth(shortLived);
        assertThat(c.next("ready", 5)).isNotNull();
        assertThat(c.closed.get(8, TimeUnit.SECONDS)).isEqualTo(4401);
    }

    @Test
    void reauthExtendsTheSession() throws Exception {
        String shortLived = tokens.issue(ANALYST_A, TENANT_A, Instant.now(), Duration.ofSeconds(3));
        Client c = new Client().open();
        c.auth(shortLived);
        assertThat(c.next("ready", 5)).isNotNull();
        c.send(mapper.writeValueAsString(java.util.Map.of("type", "reauth", "token",
                token(TENANT_A_SLUG, "analyst@demo.invalid"))));
        assertThat(c.next("reauthenticated", 5)).isNotNull();
        Thread.sleep(3500);
        assertThat(c.closed.isDone()).isFalse();
        c.close();
    }

    @Test
    void subscribedClientReceivesCoalescedPosesAndFiltersApply() throws Exception {
        UUID fl = forklift();
        WorldSeedPlan plan = BengaluruWarehouse01.build(TENANT_A, SITE_BLR);
        UUID mezzanine = plan.floors().get(1).id();
        Client all = ready(TENANT_A_SLUG, "analyst@demo.invalid");
        all.subscribe("all", SITE_BLR, null);
        assertThat(all.next("subscribed", 5)).isNotNull();
        Client workersOnly = ready(TENANT_A_SLUG, "analyst@demo.invalid");
        workersOnly.subscribe("w", SITE_BLR, "\"entity_types\":[\"WORKER\"]");
        workersOnly.next("subscribed", 5);
        Client mezz = ready(TENANT_A_SLUG, "analyst@demo.invalid");
        mezz.subscribe("m", SITE_BLR, "\"floor_id\":\"" + mezzanine + "\"");
        mezz.next("subscribed", 5);
        Client box = ready(TENANT_A_SLUG, "analyst@demo.invalid");
        box.subscribe("b", SITE_BLR, "\"bbox\":{\"min_x\":100,\"min_y\":60,\"max_x\":119,\"max_y\":79}");
        box.next("subscribed", 5);
        Client inBox = ready(TENANT_A_SLUG, "analyst@demo.invalid");
        inBox.subscribe("i", SITE_BLR, "\"bbox\":{\"min_x\":10,\"min_y\":10,\"max_x\":20,\"max_y\":20}");
        inBox.next("subscribed", 5);

        // Two quick updates for the same entity in one flush window: the client sees only the latest.
        move(fl, 11.0, 12.0, "ls-1");
        move(fl, 15.5, 16.5, "ls-2");
        drainOutbox();

        JsonNode poses = all.next("poses", 5);
        assertThat(poses).isNotNull();
        assertThat(poses.get("sub").asString()).isEqualTo("all");
        JsonNode mine = null;
        for (JsonNode item : poses.get("items")) {
            if (item.get("entity_id").asString().equals(fl.toString())) {
                mine = item;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("x").asDouble()).isEqualTo(15.5);
        assertThat(mine.get("entity_type").asString()).isEqualTo("FORKLIFT");
        assertThat(mine.get("data_mode").asString()).isEqualTo("SIMULATED");
        assertThat(mine.get("source").asString()).isEqualTo("SIMULATION");
        assertThat(inBox.next("poses", 5)).as("pose inside bbox").isNotNull();
        assertThat(workersOnly.next("poses", 1)).as("entity type filter").isNull();
        assertThat(mezz.next("poses", 1)).as("floor filter").isNull();
        assertThat(box.next("poses", 1)).as("bbox filter").isNull();
        for (Client c : List.of(all, workersOnly, mezz, box, inBox)) {
            c.close();
        }
    }

    @Test
    void siteWithoutViewRightsIsRefusedAndTenantBNeverSeesTenantAEvents() throws Exception {
        UUID fl = forklift();
        // Tenant B user asking for tenant A's site: indistinguishable from an unknown site.
        Client b = ready(TENANT_B_SLUG, "exec@demo.invalid");
        b.subscribe("x", SITE_BLR, null);
        JsonNode err = b.next("error", 5);
        assertThat(err).isNotNull();
        assertThat(err.get("code").asString()).isEqualTo("not_found");
        // ... but it may watch its own site, and must still never receive tenant A traffic.
        b.subscribe("own", SITE_B, null);
        assertThat(b.next("subscribed", 5)).isNotNull();
        // A site-scoped manager of Bengaluru may not subscribe to Mumbai.
        Client wm = ready(TENANT_A_SLUG, "wm.blr@demo.invalid");
        wm.subscribe("mum", SITE_MUM, null);
        JsonNode denied = wm.next("error", 5);
        assertThat(denied).isNotNull();
        assertThat(denied.get("code").asString()).isEqualTo("forbidden");

        move(fl, 22.0, 23.0, "ls-iso-1");
        drainOutbox();
        Thread.sleep(600);
        assertThat(b.messages.stream().filter(m -> {
            String t = m.get("type").asString();
            return t.equals("poses") || t.equals("event");
        })).isEmpty();
        b.close();
        wm.close();
    }

    @Test
    void invalidMessagesCloseWith4400() throws Exception {
        Client c = ready(TENANT_A_SLUG, "analyst@demo.invalid");
        c.send("{not json");
        assertThat(c.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4400);
    }
}
