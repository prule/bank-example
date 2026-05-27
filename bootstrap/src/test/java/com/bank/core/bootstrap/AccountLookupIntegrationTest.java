package com.bank.core.bootstrap;

import com.bank.core.application.account.Accounts;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.Money;
import com.bank.core.dto.AccountResponse;
import com.bank.core.dto.ErrorEnvelope;
import com.bank.core.infrastructure.persistence.account.AccountEntity;
import com.bank.core.infrastructure.persistence.account.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class AccountLookupIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private Accounts accounts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    public void setUp() {
        jdbcTemplate.execute("DELETE FROM ledger_movement");
        jdbcTemplate.execute("DELETE FROM journal_entry");
        jdbcTemplate.execute("DELETE FROM account");
    }

    @Test
    public void testAccountDatabaseSchemaMappingAndDuplicateCheck() {
        // Verify database schema mapping works
        AccountId accountId1 = AccountId.generate();
        AccountEntity entity = new AccountEntity(
                "TEST-1001",
                accountId1.toString(),
                new BigDecimal("150.75"),
                AccountStatus.ACTIVE,
                Instant.now()
        );
        accountRepository.save(entity);
        accountRepository.flush();

        // Verify duplicate account number constraint is rejected
        AccountEntity duplicateEntity = new AccountEntity(
                "TEST-1001",
                AccountId.generate().toString(),
                new BigDecimal("200.00"),
                AccountStatus.ACTIVE,
                Instant.now()
        );
        assertThatThrownBy(() -> {
            accountRepository.save(duplicateEntity);
            accountRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    public void testLookupAccountSuccessWithHALLinksAndDecimals() {
        // Seed account
        AccountId accountId = AccountId.generate();
        AccountEntity entity = new AccountEntity(
                "TEST-1002",
                accountId.toString(),
                new BigDecimal("100.00"),
                AccountStatus.ACTIVE,
                Instant.now()
        );
        accountRepository.save(entity);
        accountRepository.flush();

        // Perform GET request
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/accounts/TEST-1002", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();

        // Assert JSON body fields exactly and decimal formatting
        String json = response.getBody();
        assertThat(json).contains("\"accountNumber\":\"TEST-1002\"");
        assertThat(json).contains("\"balance\":\"100.00\""); // formatted exactly with two decimal places
        assertThat(json).contains("\"status\":\"ACTIVE\"");
        assertThat(json).contains("\"_links\"");
        assertThat(json).contains("\"self\":{\"href\":\"/api/v1/accounts/TEST-1002\",\"templated\":null,\"title\":null}");
        assertThat(json).contains("\"transfers\":{\"href\":\"/api/v1/transfers\",\"templated\":null,\"title\":null}");

        // Assert idempotency (three requests are byte-for-byte identical)
        ResponseEntity<String> response2 = restTemplate.getForEntity("/api/v1/accounts/TEST-1002", String.class);
        ResponseEntity<String> response3 = restTemplate.getForEntity("/api/v1/accounts/TEST-1002", String.class);
        assertThat(response.getBody()).isEqualTo(response2.getBody());
        assertThat(response.getBody()).isEqualTo(response3.getBody());
    }

    @Test
    public void testLookupAccountStatusRepresentations() {
        // Seed closed account
        accountRepository.save(new AccountEntity(
                "TEST-CLOSED",
                AccountId.generate().toString(),
                new BigDecimal("0.00"),
                AccountStatus.CLOSED,
                Instant.now()
        ));
        // Seed suspended account
        accountRepository.save(new AccountEntity(
                "TEST-SUSPENDED",
                AccountId.generate().toString(),
                new BigDecimal("50.00"),
                AccountStatus.SUSPENDED,
                Instant.now()
        ));
        accountRepository.flush();

        // Verify CLOSED account details
        ResponseEntity<AccountResponse> responseClosed = restTemplate.getForEntity(
                "/api/v1/accounts/TEST-CLOSED", AccountResponse.class);
        assertThat(responseClosed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseClosed.getBody().getStatus()).isEqualTo(AccountResponse.StatusEnum.CLOSED);

        // Verify SUSPENDED account details
        ResponseEntity<AccountResponse> responseSuspended = restTemplate.getForEntity(
                "/api/v1/accounts/TEST-SUSPENDED", AccountResponse.class);
        assertThat(responseSuspended.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseSuspended.getBody().getStatus()).isEqualTo(AccountResponse.StatusEnum.SUSPENDED);
    }

    @Test
    public void testLookupAccountNotFoundReturns404Envelope() {
        ResponseEntity<ErrorEnvelope> response = restTemplate.getForEntity(
                "/api/v1/accounts/UNKNOWN-999", ErrorEnvelope.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ErrorEnvelope envelope = response.getBody();
        assertThat(envelope).isNotNull();
        assertThat(envelope.getCode()).isEqualTo(ErrorEnvelope.CodeEnum.RESOURCE_NOT_FOUND);
        assertThat(envelope.getMessage()).contains("Could not find account with identifier UNKNOWN-999");
        assertThat(envelope.getTimestamp()).isBeforeOrEqualTo(OffsetDateTime.now());
    }

    @Test
    public void testHALContentNegotiation() {
        // Seed account
        AccountId accountId = AccountId.generate();
        accountRepository.save(new AccountEntity(
                "TEST-HAL",
                accountId.toString(),
                new BigDecimal("100.00"),
                AccountStatus.ACTIVE,
                Instant.now()
        ));
        accountRepository.flush();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/hal+json");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/accounts/TEST-HAL",
                HttpMethod.GET,
                entity,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Under spring content negotiation, application/hal+json is returned when accepted
        assertThat(response.getHeaders().getContentType().toString()).contains("application/hal+json");

        // Verify plain JSON Accept still returns application/json
        HttpHeaders headersJson = new HttpHeaders();
        headersJson.set("Accept", "application/json");
        HttpEntity<Void> entityJson = new HttpEntity<>(headersJson);

        ResponseEntity<String> responseJson = restTemplate.exchange(
                "/api/v1/accounts/TEST-HAL",
                HttpMethod.GET,
                entityJson,
                String.class
        );
        assertThat(responseJson.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseJson.getHeaders().getContentType().toString()).contains("application/json");
    }
}
