package io.vorg.api.replay;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link ReplayProperties}, the pure reducer and the canonical replay codec. */
@Configuration
@EnableConfigurationProperties(ReplayProperties.class)
public class ReplayConfig {

    @Bean
    ReplayReducer replayReducer(ReplayProperties props) {
        return new ReplayReducer(props.recentWindow());
    }

    @Bean
    ReplayCodec replayCodec() {
        return new ReplayCodec();
    }
}
