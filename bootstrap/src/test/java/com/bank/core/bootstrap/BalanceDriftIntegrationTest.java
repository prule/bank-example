package com.bank.core.bootstrap;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.ledger.AuditCheckpoints;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.domain.*;
import com.bank.core.infrastructure.persistence.account.AccountEntity;
import com.bank.core.infrastructure.persistence.account.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.awaitility.Awaitility;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "bank.seed.enabled=false",
        "bank.balance-drift.fixed-delay-ms=200",
        "bank.balance-drift.initial-delay-ms=0"
})
@ActiveProfiles("test")
public class BalanceDriftIntegrationTest {

    @Autowired
    private JournalEntries journalEntries;

    @Autowired
    private Accounts accounts;

    @Autowired
    private AuditCheckpoints checkpoints;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AccountId accountId;
    private static final String CLEARING_NUM = "CLEARING-000";

    @BeforeEach
    public void setUp() {
        // Clean up database tables to ensure isolation
        jdbcTemplate.execute("DELETE FROM ledger_movement");
        jdbcTemplate.execute("DELETE FROM journal_entry");
        jdbcTemplate.execute("DELETE FROM audit_checkpoint");
        jdbcTemplate.execute("DELETE FROM account");

        accountId = AccountId.generate();

        // Seed clearing account and a customer account
        accountRepository.save(new AccountEntity(CLEARING_NUM, AccountId.generate().toString(), new BigDecimal("1000.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.save(new AccountEntity("111111", accountId.toString(), new BigDecimal("500.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.flush();
    }

    @Test
    public void testSchedulerAuditLivenessAndCheckpointProgression() {
        // Pre-insert a balanced journal entry
        AccountId clearingId = AccountId.fromString(accountRepository.findByAccountNumber(CLEARING_NUM).get().getId());
        List<Movement> balancedMovements = List.of(
                new Movement(accountId, Money.of("100.00"), MovementType.CREDIT),
                new Movement(clearingId, Money.of("100.00"), MovementType.DEBIT)
        );
        JournalEntry journal = JournalEntry.create("Balanced Transfer", Instant.now(), balancedMovements);
        journalEntries.save(journal);

        // Get the maximum movement ID (ceiling)
        long ceiling = journalEntries.currentCeiling();
        assertThat(ceiling).isGreaterThan(0L);

        // Wait until Awaitility confirms the checkpoint has advanced to ceiling
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long savedCheckpoint = checkpoints.readOrZero("balance_drift");
                    assertThat(savedCheckpoint).isEqualTo(ceiling);
                });
    }

    @Test
    public void testDriftSuspendsCustomerAccount() {
        // Direct SQL insert to bypass domain validation and create a drift
        // Customer account cached balance is 500.00, but signed ledger sum is 450.00 (drift!)
        jdbcTemplate.update("INSERT INTO journal_entry (id, status, description) VALUES ('TEST-JE', 'VERIFIED', 'Corrupted JE')");
        jdbcTemplate.update("INSERT INTO ledger_movement (journal_entry_id, account_number, type, amount) VALUES ('TEST-JE', '111111', 'CREDIT', 450.00)");

        // Verify that the background scheduler picks this up, suspends the account, and advances checkpoint
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Account acc = accounts.findById(accountId).orElseThrow();
                    assertThat(acc.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
                });
    }

    @Test
    public void testClearingAccountCarveOutIsNeverSuspended() {
        // Force a drift on the clearing account
        // Clearing balance is 1000.00, but ledger has CREDIT 900.00 (drift!)
        jdbcTemplate.update("INSERT INTO journal_entry (id, status, description) VALUES ('TEST-JE-CLEARING', 'VERIFIED', 'Clearing Corrupted')");
        jdbcTemplate.update("INSERT INTO ledger_movement (journal_entry_id, account_number, type, amount) VALUES ('TEST-JE-CLEARING', 'CLEARING-000', 'CREDIT', 900.00)");

        long ceiling = journalEntries.currentCeiling();

        // Wait until checkpoint advances to verify audit ran over the clearing account movement
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long savedCheckpoint = checkpoints.readOrZero("balance_drift");
                    assertThat(savedCheckpoint).isEqualTo(ceiling);
                });

        // Verify clearing account remains ACTIVE
        Account clearing = accounts.findByNumber(CLEARING_NUM).orElseThrow();
        assertThat(clearing.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }
}
