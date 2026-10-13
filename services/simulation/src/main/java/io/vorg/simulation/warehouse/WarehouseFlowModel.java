package io.vorg.simulation.warehouse;

import io.vorg.simulation.engine.Entity;
import io.vorg.simulation.engine.RunControl;
import io.vorg.simulation.engine.ScheduledEvent;
import io.vorg.simulation.engine.SimTime;
import io.vorg.simulation.engine.Simulation;
import io.vorg.simulation.random.Distribution;
import io.vorg.simulation.random.RandomStream;
import io.vorg.simulation.resource.Store;
import io.vorg.simulation.resource.UnitPool;
import io.vorg.simulation.stats.Counter;
import io.vorg.simulation.stats.Tally;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pallet-level DES of the warehouse flow (ADR-48), one replication per instance:
 *
 * <pre>
 * inbound truck -> yard (FIFO) -> IN dock -> dock-in -> unloading (forklifts, k lanes) -> receiving check (worker)
 *   -> putaway (forklift, travel from layout) -> storage
 * order -> takes stored pallets (FIFO) -> retrieval (forklift) -> picking (worker) -> packing (worker)
 *   -> dispatch staging
 * outbound truck -> yard (FIFO) -> OUT dock -> dock-in -> loading (dispatch worker + forklift, k lanes) until full or
 *   max dwell -> dock-out -> departure (orders complete when their last pallet departs)
 * </pre>
 *
 * Work content of every entity (pallet counts, service times, storage zone) is drawn when the entity arrives from the
 * stream of its process, so runs that differ only in resources/closures use common random numbers.
 */
public final class WarehouseFlowModel {

    public static final String LOGIC_VERSION = "warehouse-flow/1.0.0";

    /** Forklift request priorities: lower first (free docks first). */
    static final int FL_LOAD = 0;
    static final int FL_UNLOAD = 1;
    static final int FL_RETRIEVE = 2;
    static final int FL_PUTAWAY = 3;
    static final int PRIORITY_SAMPLE = Integer.MAX_VALUE;

    /** Where a pallet is. */
    public enum Loc {
        ON_INBOUND_TRUCK, UNLOADING, RECEIVING, PUTAWAY, STORED, RETRIEVAL, PICKING, PACKING, DISPATCH_STAGED, LOADING,
        ON_OUTBOUND_TRUCK, SHIPPED
    }

    final class Pallet {
        final String id;
        Loc loc;
        String zone;
        Order order;
        double unloadMin;
        double checkMin;
        double putawayHandlingMin;

        Pallet(String id, Loc loc) {
            this.id = id;
            this.loc = loc;
            locCount.merge(loc, 1, Integer::sum);
        }
    }

    final class InboundTruck extends Entity {
        final List<Pallet> pallets = new ArrayList<>();
        double dockInMin;
        double dockOutMin;
        int next;
        int unloaded;
        int lanes;
        UnitPool.Unit dock;
        boolean docked;

        InboundTruck(String id, long at) {
            super(id, at);
        }
    }

    final class Order extends Entity {
        final int pallets;
        final double[] retrievalMin;
        final double[] pickMin;
        final double[] packMin;
        int shipped;

        Order(String id, long at, int pallets) {
            super(id, at);
            this.pallets = pallets;
            this.retrievalMin = new double[pallets];
            this.pickMin = new double[pallets];
            this.packMin = new double[pallets];
        }
    }

    final class OutboundTruck extends Entity {
        final int capacity;
        final double[] loadMin;
        final List<Pallet> loaded = new ArrayList<>();
        double dockInMin;
        double dockOutMin;
        int reserved;
        int lanes;
        long deadline;
        UnitPool.Unit dock;

        OutboundTruck(String id, long at, int capacity) {
            super(id, at);
            this.capacity = capacity;
            this.loadMin = new double[capacity];
        }
    }

    private final ModelInput model;
    private final WarehouseParameters p;
    private final Overlay overlay;
    private final RunSettings run;
    private final int index;
    private final long seed;
    private final Simulation sim;

    private final UnitPool inDocks;
    private final UnitPool outDocks;
    private final UnitPool forklifts;
    private final Map<String, UnitPool> workers = new LinkedHashMap<>();
    private final Store<Pallet> storage;
    private final Store<Pallet> dispatch;

