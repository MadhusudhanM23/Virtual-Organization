package io.vorg.api.platform.live;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link LiveProperties}. */
@Configuration
@EnableConfigurationProperties(LiveProperties.class)
public class LiveConfig {}
