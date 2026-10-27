package io.vorg.api.kpi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vorg.api.kpi.KpiFormulas.Computed;
import io.vorg.api.kpi.KpiInputs.Group;
import io.vorg.api.kpi.KpiInputs.Sample;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KpiFormulasTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final Instant FROM = NOW.minusSeconds(3600);

    private static Group group(String mode, Object... kv) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            counts.put((String) kv[i], ((Number) kv[i + 1]).longValue());
        }
        return new Group(counts, Set.of(mode), NOW);
    }

    private static KpiInputs with(KpiInputs base, java.util.function.UnaryOperator<Builder> f) {
        return f.apply(new Builder(base)).build();
    }

    /** Minimal builder so each test states only the inputs it cares about. */
    private static final class Builder {
        KpiInputs in;

        Builder(KpiInputs in) {
            this.in = in;
        }

        Builder tasks(Group g) {
            in = new KpiInputs(in.now(), in.from(), in.to(), g, in.tasksInWindow(), in.cycleSeconds(), in.docks(),
                    in.entities(), in.findings(), in.orders(), in.orderRevisions(), in.events());
            return this;
        }

        Builder window(Group g) {
            in = new KpiInputs(in.now(), in.from(), in.to(), in.tasks(), g, in.cycleSeconds(), in.docks(),
                    in.entities(), in.findings(), in.orders(), in.orderRevisions(), in.events());
            return this;
        }

        Builder cycle(List<Sample> s) {
            in = new KpiInputs(in.now(), in.from(), in.to(), in.tasks(), in.tasksInWindow(), s, in.docks(),
                    in.entities(), in.findings(), in.orders(), in.orderRevisions(), in.events());
            return this;
        }

        Builder docks(Group g) {
            in = new KpiInputs(in.now(), in.from(), in.to(), in.tasks(), in.tasksInWindow(), in.cycleSeconds(), g,
                    in.entities(), in.findings(), in.orders(), in.orderRevisions(), in.events());
            return this;
        }

        Builder entities(Group g) {
            in = new KpiInputs(in.now(), in.from(), in.to(), in.tasks(), in.tasksInWindow(), in.cycleSeconds(),
                    in.docks(), g, in.findings(), in.orders(), in.orderRevisions(), in.events());
            return this;
        }

        Builder findings(Group g) {
            in = new KpiInputs(in.now(), in.from(), in.to(), in.tasks(), in.tasksInWindow(), in.cycleSeconds(),
                    in.docks(), in.entities(), g, in.orders(), in.orderRevisions(), in.events());
            return this;
        }

        Builder orders(Group g, Group revisions) {
            in = new KpiInputs(in.now(), in.from(), in.to(), in.tasks(), in.tasksInWindow(), in.cycleSeconds(),
                    in.docks(), in.entities(), in.findings(), g, revisions, in.events());
            return this;
        }

        Builder events(Group g) {
            in = new KpiInputs(in.now(), in.from(), in.to(), in.tasks(), in.tasksInWindow(), in.cycleSeconds(),
                    in.docks(), in.entities(), in.findings(), in.orders(), in.orderRevisions(), g);
            return this;
        }

        KpiInputs build() {
            return in;
        }
    }

    private static final KpiInputs EMPTY = KpiInputs.empty(NOW, FROM, NOW);

    @Test
    void everyRegisteredKpiHasAFormulaAndIsInsufficientOnEmptyData() {
        for (KpiDefinition d : KpiRegistry.all()) {
            Computed c = KpiFormulas.compute(d.id(), EMPTY);
            assertThat(c.value()).as(d.id()).isNull();
            assertThat(c.reason()).as(d.id()).isNotBlank();
            assertThat(KpiFormulas.dataMode(c.modes())).as(d.id()).isNull();
        }
    }

    @Test
    void unknownKpiIsRejected() {
        assertThatThrownBy(() -> KpiFormulas.compute("nope", EMPTY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void taskCountsFollowTheStatusDefinitions() {
        KpiInputs in = with(EMPTY, b -> b.tasks(group("SIMULATED", "ASSIGNED", 2, "ACCEPTED", 1, "PAUSED", 1,
                "STARTED", 3, "COMPLETED", 10, "FAILED", 1, "CANCELLED", 4))
                .window(group("SIMULATED", "COMPLETED", 6, "FAILED", 1)));
        assertThat(KpiFormulas.compute("tasks_open", in).value()).isEqualTo(4.0);
        assertThat(KpiFormulas.compute("tasks_in_progress", in).value()).isEqualTo(3.0);
        assertThat(KpiFormulas.compute("tasks_completed", in).value()).isEqualTo(6.0);
        assertThat(KpiFormulas.compute("tasks_failed", in).value()).isEqualTo(1.0);
        assertThat(KpiFormulas.compute("tasks_open", in).sampleSize()).isEqualTo(22);
    }

    @Test
    void completedInWindowIsARealZeroWhenTasksExistButNoneFinished() {
        KpiInputs in = with(EMPTY, b -> b.tasks(group("SIMULATED", "ASSIGNED", 2)));
        Computed c = KpiFormulas.compute("tasks_completed", in);
        assertThat(c.value()).isEqualTo(0.0);
        assertThat(KpiFormulas.dataMode(c.modes())).isEqualTo("SIMULATED");
    }

    @Test
    void cycleTimeIsTheMeanInMinutesAndNeedsSamples() {
        KpiInputs in = with(EMPTY, b -> b.cycle(List.of(new Sample(120, "SIMULATED", NOW),
                new Sample(240, "SIMULATED", NOW.minusSeconds(60)), new Sample(360, "SYNTHETIC", NOW))));
        Computed c = KpiFormulas.compute("task_cycle_time_avg", in);
        assertThat(c.value()).isEqualTo(4.0);
        assertThat(c.sampleSize()).isEqualTo(3);
        assertThat(KpiFormulas.dataMode(c.modes())).isEqualTo("MIXED");
        assertThat(c.asOf()).isEqualTo(NOW);
        assertThat(KpiFormulas.compute("task_cycle_time_avg", EMPTY).value()).isNull();
    }

    @Test
    void dockUtilizationExcludesClosedDocks() {
        KpiInputs in = with(EMPTY, b -> b.docks(group("SYNTHETIC", "OCCUPIED", 3, "AVAILABLE", 2, "CLOSED", 1)));
        Computed c = KpiFormulas.compute("dock_utilization", in);
        assertThat(c.value()).isEqualTo(60.0);
        assertThat(c.sampleSize()).isEqualTo(5);
        KpiInputs allClosed = with(EMPTY, b -> b.docks(group("SYNTHETIC", "CLOSED", 2)));
        assertThat(KpiFormulas.compute("dock_utilization", allClosed).value()).isNull();
    }

    @Test
    void forkliftUtilizationCountsMovingAndLoading() {
        KpiInputs in = with(EMPTY, b -> b.entities(group("SIMULATED", "FORKLIFT|MOVING", 4, "FORKLIFT|LOADING", 1,
                "FORKLIFT|IDLE", 5, "TRUCK|WAITING", 2)));
        assertThat(KpiFormulas.compute("forklift_utilization", in).value()).isEqualTo(50.0);
        assertThat(KpiFormulas.compute("forklift_utilization", in).sampleSize()).isEqualTo(10);
        KpiInputs noForklifts = with(EMPTY, b -> b.entities(group("SIMULATED", "TRUCK|WAITING", 2)));
        assertThat(KpiFormulas.compute("forklift_utilization", noForklifts).value()).isNull();
    }

    @Test
    void trucksAndPalletsAreCountedByStatusPerType() {
        KpiInputs in = with(EMPTY, b -> b.entities(group("SYNTHETIC", "TRUCK|WAITING", 2, "TRUCK|DOCKED", 4,
                "PALLET|STORED", 80, "PALLET|STAGED", 40, "FORKLIFT|IDLE", 1)));
        assertThat(KpiFormulas.compute("trucks_waiting", in).value()).isEqualTo(2.0);
        assertThat(KpiFormulas.compute("trucks_docked", in).value()).isEqualTo(4.0);
        assertThat(KpiFormulas.compute("pallets_stored", in).value()).isEqualTo(80.0);
        assertThat(KpiFormulas.compute("pallets_staged", in).value()).isEqualTo(40.0);
        assertThat(KpiFormulas.compute("pallets_stored", in).sampleSize()).isEqualTo(120);
    }

    @Test
    void openFindingsIgnoreResolvedOnes() {
        KpiInputs in = with(EMPTY, b -> b.findings(group("SIMULATED", "OPEN", 3, "RESOLVED", 7)));
        assertThat(KpiFormulas.compute("integration_findings_open", in).value()).isEqualTo(3.0);
    }

    @Test
    void orderKpisUseMirrorsAndRevisions() {
        KpiInputs in = with(EMPTY, b -> b.orders(group("SIMULATED", "RELEASED", 2, "IN_PROGRESS", 3, "COMPLETED", 9),
                group("SIMULATED", "RELEASED", 1, "COMPLETED", 4)));
        assertThat(KpiFormulas.compute("orders_open", in).value()).isEqualTo(5.0);
        assertThat(KpiFormulas.compute("orders_completed", in).value()).isEqualTo(4.0);
    }

    @Test
    void eventThroughputIsEventsPerMinuteOfTheWindow() {
        KpiInputs in = with(EMPTY, b -> b.events(group("SIMULATED", "EVENTS", 120)));
        Computed c = KpiFormulas.compute("event_throughput", in);
        assertThat(c.value()).isEqualTo(2.0);
        assertThat(c.sampleSize()).isEqualTo(120);
        KpiInputs mixed = with(EMPTY, b -> b.events(new Group(Map.of("EVENTS", 30L), Set.of("SIMULATED", "SYNTHETIC"),
                NOW)));
        assertThat(KpiFormulas.dataMode(KpiFormulas.compute("event_throughput", mixed).modes())).isEqualTo("MIXED");
    }

    @Test
    void windowParsingUsesTheMenuAndBucketsCoverTheWindow() {
        KpiWindow w = KpiWindow.parse("1h", NOW);
        assertThat(w.from()).isEqualTo(FROM);
        assertThat(w.bucketCount()).isEqualTo(12);
        assertThat(KpiWindow.parse(null, NOW).label()).isEqualTo("24h");
        assertThatThrownBy(() -> KpiWindow.parse("3y", NOW))
                .isInstanceOf(io.vorg.api.platform.web.ApiProblemException.class);
    }

    private static KpiInputs shipped(KpiInputs.Shipped... rows) {
        KpiInputs e = KpiInputs.empty(NOW, FROM, NOW);
        return new KpiInputs(e.now(), e.from(), e.to(), e.tasks(), e.tasksInWindow(), e.cycleSeconds(), e.docks(),
                e.entities(), e.findings(), e.orders(), e.orderRevisions(), e.events(), List.of(rows));
    }

    @Test
    void shippedValueSumsPricedOrdersAndIgnoresUnpricedOnes() {
        Computed c = KpiFormulas.compute("shipped_order_value", shipped(
                new KpiInputs.Shipped(12000.0, 90.0, 80.0, "SIMULATED", NOW),
                new KpiInputs.Shipped(3500.5, 90.0, 95.0, "SIMULATED", NOW),
                new KpiInputs.Shipped(null, null, null, "SIMULATED", NOW)));
        assertThat(c.value()).isEqualTo(15500.5);
        assertThat(c.sampleSize()).isEqualTo(2);
    }

    @Test
    void onTimeDispatchIsTheShareWithinThePromise() {
        Computed c = KpiFormulas.compute("on_time_dispatch", shipped(
                new KpiInputs.Shipped(1.0, 90.0, 80.0, "SIMULATED", NOW),
                new KpiInputs.Shipped(1.0, 90.0, 90.0, "SIMULATED", NOW),
                new KpiInputs.Shipped(1.0, 75.0, 95.0, "SIMULATED", NOW),
                new KpiInputs.Shipped(1.0, null, 95.0, "SIMULATED", NOW)));
        assertThat(c.value()).isEqualTo(66.67);
        assertThat(c.sampleSize()).isEqualTo(3);
    }

    @Test
    void orderValueKpisNeedData() {
        assertThat(KpiFormulas.compute("shipped_order_value", shipped()).value()).isNull();
        assertThat(KpiFormulas.compute("on_time_dispatch", shipped()).reason()).contains("promised");
    }

    @Test
    void registryDefinitionsAreCompleteAndUnique() {
        assertThat(KpiRegistry.all()).extracting(KpiDefinition::id).doesNotHaveDuplicates();
        for (KpiDefinition d : KpiRegistry.all()) {
            assertThat(d.name()).isNotBlank();
            assertThat(d.formula()).isNotBlank();
            assertThat(d.unit()).isNotBlank();
            assertThat(d.sourceTables()).isNotEmpty();
            assertThat(d.freshness()).isNotBlank();
        }
    }
}
