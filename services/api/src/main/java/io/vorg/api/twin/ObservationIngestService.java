package io.vorg.api.twin;

import io.micrometer.core.instrument.MeterRegistry;
import io.vorg.api.platform.Ids;
import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.DomainEvents;
import io.vorg.api.platform.events.EventDraft;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.events.ObservationStatus;
import io.vorg.api.platform.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The single observation-ingestion path for entity positions: the simulated movement source uses it today, and vision
 * (Phase 7) will use the same service, so every source produces the same events with the same provenance (REQ-042).
 *
 * <p>For a batch, in one transaction with the tenant bound:
 * <ol>
 *   <li>validation: finite coordinates, known entity of this site, an observation id; invalid ones are rejected;</li>
 *   <li>coalescing: several observations of one entity in a batch collapse to the latest by {@code occurred_at}
 *       (rate control; the dropped ones are counted, not stored);</li>
 *   <li>deduplication: {@code (source_system, observation_id)} is recorded in {@code world.observation_ledger}; a
 *       re-delivered observation is ignored (no second event);</li>
 *   <li>one {@code <ENTITY_TYPE>_POSITION_UPDATED} event per remaining observation, with the documented envelope
 *       (per-aggregate sequence, source, data mode, occurred_at/ingested_at, one correlation id per batch).</li>
 * </ol>
 * The entity pose projection is updated by {@link PoseProjectionHandler} from those events. A <em>late</em>
 * observation (occurred_at not after the entity's current pose time) is still recorded as an event, because history is
 * truthful, and is flagged {@code late: true} (relative to the newest pose time known at ingest, projected or merely
 * ingested); the projection never moves a pose backwards in time.
 */
@Service
public class ObservationIngestService {

    public static final String EVENT_SUFFIX = "_POSITION_UPDATED";

    /**
     * One position observation. {@code speedMps}, {@code status} and {@code confidence} are optional. {@code extra} is
     * optional source-specific provenance merged into the payload under its own keys (vision: {@code camera}); it can
     * never replace a standard payload field.
     */
    public record Observation(String observationId, UUID entityId, Instant occurredAt, double x, double y, double z,
            double headingDeg, Double speedMps, String status, Double confidence, Map<String, Object> extra) {

        public Observation(String observationId, UUID entityId, Instant occurredAt, double x, double y, double z,
                double headingDeg, Double speedMps, String status, Double confidence) {
            this(observationId, entityId, occurredAt, x, y, z, headingDeg, speedMps, status, confidence, null);
        }
    }

    /** Who delivered the observations and how they are labeled ({@code observationStatus} optional, ADR-69). */
    public record IngestSource(String sourceSystem, EventSource source, DataMode dataMode, ActorType actorType,
            String actorId, ObservationStatus observationStatus) {

        public IngestSource(String sourceSystem, EventSource source, DataMode dataMode, ActorType actorType,
                String actorId) {
            this(sourceSystem, source, dataMode, actorType, actorId, null);
        }
    }

    public record IngestResult(int received, int accepted, int duplicates, int coalesced, int rejected, int late) {}

    private record EntityRow(UUID id, String type, String code, UUID siteId, UUID floorId, Instant poseAt) {}

    private final JdbcClient jdbc;
    private final DomainEvents events;
    private final ZoneLocator zones;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;

    public ObservationIngestService(JdbcClient jdbc, DomainEvents events, ZoneLocator zones, MeterRegistry meters,
            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.events = events;
        this.zones = zones;
        this.meters = meters;
        this.tx = new TransactionTemplate(txManager);
    }

    public IngestResult ingest(UUID tenantId, UUID siteId, IngestSource src, List<Observation> batch) {
        if (batch.isEmpty()) {
            return new IngestResult(0, 0, 0, 0, 0, 0);
        }
        IngestResult result = TenantContext.callWith(tenantId, () -> tx.execute(status -> apply(tenantId, siteId, src,
                batch)));
        count("accepted", result.accepted());
        count("duplicate", result.duplicates());
        count("coalesced", result.coalesced());
        count("rejected", result.rejected());
        count("late", result.late());
        return result;
    }

    private void count(String outcome, int n) {
        if (n > 0) {
            meters.counter("vo.observations", "outcome", outcome).increment(n);
        }
    }

    private IngestResult apply(UUID tenantId, UUID siteId, IngestSource src, List<Observation> batch) {
        int rejected = 0;
        Map<UUID, Observation> latest = new LinkedHashMap<>();
        int coalesced = 0;
        for (Observation o : batch) {
            if (!valid(o)) {
                rejected++;
                continue;
            }
            Observation previous = latest.get(o.entityId());
            if (previous == null) {
                latest.put(o.entityId(), o);
            } else {
                coalesced++;
                if (o.occurredAt().isAfter(previous.occurredAt())) {
                    latest.put(o.entityId(), o);
                }
            }
        }
        Map<UUID, EntityRow> entities = entities(siteId, new ArrayList<>(latest.keySet()));
        UUID correlationId = Ids.newId();
        int accepted = 0;
        int duplicates = 0;
        int late = 0;
        List<Observation> ordered = new ArrayList<>(latest.values());
        ordered.sort(Comparator.comparing(Observation::occurredAt).thenComparing(Observation::entityId));
        for (Observation o : ordered) {
            EntityRow entity = entities.get(o.entityId());
            if (entity == null) {
                rejected++;
                continue;
            }
            int fresh = jdbc.sql("""
                    insert into world.observation_ledger (tenant_id, source_system, observation_id, entity_id,
                        occurred_at) values (:t, :s, :o, :e, :at) on conflict do nothing
                    """).param("t", tenantId).param("s", src.sourceSystem()).param("o", o.observationId())
                    .param("e", o.entityId()).param("at", OffsetDateTime.ofInstant(o.occurredAt(), ZoneOffset.UTC))
                    .update();
            if (fresh == 0) {
                duplicates++;
                continue;
            }
            boolean isLate = !o.occurredAt().isAfter(entity.poseAt());
            if (isLate) {
                late++;
            }
            record(tenantId, siteId, src, o, entity, isLate, correlationId);
            accepted++;
        }
        return new IngestResult(batch.size(), accepted, duplicates, coalesced, rejected, late);
    }

    private void record(UUID tenantId, UUID siteId, IngestSource src, Observation o, EntityRow entity,
            boolean late, UUID correlationId) {
        double x = round3(o.x());
        double y = round3(o.y());
        double z = round3(o.z());
        double heading = normalizeHeading(o.headingDeg());
        UUID zone = entity.floorId() == null ? null
                : zones.locate(tenantId, siteId, entity.floorId(), x, y).orElse(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entity_code", entity.code());
        payload.put("observation_id", o.observationId());
        payload.put("source_system", src.sourceSystem());
        if (o.speedMps() != null) {
            payload.put("speed_mps", round3(o.speedMps()));
        }
        if (o.status() != null) {
            payload.put("status", o.status());
        }
        payload.put("late", late);
        if (o.extra() != null) {
            o.extra().forEach(payload::putIfAbsent);
        }
        EventDraft draft = EventDraft.of(entity.type(), entity.id(), entity.type() + EVENT_SUFFIX, payload)
                .source(src.source()).dataMode(src.dataMode()).actor(src.actorType(), src.actorId())
                .occurredAt(o.occurredAt()).correlationId(correlationId)
                .location(new EventLocation(siteId, entity.floorId(), zone, new EventLocation.Position(x, y, z),
                        heading));
        if (o.confidence() != null || src.observationStatus() != null) {
            draft.observation(o.confidence(), src.observationStatus());
        }
        events.record(draft);
    }

    private Map<UUID, EntityRow> entities(UUID siteId, List<UUID> ids) {
        Map<UUID, EntityRow> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select e.id, e.entity_type, e.code, e.site_id, e.floor_id,
                       greatest(e.pose_occurred_at, coalesce((select max(l.occurred_at) from world.observation_ledger l
                                where l.tenant_id = e.tenant_id and l.entity_id = e.id), e.pose_occurred_at))
                           as pose_occurred_at
                from world.entities e where e.site_id = :s and e.id in (:ids)
                """).param("s", siteId).param("ids", ids).query((rs, n) -> new EntityRow(
                        rs.getObject("id", UUID.class), rs.getString("entity_type"), rs.getString("code"),
                        rs.getObject("site_id", UUID.class), rs.getObject("floor_id", UUID.class),
                        rs.getObject("pose_occurred_at", OffsetDateTime.class).toInstant()))
                .list().forEach(e -> out.put(e.id(), e));
        return out;
    }

    /** Deletes ledger rows older than the retention window; returns how many were removed. */
    public int prune(UUID tenantId, Duration retention) {
        return TenantContext.callWith(tenantId, () -> jdbc.sql("""
                delete from world.observation_ledger where ingested_at < now() - make_interval(secs => :secs)
                """).param("secs", retention.toSeconds()).update());
    }

    private static boolean valid(Observation o) {
        return o != null && o.observationId() != null && !o.observationId().isBlank() && o.entityId() != null
                && o.occurredAt() != null && Double.isFinite(o.x()) && Double.isFinite(o.y())
                && Double.isFinite(o.z()) && Double.isFinite(o.headingDeg());
    }

    static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    static double normalizeHeading(double deg) {
        double h = deg % 360.0;
        if (h < 0) {
            h += 360.0;
        }
        h = round3(h);
        return h >= 360.0 ? 0.0 : h;
    }
}
