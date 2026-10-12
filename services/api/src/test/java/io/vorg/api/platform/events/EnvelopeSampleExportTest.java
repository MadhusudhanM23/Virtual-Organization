package io.vorg.api.platform.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Writes representative envelopes produced by the real Java serializer ({@link EnvelopeFactory} +
 * {@link EnvelopeCodec}) to {@code tests/contract/envelope-samples/}, with deterministic ids and timestamps, and checks
 * each against the shared JSON Schema. The TypeScript contract test validates the same files with Ajv, so a Java-side
 * shape change fails the TypeScript build and vice versa. Regenerate by running this test (see tests/contract/README.md);
 * the output is byte-stable, so a clean {@code git diff} means the Java serializer still produces the committed shape.
 */
class EnvelopeSampleExportTest {

    private static final UUID TENANT = UUID.fromString("0199f3a0-0000-7000-8000-000000000001");
    private static final UUID SITE = UUID.fromString("0199f3a0-0000-7000-8000-0000000000a1");
    private static final UUID FORKLIFT = UUID.fromString("0199f3a0-0000-7000-8000-0000000000f1");
    private static final UUID INCIDENT = UUID.fromString("0199f3a0-0000-7000-8000-0000000000e1");
    private static final UUID CORRELATION = UUID.fromString("0199f3a0-0000-7000-8000-0000000000c4");
    private static final Instant NOW = Instant.parse("2026-10-09T09:00:00.010Z");

    private final EnvelopeCodec codec = new EnvelopeCodec();
    private final EventSchemaValidator validator = new EventSchemaValidator();
    private final AtomicInteger ids = new AtomicInteger();
    private final EnvelopeFactory factory = new EnvelopeFactory(Clock.fixed(NOW, ZoneOffset.UTC),
            () -> new UUID(0x0199f3a000007000L, 0x8000000000000100L + ids.incrementAndGet()), codec);

    private static Path samplesDir() {
        // Surefire runs with the module directory (services/api) as the working directory.
        Path dir = Path.of("..", "..", "tests", "contract", "envelope-samples").toAbsolutePath().normalize();
        // dir = <repo>/tests/contract/envelope-samples; the repo root must hold packages/contracts.
        assertThat(dir.getParent().getParent().getParent().resolve("packages").resolve("contracts")).exists();
        return dir;
    }

    @Test
    void exportsSchemaValidSamplesDeterministically() throws IOException {
        Map<String, Object> settingsPayload = new LinkedHashMap<>();
        settingsPayload.put("changed_fields", List.of("locale", "currency"));
        settingsPayload.put("new_values", Map.of("locale", "en-GB", "currency", "GBP"));

        Map<String, EventEnvelope> samples = new LinkedHashMap<>();

        // Human configuration change, sequenced, LIVE, with a site location.
        samples.put("site-settings-updated.json", factory.create(
                EventDraft.of("SITE", SITE, "SITE_SETTINGS_UPDATED", settingsPayload)
                        .source(EventSource.HUMAN).dataMode(DataMode.LIVE).location(EventLocation.site(SITE)),
                TENANT, new EnvelopeFactory.Actor(ActorType.HUMAN, "018f0000-0000-7000-8000-00000000a107"),
                CORRELATION, 3L));

        // Device position with full coordinates and heading; the source provides no sequence.
        samples.put("forklift-position-updated.json", factory.create(
                EventDraft.of("FORKLIFT", FORKLIFT, "FORKLIFT_POSITION_UPDATED",
                        Map.of("speed_mps", 1.5, "load_state", "LOADED"))
                        .unsequenced().source(EventSource.DEVICE).dataMode(DataMode.SYNTHETIC)
                        .location(new EventLocation(SITE, null, UUID.fromString("0199f3a0-0000-7000-8000-0000000000b2"),
                                new EventLocation.Position(12.5, 40.25, 0.0), 90.0))
                        .occurredAt(Instant.parse("2026-10-09T09:00:00.000Z")),
                TENANT, new EnvelopeFactory.Actor(ActorType.DEVICE, "dev:forklift-07"), CORRELATION, null));

        // Observation with confidence and status, caused by an earlier event (causation chain).
        samples.put("incident-detected.json", factory.create(
                EventDraft.of("INCIDENT", INCIDENT, "INCIDENT_DETECTED", Map.of("kind", "NEAR_MISS", "severity", "HIGH"))
                        .source(EventSource.CAMERA).dataMode(DataMode.SIMULATED)
                        .location(EventLocation.site(SITE)).observation(0.87, ObservationStatus.UNCONFIRMED)
                        .causationId(UUID.fromString("0199f3a0-0000-7000-8000-0000000000d1")),
                TENANT, new EnvelopeFactory.Actor(ActorType.SERVICE, "svc:vision-sim"), CORRELATION, 1L));

        // Late event: occurred well before it was ingested; no location, REPLAY provenance, service actor.
        samples.put("late-replayed-event.json", factory.create(
                EventDraft.of("SITE", SITE, "SITE_SETTINGS_UPDATED", Map.of("changed_fields", List.of("time_zone"),
                        "new_values", Map.of("time_zone", "Asia/Kolkata")))
                        .source(EventSource.IMPORT).dataMode(DataMode.REPLAY)
                        .occurredAt(Instant.parse("2026-10-01T00:00:00Z")),
                TENANT, new EnvelopeFactory.Actor(ActorType.SERVICE, "svc:replay"), CORRELATION, 2L));

        Path dir = samplesDir();
        Files.createDirectories(dir);
        for (Map.Entry<String, EventEnvelope> sample : samples.entrySet()) {
            assertThat(validator.validate(codec.toNode(sample.getValue())))
                    .as("sample %s must validate against event-envelope.v1", sample.getKey()).isEmpty();
            String json = codec.toPrettyJson(sample.getValue()).replace("\r\n", "\n") + "\n";
            Files.writeString(dir.resolve(sample.getKey()), json, StandardCharsets.UTF_8);
        }
        assertThat(Files.list(dir).filter(p -> p.toString().endsWith(".json")).count()).isEqualTo(samples.size());
    }
}