    private final RandomStream inArrivals;
    private final RandomStream inContent;
    private final RandomStream orderArrivals;
    private final RandomStream orderThinning;
    private final RandomStream orderContent;
    private final RandomStream outArrivals;
    private final RandomStream outContent;
    private final RandomStream initContent;

    private final Map<String, Double> putawayTravelMin = new LinkedHashMap<>();
    private final Map<String, Double> retrievalTravelMin = new LinkedHashMap<>();
    private final EnumMap<Loc, Integer> locCount = new EnumMap<>(Loc.class);
    private final Set<InboundTruck> inboundInSystem = new LinkedHashSet<>();
    private final Set<Order> openOrders = new LinkedHashSet<>();
    private final List<Map<String, Object>> trajectory = new ArrayList<>();

    // measured statistics (reset at warm-up)
    private final Counter palletsReceived;
    private final Counter palletsShipped;
    private final Counter ordersShipped;
    private final Counter ordersOnTime;
    private final Counter inboundDeparted;
    private final Counter inboundLate;
    private final Counter ordersLate;
    private final Tally inboundTurnaroundMin;
    private final Tally orderCycleMin;
    private final Tally outboundFill;

    // conservation (never reset)
    private long nextPallet;
    private long initialPallets;
    private long palletsArrived;
    private long inboundArrived;
    private long inboundDepartedTotal;
    private long outboundArrived;
    private long outboundDepartedTotal;
    private long ordersArrived;
    private long ordersCompletedTotal;
    private long shippedTotal;
    private int maxStored;

    public WarehouseFlowModel(ModelInput effectiveModel, Overlay overlay, RunSettings run, int index, long seed) {
        this.model = effectiveModel;
        this.p = WarehouseParameters.of(effectiveModel);
        this.overlay = overlay;
        this.run = run;
        this.index = index;
        this.seed = seed;
        long end = SimTime.ofMinutes(run.horizonMin());
        long warm = SimTime.ofMinutes(run.warmupMin());
        this.sim = new Simulation(seed, new RunControl(end, warm, RunControl.DEFAULT_MAX_EVENTS, 0));

        inDocks = new UnitPool(sim, "in_docks", ids(model.docks(ModelInput.Direction.INBOUND)));
        outDocks = new UnitPool(sim, "out_docks", ids(model.docks(ModelInput.Direction.OUTBOUND)));
        forklifts = new UnitPool(sim, "forklifts", model.forklifts());
        for (String role : WarehouseParameters.REQUIRED_ROLES) {
            int n = model.workers().get(role);
            List<String> unitIds = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                unitIds.add(String.format(java.util.Locale.ROOT, "%s-%02d", role, i));
            }
            workers.put(role, new UnitPool(sim, "workers_" + role.toLowerCase(java.util.Locale.ROOT), unitIds));
        }
        storage = new Store<>(sim, "storage");
        dispatch = new Store<>(sim, "dispatch_staging");

        inArrivals = sim.rng().stream("inbound.arrivals");
        inContent = sim.rng().stream("inbound.content");
        orderArrivals = sim.rng().stream("orders.arrivals");
        orderThinning = sim.rng().stream("orders.thinning");
        orderContent = sim.rng().stream("orders.content");
        outArrivals = sim.rng().stream("outbound.arrivals");
        outContent = sim.rng().stream("outbound.content");
        initContent = sim.rng().stream("initial.content");

        ModelInput.Zone receiving = model.zone(WarehouseParameters.ZONE_RECEIVING);
        ModelInput.Zone picking = model.zone(WarehouseParameters.ZONE_PICKING);
        for (String z : model.storage().zones()) {
            ModelInput.Zone sz = model.zone(z);
            putawayTravelMin.put(z, roundTripMin(receiving, sz));
            retrievalTravelMin.put(z, roundTripMin(sz, picking));
        }
        for (Loc l : Loc.values()) {
            locCount.put(l, 0);
        }

