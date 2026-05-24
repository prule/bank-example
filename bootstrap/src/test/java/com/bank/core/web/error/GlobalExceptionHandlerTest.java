package com.bank.core.web.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ErrorTestControllerConfig.class)
class GlobalExceptionHandlerTest {

    private static final Set<String> EXPECTED_KEYS = Set.of("code", "message", "timestamp");

    private static final List<String> LEAK_MARKERS = List.of(
            "java.", "org.springframework.", "com.bank.core.", "at ",
            "SELECT", "INSERT", "UPDATE", "DELETE");

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Test
    void missingRequiredParameterReturnsBadRequestPayload() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(
                "http://localhost:" + port + "/internal/test-errors/validation", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode body = assertEnvelopeShape(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("BAD_REQUEST_PAYLOAD");
        assertThat(body.get("message").asText()).isNotBlank();
    }

    @Test
    void malformedJsonBodyReturnsBadRequestPayload() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{not json", headers);

        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/internal/test-errors/parse",
                HttpMethod.POST, request, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode body = assertEnvelopeShape(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("BAD_REQUEST_PAYLOAD");
        assertThat(body.get("message").asText()).isEqualTo("Malformed request body.");
    }

    @Test
    void validationFailureNamesAtLeastOneOffendingField() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"field\":\"\"}", headers);

        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/internal/test-errors/parse",
                HttpMethod.POST, request, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode body = assertEnvelopeShape(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("BAD_REQUEST_PAYLOAD");
        assertThat(body.get("message").asText()).contains("field 'field'");
    }

    @Test
    void unhandledExceptionReturnsCatchAllWithoutLeakingInternals() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(
                "http://localhost:" + port + "/internal/test-errors/boom", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        JsonNode body = assertEnvelopeShape(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("INTERNAL_SERVER_ERROR");
        String message = body.get("message").asText();
        assertThat(message).isEqualTo("An unexpected error occurred. Please contact support.");
        assertNoLeakage(message);
        assertNoLeakage(response.getBody());
    }

    private JsonNode assertEnvelopeShape(String rawBody) throws Exception {
        assertThat(rawBody).isNotBlank();
        JsonNode body = mapper.readTree(rawBody);
        Iterator<String> keys = body.fieldNames();
        Set<String> actual = new java.util.HashSet<>();
        keys.forEachRemaining(actual::add);
        assertThat(actual).isEqualTo(EXPECTED_KEYS);
        OffsetDateTime parsed = OffsetDateTime.parse(body.get("timestamp").asText());
        assertThat(parsed).isNotNull();
        return body;
    }

    private static void assertNoLeakage(String text) {
        for (String marker : LEAK_MARKERS) {
            assertThat(text).as("body must not leak '%s'", marker).doesNotContain(marker);
        }
    }
}
