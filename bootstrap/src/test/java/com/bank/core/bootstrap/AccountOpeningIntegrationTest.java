package com.bank.core.bootstrap;

import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.domain.*;
import com.bank.core.infrastructure.account.OpenAccountService;
import com.bank.core.application.account.OpenAccountCommand;
import com.bank.core.infrastructure.persistence.account.AccountEntity;
import com.bank.core.infrastructure.persistence.account.AccountRepository;
import com.bank.core.infrastructure.persistence.ledger.JournalEntryRepository;
import com.bank.core.infrastructure.persistence.ledger.JournalEntriesJpaAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class AccountOpeningIntegrationTest {

    @Autowired
    private OpenAccountService openAccountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private FailingJournalEntriesForAccountOpening failingJournalEntries;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String CLEARING_NUM = "CLEARING-000";
    private static final String CUST_NUM = "ACC-INTEG-123";

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public FailingJournalEntriesForAccountOpening failingJournalEntriesForAccountOpening(JournalEntriesJpaAdapter realAdapter) {
            return new FailingJournalEntriesForAccountOpening(realAdapter);
        }
    }

    public static class FailingJournalEntriesForAccountOpening implements JournalEntries {
        private final JournalEntries delegate;
        private boolean simulateFailure = false;

        public FailingJournalEntriesForAccountOpening(JournalEntries delegate) {
            this.delegate = delegate;
        }

        public void setSimulateFailure(boolean simulateFailure) {
            this.simulateFailure = simulateFailure;
        }

        @Override
        public void save(JournalEntry journalEntry) {
            if (simulateFailure) {
                throw new RuntimeException("Simulated database failure during ledger entry save");
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
    }

    @BeforeEach
    public void setUp() {
        failingJournalEntries.setSimulateFailure(false);
        jdbcTemplate.execute("DELETE FROM ledger_movement");
        jdbcTemplate.execute("DELETE FROM journal_entry");
        jdbcTemplate.execute("DELETE FROM account");
    }

    @Test
    public void testZeroBalanceOpenSuccess() {
        OpenAccountCommand command = new OpenAccountCommand(CUST_NUM, Money.ZERO);

        Account openedAccount = openAccountService.open(command);

        assertThat(openedAccount).isNotNull();
        assertThat(openedAccount.getNumber()).isEqualTo(CUST_NUM);
        assertThat(openedAccount.getBalance()).isEqualTo(Money.ZERO);
        assertThat(openedAccount.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        // Verify account exists in database
        AccountEntity entity = accountRepository.findByAccountNumber(CUST_NUM).orElseThrow();
        assertThat(entity.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(entity.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        // Verify no journal entries or ledger movements exist
        assertThat(journalEntryRepository.count()).isEqualTo(0);
        int movementsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_movement", Integer.class);
        assertThat(movementsCount).isEqualTo(0);
    }

    @Test
    public void testPositiveBalanceOpenSuccess() {
        // Seed clearing account
        accountRepository.save(new AccountEntity(CLEARING_NUM, AccountId.generate().toString(), new BigDecimal("1000.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.flush();

        Money openingBalance = Money.of("250.00");
        OpenAccountCommand command = new OpenAccountCommand(CUST_NUM, openingBalance);

        Account openedAccount = openAccountService.open(command);

        assertThat(openedAccount).isNotNull();
        assertThat(openedAccount.getNumber()).isEqualTo(CUST_NUM);
        assertThat(openedAccount.getBalance()).isEqualTo(openingBalance);

        // Verify balances in database
        AccountEntity custEntity = accountRepository.findByAccountNumber(CUST_NUM).orElseThrow();
        AccountEntity clearingEntity = accountRepository.findByAccountNumber(CLEARING_NUM).orElseThrow();

        assertThat(custEntity.getBalance()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(clearingEntity.getBalance()).isEqualByComparingTo(new BigDecimal("750.00"));

        // Verify double-entry journal entry was saved
        assertThat(journalEntryRepository.count()).isEqualTo(1);
        List<Map<String, Object>> movements = jdbcTemplate.queryForList("SELECT * FROM ledger_movement");
        assertThat(movements).hasSize(2);

        // Verify debit and credit amounts sum up balanced
        BigDecimal debitSum = movements.stream()
                .filter(m -> "DEBIT".equals(m.get("type")))
                .map(m -> (BigDecimal) m.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditSum = movements.stream()
                .filter(m -> "CREDIT".equals(m.get("type")))
                .map(m -> (BigDecimal) m.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(debitSum).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(creditSum).isEqualByComparingTo(new BigDecimal("250.00"));
    }

    @Test
    public void testDuplicateAccountNumberFails() {
        // Seed an existing account
        accountRepository.save(new AccountEntity(CUST_NUM, AccountId.generate().toString(), new BigDecimal("50.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.flush();

        OpenAccountCommand command = new OpenAccountCommand(CUST_NUM, Money.ZERO);

        DuplicateAccountNumberException ex = assertThrows(DuplicateAccountNumberException.class, () -> {
            openAccountService.open(command);
        });

        assertThat(ex.number()).isEqualTo(CUST_NUM);

        // Verify that the existing account was not changed or deleted
        AccountEntity entity = accountRepository.findByAccountNumber(CUST_NUM).orElseThrow();
        assertThat(entity.getBalance()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    public void testClearingAccountMissingFails() {
        // Ensure clearing account is missing from the database
        // Attempt positive open
        OpenAccountCommand command = new OpenAccountCommand(CUST_NUM, Money.of("100.00"));

        ClearingAccountMissingException ex = assertThrows(ClearingAccountMissingException.class, () -> {
            openAccountService.open(command);
        });

        assertThat(ex.clearingAccountNumber()).isEqualTo(CLEARING_NUM);

        // Verify that the customer account was not created in the database
        assertThat(accountRepository.findByAccountNumber(CUST_NUM)).isEmpty();
    }

    @Test
    public void testTransactionRollbackIntegrity() {
        // Seed clearing account
        accountRepository.save(new AccountEntity(CLEARING_NUM, AccountId.generate().toString(), new BigDecimal("1000.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.flush();

        // Enable simulated failure inside failingJournalEntries
        failingJournalEntries.setSimulateFailure(true);

        OpenAccountCommand command = new OpenAccountCommand(CUST_NUM, Money.of("250.00"));

        // Open execution should fail
        assertThrows(RuntimeException.class, () -> {
            openAccountService.open(command);
        });

        // Verify customer account was NOT created (rolled back)
        assertThat(accountRepository.findByAccountNumber(CUST_NUM)).isEmpty();

        // Verify clearing account balance remains unchanged (rolled back)
        AccountEntity clearingEntity = accountRepository.findByAccountNumber(CLEARING_NUM).orElseThrow();
        assertThat(clearingEntity.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));

        // Verify no journal entries or ledger movements were saved (rolled back)
        assertThat(journalEntryRepository.count()).isEqualTo(0);
        int movementsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_movement", Integer.class);
        assertThat(movementsCount).isEqualTo(0);
    }
}
