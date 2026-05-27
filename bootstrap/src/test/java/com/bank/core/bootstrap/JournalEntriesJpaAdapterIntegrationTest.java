package com.bank.core.bootstrap;

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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class JournalEntriesJpaAdapterIntegrationTest {

    @Autowired
    private JournalEntries journalEntries;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AccountId accountId1;
    private AccountId accountId2;

    @BeforeEach
    public void setUp() {
        jdbcTemplate.execute("DELETE FROM ledger_movement");
        jdbcTemplate.execute("DELETE FROM journal_entry");
        jdbcTemplate.execute("DELETE FROM account");

        accountId1 = AccountId.generate();
        accountId2 = AccountId.generate();

        // Seed account entities
        accountRepository.save(new AccountEntity("111111", accountId1.toString(), new BigDecimal("1000.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.save(new AccountEntity("222222", accountId2.toString(), new BigDecimal("500.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.flush();
    }

    @Test
    public void testSaveAndFindById() {
        List<Movement> movements = List.of(
                new Movement(accountId1, Money.of("100.00"), MovementType.DEBIT),
                new Movement(accountId2, Money.of("100.00"), MovementType.CREDIT)
        );

        Instant timestamp = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        JournalEntry journal = JournalEntry.create("Transfer test", timestamp, movements);
        journalEntries.save(journal);

        Optional<JournalEntry> loadedOpt = journalEntries.findById(journal.getId());
        assertTrue(loadedOpt.isPresent());

        JournalEntry loaded = loadedOpt.get();
        assertThat(loaded.getId()).isEqualTo(journal.getId());
        assertThat(loaded.getDescription()).isEqualTo("Transfer test");
        assertThat(loaded.getTimestamp()).isEqualTo(timestamp);
        assertThat(loaded.getStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(loaded.getMovements()).hasSize(2);
        
        Movement m1 = loaded.getMovements().stream().filter(m -> m.type() == MovementType.DEBIT).findFirst().orElseThrow();
        assertThat(m1.accountId()).isEqualTo(accountId1);
        assertThat(m1.amount()).isEqualTo(Money.of("100.00"));
        
        Movement m2 = loaded.getMovements().stream().filter(m -> m.type() == MovementType.CREDIT).findFirst().orElseThrow();
        assertThat(m2.accountId()).isEqualTo(accountId2);
        assertThat(m2.amount()).isEqualTo(Money.of("100.00"));
    }

    @Test
    public void testFindByStatusPagedAndOrdered() {
        // Create three journals in sequence
        Instant t1 = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant t2 = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant t3 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        JournalEntry j1 = JournalEntry.create("J1", t1, List.of(
                new Movement(accountId1, Money.of("10.00"), MovementType.DEBIT),
                new Movement(accountId2, Money.of("10.00"), MovementType.CREDIT)
        ));
        JournalEntry j2 = JournalEntry.create("J2", t2, List.of(
                new Movement(accountId1, Money.of("20.00"), MovementType.DEBIT),
                new Movement(accountId2, Money.of("20.00"), VerificationStatus.PENDING == VerificationStatus.PENDING ? MovementType.CREDIT : MovementType.DEBIT)
        ));
        JournalEntry j3 = JournalEntry.create("J3", t3, List.of(
                new Movement(accountId1, Money.of("30.00"), MovementType.DEBIT),
                new Movement(accountId2, Money.of("30.00"), MovementType.CREDIT)
        ));

        journalEntries.save(j1);
        journalEntries.save(j2);
        journalEntries.save(j3);

        // Fetch limit 2
        List<JournalEntry> results = journalEntries.findByStatus(VerificationStatus.PENDING, 2);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).getId()).isEqualTo(j1.getId());
        assertThat(results.get(1).getId()).isEqualTo(j2.getId());

        // Fetch limit 5
        List<JournalEntry> allResults = journalEntries.findByStatus(VerificationStatus.PENDING, 5);
        assertThat(allResults).hasSize(3);
        assertThat(allResults.get(0).getId()).isEqualTo(j1.getId());
        assertThat(allResults.get(1).getId()).isEqualTo(j2.getId());
        assertThat(allResults.get(2).getId()).isEqualTo(j3.getId());
    }

    @Test
    public void testIsBalancedBalancedJournal() {
        JournalEntry journal = JournalEntry.create("Balanced", Instant.now(), List.of(
                new Movement(accountId1, Money.of("150.00"), MovementType.DEBIT),
                new Movement(accountId2, Money.of("150.00"), MovementType.CREDIT)
        ));
        journalEntries.save(journal);

        assertTrue(journalEntries.isBalanced(journal.getId()));
    }

    @Test
    public void testIsBalancedUnbalancedJournal() {
        JournalEntryId journalId = JournalEntryId.generate();

        // Bypassing the domain checks using direct JDBC templates to force insert an unbalanced entry
        jdbcTemplate.update("INSERT INTO journal_entry (id, status, description) VALUES (?, 'PENDING', 'Unbalanced force')", journalId.toString());
        jdbcTemplate.update("INSERT INTO ledger_movement (journal_entry_id, account_number, type, amount) VALUES (?, '111111', 'DEBIT', 200.00)", journalId.toString());
        jdbcTemplate.update("INSERT INTO ledger_movement (journal_entry_id, account_number, type, amount) VALUES (?, '222222', 'CREDIT', 150.00)", journalId.toString());

        assertFalse(journalEntries.isBalanced(journalId));
    }

    @Test
    public void testIsBalancedNonExistentJournal() {
        assertFalse(journalEntries.isBalanced(JournalEntryId.generate()));
    }
}
