package io.vorg.api.safety;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SafetyProperties.class)
public class SafetyConfig {}
