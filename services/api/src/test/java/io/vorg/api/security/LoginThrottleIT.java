package io.vorg.api.security;

import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.context.TestPropertySource;

/**
 * Security suite (Plan 11.3): login brute-force protection. The marker property gives this class its own Spring context,
 * so the failed logins here (which deliberately exhaust the per-address budget of 127.0.0.1) cannot throttle the
 * logins of the other integration tests, which share a JVM and an address.
 */
@TestPropertySource(properties = "vo.test.context-marker=login-throttle")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoginThrottleIT extends AbstractApiIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> login(String email, String password) throws Exception {
        String body = "{\"tenant\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(TENANT_A_SLUG, email, password);
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1) // the address budget is shared by the tests of this class, so the cheap one runs first
    void oneAccountHammeredFromOneAddressIsThrottledAfterAFewFailures() throws Exception {
        String email = "hammer-" + UUID.randomUUID() + "@demo.invalid";
        int blockedAt = -1;
        for (int i = 1; i <= 20; i++) {
            HttpResponse<String> res = login(email, "wrong");
            if (res.statusCode() == 429) {
                blockedAt = i;
                assertThat(res.headers().firstValue("Retry-After")).isPresent();
                break;
            }
            assertThat(res.statusCode()).isEqualTo(401);
        }
        assertThat(blockedAt).as("attempt at which the address+account window blocks").isBetween(2, 10);
    }

    @Test
    @Order(2)
    void passwordSprayAcrossManyAccountsFromOneAddressIsThrottled() throws Exception {
        int blockedAt = -1;
        for (int i = 1; i <= 200; i++) {
            HttpResponse<String> res = login("spray-" + i + "-" + UUID.randomUUID() + "@demo.invalid", "Summer2026!");
            if (res.statusCode() == 429) {
                blockedAt = i;
                break;
            }
            assertThat(res.statusCode()).isEqualTo(401);
        }
        assertThat(blockedAt).as("attempt at which the per-address window blocks a spray").isBetween(2, 100);
        // The throttle also stops a legitimate login from the sprayed address until the window passes: that is the
        // accepted trade-off of an address budget (documented in ADR-71), not a bug.
    }

    @Test
    @Order(3)
    void aDistributedAttackOnOneAccountIsThrottledWhateverTheAddress() throws Exception {
        // The account window counts failures from every address; the test client has one address, so it exercises the
        // account budget by cycling accounts out of the per-address budget through the unit-level throttle.
        io.vorg.api.platform.identity.LoginThrottle t = io.vorg.api.platform.identity.LoginThrottle.standard();
        String email = "victim@demo.invalid";
        for (int i = 0; i < 19; i++) {
            t.recordFailure("203.0.113." + i, TENANT_A_SLUG, email);
        }
        assertThat(t.isBlocked("198.51.100.1", TENANT_A_SLUG, email)).as("19 failures from 19 addresses").isFalse();
        t.recordFailure("203.0.113.99", TENANT_A_SLUG, email);
        assertThat(t.isBlocked("198.51.100.1", TENANT_A_SLUG, email)).as("20th failure blocks every address").isTrue();
        assertThat(t.isBlocked("198.51.100.1", TENANT_A_SLUG, "someone-else@demo.invalid")).isFalse();
        // A success from the attacker's own account does not refill the address or account budgets.
        t.recordSuccess("203.0.113.5", email);
        assertThat(t.isBlocked("198.51.100.1", TENANT_A_SLUG, email)).isTrue();
    }
}
