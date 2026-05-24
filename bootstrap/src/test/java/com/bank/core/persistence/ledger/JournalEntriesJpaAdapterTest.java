package com.bank.core.persistence.ledger;

import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.JournalEntryId;
import com.bank.core.domain.Money;
import com.bank.core.domain.Movement;
import com.bank.core.domain.MovementType;
import com.bank.core.domain.VerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JournalEntriesJpaAdapterTest {

    @Autowired
    JournalEntries journals;

    @Autowired
    JdbcTemplate jdbc;

    private AccountId source;
    private AccountId destination;

    @BeforeEach
    void freshAccounts() {
        source = AccountId.generate();
        destination = AccountId.generate();
        // Wipe between tests because @Transactional rollback is per-test but the
        // identity sequence is not transactional.
        jdbc.update("DELETE FROM ledger_movement");
        jdbc.update("DELETE FROM journal_entry");
    }

    @Test
    void saveThenFindByIdRoundTrips() {
        JournalEntry je = balanced("first", "10.00");
        journals.save(je);

        Optional<JournalEntry> loaded = journals.findById(je.id());
        assertThat(loaded).isPresent();
        JournalEntry got = loaded.get();
        assertThat(got.id()).isEqualTo(je.id());
        assertThat(got.description()).isEqualTo("first");
        assertThat(got.status()).isEqualTo(VerificationStatus.PENDING);
        assertThat(got.movements()).hasSize(2);
        assertThat(got.movements().get(0).type()).isEqualTo(MovementType.DEBIT);
        assertThat(got.movements().get(0).amount()).isEqualTo(Money.of("10.00"));
        assertThat(got.movements().get(0).accountId()).isEqualTo(source);
        assertThat(got.movements().get(1).type()).isEqualTo(MovementType.CREDIT);
        assertThat(got.movements().get(1).accountId()).isEqualTo(destination);
    }

    @Test
    void findByStatusReturnsOrderedAndLimited() {
        JournalEntry j1 = balancedAt("alpha", "5.00", Instant.parse("2026-01-01T00:00:00Z"));
        JournalEntry j2 = balancedAt("beta", "5.00", Instant.parse("2026-02-01T00:00:00Z"));
        JournalEntry j3 = balancedAt("gamma", "5.00", Instant.parse("2026-03-01T00:00:00Z"));
        journals.save(j1);
        journals.save(j2);
        journals.save(j3);

        List<JournalEntry> first2 = journals.findByStatus(VerificationStatus.PENDING, 2);
        assertThat(first2).hasSize(2);
        assertThat(first2.get(0).description()).isEqualTo("alpha");
        assertThat(first2.get(1).description()).isEqualTo("beta");
    }

    @Test
    void findByStatusEmptyWhenNoMatches() {
        journals.save(balanced("only-pending", "5.00"));
        List<JournalEntry> verified = journals.findByStatus(VerificationStatus.VERIFIED, 50);
        assertThat(verified).isEmpty();
    }

    @Test
    void isBalancedTrueForBalancedJournal() {
        JournalEntry je = balanced("balanced", "10.00");
        journals.save(je);
        assertThat(journals.isBalanced(je.id())).isTrue();
    }

    @Test
    void isBalancedFalseForRawUnbalancedRows() {
        // Bypass the domain: insert a journal with a single credit movement.
        UUID jid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO journal_entry (id, description, entry_timestamp, verification_status) "
                        + "VALUES (?, ?, ?, ?)",
                jid, "manual", Instant.parse("2026-05-24T00:00:00Z"), "PENDING");
        jdbc.update(
                "INSERT INTO ledger_movement (journal_entry_id, movement_order, account_id, amount, movement_type) "
                        + "VALUES (?, ?, ?, ?, ?)",
                jid, 0, source.value(), new java.math.BigDecimal("10.00"), "CREDIT");

        assertThat(journals.isBalanced(JournalEntryId.of(jid))).isFalse();
    }

    @Test
    void isBalancedFalseForNonExistentJournal() {
        assertThat(journals.isBalanced(JournalEntryId.generate())).isFalse();
    }

    @Test
    void movementIdsAreGloballyMonotonic() {
        JournalEntry first = balanced("first-monotonic", "10.00");
        journals.save(first);
        JournalEntry second = balanced("second-monotonic", "10.00");
        journals.save(second);

        List<Long> firstIds = jdbc.queryForList(
                "SELECT id FROM ledger_movement WHERE journal_entry_id = ? ORDER BY id ASC",
                Long.class, first.id().value());
        List<Long> secondIds = jdbc.queryForList(
                "SELECT id FROM ledger_movement WHERE journal_entry_id = ? ORDER BY id ASC",
                Long.class, second.id().value());

        assertThat(firstIds).hasSize(2);
        assertThat(secondIds).hasSize(2);
        long maxFirst = firstIds.stream().mapToLong(Long::longValue).max().orElseThrow();
        long minSecond = secondIds.stream().mapToLong(Long::longValue).min().orElseThrow();
        assertThat(minSecond).isGreaterThan(maxFirst);
    }

    @Test
    void markVerifiedPersistsAcrossReload() {
        JournalEntry je = balanced("to-verify", "10.00");
        journals.save(je);

        JournalEntry loaded = journals.findById(je.id()).orElseThrow();
        loaded.markVerified();
        journals.save(loaded);

        JournalEntry reloaded = journals.findById(je.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void markFailedPersistsAcrossReload() {
        JournalEntry je = balanced("to-fail", "10.00");
        journals.save(je);

        JournalEntry loaded = journals.findById(je.id()).orElseThrow();
        loaded.markFailed();
        journals.save(loaded);

        assertThat(journals.findById(je.id()).orElseThrow().status())
                .isEqualTo(VerificationStatus.FAILED);
    }

    private JournalEntry balanced(String description, String amount) {
        return balancedAt(description, amount, Instant.parse("2026-05-24T01:00:00Z"));
    }

    private JournalEntry balancedAt(String description, String amount, Instant when) {
        return JournalEntry.create(description, when, List.of(
                new Movement(source, Money.of(amount), MovementType.DEBIT),
                new Movement(destination, Money.of(amount), MovementType.CREDIT)));
    }
}