        palletsReceived = sim.register(new Counter("pallets_received"));
        palletsShipped = sim.register(new Counter("pallets_shipped"));
        ordersShipped = sim.register(new Counter("orders_shipped"));
        ordersOnTime = sim.register(new Counter("orders_on_time"));
        inboundDeparted = sim.register(new Counter("inbound_departed"));
        inboundLate = sim.register(new Counter("inbound_late"));
        ordersLate = sim.register(new Counter("orders_late"));
        inboundTurnaroundMin = sim.register(new Tally("inbound_turnaround_min"));
        orderCycleMin = sim.register(new Tally("order_cycle_min"));
        outboundFill = sim.register(new Tally("outbound_fill"));
    }

    /** Runs the replication and returns its result. Call once. */
    public ReplicationResult run() {
        applyOverlayDowntime();
        seedInitialState();
        scheduleNextInbound();
        scheduleNextOrderCandidate();
        scheduleNextOutbound();
        scheduleSamples();
        sim.run();
        return result();
    }

    // ------------------------------------------------------------------ setup

    private void applyOverlayDowntime() {
        for (Overlay.UnitWindow w : overlay.dockClosures()) {
            UnitPool pool = isInbound(w.unitId()) ? inDocks : outDocks;
            pool.scheduleDowntime(w.unitId(), SimTime.ofMinutes(w.startMin()), endMs(w.endMin()), "dock_closure");
        }
        for (Overlay.UnitWindow w : overlay.forkliftOutages()) {
            forklifts.scheduleDowntime(w.unitId(), SimTime.ofMinutes(w.startMin()), endMs(w.endMin()),
                    "forklift_outage");
        }
        for (Overlay.WorkerReduction w : overlay.workerReductions()) {
            UnitPool pool = workers.get(w.role());
            if (pool == null) {
                continue; // a role the model assigns no work to (e.g. STORAGE): nothing to reduce
            }
            List<UnitPool.Unit> units = pool.units();
            for (int i = 0; i < w.count(); i++) {
                UnitPool.Unit u = units.get(units.size() - 1 - i);
                pool.scheduleDowntime(u.id(), SimTime.ofMinutes(w.startMin()), endMs(w.endMin()), "worker_reduction");
            }
        }
    }

    private long endMs(double endMin) {
        // windows reaching past the horizon end at the horizon (the end event is simply never executed)
        return SimTime.ofMinutes(Math.min(endMin, run.horizonMin() + 1));
    }

    private boolean isInbound(String dockId) {
        return model.docks(ModelInput.Direction.INBOUND).stream().anyMatch(d -> d.dockId().equals(dockId));
    }

    private void seedInitialState() {
        ModelInput.InitialState s = model.initialState();
        List<String> zones = model.storage().zones();
        for (int i = 0; i < s.storedPallets(); i++) {
            Pallet pl = newPallet(Loc.STORED);
            pl.zone = zones.get(i % zones.size());
            storage.put(pl);
        }
        for (int i = 0; i < s.receivingStagedPallets(); i++) {
            Pallet pl = newPallet(Loc.RECEIVING);
            pl.checkMin = p.receivingCheckTimePerPalletMin().sample(initContent);
            pl.putawayHandlingMin = p.putawayHandlingTimeMin().sample(initContent);
            pl.zone = zones.get(initContent.nextInt(zones.size()));
            sim.schedule(0, "init.receiving|" + pl.id, () -> startReceivingCheck(pl));
        }
        for (int i = 0; i < s.dispatchStagedPallets(); i++) {
            dispatch.put(newPallet(Loc.DISPATCH_STAGED));
        }
        initialPallets = nextPallet;
        maxStored = storage.size();
    }

    private void scheduleSamples() {
        long step = SimTime.ofMinutes(run.trajectorySampleMin());
        long end = SimTime.ofMinutes(run.horizonMin());
        for (long t = 0; t < end; t += step) {
            sim.scheduleAt(t, PRIORITY_SAMPLE, "sample", this::sample);
        }
    }

    // ------------------------------------------------------------------ inbound

    private void scheduleNextInbound() {
        double gap = -60.0 / p.inboundTruckArrivalRatePerHour() * StrictLog.log(inArrivals.nextUniformOpenLow());
        String id = String.format(java.util.Locale.ROOT, "IT-%05d", inboundArrived + 1);
        sim.schedule(SimTime.ofMinutes(gap), "in.arrive|" + id, () -> inboundArrive(id));
    }

    private void inboundArrive(String id) {
        inboundArrived++;
        scheduleNextInbound();
        InboundTruck t = new InboundTruck(id, sim.now());
        int n = count(p.inboundPalletsPerTruck(), inContent);
        t.dockInMin = p.dockInTimeMin().sample(inContent);
        t.dockOutMin = p.dockOutTimeMin().sample(inContent);
        List<String> zones = model.storage().zones();
        for (int i = 0; i < n; i++) {
            Pallet pl = newPallet(Loc.ON_INBOUND_TRUCK);
            pl.unloadMin = p.unloadTimePerPalletMin().sample(inContent);
            pl.checkMin = p.receivingCheckTimePerPalletMin().sample(inContent);
            pl.putawayHandlingMin = p.putawayHandlingTimeMin().sample(inContent);
            pl.zone = zones.get(inContent.nextInt(zones.size()));
            t.pallets.add(pl);
        }
        palletsArrived += n;
        inboundInSystem.add(t);
        inDocks.request(0, t.id(), dock -> {
            t.dock = dock;
            t.docked = true;
            sim.schedule(SimTime.ofMinutes(t.dockInMin), "in.docked|" + t.id() + "|" + dock.id(),
                    () -> startUnloading(t));
        });
    }

    private void startUnloading(InboundTruck t) {
        int lanes = Math.min(p.inboundUnloadLanesPerTruck(), t.pallets.size());
        if (lanes == 0) {
            inboundDockOut(t);
            return;
        }
        t.lanes = lanes;
        for (int i = 0; i < lanes; i++) {
            unloadNext(t);
        }
    }

    private void unloadNext(InboundTruck t) {
        if (t.next >= t.pallets.size()) {
            if (--t.lanes == 0) {
                inboundDockOut(t);
            }
            return;
        }
        Pallet pl = t.pallets.get(t.next++);
        forklifts.request(FL_UNLOAD, "unload|" + pl.id, fl -> {
            move(pl, Loc.UNLOADING);
            sim.schedule(SimTime.ofMinutes(pl.unloadMin), "unloaded|" + pl.id, () -> {
                forklifts.release(fl);
                t.unloaded++;
                move(pl, Loc.RECEIVING);
                startReceivingCheck(pl);
                unloadNext(t);
            });
        });
    }

    private void inboundDockOut(InboundTruck t) {
        sim.schedule(SimTime.ofMinutes(t.dockOutMin), "in.depart|" + t.id(), () -> {
            inDocks.release(t.dock);
            inboundInSystem.remove(t);
            inboundDepartedTotal++;
            inboundDeparted.increment();
            double turnaround = SimTime.toMinutes(sim.now() - t.createdAt());
            inboundTurnaroundMin.add(turnaround);
            if (turnaround > p.inboundTurnaroundTargetMin()) {
                inboundLate.increment();
            }
        });
    }

    private void startReceivingCheck(Pallet pl) {
        UnitPool rw = workers.get(WarehouseParameters.ROLE_RECEIVING);
        rw.request(0, "check|" + pl.id, w -> sim.schedule(SimTime.ofMinutes(pl.checkMin), "checked|" + pl.id, () -> {
            rw.release(w);
            forklifts.request(FL_PUTAWAY, "putaway|" + pl.id, fl -> {
                move(pl, Loc.PUTAWAY);
                double min = pl.putawayHandlingMin + putawayTravelMin.get(pl.zone);
                sim.schedule(SimTime.ofMinutes(min), "stored|" + pl.id, () -> {
                    forklifts.release(fl);
                    move(pl, Loc.STORED);
                    storage.put(pl);
                    maxStored = Math.max(maxStored, storage.size());
                    palletsReceived.increment();
                });
            });
        }));
    }

    // ------------------------------------------------------------------ orders

    private double demandFactor(long now) {
        Overlay.DemandMultiplier d = overlay.demandMultiplier();
        if (d == null) {
            return 1.0;
        }
        double m = SimTime.toMinutes(now);
        return (m >= d.startMin() && m < d.endMin()) ? d.factor() : 1.0;
    }

    private double maxDemandFactor() {
        Overlay.DemandMultiplier d = overlay.demandMultiplier();
        return d == null ? 1.0 : Math.max(1.0, d.factor());
    }

    /** Non-homogeneous Poisson orders by thinning (Lewis-Shedler) with the maximum rate. */
    private void scheduleNextOrderCandidate() {
        double maxRate = p.orderArrivalRatePerHour() * maxDemandFactor();
        double gap = -60.0 / maxRate * StrictLog.log(orderArrivals.nextUniformOpenLow());
        sim.schedule(SimTime.ofMinutes(gap), "order.candidate", () -> {
            double u = orderThinning.nextUniform();
            if (u < demandFactor(sim.now()) / maxDemandFactor()) {
                orderArrive();
            }
            scheduleNextOrderCandidate();
        });
    }

    private void orderArrive() {
        ordersArrived++;
        String id = String.format(java.util.Locale.ROOT, "OR-%05d", ordersArrived);
        int n = Math.max(1, count(p.palletsPerOrder(), orderContent));
        Order o = new Order(id, sim.now(), n);
        for (int i = 0; i < n; i++) {
            o.retrievalMin[i] = p.retrievalHandlingTimeMin().sample(orderContent);
            o.pickMin[i] = p.pickTimePerPalletMin().sample(orderContent);
            o.packMin[i] = p.packTimePerPalletMin().sample(orderContent);
        }
        openOrders.add(o);
        for (int i = 0; i < n; i++) {
            int line = i;
            storage.take("order|" + id + "|" + line, pl -> retrieve(o, line, pl));
        }
    }

    private void retrieve(Order o, int line, Pallet pl) {
        pl.order = o;
        move(pl, Loc.RETRIEVAL);
        forklifts.request(FL_RETRIEVE, "retrieve|" + pl.id, fl -> {
            double min = o.retrievalMin[line] + retrievalTravelMin.get(pl.zone);
            sim.schedule(SimTime.ofMinutes(min), "retrieved|" + pl.id, () -> {
                forklifts.release(fl);
                move(pl, Loc.PICKING);
                UnitPool pickers = workers.get(WarehouseParameters.ROLE_PICKING);
                pickers.request(0, "pick|" + pl.id, w -> sim.schedule(SimTime.ofMinutes(o.pickMin[line]),
                        "picked|" + pl.id, () -> {
                            pickers.release(w);
                            move(pl, Loc.PACKING);
                            UnitPool packers = workers.get(WarehouseParameters.ROLE_PACKING);
                            packers.request(0, "pack|" + pl.id, w2 -> sim.schedule(
                                    SimTime.ofMinutes(o.packMin[line]), "packed|" + pl.id, () -> {
                                        packers.release(w2);
                                        move(pl, Loc.DISPATCH_STAGED);
                                        dispatch.put(pl);
                                    }));
                        }));
            });
        });
    }

    // ------------------------------------------------------------------ outbound

    private void scheduleNextOutbound() {
        double gap = -60.0 / p.outboundTruckArrivalRatePerHour() * StrictLog.log(outArrivals.nextUniformOpenLow());
        String id = String.format(java.util.Locale.ROOT, "OT-%05d", outboundArrived + 1);
        sim.schedule(SimTime.ofMinutes(gap), "out.arrive|" + id, () -> outboundArrive(id));
    }

    private void outboundArrive(String id) {
        outboundArrived++;
        scheduleNextOutbound();
        int cap = count(p.outboundTruckCapacityPallets(), outContent);
        OutboundTruck t = new OutboundTruck(id, sim.now(), cap);
        t.dockInMin = p.dockInTimeMin().sample(outContent);
        t.dockOutMin = p.dockOutTimeMin().sample(outContent);
        for (int i = 0; i < cap; i++) {
            t.loadMin[i] = p.loadTimePerPalletMin().sample(outContent);
        }
        outDocks.request(0, t.id(), dock -> {
            t.dock = dock;
            sim.schedule(SimTime.ofMinutes(t.dockInMin), "out.docked|" + t.id() + "|" + dock.id(),
                    () -> startLoading(t));
        });
    }

    private void startLoading(OutboundTruck t) {
        t.deadline = sim.now() + SimTime.ofMinutes(p.outboundMaxDwellMin());
        int lanes = Math.min(p.outboundLoadLanesPerTruck(), t.capacity);
        if (lanes == 0) {
            outboundDockOut(t);
            return;
        }
        t.lanes = lanes;
        for (int i = 0; i < lanes; i++) {
            loadNext(t);
        }
    }

    private void loadNext(OutboundTruck t) {
        if (t.reserved >= t.capacity || sim.now() >= t.deadline) {
            laneEnd(t);
            return;
        }
        int slot = t.reserved++;
        ScheduledEvent[] timeout = new ScheduledEvent[1];
        Store<Pallet>.Taker taker = dispatch.take("load|" + t.id() + "|" + slot, pl -> {
            if (timeout[0] != null) {
                timeout[0].cancel();
            }
            loadPallet(t, slot, pl);
        });
        if (!taker.served()) {
            timeout[0] = sim.scheduleAt(t.deadline, 0, "load.timeout|" + t.id() + "|" + slot, () -> {
                if (taker.cancel()) {
                    t.reserved--;
                    laneEnd(t);
                }
            });
        }
    }

    private void loadPallet(OutboundTruck t, int slot, Pallet pl) {
        move(pl, Loc.LOADING);
        UnitPool dw = workers.get(WarehouseParameters.ROLE_DISPATCH);
        dw.request(0, "load|" + pl.id, w -> forklifts.request(FL_LOAD, "load|" + pl.id, fl ->
                sim.schedule(SimTime.ofMinutes(t.loadMin[slot]), "loaded|" + pl.id + "|" + t.id(), () -> {
                    forklifts.release(fl);
                    dw.release(w);
                    move(pl, Loc.ON_OUTBOUND_TRUCK);
                    t.loaded.add(pl);
                    loadNext(t);
                })));
    }

    private void laneEnd(OutboundTruck t) {
        if (--t.lanes == 0) {
            outboundDockOut(t);
        }
    }

    private void outboundDockOut(OutboundTruck t) {
        sim.schedule(SimTime.ofMinutes(t.dockOutMin), "out.depart|" + t.id(), () -> {
            outDocks.release(t.dock);
            outboundDepartedTotal++;
            outboundFill.add(t.capacity == 0 ? 0 : t.loaded.size() / (double) t.capacity);
            for (Pallet pl : t.loaded) {
                move(pl, Loc.SHIPPED);
                shippedTotal++;
                palletsShipped.increment();
                Order o = pl.order;
                if (o != null && ++o.shipped == o.pallets) {
                    completeOrder(o);
                }
            }
        });
    }

    private void completeOrder(Order o) {
        openOrders.remove(o);
        ordersCompletedTotal++;
        ordersShipped.increment();
        double cycle = SimTime.toMinutes(sim.now() - o.createdAt());
        orderCycleMin.add(cycle);
        if (cycle > p.orderCycleTimeTargetMin()) {
            ordersLate.increment();
        } else {
            ordersOnTime.increment();
        }
    }

    // ------------------------------------------------------------------ helpers

    private Pallet newPallet(Loc loc) {
        nextPallet++;
        return new Pallet(String.format(java.util.Locale.ROOT, "PL-%06d", nextPallet), loc);
    }

    private void move(Pallet pl, Loc to) {
        locCount.merge(pl.loc, -1, Integer::sum);
        locCount.merge(to, 1, Integer::sum);
        pl.loc = to;
    }

    private static int count(Distribution d, RandomStream s) {
        return (int) Math.max(0, Math.round(d.sample(s)));
    }

    private double roundTripMin(ModelInput.Zone a, ModelInput.Zone b) {
        double manhattan = Math.abs(a.centerX() - b.centerX()) + Math.abs(a.centerY() - b.centerY());
        return 2 * manhattan / p.forkliftSpeedMPerS() / 60.0;
    }

    private static List<String> ids(List<ModelInput.Dock> docks) {
        return docks.stream().map(ModelInput.Dock::dockId).toList();
    }

    private void sample() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("t_min", SimTime.toMinutes(sim.now()));
        s.put("inbound_trucks_waiting", inDocks.queueLength());
        s.put("inbound_docks_occupied", inDocks.busyCount());
        s.put("inbound_docks_open", inDocks.availableCount());
        s.put("outbound_trucks_waiting", outDocks.queueLength());
        s.put("outbound_docks_occupied", outDocks.busyCount());
        s.put("forklifts_busy", forklifts.busyCount());
        s.put("forklifts_available", forklifts.availableCount());
        s.put("forklift_queue", forklifts.queueLength());
        s.put("receiving_pallets", locCount.get(Loc.RECEIVING) + locCount.get(Loc.PUTAWAY));
        s.put("stored_pallets", locCount.get(Loc.STORED));
        s.put("dispatch_staged_pallets", locCount.get(Loc.DISPATCH_STAGED));
        s.put("open_orders", openOrders.size());
        s.put("pallets_shipped_total", shippedTotal);
        trajectory.add(s);
    }

    // ------------------------------------------------------------------ results

    private ReplicationResult result() {
        long now = sim.now();
        double hours = SimTime.toHours(sim.control().measuredDuration());
        double nowMin = SimTime.toMinutes(now);
        int inWaiting = 0;
        double oldestIn = 0;
        int inLateOpen = 0;
        for (InboundTruck t : inboundInSystem) {
            if (!t.docked) {
                inWaiting++;
            }
            double age = nowMin - SimTime.toMinutes(t.createdAt());
            oldestIn = Math.max(oldestIn, age);
            if (age > p.inboundTurnaroundTargetMin()) {
                inLateOpen++;
            }
        }
        int ordersOverdueOpen = 0;
        for (Order o : openOrders) {
            if (nowMin - SimTime.toMinutes(o.createdAt()) > p.orderCycleTimeTargetMin()) {
                ordersOverdueOpen++;
            }
        }
        Tally inWait = inDocks.waitTimes();
        Tally outWait = outDocks.waitTimes();

        Map<String, Double> k = new LinkedHashMap<>();
        k.put("pallets_received_per_hour", palletsReceived.count() / hours);
        k.put("pallets_shipped_per_hour", palletsShipped.count() / hours);
        k.put("orders_shipped_per_hour", ordersShipped.count() / hours);
        k.put("inbound_trucks_completed", (double) inboundDeparted.count());
        k.put("inbound_truck_wait_mean_min", SimTime.toMinutes(1) * inWait.mean());
        k.put("inbound_truck_wait_p95_min", SimTime.toMinutes(1) * inWait.quantile(0.95));
        k.put("inbound_truck_wait_max_min", SimTime.toMinutes(1) * inWait.max());
        k.put("inbound_truck_turnaround_mean_min", inboundTurnaroundMin.mean());
        k.put("inbound_yard_queue_mean", inDocks.meanQueueLength());
        k.put("inbound_dock_utilization", inDocks.utilization());
        k.put("inbound_dock_closed_fraction", inDocks.downFraction());
        k.put("outbound_dock_utilization", outDocks.utilization());
        k.put("outbound_truck_wait_mean_min", SimTime.toMinutes(1) * outWait.mean());
        k.put("outbound_truck_fill_rate", outboundFill.count() == 0 ? Double.NaN : outboundFill.mean());
        k.put("forklift_utilization", forklifts.utilization());
        k.put("forklift_wait_mean_min", SimTime.toMinutes(1) * forklifts.waitTimes().mean());
        k.put("worker_utilization_receiving", workers.get(WarehouseParameters.ROLE_RECEIVING).utilization());
        k.put("worker_utilization_picking", workers.get(WarehouseParameters.ROLE_PICKING).utilization());
        k.put("worker_utilization_packing", workers.get(WarehouseParameters.ROLE_PACKING).utilization());
        k.put("worker_utilization_dispatch", workers.get(WarehouseParameters.ROLE_DISPATCH).utilization());
        k.put("order_cycle_time_mean_min", orderCycleMin.count() == 0 ? Double.NaN : orderCycleMin.mean());
        k.put("order_cycle_time_p95_min", orderCycleMin.count() == 0 ? Double.NaN : orderCycleMin.quantile(0.95));
        k.put("backlog_inbound_trucks_waiting", (double) inWaiting);
        k.put("backlog_inbound_trucks_in_system", (double) inboundInSystem.size());
        k.put("backlog_oldest_inbound_truck_age_min", oldestIn);
        k.put("backlog_receiving_pallets", (double) (locCount.get(Loc.RECEIVING) + locCount.get(Loc.PUTAWAY)));
        k.put("backlog_open_orders", (double) openOrders.size());
        k.put("backlog_dispatch_staged_pallets", (double) locCount.get(Loc.DISPATCH_STAGED));
        k.put("inbound_turnaround_sla_misses", (double) (inboundLate.count() + inLateOpen));
        k.put("order_sla_misses", (double) (ordersLate.count() + ordersOverdueOpen));
        long denom = ordersShipped.count() + ordersOverdueOpen;
        k.put("order_on_time_rate", denom == 0 ? Double.NaN : ordersOnTime.count() / (double) denom);
        if (!k.keySet().equals(new LinkedHashSet<>(Kpis.ALL.stream().map(Kpis.Def::key).toList()))) {
            throw new IllegalStateException("KPI set does not match the catalog");
        }

        return new ReplicationResult(index, seed, sim.trace().hash(), sim.trace().count(), k, conservation(),
                constraints(), trajectory);
    }

    private Map<String, Object> conservation() {
        Map<String, Object> c = new LinkedHashMap<>();
        Map<String, Object> pallets = new LinkedHashMap<>();
        pallets.put("initial", initialPallets);
        pallets.put("arrived_inbound", palletsArrived);
        pallets.put("shipped", shippedTotal);
        long inSystem = 0;
        Map<String, Object> byLoc = new LinkedHashMap<>();
        for (Loc l : Loc.values()) {
            if (l == Loc.SHIPPED) {
                continue;
            }
            int n = locCount.get(l);
            inSystem += n;
            byLoc.put(l.name().toLowerCase(java.util.Locale.ROOT), n);
        }
        pallets.put("in_system", inSystem);
        pallets.put("in_system_by_location", byLoc);
        pallets.put("balanced", initialPallets + palletsArrived == shippedTotal + inSystem
                && locCount.get(Loc.SHIPPED) == shippedTotal);
        c.put("pallets", pallets);

        Map<String, Object> in = new LinkedHashMap<>();
        in.put("arrived", inboundArrived);
        in.put("departed", inboundDepartedTotal);
        in.put("in_system", inboundInSystem.size());
        in.put("balanced", inboundArrived == inboundDepartedTotal + inboundInSystem.size());
        c.put("inbound_trucks", in);

        long outInSystem = outboundArrived - outboundDepartedTotal;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arrived", outboundArrived);
        out.put("departed", outboundDepartedTotal);
        out.put("in_system", outInSystem);
        out.put("balanced", outInSystem == outDocks.queueLength() + outDocks.busyCount());
        c.put("outbound_trucks", out);

        Map<String, Object> orders = new LinkedHashMap<>();
        orders.put("arrived", ordersArrived);
        orders.put("shipped", ordersCompletedTotal);
        orders.put("open", openOrders.size());
        orders.put("balanced", ordersArrived == ordersCompletedTotal + openOrders.size());
        c.put("orders", orders);
        return c;
    }

    private List<Map<String, Object>> constraints() {
        List<Map<String, Object>> out = new ArrayList<>();
        int positions = model.storage().positions();
        if (maxStored > positions) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("code", "STORAGE_CAPACITY_EXCEEDED");
            f.put("message", "stored pallets peaked at " + maxStored + " > " + positions + " rack positions");
            f.put("value", maxStored);
            out.add(f);
        }
        if (storage.waitingTakers() > 0) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("code", "STOCK_SHORTAGE_AT_END");
            f.put("message", storage.waitingTakers() + " order lines waiting for stored pallets at the horizon");
            f.put("value", storage.waitingTakers());
            out.add(f);
        }
        return out;
    }

    /** {@link StrictMath#log} alias, kept local so every logarithm in the model is the strict one. */
    private static final class StrictLog {
        static double log(double x) {
            return StrictMath.log(x);
        }
    }
}
