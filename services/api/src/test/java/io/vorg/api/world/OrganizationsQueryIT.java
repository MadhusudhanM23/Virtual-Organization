package io.vorg.api.world;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_MUM;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_B;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
class OrganizationsQueryIT extends AbstractIntegrationTest {

    private static final String DEMO_PASSWORD = "demo-password-for-tests";

    @DynamicPropertySource
    static void demoPassword(DynamicPropertyRegistry registry) {
        registry.add("vo.demo.user-password", () -> DEMO_PASSWORD);
    }

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private String token(String tenant, String email) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        Map.of("tenant", tenant, "email", email, "password", DEMO_PASSWORD)))).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("login %s", email).isEqualTo(200);
        return mapper.readTree(res.body()).get("access_token").asString();
    }

    private JsonNode organizations(String tenant, String email) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/organizations"))
                .header("Authorization", "Bearer " + token(tenant, email)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        return mapper.readTree(res.body());
    }

    private static List<String> siteIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        for (JsonNode org : body.get("organizations")) {
            for (JsonNode region : org.get("regions")) {
                for (JsonNode site : region.get("sites")) {
                    ids.add(site.get("id").asString());
                }
            }
        }
        return ids;
    }

    @Test
    void executiveSeesBothSitesWithLocaleCurrencyAndUnits() throws Exception {
        JsonNode body = organizations(TENANT_A_SLUG, "exec@demo.invalid");
        assertThat(siteIds(body)).containsExactlyInAnyOrder(SITE_BLR.toString(), SITE_MUM.toString());
        assertThat(body.get("technical_metadata_only").asBoolean()).isFalse();
        JsonNode blr = null;
        for (JsonNode region : body.get("organizations").get(0).get("regions")) {
            for (JsonNode site : region.get("sites")) {
                if (site.get("id").asString().equals(SITE_BLR.toString())) {
                    blr = site;
                }
            }
        }
        assertThat(blr).isNotNull();
        assertThat(blr.get("name").asString()).isEqualTo("Bengaluru Warehouse 01");
        assertThat(blr.get("time_zone").asString()).isEqualTo("Asia/Kolkata");
        assertThat(blr.get("locale").asString()).isEqualTo("en-IN");
        assertThat(blr.get("currency").asString()).isEqualTo("INR");
        assertThat(blr.get("unit_system").asString()).isEqualTo("METRIC");
    }

    @Test
    void analystAndSafetyHaveOrgWideVisibility() throws Exception {
        assertThat(siteIds(organizations(TENANT_A_SLUG, "analyst@demo.invalid")))
                .containsExactlyInAnyOrder(SITE_BLR.toString(), SITE_MUM.toString());
        assertThat(siteIds(organizations(TENANT_A_SLUG, "safety@demo.invalid")))
                .containsExactlyInAnyOrder(SITE_BLR.toString(), SITE_MUM.toString());
    }

    @Test
    void regionalManagerSouthSeesOnlyBengaluru() throws Exception {
        JsonNode body = organizations(TENANT_A_SLUG, "regional.south@demo.invalid");
        assertThat(siteIds(body)).containsExactly(SITE_BLR.toString());
        assertThat(body.toString()).doesNotContain("Mumbai");
    }

    @Test
    void warehouseManagerBengaluruSeesOnlyBengaluru() throws Exception {
        JsonNode body = organizations(TENANT_A_SLUG, "wm.blr@demo.invalid");
        assertThat(siteIds(body)).containsExactly(SITE_BLR.toString());
        assertThat(body.toString()).doesNotContain("Mumbai");
    }

    @Test
    void driverHasNoBroadDataAccess() throws Exception {
        assertThat(siteIds(organizations(TENANT_A_SLUG, "driver.blr@demo.invalid"))).isEmpty();
    }

    @Test
    void platformAdminGetsTechnicalMetadataOnly() throws Exception {
        JsonNode body = organizations(TENANT_A_SLUG, "admin@demo.invalid");
        assertThat(body.get("technical_metadata_only").asBoolean()).isTrue();
        assertThat(siteIds(body)).containsExactlyInAnyOrder(SITE_BLR.toString(), SITE_MUM.toString());
        assertThat(body.toString()).doesNotContain("Bengaluru", "Mumbai", "MarutiFlex", "Asia/Kolkata", "INR");
    }

    @Test
    void tenantBExecutiveNeverSeesTenantAData() throws Exception {
        JsonNode body = organizations(TENANT_B_SLUG, "exec@demo.invalid");
        assertThat(siteIds(body)).containsExactly(SITE_B.toString());
        assertThat(body.toString()).doesNotContain(SITE_BLR.toString(), SITE_MUM.toString(), "Bengaluru", "MarutiFlex");
        assertThat(siteIds(organizations(TENANT_B_SLUG, "wm@demo.invalid"))).containsExactly(SITE_B.toString());
    }

    @Test
    void tenantAExecutiveNeverSeesTenantBData() throws Exception {
        JsonNode body = organizations(TENANT_A_SLUG, "exec@demo.invalid");
        assertThat(body.toString()).doesNotContain(SITE_B.toString(), "Pune", "Tenant B");
    }

    @Test
    void seedingIsIdempotent() throws Exception {
        UUID before = UUID.fromString(organizations(TENANT_A_SLUG, "exec@demo.invalid")
                .get("organizations").get(0).get("id").asString());
        // Running the seeder again must not duplicate or fail.
        seeder.run(null);
        JsonNode after = organizations(TENANT_A_SLUG, "exec@demo.invalid");
        assertThat(after.get("organizations")).hasSize(1);
        assertThat(UUID.fromString(after.get("organizations").get(0).get("id").asString())).isEqualTo(before);
        assertThat(siteIds(after)).hasSize(2);
    }

    @Autowired
    io.vorg.api.platform.identity.seed.DemoSeeder seeder;
}
