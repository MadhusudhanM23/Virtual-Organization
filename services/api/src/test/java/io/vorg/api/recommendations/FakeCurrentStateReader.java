package io.vorg.api.recommendations;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Test-only</b> {@link CurrentStateReader}: an in-memory fixture of the trip and dock versions the fake generator
 * plans against ({@code TRIP-00003} at {@code IN-02}, version 4; docks version 1). Tests can bump a version to
 * exercise the stale list of the dry-run. Plan 8.3 implements the real reader over the assignment projections.
 */
public class FakeCurrentStateReader implements CurrentStateReader {

    public static final String FROM_DOCK = "IN-02";

    private final Map<String, EntityState> states = new ConcurrentHashMap<>();

    public FakeCurrentStateReader() {
        reset();
    }

    public final void reset() {
        states.clear();
        states.put(FakeRecommendationGenerator.TRIP,
                new EntityState(FakeRecommendationGenerator.TRIP, 4, Map.of(StepEffects.DOCK_FIELD, FROM_DOCK)));
        states.put(FROM_DOCK, new EntityState(FROM_DOCK, 1, Map.of()));
        states.put(FakeRecommendationGenerator.TO_DOCK,
                new EntityState(FakeRecommendationGenerator.TO_DOCK, 1, Map.of()));
    }

    /** Simulates an external change of an entity (version + 1). */
    public void bump(String entity) {
        states.computeIfPresent(entity, (k, s) -> new EntityState(k, s.version() + 1, s.fields()));
    }

    int version(String entity) {
        EntityState s = states.get(entity);
        return s == null ? 1 : s.version();
    }

    @Override
    public Map<String, EntityState> read(UUID tenantId, UUID siteId, Collection<String> entities) {
        Map<String, EntityState> out = new LinkedHashMap<>();
        for (String e : entities) {
            EntityState s = states.get(e);
            if (s != null) {
                out.put(e, s);
            }
        }
        return out;
    }
}
