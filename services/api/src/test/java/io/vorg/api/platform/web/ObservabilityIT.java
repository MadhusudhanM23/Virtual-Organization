package io.vorg.api.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    private HttpResponse<String> get(String path, String correlationId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (correlationId != null) {
            b.header("X-Correlation-Id", correlationId);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void prometheusExposesHttpServerRequests() throws Exception {
        get("/actuator/health", null);
        HttpResponse<String> metrics = get("/actuator/prometheus", null);
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("http_server_requests_seconds");
    }

    @Test
    void unauthenticatedRequestReturns401ProblemJsonWithCorrelationId() throws Exception {
        String id = UUID.randomUUID().toString();
        HttpResponse<String> res = get("/api/anything", id);
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(res.headers().firstValue("X-Correlation-Id")).contains(id);
        assertThat(res.body()).contains("\"correlation_id\":\"" + id + "\"").contains("\"status\":401");
    }

    @Test
    void logLinesAreJsonWithCorrelationId(CapturedOutput output) {
        Logger log = LoggerFactory.getLogger("observability.it");
        String id = UUID.randomUUID().toString();
        MDC.put("correlation_id", id);
        try {
            log.info("observability probe message");
        } finally {
            MDC.remove("correlation_id");
        }
        String line = output.getAll().lines().filter(l -> l.contains("observability probe message"))
                .findFirst().orElseThrow();
        assertThat(line).startsWith("{").contains("\"correlation_id\":\"" + id + "\"");
    }
}
