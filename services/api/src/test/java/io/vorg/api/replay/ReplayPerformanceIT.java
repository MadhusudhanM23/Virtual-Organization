package io.vorg.api.replay;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.twin.ObservationIngestService.Observation;
import io.vorg.api.world.seed.WorldSeedPlan;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * NFR-05 (replay navigation within 3 s at MCD scale), measured on the API: a snapshot followed by a full snapshot
 * interval's worth of position events (ADR-23 bounds the live loop at about 10 events/s, i.e. about 3 000 per 5 min;
 * this test uses more), then the time to reconstruct the state at the end and to fetch playback frames over the window.
 */
class ReplayPerformanceIT extends AbstractReplayIT {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayPerformanceIT.class);
    private static final long BUDGET_MS = 3000;

    @Test
    void reconstructionAfterAFullSnapshotIntervalIsWithinTheNavigationBudget() throws Exception {
        String wm = token(TENANT_A_SLUG, "wm.blr@demo.invalid");
        snapshot();
        List<WorldSeedPlan.Entity> movers = new ArrayList<>(seedOfType("FORKLIFT"));
        movers.addAll(seedOfType("TRUCK"));
        int rounds = Math.max(1, 3600 / movers.size() + 1);
        Instant t0 = now().plusMillis(20);
        int events = 0;
        long ingestStart = System.nanoTime();
        for (int r = 0; r < rounds; r++) {
            Instant at = t0.plusMillis(r);
            List<Observation> batch = new ArrayList<>();
            for (WorldSeedPlan.Entity e : movers) {
                double dx = 0.3 * Math.sin(r / 10.0);
                batch.add(new Observation("perf-" + r + "-" + e.code() + "-" + System.nanoTime(), e.id(), at,
                        e.x().doubleValue() + dx, e.y().doubleValue(), 0, e.headingDeg().doubleValue(), 1.0,
                        "MOVING", 1.0));
            }
            events += ingest.ingest(TENANT_A, SITE_BLR, SRC, batch).accepted();
        }
        long ingestMs = (System.nanoTime() - ingestStart) / 1_000_000;
        drain();
        waitPast(t0.plusMillis(rounds));
        Instant t = now();

        long worst = 0;
        JsonNode state = null;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            HttpResponse<String> res = stateRaw(wm, t);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
            state = json(res);
            worst = Math.max(worst, ms);
        }
        long framesStart = System.nanoTime();
        HttpResponse<String> frames = get("/api/replay/frames?site_id=" + SITE_BLR + "&from=" + t0.minusMillis(1)
                + "&to=" + t + "&step_ms=100", wm);
        long framesMs = (System.nanoTime() - framesStart) / 1_000_000;
        assertThat(frames.statusCode()).as(frames.body()).isEqualTo(200);
        int applied = state.get("reconstructed_from").get("events_applied").asInt();
        LOG.info("NFR-05 replay: {} position events ({} movers x {} rounds, ingest {} ms); GET /api/replay/state "
                + "worst of 3 = {} ms (events_read={}, events_applied={}); GET /api/replay/frames = {} ms "
                + "(frames={}, events_applied={})", events, movers.size(), rounds, ingestMs, worst,
                state.get("reconstructed_from").get("events_read").asInt(), applied, framesMs,
                json(frames).get("frame_count").asInt(), json(frames).get("events_applied").asInt());
        assertThat(events).isGreaterThanOrEqualTo(3600);
        assertThat(applied).isGreaterThanOrEqualTo(movers.size());
        assertThat(worst).as("state reconstruction ms").isLessThan(BUDGET_MS);
        assertThat(framesMs).as("frames ms").isLessThan(BUDGET_MS);
    }
}
