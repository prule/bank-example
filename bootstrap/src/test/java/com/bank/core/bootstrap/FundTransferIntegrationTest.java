package com.bank.core.bootstrap;

import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.JournalEntryId;
import com.bank.core.domain.VerificationStatus;
import com.bank.core.dto.ErrorEnvelope;
import com.bank.core.infrastructure.persistence.account.AccountEntity;
import com.bank.core.infrastructure.persistence.account.AccountRepository;
import com.bank.core.infrastructure.persistence.ledger.JournalEntryRepository;
import com.bank.core.infrastructure.persistence.ledger.JournalEntriesJpaAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class FundTransferIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private FailingJournalEntries failingJournalEntries;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String ACC_SRC = "ACC-001";
    private static final String ACC_DST = "ACC-002";
    private static final String ACC_SUS = "ACC-SUSPENDED";

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public FailingJournalEntries failingJournalEntries(JournalEntriesJpaAdapter realAdapter) {
            return new FailingJournalEntries(realAdapter);
        }
    }

    public static class FailingJournalEntries implements JournalEntries {
        private final JournalEntries delegate;
        private boolean simulateFailure = false;

        public FailingJournalEntries(JournalEntries delegate) {
            this.delegate = delegate;
        }

        public void setSimulateFailure(boolean simulateFailure) {
            this.simulateFailure = simulateFailure;
        }

        @Override
        public void save(JournalEntry journalEntry) {
            if (simulateFailure) {
                throw new RuntimeException("Simulated database constraint crash");
            }
            delegate.save(journalEntry);
        }

        @Override
        public Optional<JournalEntry> findById(JournalEntryId id) {
            return delegate.findById(id);
        }

        @Override
        public List<JournalEntry> findByStatus(VerificationStatus status, int limit) {
            return delegate.findByStatus(status, limit);
        }

        @Override
        public boolean isBalanced(JournalEntryId id) {
            return delegate.isBalanced(id);
        }

        @Override
        public long currentCeiling() {
            return delegate.currentCeiling();
        }

        @Override
        public List<com.bank.core.domain.AccountId> distinctAccountIdsInWindow(long floor, long ceiling) {
            return delegate.distinctAccountIdsInWindow(floor, ceiling);
        }

        @Override
        public java.math.BigDecimal sumSignedAmountForAccount(com.bank.core.domain.AccountId id) {
            return delegate.sumSignedAmountForAccount(id);
        }
    }

    @BeforeEach
    public void setUp() {
        failingJournalEntries.setSimulateFailure(false);
        jdbcTemplate.execute("DELETE FROM ledger_movement");
        jdbcTemplate.execute("DELETE FROM journal_entry");
        jdbcTemplate.execute("DELETE FROM account");

        // Seed default active accounts
        accountRepository.save(new AccountEntity(ACC_SRC, AccountId.generate().toString(), new BigDecimal("100.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.save(new AccountEntity(ACC_DST, AccountId.generate().toString(), new BigDecimal("50.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.save(new AccountEntity(ACC_SUS, AccountId.generate().toString(), new BigDecimal("50.00"), AccountStatus.SUSPENDED, Instant.now()));
        accountRepository.flush();
    }

    @Test
    public void testValidTransferFlowSucceeds() {
        Map<String, Object> request = new HashMap<>();
        request.put("sourceAccountNumber", ACC_SRC);
        request.put("destinationAccountNumber", ACC_DST);
        request.put("amount", new BigDecimal("25.00"));

        ResponseEntity<Void> response = restTemplate.postForEntity("/api/v1/transfers", request, Void.class);

        // Verify status is 204 No Content
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();

        // Verify balance changes in database
        AccountEntity src = accountRepository.findByAccountNumber(ACC_SRC).orElseThrow();
        AccountEntity dst = accountRepository.findByAccountNumber(ACC_DST).orElseThrow();

        assertThat(src.getBalance()).isEqualByComparingTo(new BigDecimal("75.00"));
        assertThat(dst.getBalance()).isEqualByComparingTo(new BigDecimal("75.00"));

        // Verify journal entry and movements are generated
        assertThat(journalEntryRepository.count()).isEqualTo(1);
        List<Map<String, Object>> movements = jdbcTemplate.queryForList("SELECT * FROM ledger_movement");
        assertThat(movements).hasSize(2);
        
        // Assert sum of debit amounts equals sum of credit amounts
        BigDecimal creditSum = movements.stream()
                .filter(m -> "CREDIT".equals(m.get("type")))
                .map(m -> (BigDecimal) m.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal debitSum = movements.stream()
                .filter(m -> "DEBIT".equals(m.get("type")))
                .map(m -> (BigDecimal) m.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(creditSum).isEqualByComparingTo(debitSum);
        assertThat(creditSum).isEqualByComparingTo(new BigDecimal("25.00"));
    }

    @Test
    public void testPayloadValidations() {
        // Missing source
        Map<String, Object> req1 = new HashMap<>();
        req1.put("destinationAccountNumber", ACC_DST);
        req1.put("amount", new BigDecimal("25.00"));
        ResponseEntity<ErrorEnvelope> res1 = restTemplate.postForEntity("/api/v1/transfers", req1, ErrorEnvelope.class);
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res1.getBody().getCode()).isEqualTo(ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD);
        assertThat(res1.getBody().getMessage()).contains("sourceAccountNumber");

        // Missing destination
        Map<String, Object> req2 = new HashMap<>();
        req2.put("sourceAccountNumber", ACC_SRC);
        req2.put("amount", new BigDecimal("25.00"));
        ResponseEntity<ErrorEnvelope> res2 = restTemplate.postForEntity("/api/v1/transfers", req2, ErrorEnvelope.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res2.getBody().getCode()).isEqualTo(ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD);

        // Zero amount
        Map<String, Object> req3 = new HashMap<>();
        req3.put("sourceAccountNumber", ACC_SRC);
        req3.put("destinationAccountNumber", ACC_DST);
        req3.put("amount", BigDecimal.ZERO);
        ResponseEntity<ErrorEnvelope> res3 = restTemplate.postForEntity("/api/v1/transfers", req3, ErrorEnvelope.class);
        assertThat(res3.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res3.getBody().getCode()).isEqualTo(ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD);

        // Negative amount
        Map<String, Object> req4 = new HashMap<>();
        req4.put("sourceAccountNumber", ACC_SRC);
        req4.put("destinationAccountNumber", ACC_DST);
        req4.put("amount", new BigDecimal("-5.00"));
        ResponseEntity<ErrorEnvelope> res4 = restTemplate.postForEntity("/api/v1/transfers", req4, ErrorEnvelope.class);
        assertThat(res4.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res4.getBody().getCode()).isEqualTo(ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD);

        // Amount below 0.01
        Map<String, Object> req5 = new HashMap<>();
        req5.put("sourceAccountNumber", ACC_SRC);
        req5.put("destinationAccountNumber", ACC_DST);
        req5.put("amount", new BigDecimal("0.005"));
        ResponseEntity<ErrorEnvelope> res5 = restTemplate.postForEntity("/api/v1/transfers", req5, ErrorEnvelope.class);
        assertThat(res5.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res5.getBody().getCode()).isEqualTo(ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD);

        // Self-transfer rejection
        Map<String, Object> req6 = new HashMap<>();
        req6.put("sourceAccountNumber", ACC_SRC);
        req6.put("destinationAccountNumber", ACC_SRC);
        req6.put("amount", new BigDecimal("25.00"));
        ResponseEntity<ErrorEnvelope> res6 = restTemplate.postForEntity("/api/v1/transfers", req6, ErrorEnvelope.class);
        assertThat(res6.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res6.getBody().getCode()).isEqualTo(ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD);
        assertThat(res6.getBody().getMessage()).contains("Self-transfer is rejected");
    }

    @Test
    public void testBusinessRuleRejections() {
        // Missing source account -> 404 RESOURCE_NOT_FOUND
        Map<String, Object> req1 = new HashMap<>();
        req1.put("sourceAccountNumber", "UNKNOWN-SRC");
        req1.put("destinationAccountNumber", ACC_DST);
        req1.put("amount", new BigDecimal("25.00"));
        ResponseEntity<ErrorEnvelope> res1 = restTemplate.postForEntity("/api/v1/transfers", req1, ErrorEnvelope.class);
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res1.getBody().getCode()).isEqualTo(ErrorEnvelope.CodeEnum.RESOURCE_NOT_FOUND);
        assertThat(res1.getBody().getMessage()).contains("UNKNOWN-SRC");

        // Suspended source account -> 400 ACCOUNT_INACTIVE
        Map<String, Object> req2 = new HashMap<>();
        req2.put("sourceAccountNumber", ACC_SUS);
        req2.put("destinationAccountNumber", ACC_DST);
        req2.put("amount", new BigDecimal("10.00"));
        ResponseEntity<ErrorEnvelope> res2 = restTemplate.postForEntity("/api/v1/transfers", req2, ErrorEnvelope.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res2.getBody().getCode()).isEqualTo(ErrorEnvelope.CodeEnum.ACCOUNT_INACTIVE);

        // Insufficient funds -> 400 INSUFFICIENT_FUNDS
        Map<String, Object> req3 = new HashMap<>();
        req3.put("sourceAccountNumber", ACC_SRC);
        req3.put("destinationAccountNumber", ACC_DST);
        req3.put("amount", new BigDecimal("150.00")); // source has only 100.00
        ResponseEntity<ErrorEnvelope> res3 = restTemplate.postForEntity("/api/v1/transfers", req3, ErrorEnvelope.class);
        assertThat(res3.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res3.getBody().getCode()).isEqualTo(ErrorEnvelope.CodeEnum.INSUFFICIENT_FUNDS);
    }

    @Test
    public void testAtomicityAndRollbackMidFlight() {
        // Enable simulated database constraint crash
        failingJournalEntries.setSimulateFailure(true);

        Map<String, Object> request = new HashMap<>();
        request.put("sourceAccountNumber", ACC_SRC);
        request.put("destinationAccountNumber", ACC_DST);
        request.put("amount", new BigDecimal("25.00"));

        // POST request will fail due to the simulated exception
        ResponseEntity<ErrorEnvelope> response = restTemplate.postForEntity("/api/v1/transfers", request, ErrorEnvelope.class);

        // Verify it returns HTTP 500 error envelope
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorEnvelope.CodeEnum.INTERNAL_SERVER_ERROR);

        // Verify both balances are UNCHANGED in the database (rolled back)
        AccountEntity src = accountRepository.findByAccountNumber(ACC_SRC).orElseThrow();
        AccountEntity dst = accountRepository.findByAccountNumber(ACC_DST).orElseThrow();
        assertThat(src.getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(dst.getBalance()).isEqualByComparingTo(new BigDecimal("50.00"));

        // Verify no journal entries or ledger movements were created
        assertThat(journalEntryRepository.count()).isEqualTo(0);
    }
}
