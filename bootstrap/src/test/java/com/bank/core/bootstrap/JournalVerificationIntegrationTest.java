package com.bank.core.bootstrap;

import com.bank.core.application.account.Accounts;
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
        "bank.journal-verification.fixed-delay-ms=200",
        "bank.journal-verification.initial-delay-ms=0",
        "bank.journal-verification.page-size=50"
})
@ActiveProfiles("test")
public class JournalVerificationIntegrationTest {

    @Autowired
    private JournalEntries journalEntries;

    @Autowired
    private Accounts accounts;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AccountId accountId1;
    private AccountId accountId2;

    @BeforeEach
    public void setUp() {
        // Purge ledger movements, journal entries, and accounts to ensure test isolation
        jdbcTemplate.execute("DELETE FROM ledger_movement");
        jdbcTemplate.execute("DELETE FROM journal_entry");
        jdbcTemplate.execute("DELETE FROM account");

        accountId1 = AccountId.generate();
        accountId2 = AccountId.generate();

        // Seed two active accounts in the DB directly
        accountRepository.save(new AccountEntity("111111", accountId1.toString(), new BigDecimal("1000.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.save(new AccountEntity("222222", accountId2.toString(), new BigDecimal("500.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.flush();
    }

    @Test
    public void testPendingJournalBalancedMovesToVerified() {
        // Create balanced movements
        List<Movement> movements = List.of(
                new Movement(accountId1, Money.of("100.00"), MovementType.DEBIT),
                new Movement(accountId2, Money.of("100.00"), MovementType.CREDIT)
        );
        JournalEntry journal = JournalEntry.create("Balanced Transfer Integration", Instant.now(), movements);
        journalEntries.save(journal);

        // Under Awaitility poll, wait until the journal is VERIFIED
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Optional<JournalEntry> loaded = journalEntries.findById(journal.getId());
                    assertThat(loaded).isPresent();
                    assertThat(loaded.get().getStatus()).isEqualTo(VerificationStatus.VERIFIED);
                });
    }

    @Test
    public void testUnbalancedJournalTransitionsToFailedAndSuspendsAccounts() {
        JournalEntryId journalId = JournalEntryId.generate();

        // Direct SQL insertions bypass domain checks to simulate a corrupted/unbalanced journal in the DB
        jdbcTemplate.update("INSERT INTO journal_entry (id, status, description) VALUES (?, 'PENDING', 'Corrupted Unbalanced Integration')", journalId.toString());
        // Touching both accounts in unbalanced state: DEBIT 200 vs CREDIT 150
        jdbcTemplate.update("INSERT INTO ledger_movement (journal_entry_id, account_number, type, amount) VALUES (?, '111111', 'DEBIT', 200.00)", journalId.toString());
        jdbcTemplate.update("INSERT INTO ledger_movement (journal_entry_id, account_number, type, amount) VALUES (?, '222222', 'CREDIT', 150.00)", journalId.toString());

        // Wait until status transitions to FAILED
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Optional<JournalEntry> loaded = journalEntries.findById(journalId);
                    assertThat(loaded).isPresent();
                    assertThat(loaded.get().getStatus()).isEqualTo(VerificationStatus.FAILED);
                });

        // Verify both touched accounts were suspended
        Account acc1 = accounts.findById(accountId1).orElseThrow();
        Account acc2 = accounts.findById(accountId2).orElseThrow();
        assertThat(acc1.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(acc2.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
    }
}
