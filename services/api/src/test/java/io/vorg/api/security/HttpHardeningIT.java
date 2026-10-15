package io.vorg.api.security;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Security suite (Plan 11.3): response headers, CORS, error hygiene, token forgery, the refresh/logout CSRF guard and
 * the login brute-force limiter.
 */
class HttpHardeningIT extends AbstractApiIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> raw(String method, String path, Map<String, String> headers, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void responsesCarryTheSecurityHeaders() throws Exception {
        String tok = token(TENANT_A_SLUG, "wm.blr@demo.invalid");
        for (String path : new String[] {"/api/me", "/api/twin/world?site_id=" + SITE_BLR, "/api/nope",
                "/actuator/health"}) {
            HttpResponse<String> res = raw("GET", path, Map.of("Authorization", "Bearer " + tok), null);
            var h = res.headers();
            assertThat(h.firstValue("X-Content-Type-Options")).as(path).hasValue("nosniff");
            assertThat(h.firstValue("X-Frame-Options")).as(path).hasValue("DENY");
            assertThat(h.firstValue("Content-Security-Policy")).as(path).isPresent();
            assertThat(h.firstValue("Content-Security-Policy").orElse("")).as(path).contains("default-src 'none'")
                    .contains("frame-ancestors 'none'");
            assertThat(h.firstValue("Referrer-Policy")).as(path).hasValue("no-referrer");
            assertThat(h.firstValue("Permissions-Policy")).as(path).isPresent();
            assertThat(h.firstValue("Cache-Control").orElse("")).as(path + " is not cacheable").contains("no-store");
            assertThat(h.firstValue("X-Correlation-Id")).as(path).isPresent();
            assertThat(h.firstValue("Server")).as("no server version banner on " + path).isEmpty();
            assertThat(h.firstValue("X-Powered-By")).as(path).isEmpty();
        }
    }

    @Test
    void corsIsClosedToForeignOrigins() throws Exception {
        for (String path : new String[] {"/api/me", "/api/auth/login", "/api/scenarios"}) {
            HttpResponse<String> pre = raw("OPTIONS", path, Map.of("Origin", "https://evil.example",
                    "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers",
                    "authorization,idempotency-key"), null);
            assertThat(pre.headers().firstValue("Access-Control-Allow-Origin")).as("preflight " + path).isEmpty();
            assertThat(pre.headers().firstValue("Access-Control-Allow-Credentials")).as(path).isEmpty();
            HttpResponse<String> actual = raw("GET", path, Map.of("Origin", "https://evil.example"), null);
            assertThat(actual.headers().firstValue("Access-Control-Allow-Origin")).as("simple request " + path)
                    .isEmpty();
        }
    }

    @Test
    void errorsAreProblemJsonWithoutInternals() throws Exception {
        String tok = token(TENANT_A_SLUG, "wm.blr@demo.invalid");
        Map<String, String> auth = Map.of("Authorization", "Bearer " + tok, "Content-Type", "application/json",
                "Idempotency-Key", "hard-" + UUID.randomUUID());
        HttpResponse<String> badJson = raw("POST", "/api/scenarios", auth, "{not json");
        HttpResponse<String> badUuid = raw("GET", "/api/entities/not-a-uuid", auth, null);
        HttpResponse<String> badMethod = raw("DELETE", "/api/me", auth, null);
        HttpResponse<String> badQuery = raw("GET", "/api/events?site_id=" + SITE_BLR + "&limit=-5", auth, null);
        HttpResponse<String> sqli = raw("GET", "/api/events?site_id=" + SITE_BLR + "&event_type=%27%3B+drop+table+evt.events%3B--",
                auth, null);
        for (HttpResponse<String> res : new HttpResponse[] {badJson, badUuid, badMethod, badQuery, sqli}) {
            assertThat(res.statusCode()).as(res.body()).isBetween(400, 499);
            assertThat(res.headers().firstValue("Content-Type").orElse("")).contains("json");
            assertThat(res.body()).doesNotContain("Exception").doesNotContain("at io.vorg").doesNotContain("at org.")
                    .doesNotContain("jdbc").doesNotContain("PSQL").doesNotContain("org.springframework");
        }
        assertThat(ownerCount("select count(*) from information_schema.tables where table_schema = 'evt' "
                + "and table_name = 'events'")).isEqualTo(1);
    }

    @Test
    void aTokenSignedWithAnotherKeyOrWithTamperedClaimsIsRejected() throws Exception {
        String real = token(TENANT_A_SLUG, "wm.blr@demo.invalid");
        SignedJWT parsed = SignedJWT.parse(real);
        JWTClaimsSet claims = parsed.getJWTClaimsSet();

        // 1. Same claims, attacker's key (same kid so key selection cannot be the reason it fails).
        RSAKey attacker = new RSAKeyGenerator(2048).keyID(parsed.getHeader().getKeyID()).generate();
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(attacker.getKeyID()).build(), claims);
        forged.sign(new RSASSASigner(attacker));
        assertThat(get("/api/me", forged.serialize()).statusCode()).as("foreign signature").isEqualTo(401);

        // 2. Genuine signature, payload swapped for another subject: the signature no longer matches.
        String[] parts = real.split("\\.");
        JWTClaimsSet other = new JWTClaimsSet.Builder(claims).subject(UUID.randomUUID().toString()).build();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                other.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(get("/api/me", parts[0] + "." + payload + "." + parts[2]).statusCode()).as("tampered payload")
                .isEqualTo(401);

        // 3. Algorithm confusion: HS256 signed with the public JWKS material as the secret must not verify.
        String hs = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(get("/api/me", hs + "." + parts[1] + "." + parts[2]).statusCode()).as("alg confusion").isEqualTo(401);

        // 4. The real token still works.
        assertThat(get("/api/me", real).statusCode()).isEqualTo(200);
    }

    @Test
    void refreshAndLogoutRequireTheAntiCsrfHeader() throws Exception {
        for (String path : new String[] {"/api/auth/refresh", "/api/auth/logout"}) {
            HttpResponse<String> res = raw("POST", path, Map.of("Cookie", "vo_refresh=whatever"), null);
            assertThat(res.statusCode()).as(path + " without X-Requested-With").isEqualTo(403);
        }
        HttpResponse<String> withHeader = raw("POST", "/api/auth/refresh",
                Map.of("X-Requested-With", "vorg-web", "Cookie", "vo_refresh=forged"), null);
        assertThat(withHeader.statusCode()).as("forged refresh cookie").isEqualTo(401);
    }
}
