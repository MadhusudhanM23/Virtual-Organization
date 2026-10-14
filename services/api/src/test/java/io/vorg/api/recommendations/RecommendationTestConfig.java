package io.vorg.api.recommendations;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Registers the test-only generator and current-state reader for the tests that {@code @Import} it. */
@TestConfiguration(proxyBeanMethods = false)
public class RecommendationTestConfig {

    @Bean
    FakeCurrentStateReader fakeCurrentStateReader() {
        return new FakeCurrentStateReader();
    }

    @Bean
    FakeRecommendationGenerator fakeRecommendationGenerator(FakeCurrentStateReader state) {
        return new FakeRecommendationGenerator(state);
    }
}
