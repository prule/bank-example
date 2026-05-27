package com.bank.core.bootstrap;

import com.bank.core.domain.*;
import com.bank.core.dto.ErrorEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class GlobalExceptionHandlerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private void verifyEnvelopeAndTimestamp(ErrorEnvelope envelope) {
        assertThat(envelope).isNotNull();
        assertThat(envelope.getCode()).isNotNull();
        assertThat(envelope.getMessage()).isNotBlank();
        assertThat(envelope.getTimestamp()).isNotNull();

        // Verify timestamp matches ISO-8601 format by parsing it
        String formatted = envelope.getTimestamp().toString();
        OffsetDateTime parsed = OffsetDateTime.parse(formatted);
        assertThat(parsed).isBeforeOrEqualTo(OffsetDateTime.now());
    }

    @Test
    public void testInsufficientFundsMapping() {
        ResponseEntity<ErrorEnvelope> response = restTemplate.getForEntity(
                "/test-errors/insufficient-funds", ErrorEnvelope.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorEnvelope envelope = response.getBody();
        verifyEnvelopeAndTimestamp(envelope);
        assertThat(envelope.getCode()).isEqualTo(ErrorEnvelope.CodeEnum.INSUFFICIENT_FUNDS);
        assertThat(envelope.getMessage()).contains("insufficient funds");
    }

    @Test
    public void testAccountInactiveMapping() {
        ResponseEntity<ErrorEnvelope> response = restTemplate.getForEntity(
                "/test-errors/account-inactive", ErrorEnvelope.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorEnvelope envelope = response.getBody();
        verifyEnvelopeAndTimestamp(envelope);
        assertThat(envelope.getCode()).isEqualTo(ErrorEnvelope.CodeEnum.ACCOUNT_INACTIVE);
        assertThat(envelope.getMessage()).contains("is inactive");
    }

    @Test
    public void testInvalidAmountMapping() {
        ResponseEntity<ErrorEnvelope> response = restTemplate.getForEntity(
                "/test-errors/invalid-amount", ErrorEnvelope.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorEnvelope envelope = response.getBody();
        verifyEnvelopeAndTimestamp(envelope);
        assertThat(envelope.getCode()).isEqualTo(ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD);
        assertThat(envelope.getMessage()).contains("Amount must be strictly positive");
    }

    @Test
    public void testIllegalStatusMapping() {
        ResponseEntity<ErrorEnvelope> response = restTemplate.getForEntity(
                "/test-errors/illegal-status", ErrorEnvelope.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorEnvelope envelope = response.getBody();
        verifyEnvelopeAndTimestamp(envelope);
        assertThat(envelope.getCode()).isEqualTo(ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD);
        assertThat(envelope.getMessage()).contains("Illegal status transition");
    }

    @Test
    public void testNoHandlerFoundMapping() {
        ResponseEntity<ErrorEnvelope> response = restTemplate.getForEntity(
                "/non-existent-url-that-is-not-mapped", ErrorEnvelope.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ErrorEnvelope envelope = response.getBody();
        verifyEnvelopeAndTimestamp(envelope);
        assertThat(envelope.getCode()).isEqualTo(ErrorEnvelope.CodeEnum.RESOURCE_NOT_FOUND);
        assertThat(envelope.getMessage()).contains("Resource not found");
    }

    @Test
    public void testMethodArgumentNotValidMapping() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"name\":\"\"}", headers);

        ResponseEntity<ErrorEnvelope> response = restTemplate.postForEntity(
                "/test-errors/validation", request, ErrorEnvelope.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorEnvelope envelope = response.getBody();
        verifyEnvelopeAndTimestamp(envelope);
        assertThat(envelope.getCode()).isEqualTo(ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD);
        assertThat(envelope.getMessage()).contains("Field 'name' is invalid or missing");
    }

    @Test
    public void testHttpMessageNotReadableMapping() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{malformed-json}", headers);

        ResponseEntity<ErrorEnvelope> response = restTemplate.postForEntity(
                "/test-errors/validation", request, ErrorEnvelope.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorEnvelope envelope = response.getBody();
        verifyEnvelopeAndTimestamp(envelope);
        assertThat(envelope.getCode()).isEqualTo(ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD);
        assertThat(envelope.getMessage()).isEqualTo("Malformed JSON request body");
    }

    @Test
    public void testGenericExceptionRedaction() {
        ResponseEntity<ErrorEnvelope> response = restTemplate.getForEntity(
                "/test-errors/generic-runtime", ErrorEnvelope.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorEnvelope envelope = response.getBody();
        verifyEnvelopeAndTimestamp(envelope);
        assertThat(envelope.getCode()).isEqualTo(ErrorEnvelope.CodeEnum.INTERNAL_SERVER_ERROR);
        assertThat(envelope.getMessage()).isEqualTo("An unexpected error occurred. Please contact support.");

        // Ensure no internal markers, package paths, stacktrace signs, or SQL statements exist in the message
        String msg = envelope.getMessage();
        assertThat(msg).doesNotContain("java.");
        assertThat(msg).doesNotContain("com.bank.core");
        assertThat(msg).doesNotContain("org.springframework");
        assertThat(msg).doesNotContain("SELECT ");
        assertThat(msg).doesNotContain("FROM ");
        assertThat(msg).doesNotContain("at ");
    }
}
