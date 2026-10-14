package io.vorg.api.cameras;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(VisionProperties.class)
public class CamerasConfig {}
