package io.vorg.api.recommendations;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the recommendation approval policies (ADR-61) and the generation/execution settings (ADR-63). */
@Configuration
@EnableConfigurationProperties({DockReassignmentPolicy.class, ExecutionProperties.class})
public class RecommendationsConfig {}
