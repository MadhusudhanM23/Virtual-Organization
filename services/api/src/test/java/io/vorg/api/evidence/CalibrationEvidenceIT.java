package io.vorg.api.evidence;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.calibration.CalibrationService;
import io.vorg.api.integration.simulated.MovementSourceRunner;
import io.vorg.api.platform.events.outbox.OutboxPoller;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.support.AbstractLiveWorldIT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Calibration against the platform's own event store (REQ-140, ADR-82). The movement source (seeded, SIMULATED) writes
 * one simulated hour of forklift positions into {@code evt.events}; {@link CalibrationService} reads them back and
 * fits {@code forklift_speed_m_per_s}. The always-on test checks the shape on a small run; the opt-in evidence run
 * ({@code -Dvorg.evidence=true}) writes {@code target/evidence/calibration.json}, cited by
 * docs/scenarios/calibration.md.
 */
class CalibrationEvidenceIT extends AbstractLiveWorldIT {

    @DynamicPropertySource
    static void live(DynamicPropertyRegistry registry) {
        registry.add("vo.live.tenant-id", TENANT_A::toString);
        registry.add("vo.live.site-id", SITE_BLR::toString);
        registry.add("vo.live.enabled", () -> "false");
    }

    @Autowired
    MovementSourceRunner runner;
    @Autowired
    OutboxPoller poller;
    @Autowired
    CalibrationService calibration;

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(int ticks) {
        runner.reset();
        runner.configure(1000L, 1.0);
        Instant from = Instant.now().minusSeconds(5);
        runner.step(ticks);
        Instant to = Instant.now().plusSeconds(5);
        runner.reset();
        return TenantContext.callWith(TENANT_A, () -> calibration.report(TENANT_A, SITE_BLR, from, to));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportHasTheDocumentedShapeAndIsHonestAboutWhatItDoesNotFit() {
        Map<String, Object> report = run(300);
        assertThat(report.get("schema")).isEqualTo(CalibrationService.SCHEMA);
        assertThat((Map<String, Long>) report.get("observed_data_modes")).containsOnlyKeys("SIMULATED");
        Map<String, Object> fit = (Map<String, Object>) report.get("fit");
        assertThat(fit.get("parameter")).isEqualTo("forklift_speed_m_per_s");
        assertThat(fit.get("model_value")).isEqualTo(2.0);
        assertThat((int) fit.get("n_samples")).isGreaterThan(0);
        Map<String, Object> inventory = (Map<String, Object>) report.get("inventory");
        assertThat((Map<String, Object>) inventory.get("not_fitted")).containsKeys(
                "inbound_truck_arrival_rate_per_hour", "order_arrival_rate_per_hour", "all other assumptions");
        if (Boolean.TRUE.equals(fit.get("applied"))) {
            assertThat(report).containsKey("effect_on_model");
        } else {
            assertThat((String) fit.get("reason")).startsWith("insufficient data");
            assertThat(fit.get("fitted_value")).isEqualTo(fit.get("model_value"));
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "vorg.evidence", matches = "true")
    void evidenceRun() throws Exception {
        Map<String, Object> report = run((int) Duration.ofHours(1).toSeconds());
        Path dir = Path.of(System.getProperty("vorg.evidence.out", "target/evidence"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("calibration.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertThat(report).containsKey("fit");
    }
}
