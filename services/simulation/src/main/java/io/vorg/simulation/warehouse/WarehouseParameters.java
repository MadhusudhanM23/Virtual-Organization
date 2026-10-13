package io.vorg.simulation.warehouse;

import io.vorg.simulation.random.Distribution;
import java.util.List;

/**
 * Typed, validated view of the assumptions the warehouse flow model needs (ADR-48). Times are minutes, rates per hour,
 * distances meters, speeds m/s.
 */
public record WarehouseParameters(
        double inboundTruckArrivalRatePerHour,
        Distribution inboundPalletsPerTruck,
        int inboundUnloadLanesPerTruck,
        Distribution dockInTimeMin,
        Distribution dockOutTimeMin,
        Distribution unloadTimePerPalletMin,
        Distribution receivingCheckTimePerPalletMin,
        Distribution putawayHandlingTimeMin,
        double forkliftSpeedMPerS,
        double inboundTurnaroundTargetMin,
        double orderArrivalRatePerHour,
        Distribution palletsPerOrder,
        Distribution retrievalHandlingTimeMin,
        Distribution pickTimePerPalletMin,
        Distribution packTimePerPalletMin,
        double orderCycleTimeTargetMin,
        double outboundTruckArrivalRatePerHour,
        Distribution outboundTruckCapacityPallets,
        int outboundLoadLanesPerTruck,
        Distribution loadTimePerPalletMin,
        double outboundMaxDwellMin) {

    /** Worker roles the model assigns work to. */
    public static final String ROLE_RECEIVING = "RECEIVING";
    public static final String ROLE_PICKING = "PICKING";
    public static final String ROLE_PACKING = "PACKING";
    public static final String ROLE_DISPATCH = "DISPATCH";
    public static final List<String> REQUIRED_ROLES = List.of(ROLE_RECEIVING, ROLE_PICKING, ROLE_PACKING, ROLE_DISPATCH);

    public static final String ZONE_RECEIVING = "RECEIVING";
    public static final String ZONE_PICKING = "PICKING";

    /** Assumption keys, in documentation order. */
    public static final List<String> KEYS = List.of(
            "inbound_truck_arrival_rate_per_hour", "inbound_pallets_per_truck", "inbound_unload_lanes_per_truck",
            "dock_in_time_min", "dock_out_time_min", "unload_time_per_pallet_min",
            "receiving_check_time_per_pallet_min", "putaway_handling_time_min", "forklift_speed_m_per_s",
            "inbound_turnaround_target_min", "order_arrival_rate_per_hour", "pallets_per_order",
            "retrieval_handling_time_min", "pick_time_per_pallet_min", "pack_time_per_pallet_min",
            "order_cycle_time_target_min", "outbound_truck_arrival_rate_per_hour", "outbound_truck_capacity_pallets",
            "outbound_load_lanes_per_truck", "load_time_per_pallet_min", "outbound_max_dwell_min");

    public static WarehouseParameters of(ModelInput in) {
        return new WarehouseParameters(
                positive(in, "inbound_truck_arrival_rate_per_hour"),
                nonNegativeDist(in, "inbound_pallets_per_truck"),
                positiveInt(in, "inbound_unload_lanes_per_truck"),
                nonNegativeDist(in, "dock_in_time_min"),
                nonNegativeDist(in, "dock_out_time_min"),
                nonNegativeDist(in, "unload_time_per_pallet_min"),
                nonNegativeDist(in, "receiving_check_time_per_pallet_min"),
                nonNegativeDist(in, "putaway_handling_time_min"),
                positive(in, "forklift_speed_m_per_s"),
                positive(in, "inbound_turnaround_target_min"),
                positive(in, "order_arrival_rate_per_hour"),
                nonNegativeDist(in, "pallets_per_order"),
                nonNegativeDist(in, "retrieval_handling_time_min"),
                nonNegativeDist(in, "pick_time_per_pallet_min"),
                nonNegativeDist(in, "pack_time_per_pallet_min"),
                positive(in, "order_cycle_time_target_min"),
                positive(in, "outbound_truck_arrival_rate_per_hour"),
                nonNegativeDist(in, "outbound_truck_capacity_pallets"),
                positiveInt(in, "outbound_load_lanes_per_truck"),
                nonNegativeDist(in, "load_time_per_pallet_min"),
                positive(in, "outbound_max_dwell_min"));
    }

    /** Validates assumptions, layout and resources needed by the model. */
    static void validate(ModelInput in) {
        of(in);
        for (String k : in.assumptions().keySet()) {
            if (!KEYS.contains(k)) {
                throw new InvalidInputException("model.assumptions." + k + ": not used by the warehouse flow model");
            }
        }
        if (in.docks(ModelInput.Direction.INBOUND).isEmpty() || in.docks(ModelInput.Direction.OUTBOUND).isEmpty()) {
            throw new InvalidInputException("model.layout.docks: at least one INBOUND and one OUTBOUND dock");
        }
        if (in.forklifts().isEmpty()) {
            throw new InvalidInputException("model.resources.forklifts: at least one forklift");
        }
        for (String role : REQUIRED_ROLES) {
            Integer n = in.workers().get(role);
            if (n == null || n < 1) {
                throw new InvalidInputException("model.resources.workers." + role + ": at least one worker");
            }
        }
        in.zone(ZONE_RECEIVING);
        in.zone(ZONE_PICKING);
        if (in.storage().zones().isEmpty()) {
            throw new InvalidInputException("model.layout.storage.zones: at least one storage zone");
        }
        for (String z : in.storage().zones()) {
            in.zone(z);
        }
        if (in.storage().positions() <= 0) {
            throw new InvalidInputException("model.layout.storage: positive capacity required");
        }
        ModelInput.InitialState s = in.initialState();
        if (s.storedPallets() < 0 || s.receivingStagedPallets() < 0 || s.dispatchStagedPallets() < 0) {
            throw new InvalidInputException("model.initial_state: counts must be >= 0");
        }
    }

    private static double positive(ModelInput in, String key) {
        Assumption a = in.assumption(key);
        if (a.isDistribution() || !(a.number() > 0)) {
            throw new InvalidInputException("model.assumptions." + key + ": positive number required");
        }
        return a.number();
    }

    private static int positiveInt(ModelInput in, String key) {
        double v = positive(in, key);
        if (v != Math.rint(v) || v > 1000) {
            throw new InvalidInputException("model.assumptions." + key + ": integer in [1, 1000] required");
        }
        return (int) v;
    }

    private static Distribution nonNegativeDist(ModelInput in, String key) {
        Assumption a = in.assumption(key);
        if (!a.isDistribution()) {
            throw new InvalidInputException("model.assumptions." + key + ": distribution required");
        }
        Distribution d = a.distribution();
        boolean ok = switch (d) {
            case Distribution.Constant c -> c.value() >= 0;
            case Distribution.Uniform u -> u.min() >= 0;
            case Distribution.Triangular t -> t.min() >= 0;
            case Distribution.DiscreteUniform du -> du.min() >= 0;
            case Distribution.Discrete di -> di.values().stream().allMatch(v -> v >= 0);
            case Distribution.Exponential e -> true;
            case Distribution.Lognormal l -> true;
            case Distribution.Erlang e -> true;
            case Distribution.Normal n -> false; // can be negative
        };
        if (!ok) {
            throw new InvalidInputException("model.assumptions." + key + ": distribution must be non-negative");
        }
        return d;
    }
}
