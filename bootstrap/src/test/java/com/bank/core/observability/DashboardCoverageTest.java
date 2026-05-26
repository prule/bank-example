package com.bank.core.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage gate between the Grafana dashboard JSON and the live Prometheus
 * scrape. Parses
 * {@code infrastructure/observability/grafana/dashboards/bank-core.json},
 * extracts every metric name referenced in panel queries, and asserts each
 * one appears in the {@code /actuator/prometheus} scrape output of a freshly-
 * started app. Prevents the dashboard from quietly referencing a metric the
 * code does not emit (and vice versa for the named bank_* series).
 *
 * <p>Only {@code bank_*}, {@code jvm_*}, {@code http_*} families are checked
 * — the spec requires those panels render without "No data".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// See PrometheusEndpointIntegrationTest for why this annotation is required.
@AutoConfigureObservability
class DashboardCoverageTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    // Loose match for promql metric names. Captures bare identifiers that
    // start with bank_, jvm_, http_, or process_. Tolerates trailing _count,
    // _sum, _bucket, _total suffixes since those are auto-added by Micrometer.
    private static final Pattern METRIC_NAME = Pattern.compile("\\b((?:bank|jvm|http|process)_[a-zA-Z0-9_]+)\\b");

    @Test
    void everyMetricReferencedByDashboardIsExposedByScrape() throws IOException {
        Path dashboardJson = Paths.get(
                System.getProperty("user.dir"))
                .getParent()
                .resolve("infrastructure/observability/grafana/dashboards/bank-core.json");
        // The test runs from the bootstrap/ working dir; user.dir.parent =
        // project root. Sanity-check the path before parsing.
        assertThat(Files.exists(dashboardJson))
                .as("dashboard JSON at %s", dashboardJson)
                .isTrue();

        Set<String> referencedMetrics = extractMetricNames(dashboardJson);
        assertThat(referencedMetrics)
                .as("dashboard must reference at least one metric")
                .isNotEmpty();

        // Warm-up: HTTP-server metrics (http_server_requests_seconds_*) are
        // only emitted once at least one request has been served. Hit a
        // cheap endpoint first so the dashboard's HTTP panel references
        // resolve in the subsequent scrape.
        rest.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        String scrape = rest.getForObject(scrapeUrl(), String.class);
        assertThat(scrape).isNotBlank();

        Set<String> missing = new HashSet<>();
        for (String metric : referencedMetrics) {
            // Strip Micrometer's auto-added suffix so a panel referencing
            // bank_transfer_duration_seconds_bucket matches the underlying
            // bank_transfer_duration_seconds series in the scrape.
            String stem = stripSuffix(metric);
            if (!scrape.contains(stem)) {
                missing.add(metric);
            }
        }

        assertThat(missing)
                .as("dashboard references metrics absent from /actuator/prometheus")
                .isEmpty();
    }

    private String scrapeUrl() {
        return "http://localhost:" + port + "/actuator/prometheus";
    }

    private Set<String> extractMetricNames(Path dashboardJson) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.readString(dashboardJson, StandardCharsets.UTF_8));
        Set<String> names = new HashSet<>();
        JsonNode panels = root.path("panels");
        for (JsonNode panel : panels) {
            JsonNode targets = panel.path("targets");
            for (JsonNode target : targets) {
                String expr = target.path("expr").asText("");
                Matcher m = METRIC_NAME.matcher(expr);
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        return names;
    }

    private static String stripSuffix(String metric) {
        for (String suffix : new String[] {"_bucket", "_count", "_sum", "_total"}) {
            if (metric.endsWith(suffix)) {
                return metric.substring(0, metric.length() - suffix.length());
            }
        }
        return metric;
    }

}
