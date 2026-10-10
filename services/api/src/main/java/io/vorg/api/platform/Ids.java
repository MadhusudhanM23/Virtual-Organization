package io.vorg.api.platform;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/** Application-generated identifiers: UUIDv7 (ADR-28). */
public final class Ids {

    private Ids() {}

    public static UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
