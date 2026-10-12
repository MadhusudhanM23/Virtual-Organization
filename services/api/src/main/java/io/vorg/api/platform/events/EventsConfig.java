package io.vorg.api.platform.events;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Shared event-platform beans: the UTC clock, the envelope codec, and scheduling for the outbox and partitions. */
@Configuration
@EnableScheduling
public class EventsConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EnvelopeCodec envelopeCodec() {
        return new EnvelopeCodec();
    }
}
