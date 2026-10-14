package io.vorg.api.cameras;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Vision settings, {@code vo.vision.*} (ADR-68, ADR-69).
 * <ul>
 *   <li>{@code mediaDir}: local directory holding the registered camera media (the downloaded demo clip, the synthetic
 *       still). Blank: no media is served. Media is never uploaded or stored by the API.</li>
 *   <li>{@code maxBatch}: most observations per ingestion command.</li>
 *   <li>{@code pruneInterval}: how often observations older than their camera's {@code retention_days} are deleted.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "vo.vision")
public record VisionProperties(@DefaultValue("") String mediaDir, @DefaultValue("500") int maxBatch,
        @DefaultValue("1h") Duration pruneInterval) {}
