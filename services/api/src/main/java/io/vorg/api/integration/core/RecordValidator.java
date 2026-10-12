package io.vorg.api.integration.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structural data-quality rules applied to every incoming record before it can be mirrored (REQ-041). */
public final class RecordValidator {

    static final Set<String> ORDER_TYPES = Set.of("INBOUND", "OUTBOUND");
    static final Set<String> SHIPMENT_STATES = Set.of("ARRIVING", "AT_DOCK", "UNLOADING", "LOADING", "DEPARTED");
    public static final Set<String> DOCKED_STATES = Set.of("AT_DOCK", "UNLOADING", "LOADING");
    static final Set<String> TASK_TYPES = Set.of("PICK", "PUTAWAY", "DOCK");

    private RecordValidator() {}

    /** Returns the violations; empty means the record is acceptable. */
    public static List<String> validate(SourceRecord record) {
        List<String> errors = new ArrayList<>();
        if (record.sourceRecordId().isBlank()) {
            errors.add("source_record_id is blank");
        }
        if (record.sourceVersion() < 1) {
            errors.add("source_version must be >= 1");
        }
        Map<String, Object> p = record.payload();
        switch (record.kind()) {
            case ORDER -> {
                if (!ORDER_TYPES.contains(text(p, "order_type"))) {
                    errors.add("order_type must be INBOUND or OUTBOUND");
                }
                if (!(p.get("lines") instanceof List<?> lines) || lines.isEmpty()) {
                    errors.add("order has no lines");
                } else {
                    for (Object line : lines) {
                        if (!(line instanceof Map<?, ?> m) || blank(m.get("sku")) || !(number(m.get("qty")) > 0)) {
                            errors.add("order line needs a sku and a positive qty");
                            break;
                        }
                    }
                }
            }
            case INVENTORY -> {
                if (blank(p.get("sku")) || blank(p.get("location_code"))) {
                    errors.add("inventory needs sku and location_code");
                }
                double onHand = number(p.get("on_hand"));
                if (Double.isNaN(onHand) || onHand < 0) {
                    errors.add("on_hand must be a number >= 0");
                }
            }
            case SHIPMENT -> {
                String state = text(p, "state");
                if (!SHIPMENT_STATES.contains(state)) {
                    errors.add("unknown shipment state");
                }
                if (blank(p.get("truck_code"))) {
                    errors.add("shipment needs truck_code");
                }
                if (DOCKED_STATES.contains(state) && blank(p.get("dock_name"))) {
                    errors.add("a docked shipment needs dock_name");
                }
            }
            case TASK -> {
                if (!TASK_TYPES.contains(text(p, "task_type"))) {
                    errors.add("task_type must be PICK, PUTAWAY or DOCK");
                }
                for (String end : new String[] {"from", "to"}) {
                    if (!(p.get(end) instanceof Map<?, ?> m) || blank(m.get("label"))
                            || !finite(m.get("x")) || !finite(m.get("y"))) {
                        errors.add("task " + end + " needs label, x and y");
                    }
                }
            }
        }
        return errors;
    }

    private static String text(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? "" : v.toString();
    }

    private static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }

    private static double number(Object v) {
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static boolean finite(Object v) {
        return v instanceof Number n && Double.isFinite(n.doubleValue());
    }
}
