package io.vorg.api.recommendations;

import java.util.Map;
import java.util.Optional;

/**
 * What each plan command changes, for the dry-run diff: the entity field it sets and the step parameter holding the
 * new value. The command types are the ones the simulated adapter executes for {@code DOCK_REASSIGNMENT} plans
 * (Plan 8.3: {@code REASSIGN_TRIP_DOCK {trip_code, to_dock}} and {@code REASSIGN_FORKLIFT_DOCK {forklift_code,
 * to_dock}}); a {@link CurrentStateReader} reports the current value under the same field name. A command type that
 * is not listed cannot be dry-run (422 {@code dry_run_unsupported}) rather than being shown with a guessed effect.
 */
public final class StepEffects {

    public static final String REASSIGN_TRIP_DOCK = "REASSIGN_TRIP_DOCK";
    public static final String REASSIGN_FORKLIFT_DOCK = "REASSIGN_FORKLIFT_DOCK";
    public static final String DOCK_FIELD = "dock_code";

    /** The field a command sets and the parameter that holds its new value. */
    public record Effect(String field, String param) {}

    private static final Map<String, Effect> EFFECTS = Map.of(
            REASSIGN_TRIP_DOCK, new Effect(DOCK_FIELD, "to_dock"),
            REASSIGN_FORKLIFT_DOCK, new Effect(DOCK_FIELD, "to_dock"));

    private StepEffects() {}

    public static Optional<Effect> of(String commandType) {
        return Optional.ofNullable(EFFECTS.get(commandType));
    }
}
