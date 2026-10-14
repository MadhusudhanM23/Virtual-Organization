package io.vorg.api.recommendations;

import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.KeysetCursor;
import io.vorg.api.platform.web.PageParams;
import io.vorg.api.platform.web.PageParams.Page;
import io.vorg.api.recommendations.CurrentStateReader.EntityState;
import io.vorg.api.recommendations.RecommendationStore.RecommendationView;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Recommendation reads (REQ-090) and the dry-run diff (REQ-093). Visibility: {@code PROPOSE_RECOMMENDATION} on the
 * site ({@link RecommendationAccess}); another tenant's recommendation is 404 (RLS plus an explicit tenant filter).
 *
 * <p>The dry-run applies the execution plan, step by step, to the <i>current</i> values read through the
 * {@link CurrentStateReader} port, without changing anything, and lists what is stale: affected entities whose current
 * version differs from {@code baseline_entity_versions} (or that no longer exist) and policies whose version differs
 * from {@code policy_versions}. Only command types with a known effect ({@link StepEffects}) can be dry-run.
 */
@Service
public class RecommendationQueryService {

    private final RecommendationStore store;
    private final RecommendationAccess access;
    private final ObjectProvider<CurrentStateReader> currentState;
    private final PolicyVersions policyVersions;
    private final Clock clock;

    public RecommendationQueryService(RecommendationStore store, RecommendationAccess access,
            ObjectProvider<CurrentStateReader> currentState, PolicyVersions policyVersions, Clock clock) {
        this.store = store;
        this.access = access;
        this.currentState = currentState;
        this.policyVersions = policyVersions;
        this.clock = clock;
    }

    public RecommendationView get(Subject subject, UUID id) {
        RecommendationView v = store.findRecommendation(subject.tenantId(), id, false)
                .orElseThrow(() -> RecommendationProblems.notFound("recommendation"));
        access.requireView(subject, v.siteId());
        return v;
    }

    public Page<RecommendationView> list(Subject subject, UUID siteId, String status, Integer limit, String cursor) {
        if (siteId == null) {
            throw RecommendationProblems.invalid("site_id", "is required");
        }
        RecommendationStatus filter = parse(RecommendationStatus.class, status);
        access.requireView(subject, siteId);
        int lim = PageParams.limit(limit);
        List<RecommendationView> rows = store.listRecommendations(subject.tenantId(), siteId, filter,
                KeysetCursor.decode(cursor), lim + 1);
        if (rows.size() <= lim) {
            return new Page<>(rows, null);
        }
        List<RecommendationView> items = new ArrayList<>(rows.subList(0, lim));
        RecommendationView last = items.get(items.size() - 1);
        return new Page<>(items, KeysetCursor.encode(last.createdAt(), last.id()));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> dryRun(Subject subject, UUID id) {
        RecommendationView v = get(subject, id);
        CurrentStateReader reader = currentState.getIfAvailable();
        if (reader == null) {
            throw RecommendationProblems.of(HttpStatus.SERVICE_UNAVAILABLE, "Current state unavailable",
                    "No current-state reader is configured, so the plan cannot be dry-run.",
                    RecommendationProblems.CURRENT_STATE_UNAVAILABLE);
        }
        Map<String, EntityState> states = reader.read(subject.tenantId(), v.siteId(), v.affectedEntities());

        // Working copy of the current field values, so a later step on the same entity sees the earlier step's result.
        Map<String, Map<String, Object>> working = new HashMap<>();
        states.forEach((entity, state) -> working.put(entity,
                new HashMap<>(state.fields() == null ? Map.of() : state.fields())));
        List<Map<String, Object>> diff = new ArrayList<>();
        List<Map<String, Object>> steps = (List<Map<String, Object>>) v.executionPlan().get("steps");
        for (Map<String, Object> step : steps) {
            String commandType = String.valueOf(step.get("command_type"));
            String target = String.valueOf(step.get("target"));
            StepEffects.Effect effect = StepEffects.of(commandType).orElseThrow(() -> RecommendationProblems.of(
                    HttpStatus.UNPROCESSABLE_CONTENT, "Dry-run unsupported",
                    "Command type " + commandType + " has no known effect to dry-run.",
                    RecommendationProblems.DRY_RUN_UNSUPPORTED, Map.of("command_type", commandType)));
            Map<String, Object> params = (Map<String, Object>) step.getOrDefault("params", Map.of());
            Map<String, Object> fields = working.computeIfAbsent(target, k -> new HashMap<>());
            Object before = fields.get(effect.field());
            Object after = params.get(effect.param());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entity", target);
            row.put("field", effect.field());
            row.put("before", before);
            row.put("after", after);
            diff.add(row);
            fields.put(effect.field(), after);
        }

        List<Map<String, Object>> stale = new ArrayList<>();
        v.baselineEntityVersions().forEach((entity, baseline) -> {
            EntityState now = states.get(entity);
            Integer current = now == null ? null : now.version();
            Integer recorded = baseline instanceof Number n ? n.intValue() : null;
            if (!Objects.equals(recorded, current)) {
                stale.add(staleRow("ENTITY", entity, recorded, current));
            }
        });
        Map<String, Integer> recordedPolicies = new LinkedHashMap<>();
        v.policyVersions().forEach((name, ver) -> recordedPolicies.put(name,
                ver instanceof Number n ? n.intValue() : null));
        for (PolicyVersions.Change c : policyVersions.changed(recordedPolicies)) {
            stale.add(staleRow("POLICY", c.policy(), c.baselineVersion(), c.currentVersion()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recommendation_id", v.id().toString());
        out.put("computed_at", clock.instant().toString());
        out.put("diff", diff);
        out.put("stale", stale);
        return out;
    }

    private static Map<String, Object> staleRow(String kind, String entity, Integer baseline, Integer current) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("entity", entity);
        row.put("baseline_version", baseline);
        row.put("current_version", current);
        return row;
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw RecommendationProblems.invalid("status", "is not a valid status");
        }
    }
}
