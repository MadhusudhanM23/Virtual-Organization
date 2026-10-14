package io.vorg.api.recommendations;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Without a {@link RecommendationGenerator} bean, proposing is a 503 {@code generator_unavailable} for an authorized
 * caller, and an unauthorized caller is still rejected (403) by the gateway. The OR-Tools generator is always
 * registered on a normal server, so this context removes its bean definition.
 */
@Import(RecommendationGeneratorUnavailableIT.NoGenerator.class)
@DirtiesContext
class RecommendationGeneratorUnavailableIT extends AbstractApiIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class NoGenerator {

        @Bean
        static BeanDefinitionRegistryPostProcessor removeGenerator() {
            return (BeanDefinitionRegistry registry) -> {
                for (String name : registry.getBeanDefinitionNames()) {
                    String type = registry.getBeanDefinition(name).getBeanClassName();
                    if (OrToolsRecommendationGenerator.class.getName().equals(type)) {
                        registry.removeBeanDefinition(name);
                    }
                }
            };
        }
    }

    private String body() {
        return "{\"site_id\":\"" + SITE_BLR + "\",\"simulation_run_id\":\"" + UUID.randomUUID() + "\"}";
    }

    @Test
    void proposingWithoutAGeneratorIs503() throws Exception {
        HttpResponse<String> res = post("/api/recommendations", token(TENANT_A_SLUG, "wm.blr@demo.invalid"), body(),
                "Idempotency-Key", "rec-unavailable-" + System.nanoTime());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(503);
        assertThat(json(res).get("code").asString()).isEqualTo("generator_unavailable");
        assertThat(json(res).get("correlation_id").asString()).isNotBlank();
    }

    @Test
    void aDriverIsStillForbidden() throws Exception {
        HttpResponse<String> res = post("/api/recommendations", token(TENANT_A_SLUG, "driver.blr@demo.invalid"),
                body(), "Idempotency-Key", "rec-unavailable-driver-" + System.nanoTime());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(403);
    }
}
