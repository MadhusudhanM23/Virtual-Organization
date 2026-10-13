package io.vorg.api.platform.events;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_MUM;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractLiveWorldIT;
import io.vorg.api.twin.ObservationIngestService;
import io.vorg.api.twin.ObservationIngestService.IngestSource;
import io.vorg.api.twin.ObservationIngestService.Observation;
import io.vorg.api.world.seed.BengaluruWarehouse01;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class EventsApiIT extends AbstractLiveWorldIT {

    private static final IngestSource SRC = new IngestSource("SIM-MOVEMENT", EventSource.SIMULATION,
            DataMode.SIMULATED, ActorType.SERVICE, "svc:movement-source");

    @Autowired
    ObservationIngestService ingest;

    private static UUID entity(String code) {
        return BengaluruWarehouse01.build(TENANT_A, SITE_BLR).entities().stream()
                .filter(e -> e.code().equals(code)).findFirst().orElseThrow().id();
    }

    private String analyst() throws Exception {
        return token(TENANT_A_SLUG, "analyst@demo.invalid");
    }

    private JsonNode ok(String path, String token) throws Exception {
        HttpResponse<String> res = get(path, token);
        assertThat(res.statusCode()).as("GET %s -> %s", path, res.body()).isEqualTo(200);
        return json(res);
    }

    /** Ingests n observations of one forklift, one second apart, ending now; returns the occurred_at values. */
    private List<Instant> ingestTrack(UUID entity, String prefix, int n) {
        Instant end = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<Instant> times = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Instant t = end.minusSeconds(n - 1 - i);
            times.add(t);
            ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(new Observation(prefix + i, entity, t, 10 + i, 37, 0, 0,
                    1.5, "MOVING", 1.0)));
        }
        return times;
    }

    private static String q(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Test
    void itemsAreEnvelopesWithProvenanceNewestFirst() throws Exception {
        UUID fl = entity("FL-08");
        List<Instant> times = ingestTrack(fl, "ev-a-", 5);
        JsonNode page = ok("/api/events?site_id=" + SITE_BLR + "&aggregate_id=" + fl
                + "&event_type=FORKLIFT_POSITION_UPDATED&limit=500", analyst());
        JsonNode items = page.get("items");
        List<JsonNode> mine = new ArrayList<>();
        items.forEach(e -> {
            if (e.get("payload").get("observation_id").asString().startsWith("ev-a-")) {
                mine.add(e);
            }
        });
        assertThat(mine).hasSize(5);
        // newest first
        for (int i = 0; i + 1 < mine.size(); i++) {
            assertThat(Instant.parse(mine.get(i).get("occurred_at").asString()))
                    .isAfter(Instant.parse(mine.get(i + 1).get("occurred_at").asString()));
        }
        assertThat(Instant.parse(mine.get(0).get("occurred_at").asString())).isEqualTo(times.get(4));
        JsonNode e = mine.get(0);
        assertThat(e.get("event_id").asString()).isNotBlank();
        assertThat(e.get("tenant_id").asString()).isEqualTo(TENANT_A.toString());
        assertThat(e.get("aggregate_type").asString()).isEqualTo("FORKLIFT");
        assertThat(e.get("aggregate_id").asString()).isEqualTo(fl.toString());
        assertThat(e.get("event_type").asString()).isEqualTo("FORKLIFT_POSITION_UPDATED");
        assertThat(e.get("source").asString()).isEqualTo("SIMULATION");
        assertThat(e.get("data_mode").asString()).isEqualTo("SIMULATED");
        assertThat(e.get("actor_type").asString()).isEqualTo("SERVICE");
        assertThat(e.get("actor_id").asString()).isEqualTo("svc:movement-source");
        assertThat(e.get("sequence").asLong()).isGreaterThan(mine.get(1).get("sequence").asLong());
        assertThat(e.get("ingested_at").asString()).isNotBlank();
        assertThat(e.get("correlation_id").asString()).isNotBlank();
        assertThat(e.get("schema_version").asInt()).isEqualTo(1);
        assertThat(e.get("location").get("site_id").asString()).isEqualTo(SITE_BLR.toString());
        assertThat(e.get("location").get("position").get("x").asDouble()).isEqualTo(14.0);
        assertThat(e.get("location").get("heading_deg").asDouble()).isZero();
    }

    @Test
    void filtersNarrowByTypeAggregateModeSourceAndTime() throws Exception {
        UUID fl = entity("FL-09");
        UUID w = entity("W-010");
        List<Instant> times = ingestTrack(fl, "ev-b-", 6);
        ingestTrack(w, "ev-bw-", 3);
        String base = "/api/events?site_id=" + SITE_BLR + "&limit=500";

        JsonNode byAggregate = ok(base + "&aggregate_id=" + fl, analyst());
        assertThat(byAggregate.get("items")).isNotEmpty();
        byAggregate.get("items").forEach(e -> assertThat(e.get("aggregate_id").asString()).isEqualTo(fl.toString()));

        JsonNode workers = ok(base + "&event_type=worker_position_updated", analyst());
        assertThat(workers.get("items")).isNotEmpty();
        workers.get("items").forEach(e -> assertThat(e.get("event_type").asString())
                .isEqualTo("WORKER_POSITION_UPDATED"));
        JsonNode both = ok(base + "&aggregate_type=FORKLIFT&event_type=" + q("FORKLIFT_POSITION_UPDATED,WORKER_POSITION_UPDATED"),
                analyst());
        both.get("items").forEach(e -> assertThat(e.get("aggregate_type").asString()).isEqualTo("FORKLIFT"));

        // data mode: the track is SIMULATED, so a LIVE-only query has none of its events.
        JsonNode live = ok(base + "&aggregate_id=" + fl + "&data_mode=LIVE", analyst());
        assertThat(live.get("items")).isEmpty();
        JsonNode simulated = ok(base + "&aggregate_id=" + fl + "&data_mode=live,simulated", analyst());
        assertThat(simulated.get("items")).isNotEmpty();
        assertThat(ok(base + "&aggregate_id=" + fl + "&source=HUMAN", analyst()).get("items")).isEmpty();
        assertThat(ok(base + "&aggregate_id=" + fl + "&source=simulation", analyst()).get("items")).isNotEmpty();

        // time window [times[2], times[4]) holds the observations 2 and 3 of this track
        JsonNode window = ok(base + "&aggregate_id=" + fl + "&from=" + q(times.get(2).toString()) + "&to="
                + q(times.get(4).toString()), analyst());
        Set<String> obs = new HashSet<>();
        window.get("items").forEach(e -> obs.add(e.get("payload").get("observation_id").asString()));
        assertThat(obs).containsExactlyInAnyOrder("ev-b-2", "ev-b-3");
    }

    @Test
    void keysetPaginationWalksEveryEventExactlyOnce() throws Exception {
        UUID fl = entity("FL-10");
        ingestTrack(fl, "ev-c-", 7);
        String base = "/api/events?site_id=" + SITE_BLR + "&aggregate_id=" + fl + "&limit=3";
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = ok(base + (cursor == null ? "" : "&cursor=" + cursor), analyst());
            page.get("items").forEach(e -> ids.add(e.get("event_id").asString()));
            cursor = page.get("next_cursor").isNull() ? null : page.get("next_cursor").asString();
            pages++;
        } while (cursor != null && pages < 50);
        assertThat(ids).doesNotHaveDuplicates();
        long total = ownerCount("select count(*) from evt.events where aggregate_id = ?", fl);
        assertThat(ids).hasSize((int) total);
        assertThat(pages).isEqualTo((int) Math.ceil(total / 3.0));
    }

    @Test
    void taskAndIntegrationEventsAreQueryableBySite() throws Exception {
        // Mirror and task events from other tests share the site; any item of these types is site-scoped.
        JsonNode mirrors = ok("/api/events?site_id=" + SITE_BLR + "&event_type=MIRROR_RECORD_UPDATED&limit=5",
                analyst());
        mirrors.get("items").forEach(e -> {
            assertThat(e.get("source").asString()).isEqualTo("SIMULATION");
            assertThat(e.get("actor_id").asString()).startsWith("svc:adapter:");
            assertThat(e.get("location").get("site_id").asString()).isEqualTo(SITE_BLR.toString());
        });
        // Another site of the same tenant has none of Bengaluru's events.
        assertThat(ok("/api/events?site_id=" + SITE_MUM + "&limit=5", token(TENANT_A_SLUG, "exec@demo.invalid"))
                .get("items")).isEmpty();
    }

    @Test
    void accessFollowsThePolicyEngineAndInputIsValidated() throws Exception {
        String path = "/api/events?site_id=" + SITE_BLR;
        assertThat(get(path, null).statusCode()).isEqualTo(401);
        assertThat(get(path, token(TENANT_A_SLUG, "driver.blr@demo.invalid")).statusCode()).isEqualTo(403);
        assertThat(get(path, token(TENANT_A_SLUG, "admin@demo.invalid")).statusCode()).isEqualTo(403);
        assertThat(get(path, token(TENANT_A_SLUG, "exec@demo.invalid")).statusCode()).isEqualTo(200);
        assertThat(get(path, token(TENANT_A_SLUG, "wm.blr@demo.invalid")).statusCode()).isEqualTo(200);
        assertThat(get(path, token(TENANT_A_SLUG, "safety@demo.invalid")).statusCode()).isEqualTo(200);
        assertThat(get(path, token(TENANT_B_SLUG, "exec@demo.invalid")).statusCode()).isEqualTo(404);
        assertThat(get("/api/events?site_id=" + UUID.randomUUID(), analyst()).statusCode()).isEqualTo(404);
        assertThat(get("/api/events", analyst()).statusCode()).isGreaterThanOrEqualTo(400);

        for (String bad : List.of("&event_type=lower%20case!", "&aggregate_id=nope", "&data_mode=SIMULATION",
                "&source=UI", "&from=yesterday", "&from=2026-10-12T10:00:00Z&to=2026-10-12T09:00:00Z", "&limit=0",
                "&limit=501", "&cursor=@@@")) {
            HttpResponse<String> res = get(path + bad, analyst());
            assertThat(res.statusCode()).as(bad).isEqualTo(400);
            assertThat(res.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        }
    }

    @Test
    void upToSixtyFourEventTypesFitInOneQuery() throws Exception {
        String path = "/api/events?site_id=" + SITE_BLR + "&limit=5&event_type=";
        List<String> types = new java.util.ArrayList<>(List.of("FORKLIFT_POSITION_UPDATED", "WORKER_POSITION_UPDATED"));
        for (int i = types.size(); i < 64; i++) {
            types.add("SOME_TYPE_" + i);
        }
        HttpResponse<String> accepted = get(path + q(String.join(",", types)), analyst());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(json(accepted).get("items")).isNotEmpty();
        types.add("ONE_TOO_MANY");
        HttpResponse<String> tooMany = get(path + q(String.join(",", types)), analyst());
        assertThat(tooMany.statusCode()).isEqualTo(400);
        assertThat(tooMany.body()).contains("at most 64 types");
    }

    @Test
    void anotherTenantNeverSeesTheseEvents() throws Exception {
        UUID fl = entity("FL-04");
        ingestTrack(fl, "ev-d-", 2);
        // Tenant B has its own site; Tenant A's site id is simply unknown to it (404) and RLS hides the rows anyway.
        assertThat(get("/api/events?site_id=" + SITE_BLR + "&aggregate_id=" + fl,
                token(TENANT_B_SLUG, "exec@demo.invalid")).statusCode()).isEqualTo(404);
    }

    @Test
    void aQueryOverManyEventsStaysWellInsideTheLatencyBudget() throws Exception {
        UUID fl = entity("FL-05");
        ingestTrack(fl, "ev-e-", 40);
        String token = analyst();
        String path = "/api/events?site_id=" + SITE_BLR + "&event_type=FORKLIFT_POSITION_UPDATED&limit=100";
        ok(path, token); // warm up
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long t0 = System.nanoTime();
            ok(path, token);
            millis.add((System.nanoTime() - t0) / 1_000_000);
        }
        millis.sort(Long::compare);
        assertThat(millis.get(18)).as("p95 of %s ms", millis).isLessThan(500L); // NFR-01
    }
}
