package io.vorg.api.recommendations;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the recommendation approval policies (ADR-61). */
@Configuration
@EnableConfigurationProperties(DockReassignmentPolicy.class)
public class RecommendationsConfig {}
