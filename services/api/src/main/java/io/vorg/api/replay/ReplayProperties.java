package io.vorg.api.replay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Replay settings, {@code vo.replay.*} (ADR-44, ADR-45, ADR-46).
 *
 * <ul>
 *   <li>{@code snapshots.enabled} / {@code interval} / {@code retention}: the periodic snapshot job (off by default, on in
 *       {@code local}), its cadence (5 min, ADR-23) and how long snapshots are kept (7 days; the newest of a site is
 *       always kept).</li>
 *   <li>{@code ingest-overlap}: events ingested up to this long before a snapshot was taken are re-read when
 *       reconstructing from it (covers transactions in flight and the projection lag of a genesis snapshot).</li>
 *   <li>{@code late-horizon}: how far before a snapshot's high-water mark a late event is still looked for.</li>
 *   <li>{@code recent-window}: terminal tasks/incidents and proximity alerts stay visible this long.</li>
 *   <li>{@code frames.*}: playback bounds per call.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "vo.replay")
public record ReplayProperties(
        @DefaultValue Snapshots snapshots,
        @DefaultValue("30s") Duration ingestOverlap,
        @DefaultValue("1h") Duration lateHorizon,
        @DefaultValue("15m") Duration recentWindow,
        @DefaultValue Frames frames) {

    public record Snapshots(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("5m") Duration interval,
            @DefaultValue("30s") Duration initialDelay,
            @DefaultValue("7d") Duration retention) {}

    public record Frames(
            @DefaultValue("100") long minStepMs,
            @DefaultValue("60000") long maxStepMs,
            @DefaultValue("600") int maxFrames,
            @DefaultValue("50000") int maxEvents) {}
}
