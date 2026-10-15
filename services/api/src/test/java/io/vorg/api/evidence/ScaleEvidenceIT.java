package io.vorg.api.evidence;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.integration.simulated.MovementSourceRunner;
import io.vorg.api.platform.events.outbox.OutboxPoller;
import io.vorg.api.support.AbstractLiveWorldIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Evidence run for REQ-150 / ADR-81 (NATS JetStream decision). NOT part of the normal build: enabled only with
 * {@code -Dvorg.evidence=true}. Measures, on one machine and one JVM (load generator and server share the CPU):
 * <ol>
 *   <li>how fast the in-process pipeline writes events (movement source -> ingest -> evt.events + outbox);</li>
 *   <li>how fast the single-process outbox poller drains and projects them with 0 live sockets;</li>
 *   <li>the same drain with M real WebSocket clients subscribed to the site (live fan-out), for increasing M.</li>
 * </ol>
 * Results go to {@code target/evidence/scale.json}; docs/reports/scale-evidence.md cites them. Reproduce with
 * {@code scripts/mvnw.ps1 -B -pl services/api -am verify -Dit.test=ScaleEvidenceIT -Dvorg.evidence=true}.
 */
@EnabledIfSystemProperty(named = "vorg.evidence", matches = "true")
class ScaleEvidenceIT extends AbstractLiveWorldIT {

    @DynamicPropertySource
    static void live(DynamicPropertyRegistry registry) {
        registry.add("vo.live.tenant-id", TENANT_A::toString);
        registry.add("vo.live.site-id", SITE_BLR::toString);
        registry.add("vo.live.enabled", () -> "false");
    }

    @Autowired
    MovementSourceRunner runner;
    @Autowired
    OutboxPoller poller;

    private static final int TICKS = 600;

    private static final class Sock implements WebSocket.Listener {
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final AtomicInteger frames = new AtomicInteger();
        final AtomicLong bytes = new AtomicLong();
        final AtomicInteger gaps = new AtomicInteger();
        final StringBuilder partial = new StringBuilder();
        WebSocket ws;

        @Override
        public void onOpen(WebSocket w) {
            w.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String s = partial.toString();
                partial.setLength(0);
                if (s.contains("\"type\":\"ready\"")) {
                    ready.complete(null);
                } else if (s.contains("\"type\":\"ping\"")) {
                    w.sendText("{\"type\":\"pong\"}", true);
                } else if (s.contains("\"type\":\"poses\"") || s.contains("\"type\":\"event\"")) {
                    frames.incrementAndGet();
                    bytes.addAndGet(s.length());
                } else if (s.contains("gap")) {
                    gaps.incrementAndGet();
                }
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            closed.complete(code);
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable e) {
            closed.complete(-1);
        }
    }

    private long drain() {
        long total = 0;
        int n;
        while ((n = poller.pollOnce()) > 0) {
            total += n;
        }
        return total;
    }

    @Test
    void measure() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("machine", "local Windows 11 dev machine (Intel i3), JDK " + System.getProperty("java.version")
                + ", Testcontainers Postgres, one JVM for server and load generator");
        out.put("outbox_batch_size", 50);
        out.put("ticks_per_run", TICKS);
        runner.reset();
        runner.configure(1000L, 1.0);
        drain(); // clear anything left by earlier tests

        // 1 + 2: write rate and drain rate with no sockets
        List<Object> runs = new ArrayList<>();
        for (int rep = 0; rep < 3; rep++) {
            long emittedBefore = runner.status().observationsEmitted();
            long t0 = System.nanoTime();
            runner.step(TICKS);
            double writeSec = (System.nanoTime() - t0) / 1e9;
            long emitted = runner.status().observationsEmitted() - emittedBefore;
            long t1 = System.nanoTime();
            long published = drain();
            double drainSec = (System.nanoTime() - t1) / 1e9;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("run", rep + 1);
            r.put("events_written", emitted);
            r.put("write_seconds", round(writeSec));
            r.put("write_events_per_s", round(emitted / writeSec));
            r.put("outbox_rows_drained", published);
            r.put("drain_seconds", round(drainSec));
            r.put("drain_rows_per_s", round(published / drainSec));
            runs.add(r);
        }
        out.put("no_sockets", runs);

        // 3: drain with M sockets
        String token = token(TENANT_A_SLUG, "wm.blr@demo.invalid");
        HttpClient http = HttpClient.newHttpClient();
        List<Object> fan = new ArrayList<>();
        for (int m : new int[] {1, 10, 50, 100, 200, 400}) {
            List<Sock> socks = new ArrayList<>();
            int connectFailures = 0;
            for (int i = 0; i < m; i++) {
                Sock s = new Sock();
                try {
                    s.ws = http.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + port + "/ws/live"), s)
                            .get(10, TimeUnit.SECONDS);
                    s.ws.sendText("{\"type\":\"auth\",\"token\":\"" + token + "\"}", true).join();
                    s.ready.get(10, TimeUnit.SECONDS);
                    s.ws.sendText("{\"type\":\"subscribe\",\"id\":\"ev\",\"site_id\":\"" + SITE_BLR
                            + "\",\"poses\":true,\"events\":true}", true).join();
                    socks.add(s);
                } catch (Exception e) {
                    connectFailures++;
                }
            }
            Thread.sleep(500);
            long emittedBefore = runner.status().observationsEmitted();
            runner.step(TICKS);
            long emitted = runner.status().observationsEmitted() - emittedBefore;
            long t1 = System.nanoTime();
            long published = drain();
            double drainSec = (System.nanoTime() - t1) / 1e9;
            Thread.sleep(1500); // let the 100 ms flusher write the last frames
            int open = 0;
            int slowClosed = 0;
            long frames = 0;
            long bytes = 0;
            int gaps = 0;
            for (Sock s : socks) {
                if (s.closed.isDone()) {
                    if (s.closed.get() == 4429) {
                        slowClosed++;
                    }
                } else {
                    open++;
                }
                frames += s.frames.get();
                bytes += s.bytes.get();
                gaps += s.gaps.get();
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("sockets_requested", m);
            r.put("sockets_connected", socks.size());
            r.put("connect_failures", connectFailures);
            r.put("events_written", emitted);
            r.put("outbox_rows_drained", published);
            r.put("drain_seconds", round(drainSec));
            r.put("drain_rows_per_s", round(published / drainSec));
            r.put("frames_received_total", frames);
            r.put("frames_per_socket_mean", socks.isEmpty() ? 0 : round((double) frames / socks.size()));
            r.put("megabytes_received_total", round(bytes / 1e6));
            r.put("gap_messages", gaps);
            r.put("sockets_still_open", open);
            r.put("sockets_closed_as_slow_4429", slowClosed);
            fan.add(r);
            for (Sock s : socks) {
                if (!s.closed.isDone()) {
                    s.ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                }
            }
            Thread.sleep(500);
            if (connectFailures > m / 2) {
                break;
            }
        }
        out.put("with_sockets", fan);
        runner.reset();

        Path dir = Path.of(System.getProperty("vorg.evidence.out", "target/evidence"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("scale.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        assertThat(out).isNotEmpty();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
