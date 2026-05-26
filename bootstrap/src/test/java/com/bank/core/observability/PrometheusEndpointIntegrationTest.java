package com.bank.core.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the {@code /actuator/prometheus} scrape endpoint.
 * Asserts:
 * <ul>
 *   <li>Endpoint returns 200 with a {@code text/plain} content-type.</li>
 *   <li>Body contains at least one {@code # HELP} line (Prometheus exposition format).</li>
 *   <li>Body contains at least one custom {@code bank_*} series — proves the
 *       infrastructure-boundary instrumentation registered its meters at startup.</li>
 *   <li>{@code /actuator/env} returns 404 — non-exposed endpoints stay hidden
 *       (covers the spec scenario in {@code metrics-exposure}).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// Spring Boot's ObservabilityContextCustomizerFactory disables observability
// in @SpringBootTest contexts by default (so test runs don't ship metrics to
// a production registry). That sets management.defaults.metrics.export.enabled=false,
// which gates the Prometheus auto-configuration off and produces 404 on
// /actuator/prometheus even with the right exposure list. This annotation
// re-enables it for this context.
@AutoConfigureObservability
class PrometheusEndpointIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void prometheusEndpointReturns200WithCustomBankMetrics() {
        ResponseEntity<String> response = rest.getForEntity(url("/actuator/prometheus"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/plain");

        String body = response.getBody();
        assertThat(body).isNotBlank();
        assertThat(body).contains("# HELP ");
        // At least one custom bank_* series must be registered at startup
        // even before any traffic — counters/timers/gauges built in the
        // infrastructure-observability components register eagerly.
        assertThat(body).containsPattern("(?m)^bank_");
    }

    @Test
    void healthEndpointStillReturns200() {
        ResponseEntity<String> response = rest.getForEntity(url("/actuator/health"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void nonExposedEnvEndpointReturns404() {
        // Confirms the actuator exposure list is exactly the four whitelisted
        // endpoints. /env is not in the list and SHALL stay hidden.
        ResponseEntity<String> response = rest.getForEntity(url("/actuator/env"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
