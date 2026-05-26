package com.bank.core.web.transfer;

import com.bank.core.application.account.Accounts;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the {@code transfer-idempotency} capability:
 *
 * <ul>
 *   <li>Header absent → existing behaviour (no row, pipeline runs)</li>
 *   <li>Header present + first request → 204 + row persisted with the right shape</li>
 *   <li>Header present + replay → 204 (no second journal_entry)</li>
 *   <li>Header present + classified rejection → 400 + row persisted with envelope columns</li>
 *   <li>Classified-rejection replay → byte-stable envelope (same timestamp)</li>
 *   <li>Same key + different body → 422 IDEMPOTENCY_KEY_REUSED</li>
 *   <li>Malformed key → 400 BAD_REQUEST_PAYLOAD before any pipeline runs</li>
 *   <li>Whitespace-only body difference → still a replay (canonical fingerprint matches)</li>
 * </ul>
 *
 * <p>Concurrent in-flight (409 CONCURRENT_IDEMPOTENT_REQUEST) is exercised in
 * a separate test where two threads race; that path is harder to provoke
 * deterministically without slowing down the pipeline, so it's covered by
 * the adapter's unit tests for now.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TransferIdempotencyTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;
    @Autowired Accounts accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach
    void wipe() {
        jdbc.update("DELETE FROM idempotency_key");
        jdbc.update("DELETE FROM ledger_movement");
        jdbc.update("DELETE FROM journal_entry");
        jdbc.update("DELETE FROM account");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private void seedActive(String number, String balance) {
        Account opened = Account.open(AccountNumber.of(number), Money.of(balance));
        tx().executeWithoutResult(s -> accounts.save(opened));
    }

    private ResponseEntity<String> post(String body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange("http://localhost:" + port + "/api/v1/transfers",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private long journalCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Long.class);
    }

    private long idempotencyRowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_key", Long.class);
    }

    private static String body(String src, String dst, String amount) {
        return "{\"sourceAccountNumber\":\"" + src + "\",\"destinationAccountNumber\":\""
                + dst + "\",\"amount\":" + amount + "}";
    }

    // ----- header absent --------------------------------------------------

    @Test
    void noHeader_runsPipeline_andDoesNotPersistIdempotencyRow() {
        seedActive("A", "100.00");
        seedActive("B", "0.00");

        ResponseEntity<String> r = post(body("A", "B", "10.00"), null);

        assertThat(r.getStatusCode().value()).isEqualTo(204);
        assertThat(journalCount()).isEqualTo(1);
        assertThat(idempotencyRowCount()).isEqualTo(0);
    }

    // ----- happy-path replay ----------------------------------------------

    @Test
    void firstRequestWithKey_persistsRow_andReplayDoesNotRunPipelineAgain() {
        seedActive("A", "100.00");
        seedActive("B", "0.00");
        String key = UUID.randomUUID().toString();
        String body = body("A", "B", "10.00");

        ResponseEntity<String> first = post(body, key);
        assertThat(first.getStatusCode().value()).isEqualTo(204);
        assertThat(journalCount()).isEqualTo(1);
        assertThat(idempotencyRowCount()).isEqualTo(1);

        // Replay: same key + same body → 204, NO second journal.
        ResponseEntity<String> replay = post(body, key);
        assertThat(replay.getStatusCode().value()).isEqualTo(204);
        assertThat(journalCount()).isEqualTo(1);
        assertThat(idempotencyRowCount()).isEqualTo(1);
    }

    // ----- rejection replay -----------------------------------------------

    @Test
    void insufficientFundsResponse_isPersisted_andReplayReturnsSameEnvelopeTimestamp() throws Exception {
        seedActive("A", "1.00");
        seedActive("B", "0.00");
        String key = UUID.randomUUID().toString();
        String body = body("A", "B", "999.00");

        ResponseEntity<String> first = post(body, key);
        assertThat(first.getStatusCode().value()).isEqualTo(400);
        JsonNode firstBody = json.readTree(first.getBody());
        assertThat(firstBody.get("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        String firstTimestamp = firstBody.get("timestamp").asText();

        // The row was persisted despite the rejection — proves the controller
        // catches and stores the classified-rejection envelope rather than
        // letting the exception roll back the transaction.
        assertThat(idempotencyRowCount()).isEqualTo(1);
        assertThat(journalCount()).isEqualTo(0);

        // Replay returns the same envelope timestamp (NOT a fresh "now").
        ResponseEntity<String> replay = post(body, key);
        assertThat(replay.getStatusCode().value()).isEqualTo(400);
        JsonNode replayBody = json.readTree(replay.getBody());
        assertThat(replayBody.get("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(replayBody.get("message").asText())
                .isEqualTo(firstBody.get("message").asText());
        assertThat(replayBody.get("timestamp").asText()).isEqualTo(firstTimestamp);
    }

    // ----- key reuse with different body ----------------------------------

    @Test
    void sameKey_differentAmount_returns422_IDEMPOTENCY_KEY_REUSED() throws Exception {
        seedActive("A", "100.00");
        seedActive("B", "0.00");
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = post(body("A", "B", "10.00"), key);
        assertThat(first.getStatusCode().value()).isEqualTo(204);

        ResponseEntity<String> second = post(body("A", "B", "20.00"), key);
        assertThat(second.getStatusCode().value()).isEqualTo(422);
        JsonNode envelope = json.readTree(second.getBody());
        assertThat(envelope.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(envelope.get("message").asText()).contains(key);

        // No second journal_entry produced by the reuse-attempt.
        assertThat(journalCount()).isEqualTo(1);
    }

    // ----- whitespace-only body difference --------------------------------

    @Test
    void sameKey_whitespaceOnlyBodyDifference_isStillAReplay() throws Exception {
        seedActive("A", "100.00");
        seedActive("B", "0.00");
        String key = UUID.randomUUID().toString();

        // First request: compact body.
        String compact = body("A", "B", "10.00");
        assertThat(post(compact, key).getStatusCode().value()).isEqualTo(204);

        // Second request: same fields, different whitespace + key order.
        String reordered = "{ \"amount\": 10.00 , \"destinationAccountNumber\": \"B\" , \"sourceAccountNumber\": \"A\" }";
        ResponseEntity<String> replay = post(reordered, key);
        assertThat(replay.getStatusCode().value()).isEqualTo(204);
        assertThat(journalCount()).isEqualTo(1);  // no second pipeline run
    }

    // ----- malformed key --------------------------------------------------

    @Test
    void emptyIdempotencyKey_returns400_BAD_REQUEST_PAYLOAD() throws Exception {
        seedActive("A", "100.00");
        seedActive("B", "0.00");

        ResponseEntity<String> r = post(body("A", "B", "10.00"), "");
        // OpenAPI bean-validation (Size min=1) rejects the empty header value
        // before the controller body runs.
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        JsonNode envelope = json.readTree(r.getBody());
        assertThat(envelope.get("code").asText()).isEqualTo("BAD_REQUEST_PAYLOAD");
        assertThat(journalCount()).isEqualTo(0);
        assertThat(idempotencyRowCount()).isEqualTo(0);
    }

    @Test
    void tooLongIdempotencyKey_returns400() throws Exception {
        seedActive("A", "100.00");
        seedActive("B", "0.00");

        String tooLong = "a".repeat(201);
        ResponseEntity<String> r = post(body("A", "B", "10.00"), tooLong);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        JsonNode envelope = json.readTree(r.getBody());
        assertThat(envelope.get("code").asText()).isEqualTo("BAD_REQUEST_PAYLOAD");
        assertThat(journalCount()).isEqualTo(0);
        assertThat(idempotencyRowCount()).isEqualTo(0);
    }

    // ----- row shape ------------------------------------------------------

    @Test
    void successfulFirstRequest_persistsRowWithNullEnvelopeColumns() {
        seedActive("A", "100.00");
        seedActive("B", "0.00");
        String key = UUID.randomUUID().toString();

        assertThat(post(body("A", "B", "10.00"), key).getStatusCode().value()).isEqualTo(204);

        // http_status = 204, all envelope_* NULL.
        Short status = jdbc.queryForObject(
                "SELECT http_status FROM idempotency_key WHERE key_value = ?", Short.class, key);
        assertThat(status).isEqualTo((short) 204);

        Integer envelopeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_key WHERE key_value = ? "
                        + "AND envelope_code IS NULL AND envelope_message IS NULL AND envelope_timestamp IS NULL",
                Integer.class, key);
        assertThat(envelopeCount).isEqualTo(1);
    }

    @Test
    void rejectedFirstRequest_persistsRowWithPopulatedEnvelopeColumns() {
        seedActive("A", "1.00");
        seedActive("B", "0.00");
        String key = UUID.randomUUID().toString();

        assertThat(post(body("A", "B", "999.00"), key).getStatusCode().value()).isEqualTo(400);

        Short httpStatus = jdbc.queryForObject(
                "SELECT http_status FROM idempotency_key WHERE key_value = ?", Short.class, key);
        assertThat(httpStatus).isEqualTo((short) 400);

        String code = jdbc.queryForObject(
                "SELECT envelope_code FROM idempotency_key WHERE key_value = ?", String.class, key);
        assertThat(code).isEqualTo("INSUFFICIENT_FUNDS");

        String message = jdbc.queryForObject(
                "SELECT envelope_message FROM idempotency_key WHERE key_value = ?", String.class, key);
        assertThat(message).isNotBlank();
    }
}
