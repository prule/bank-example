package com.bank.core.web.account;

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
class AccountLookupControllerTest {

    private static final Set<String> ACCOUNT_BODY_KEYS = Set.of("accountNumber", "balance", "status");
    private static final Set<String> ERROR_BODY_KEYS = Set.of("code", "message", "timestamp");

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    Accounts accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    @BeforeEach
    void wipe() {
        jdbc.update("DELETE FROM ledger_movement");
        jdbc.update("DELETE FROM journal_entry");
        jdbc.update("DELETE FROM account");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private String url(String accountNumber) {
        return "http://localhost:" + port + "/api/v1/accounts/" + accountNumber;
    }

    @Test
    void existingAccountReturns200WithExactlyThreeFields() throws Exception {
        Account a = Account.open(AccountNumber.of("ACC-200"), Money.of("100.00"));
        tx().executeWithoutResult(s -> accounts.save(a));

        ResponseEntity<String> response = rest.getForEntity(url("ACC-200"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.isObject()).isTrue();
        assertThat(fieldNames(body)).isEqualTo(ACCOUNT_BODY_KEYS);
        assertThat(body.get("accountNumber").asText()).isEqualTo("ACC-200");
        assertThat(body.get("balance").asText()).isEqualTo("100.00");
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void closedAccountIsRepresentable() throws Exception {
        Account opened = Account.open(AccountNumber.of("ACC-CLOSED"), Money.of("0.01"));
        Account closed = Account.rehydrate(
                opened.id(), opened.number(), opened.balance(), AccountStatus.CLOSED);
        tx().executeWithoutResult(s -> accounts.save(closed));

        ResponseEntity<String> response = rest.getForEntity(url("ACC-CLOSED"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("status").asText()).isEqualTo("CLOSED");
        assertThat(body.get("balance").asText()).isEqualTo("0.01");
    }

    @Test
    void suspendedAccountIsRepresentable() throws Exception {
        Account a = Account.open(AccountNumber.of("ACC-SUS"), Money.of("12.34"));
        a.suspend();
        tx().executeWithoutResult(s -> accounts.save(a));

        ResponseEntity<String> response = rest.getForEntity(url("ACC-SUS"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("status").asText()).isEqualTo("SUSPENDED");
    }

    @Test
    void missingAccountReturns404WithResourceNotFoundEnvelope() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(url("UNKNOWN-999"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(fieldNames(body)).isEqualTo(ERROR_BODY_KEYS);
        assertThat(body.get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(body.get("message").asText()).contains("UNKNOWN-999");
        assertThat(body.get("timestamp").asText()).isNotBlank();
    }

    @Test
    void repeatedLookupsReturnIdenticalBodies() {
        Account a = Account.open(AccountNumber.of("ACC-IDEM"), Money.of("7.00"));
        tx().executeWithoutResult(s -> accounts.save(a));

        String first = rest.getForObject(url("ACC-IDEM"), String.class);
        String second = rest.getForObject(url("ACC-IDEM"), String.class);
        String third = rest.getForObject(url("ACC-IDEM"), String.class);

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    @Test
    void lookupDoesNotMutateAccountOrLedger() {
        Account a = Account.open(AccountNumber.of("ACC-READONLY"), Money.of("42.00"));
        tx().executeWithoutResult(s -> accounts.save(a));

        Integer journalsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Integer.class);
        Integer movementsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_movement", Integer.class);
        Integer accountsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class);
        String balanceBefore = jdbc.queryForObject(
                "SELECT balance::varchar FROM account WHERE account_number = ?",
                String.class, "ACC-READONLY");
        String statusBefore = jdbc.queryForObject(
                "SELECT status FROM account WHERE account_number = ?",
                String.class, "ACC-READONLY");

        ResponseEntity<String> response = rest.getForEntity(url("ACC-READONLY"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        Integer journalsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Integer.class);
        Integer movementsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_movement", Integer.class);
        Integer accountsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class);
        String balanceAfter = jdbc.queryForObject(
                "SELECT balance::varchar FROM account WHERE account_number = ?",
                String.class, "ACC-READONLY");
        String statusAfter = jdbc.queryForObject(
                "SELECT status FROM account WHERE account_number = ?",
                String.class, "ACC-READONLY");

        assertThat(journalsAfter).isEqualTo(journalsBefore);
        assertThat(movementsAfter).isEqualTo(movementsBefore);
        assertThat(accountsAfter).isEqualTo(accountsBefore);
        assertThat(balanceAfter).isEqualTo(balanceBefore);
        assertThat(statusAfter).isEqualTo(statusBefore);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Iterator<String> it = node.fieldNames();
        java.util.HashSet<String> names = new java.util.HashSet<>();
        while (it.hasNext()) names.add(it.next());
        return names;
    }
}
