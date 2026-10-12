package io.vorg.api.platform.live;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the live (simulated) operations loop, {@code vo.live.*}. Disabled by default: nothing runs unless
 * {@code vo.live.enabled=true} and a tenant and site are configured (the {@code local} profile enables it for the demo
 * tenant's Bengaluru site). The tenant and site are deployment configuration, never request input.
 *
 * <p>Rates: the adapter sync runs every {@code sync.interval} (default 15 s, one batch per call). The movement source
 * ticks every {@code movement.tick-interval} (default 1 s); an entity emits a position observation at most every
 * {@code movement.emit-every-ticks} ticks (default 4, i.e. 0.25 Hz per entity) and only when it moved at least
 * {@code movement.min-move-m} or turned {@code movement.min-turn-deg} since its last emission, so a standing entity
 * produces no events and the database sees at most 46 / 4 = 11.5 position events per second for the 46 simulated
 * movers (typically 6 to 9, about 0.6 million events a day at the demo scale).
 */
@ConfigurationProperties(prefix = "vo.live")
public record LiveProperties(
        @DefaultValue("false") boolean enabled,
        UUID tenantId,
        UUID siteId,
        UUID operatorActorRef,
        @DefaultValue Sync sync,
        @DefaultValue Movement movement) {

    public record Sync(@DefaultValue("15s") Duration interval) {}

    public record Movement(
            @DefaultValue("1s") Duration tickInterval,
            @DefaultValue("1.0") double timeScale,
            @DefaultValue("4") int emitEveryTicks,
            @DefaultValue("0.5") double minMoveM,
            @DefaultValue("5.0") double minTurnDeg,
            @DefaultValue("20261012") long seed,
            @DefaultValue("true") boolean autostart) {}
}
