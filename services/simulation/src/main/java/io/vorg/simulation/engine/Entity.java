package io.vorg.simulation.engine;

import java.util.Objects;

/**
 * Base class of simulated entities (trucks, pallets, orders). An entity has a deterministic id assigned by the model
 * (never a random UUID) and its creation time; models drive entities through processes written as chains of
 * scheduled events and resource/store callbacks (process-interaction by continuation).
 */
public abstract class Entity {

    private final String id;
    private final long createdAt;

    protected Entity(String id, long createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.createdAt = createdAt;
    }

    public final String id() {
        return id;
    }

    public final long createdAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return id;
    }
}
