package io.vorg.api.recommendations;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Without a {@link RecommendationGenerator} bean (the default until Plan 8.3 wires the optimizer), proposing is a 503
 * {@code generator_unavailable} for an authorized caller; an unauthorized caller is still rejected (403) by the
 * gateway. Runs in the shared default context (no fake generator imported).
 */
@Disabled("Plan 8.3 registers the OR-Tools generator, so the default context always has one; the 503 path needs"
        + " a context without the bean (to be rewritten in Plan 8.3 task 4)")
class RecommendationGeneratorUnavailableIT extends AbstractApiIntegrationTest {

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
