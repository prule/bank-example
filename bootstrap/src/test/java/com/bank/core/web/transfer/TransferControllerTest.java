package com.bank.core.web.transfer;

import com.bank.core.application.account.Accounts;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.AccountStatus;
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

import java.util.Iterator;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TransferControllerTest {

    private static final Set<String> ERROR_KEYS = Set.of("code", "message", "timestamp");

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;
    @Autowired Accounts accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach
    void wipe() {
        jdbc.update("DELETE FROM ledger_movement");
        jdbc.update("DELETE FROM journal_entry");
        jdbc.update("DELETE FROM account");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private void seed(String number, String balance, AccountStatus status) {
        Account opened = Account.open(AccountNumber.of(number), Money.of(balance));
        Account toSave = status == AccountStatus.ACTIVE
                ? opened
                : Account.rehydrate(opened.id(), opened.number(), opened.balance(), status);
        tx().executeWithoutResult(s -> accounts.save(toSave));
    }

    private ResponseEntity<String> post(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + "/api/v1/transfers",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode tree(String body) throws Exception {
        return json.readTree(body);
    }

    private static Set<String> keys(JsonNode node) {
        Iterator<String> it = node.fieldNames();
        java.util.HashSet<String> names = new java.util.HashSet<>();
        while (it.hasNext()) names.add(it.next());
        return names;
    }

    private long journalCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Long.class);
    }

    private String balance(String number) {
        return jdbc.queryForObject(
                "SELECT balance::varchar FROM account WHERE account_number = ?",
                String.class, number);
    }

    // --- Happy path ---

    @Test
    void validTransferReturns204AndMovesBalances() {
        seed("ACC-X", "100.00", AccountStatus.ACTIVE);
        seed("ACC-Y", "10.00", AccountStatus.ACTIVE);

        ResponseEntity<String> response = post(
                "{\"sourceAccountNumber\":\"ACC-X\",\"destinationAccountNumber\":\"ACC-Y\",\"amount\":25.00}");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();

        assertThat(balance("ACC-X")).isEqualTo("75.00");
        assertThat(balance("ACC-Y")).isEqualTo("35.00");
        assertThat(journalCount()).isEqualTo(1);
    }

    // --- Bean-validation rejections (400 BAD_REQUEST_PAYLOAD) ---

    @Test
    void missingSourceReturns400() throws Exception {
        seed("ACC-Y", "10.00", AccountStatus.ACTIVE);
        long jb = journalCount();
        ResponseEntity<String> response = post(
                "{\"destinationAccountNumber\":\"ACC-Y\",\"amount\":25.00}");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode body = tree(response.getBody());
        assertThat(keys(body)).isEqualTo(ERROR_KEYS);
        assertThat(body.get("code").asText()).isEqualTo("BAD_REQUEST_PAYLOAD");
        assertThat(journalCount()).isEqualTo(jb);
        assertThat(balance("ACC-Y")).isEqualTo("10.00");
    }

    @Test
    void missingDestinationReturns400() throws Exception {
        seed("ACC-X", "100.00", AccountStatus.ACTIVE);
        long jb = journalCount();
        ResponseEntity<String> response = post(
                "{\"sourceAccountNumber\":\"ACC-X\",\"amount\":25.00}");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(tree(response.getBody()).get("code").asText()).isEqualTo("BAD_REQUEST_PAYLOAD");
        assertThat(journalCount()).isEqualTo(jb);
        assertThat(balance("ACC-X")).isEqualTo("100.00");
    }

    @Test
    void missingAmountReturns400() throws Exception {
        seed("ACC-X", "100.00", AccountStatus.ACTIVE);
        seed("ACC-Y", "10.00", AccountStatus.ACTIVE);
        long jb = journalCount();
        ResponseEntity<String> response = post(
                "{\"sourceAccountNumber\":\"ACC-X\",\"destinationAccountNumber\":\"ACC-Y\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(tree(response.getBody()).get("code").asText()).isEqualTo("BAD_REQUEST_PAYLOAD");
        assertThat(journalCount()).isEqualTo(jb);
    }

    @Test
    void zeroAmountReturns400() throws Exception {
        seed("ACC-X", "100.00", AccountStatus.ACTIVE);
        seed("ACC-Y", "10.00", AccountStatus.ACTIVE);
        long jb = journalCount();
        ResponseEntity<String> response = post(
                "{\"sourceAccountNumber\":\"ACC-X\",\"destinationAccountNumber\":\"ACC-Y\",\"amount\":0}");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(tree(response.getBody()).get("code").asText()).isEqualTo("BAD_REQUEST_PAYLOAD");
        assertThat(journalCount()).isEqualTo(jb);
        assertThat(balance("ACC-X")).isEqualTo("100.00");
    }

    @Test
    void negativeAmountReturns400() throws Exception {
        seed("ACC-X", "100.00", AccountStatus.ACTIVE);
        seed("ACC-Y", "10.00", AccountStatus.ACTIVE);
        long jb = journalCount();
        ResponseEntity<String> response = post(
                "{\"sourceAccountNumber\":\"ACC-X\",\"destinationAccountNumber\":\"ACC-Y\",\"amount\":-5.00}");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(tree(response.getBody()).get("code").asText()).isEqualTo("BAD_REQUEST_PAYLOAD");
        assertThat(journalCount()).isEqualTo(jb);
    }

    @Test
    void amountBelowMinimumReturns400() throws Exception {
        seed("ACC-X", "100.00", AccountStatus.ACTIVE);
        seed("ACC-Y", "10.00", AccountStatus.ACTIVE);
        long jb = journalCount();
        ResponseEntity<String> response = post(
                "{\"sourceAccountNumber\":\"ACC-X\",\"destinationAccountNumber\":\"ACC-Y\",\"amount\":0.001}");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(tree(response.getBody()).get("code").asText()).isEqualTo("BAD_REQUEST_PAYLOAD");
        assertThat(journalCount()).isEqualTo(jb);
    }

    // --- Self-transfer (400 BAD_REQUEST_PAYLOAD) ---

    @Test
    void selfTransferReturns400WithBadRequestPayload() throws Exception {
        seed("ACC-SELF", "100.00", AccountStatus.ACTIVE);
        long jb = journalCount();
        ResponseEntity<String> response = post(
                "{\"sourceAccountNumber\":\"ACC-SELF\",\"destinationAccountNumber\":\"ACC-SELF\",\"amount\":10.00}");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode body = tree(response.getBody());
        assertThat(keys(body)).isEqualTo(ERROR_KEYS);
        assertThat(body.get("code").asText()).isEqualTo("BAD_REQUEST_PAYLOAD");
        assertThat(body.get("message").asText()).contains("ACC-SELF");
        assertThat(journalCount()).isEqualTo(jb);
        assertThat(balance("ACC-SELF")).isEqualTo("100.00");
    }

    // --- Missing accounts (404 RESOURCE_NOT_FOUND) ---

    @Test
    void missingSourceAccountReturns404() throws Exception {
        seed("ACC-Y", "10.00", AccountStatus.ACTIVE);
        long jb = journalCount();
        ResponseEntity<String> response = post(
                "{\"sourceAccountNumber\":\"UNKNOWN\",\"destinationAccountNumber\":\"ACC-Y\",\"amount\":25.00}");
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        JsonNode body = tree(response.getBody());
        assertThat(keys(body)).isEqualTo(ERROR_KEYS);
        assertThat(body.get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(body.get("message").asText()).contains("UNKNOWN");
        assertThat(journalCount()).isEqualTo(jb);
        assertThat(balance("ACC-Y")).isEqualTo("10.00");
    }

    @Test
    void missingDestinationAccountReturns404() throws Exception {
        seed("ACC-X", "100.00", AccountStatus.ACTIVE);
        long jb = journalCount();
        ResponseEntity<String> response = post(
                "{\"sourceAccountNumber\":\"ACC-X\",\"destinationAccountNumber\":\"UNKNOWN\",\"amount\":25.00}");
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        JsonNode body = tree(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(body.get("message").asText()).contains("UNKNOWN");
        assertThat(journalCount()).isEqualTo(jb);
        assertThat(balance("ACC-X")).isEqualTo("100.00");
    }

    // --- Inactive accounts (400 ACCOUNT_INACTIVE) ---

    @Test
    void suspendedSourceReturns400AccountInactive() throws Exception {
        seed("ACC-S", "100.00", AccountStatus.SUSPENDED);
        seed("ACC-Y", "10.00", AccountStatus.ACTIVE);
        long jb = journalCount();
        ResponseEntity<String> response = post(
                "{\"sourceAccountNumber\":\"ACC-S\",\"destinationAccountNumber\":\"ACC-Y\",\"amount\":25.00}");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(tree(response.getBody()).get("code").asText()).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(journalCount()).isEqualTo(jb);
        assertThat(balance("ACC-S")).isEqualTo("100.00");
        assertThat(balance("ACC-Y")).isEqualTo("10.00");
    }

    @Test
    void suspendedDestinationReturns400AccountInactive() throws Exception {
        seed("ACC-X", "100.00", AccountStatus.ACTIVE);
        seed("ACC-D", "10.00", AccountStatus.SUSPENDED);
        long jb = journalCount();
        ResponseEntity<String> response = post(
                "{\"sourceAccountNumber\":\"ACC-X\",\"destinationAccountNumber\":\"ACC-D\",\"amount\":25.00}");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(tree(response.getBody()).get("code").asText()).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(journalCount()).isEqualTo(jb);
        assertThat(balance("ACC-X")).isEqualTo("100.00");
        assertThat(balance("ACC-D")).isEqualTo("10.00");
    }

    // --- Insufficient funds (400 INSUFFICIENT_FUNDS) ---

    @Test
    void overdrawReturns400InsufficientFunds() throws Exception {
        seed("ACC-LOW", "5.00", AccountStatus.ACTIVE);
        seed("ACC-Y", "10.00", AccountStatus.ACTIVE);
        long jb = journalCount();
        ResponseEntity<String> response = post(
                "{\"sourceAccountNumber\":\"ACC-LOW\",\"destinationAccountNumber\":\"ACC-Y\",\"amount\":50.00}");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(tree(response.getBody()).get("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(journalCount()).isEqualTo(jb);
        assertThat(balance("ACC-LOW")).isEqualTo("5.00");
        assertThat(balance("ACC-Y")).isEqualTo("10.00");
    }
}
