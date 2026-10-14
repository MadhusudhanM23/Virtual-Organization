package io.vorg.api.recommendations;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Registers the test-only generator and current-state reader for the tests that {@code @Import} it. They are
 * {@code @Primary} over the OR-Tools generator and assignment reader of Plan 8.3.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecommendationTestConfig {

    @Bean
    @Primary
    FakeCurrentStateReader fakeCurrentStateReader() {
        return new FakeCurrentStateReader();
    }

    @Bean
    @Primary
    FakeRecommendationGenerator fakeRecommendationGenerator(FakeCurrentStateReader state) {
        return new FakeRecommendationGenerator(state);
    }
}
