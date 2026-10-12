package io.vorg.api.replay;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vorg.api.platform.Ids;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.replay.ReplayModel.EntityState;
import io.vorg.api.replay.ReplayModel.RecordedContext;
import io.vorg.api.replay.ReplayModel.ReplayState;
import io.vorg.api.replay.ReplayStore.NewSnapshot;
import io.vorg.api.replay.ReplayStore.SnapshotMeta;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-site replay snapshots (REQ-060, ADR-44). The first snapshot of a site is a <em>genesis</em> read of the live
 * projections; every later one is <em>derived</em>: the previous snapshot plus the events since, folded by the pure
 * {@link ReplayReducer}, with the recorded context (docks, world version, entity catalog data) refreshed from the
 * tables. A snapshot is taken in one repeatable-read transaction, so its reads are mutually consistent, and its
 * high-water mark is {@code as_of = taken_at} = the instant the transaction read the history (ADR-45).
 */
@Service
public class SnapshotService {

    public static final String SCHEDULED = "SCHEDULED";
    public static final String ON_DEMAND = "ON_DEMAND";

    /** A loaded snapshot: its row and its decoded state. */
    public record Loaded(SnapshotMeta meta, ReplayState state) {}

    private final ReplayStore store;
    private final ReplayReducer reducer;
    private final ReplayCodec codec;
    private final ReplayProperties props;
    private final Clock clock;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;
    private final Map<UUID, ReplayState> cache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, ReplayState> eldest) {
            return size() > 32;
        }
    };

    public SnapshotService(ReplayStore store, ReplayReducer reducer, ReplayCodec codec, ReplayProperties props,
            Clock clock, MeterRegistry meters, PlatformTransactionManager txManager) {
        this.store = store;
        this.reducer = reducer;
        this.codec = codec;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** Takes a snapshot of the site in its own transaction with the tenant bound. */
    public SnapshotMeta snapshot(UUID tenantId, UUID siteId, String trigger) {
        return TenantContext.callWith(tenantId, () -> tx.execute(status -> take(tenantId, siteId, trigger)));
    }

    /**
     * Takes a snapshot in the caller's transaction (the command gateway's). The tenant must be bound; RLS keeps every
     * read and the insert inside it.
     */
    public SnapshotMeta take(UUID tenantId, UUID siteId, String trigger) {
        long start = System.nanoTime();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<SnapshotMeta> base = store.latest(siteId);
        ReplayState state;
        int applied;
        String origin;
        UUID baseId = null;
        RecordedContext recorded = recordedNow(siteId, now);
        if (base.isPresent()) {
            SnapshotMeta b = base.get();
            Instant asOf = now.isBefore(b.asOf()) ? b.asOf() : now;
            ReplayReducer.Fold fold = reducer.fold(load(b).state());
            fold.applyAll(store.eventsFrom(siteId, b.asOf(), asOf, b.asOf().minus(props.lateHorizon()),
                    b.takenAt().minus(props.ingestOverlap())));
            state = refreshCatalog(fold.toState(asOf, recorded), store.entities(siteId));
            applied = fold.applied();
            origin = "DERIVED";
            baseId = b.id();
            now = asOf;
        } else {
            state = genesis(siteId, now, recorded);
            applied = state.alerts().size();
            origin = "GENESIS";
        }
        byte[] json = codec.canonical(state);
        byte[] gz = ReplayCodec.gzip(json);
        String sha = ReplayCodec.sha256(json);
        UUID id = Ids.newId();
        Integer worldVersion = recorded.worldVersion() == null ? null : recorded.worldVersion().version();
        store.insert(new NewSnapshot(id, tenantId, siteId, now, now, trigger, origin, baseId, worldVersion,
                state.entities().size(), state.tasks().size(), state.incidents().size(), state.alerts().size(),
                applied, sha, gz));
        synchronized (cache) {
            cache.put(id, state);
        }
        Timer.builder("vo.replay.snapshot").tag("origin", origin).register(meters)
                .record(java.time.Duration.ofNanos(System.nanoTime() - start));
        return new SnapshotMeta(id, siteId, now, now, trigger, origin, baseId, ReplayModel.FORMAT_VERSION,
                worldVersion, state.entities().size(), state.tasks().size(), state.incidents().size(),
                state.alerts().size(), applied, sha, gz.length);
    }

    /** Loads (and caches) a snapshot's state. Snapshots are immutable, so the cache never goes stale. */
    public Loaded load(SnapshotMeta meta) {
        ReplayState cached;
        synchronized (cache) {
            cached = cache.get(meta.id());
        }
        if (cached != null) {
            return new Loaded(meta, cached);
        }
        ReplayState state = codec.readState(ReplayCodec.gunzip(store.stateGzip(meta.id())));
        synchronized (cache) {
            cache.put(meta.id(), state);
        }
        return new Loaded(meta, state);
    }

    /** Deletes the bound tenant's snapshots of the site older than the retention (the newest always stays). */
    public int prune(UUID siteId) {
        return store.prune(siteId, clock.instant().minus(props.snapshots().retention()));
    }

    private ReplayState genesis(UUID siteId, Instant now, RecordedContext recorded) {
        Instant windowStart = now.minus(reducer.recentWindow());
        ReplayState tables = new ReplayState(ReplayModel.FORMAT_VERSION, siteId, now, recorded,
                store.entities(siteId), store.tasks(siteId, windowStart), store.incidents(siteId, windowStart),
                List.of());
        List<EventEnvelope> alerts = store.alertEvents(siteId, windowStart, now);
        ReplayReducer.Fold fold = reducer.fold(tables);
        fold.applyAll(alerts);
        return fold.toState(now, recorded);
    }

    private RecordedContext recordedNow(UUID siteId, Instant now) {
        return new RecordedContext(now, store.worldVersion(siteId).orElse(null), store.docks(siteId));
    }

    /**
     * Refreshes catalog data no event describes (type, code, name, attributes, data mode) from the table and adds
     * entities that appeared since the base snapshot; reconstructed fields (pose, floor, zone, status) are kept.
     */
    static ReplayState refreshCatalog(ReplayState state, List<EntityState> table) {
        Map<UUID, EntityState> byId = new TreeMap<>(ReplayReducer::compareUuids);
        state.entities().forEach(e -> byId.put(e.id(), e));
        for (EntityState t : table) {
            EntityState cur = byId.get(t.id());
            if (cur == null) {
                byId.put(t.id(), t);
            } else {
                byId.put(t.id(), new EntityState(cur.id(), t.entityType(), t.code(), t.name(), t.siteId(),
                        cur.floorId(), cur.zoneId(), cur.status(), cur.pose(), t.attributes(), t.dataMode(),
                        cur.poseKey(), cur.floorKey(), cur.statusKey()));
            }
        }
        return new ReplayState(state.formatVersion(), state.siteId(), state.asOf(), state.recorded(),
                new ArrayList<>(byId.values()), state.tasks(), state.incidents(), state.alerts());
    }
}
