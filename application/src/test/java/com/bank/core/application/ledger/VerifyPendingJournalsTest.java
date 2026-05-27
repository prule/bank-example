package com.bank.core.application.ledger;

import com.bank.core.application.account.Accounts;
import com.bank.core.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class VerifyPendingJournalsTest {

    private FakeJournalEntries journalEntries;
    private FakeAccounts accounts;
    private VerifyPendingJournals useCase;

    private static final String ACC_1_NUM = "ACC-001";
    private static final String ACC_2_NUM = "ACC-002";

    @BeforeEach
    public void setUp() {
        journalEntries = new FakeJournalEntries();
        accounts = new FakeAccounts();
        useCase = new VerifyPendingJournals(journalEntries, accounts);
    }

    @Test
    public void testSuccessfulPromotionToVerified() {
        Account a1 = Account.open(ACC_1_NUM, Money.of("100.00"));
        Account a2 = Account.open(ACC_2_NUM, Money.of("50.00"));
        accounts.save(a1);
        accounts.save(a2);

        Movement m1 = new Movement(a1.getId(), Money.of("10.00"), MovementType.DEBIT);
        Movement m2 = new Movement(a2.getId(), Money.of("10.00"), MovementType.CREDIT);

        JournalEntry pendingJournal = JournalEntry.create("Balanced Transfer", Instant.now(), List.of(m1, m2));
        journalEntries.addPending(pendingJournal, true);

        SweepReport report = useCase.sweep(50);

        // Assert report counts
        assertEquals(1, report.processed());
        assertEquals(1, report.verified());
        assertEquals(0, report.failed());
        assertEquals(0, report.errored());

        // Invariant holds
        assertEquals(report.processed(), report.verified() + report.failed() + report.errored());

        // Assert status promoted
        Optional<JournalEntry> retrieved = journalEntries.findById(pendingJournal.getId());
        assertTrue(retrieved.isPresent());
        assertEquals(VerificationStatus.VERIFIED, retrieved.get().getStatus());
    }

    @Test
    public void testUnbalancedJournalFailsAndSuspendsTouchedAccounts() {
        Account a1 = Account.open(ACC_1_NUM, Money.of("100.00"));
        Account a2 = Account.open(ACC_2_NUM, Money.of("50.00"));
        accounts.save(a1);
        accounts.save(a2);

        Movement m1 = new Movement(a1.getId(), Money.of("10.00"), MovementType.DEBIT);
        Movement m2 = new Movement(a2.getId(), Money.of("10.00"), MovementType.CREDIT);

        // We bypass domain balancing check by reconstitute or side-channel simulating corrupted DB row
        JournalEntry corruptedJournal = JournalEntry.reconstitute(
                JournalEntryId.generate(),
                "Corrupted",
                Instant.now(),
                VerificationStatus.PENDING,
                List.of(m1, m2)
        );
        journalEntries.addPending(corruptedJournal, false);

        SweepReport report = useCase.sweep(50);

        // Assert report counts
        assertEquals(1, report.processed());
        assertEquals(0, report.verified());
        assertEquals(1, report.failed());
        assertEquals(0, report.errored());

        // Invariant holds
        assertEquals(report.processed(), report.verified() + report.failed() + report.errored());

        // Assert status transitioned to FAILED
        Optional<JournalEntry> retrievedJournal = journalEntries.findById(corruptedJournal.getId());
        assertTrue(retrievedJournal.isPresent());
        assertEquals(VerificationStatus.FAILED, retrievedJournal.get().getStatus());

        // Assert both active accounts were automatically suspended
        Optional<Account> retrievedA1 = accounts.findById(a1.getId());
        Optional<Account> retrievedA2 = accounts.findById(a2.getId());

        assertTrue(retrievedA1.isPresent());
        assertEquals(AccountStatus.SUSPENDED, retrievedA1.get().getStatus());
        assertTrue(retrievedA2.isPresent());
        assertEquals(AccountStatus.SUSPENDED, retrievedA2.get().getStatus());
    }

    @Test
    public void testSuspendCascadeIdempotencyOnAlreadySuspendedAccounts() {
        Account a1 = Account.open(ACC_1_NUM, Money.of("100.00"));
        a1.suspend(); // Pre-suspended
        Account a2 = Account.open(ACC_2_NUM, Money.of("50.00")); // Active
        accounts.save(a1);
        accounts.save(a2);

        Movement m1 = new Movement(a1.getId(), Money.of("10.00"), MovementType.DEBIT);
        Movement m2 = new Movement(a2.getId(), Money.of("10.00"), MovementType.CREDIT);

        JournalEntry corruptedJournal = JournalEntry.reconstitute(
                JournalEntryId.generate(),
                "Corrupted",
                Instant.now(),
                VerificationStatus.PENDING,
                List.of(m1, m2)
        );
        journalEntries.addPending(corruptedJournal, false);

        SweepReport report = useCase.sweep(50);

        assertEquals(1, report.processed());
        assertEquals(1, report.failed());

        // Verify active account gets suspended, and pre-suspended doesn't throw or fail
        assertEquals(AccountStatus.SUSPENDED, accounts.findById(a1.getId()).get().getStatus());
        assertEquals(AccountStatus.SUSPENDED, accounts.findById(a2.getId()).get().getStatus());
    }

    @Test
    public void testSuspendCascadeSkipsClosedAccounts() {
        Account a1 = Account.open(ACC_1_NUM, Money.of("100.00"));
        a1.suspend();
        a1.close(); // Closed
        Account a2 = Account.open(ACC_2_NUM, Money.of("50.00"));
        accounts.save(a1);
        accounts.save(a2);

        Movement m1 = new Movement(a1.getId(), Money.of("10.00"), MovementType.DEBIT);
        Movement m2 = new Movement(a2.getId(), Money.of("10.00"), MovementType.CREDIT);

        JournalEntry corruptedJournal = JournalEntry.reconstitute(
                JournalEntryId.generate(),
                "Corrupted",
                Instant.now(),
                VerificationStatus.PENDING,
                List.of(m1, m2)
        );
        journalEntries.addPending(corruptedJournal, false);

        // Running sweep should not throw IllegalStatusTransitionException
        assertDoesNotThrow(() -> useCase.sweep(50));

        // Verify CLOSED stays CLOSED, ACTIVE gets SUSPENDED
        assertEquals(AccountStatus.CLOSED, accounts.findById(a1.getId()).get().getStatus());
        assertEquals(AccountStatus.SUSPENDED, accounts.findById(a2.getId()).get().getStatus());
    }

    @Test
    public void testSuspendCascadeDeduplication() {
        Account a1 = Account.open(ACC_1_NUM, Money.of("100.00"));
        accounts.save(a1);

        // exotic malformed journal touching same account three times
        Movement m1 = new Movement(a1.getId(), Money.of("10.00"), MovementType.DEBIT);
        Movement m2 = new Movement(a1.getId(), Money.of("10.00"), MovementType.DEBIT);
        Movement m3 = new Movement(a1.getId(), Money.of("20.00"), MovementType.CREDIT);

        JournalEntry corruptedJournal = JournalEntry.reconstitute(
                JournalEntryId.generate(),
                "Corrupted Multiple",
                Instant.now(),
                VerificationStatus.PENDING,
                List.of(m1, m2, m3)
        );
        journalEntries.addPending(corruptedJournal, false);

        // Deduplication should protect against calling suspend on now-SUSPENDED aggregate
        assertDoesNotThrow(() -> useCase.sweep(50));
        assertEquals(AccountStatus.SUSPENDED, accounts.findById(a1.getId()).get().getStatus());
    }

    @Test
    public void testPerJournalResilience() {
        // Setup J1 (balanced), J2 (balanced but fails on check), J3 (balanced)
        Account a1 = Account.open(ACC_1_NUM, Money.of("100.00"));
        accounts.save(a1);

        Movement m1 = new Movement(a1.getId(), Money.of("10.00"), MovementType.DEBIT);
        Movement m2 = new Movement(a1.getId(), Money.of("10.00"), MovementType.CREDIT);

        JournalEntry j1 = JournalEntry.create("J1", Instant.now(), List.of(m1, m2));
        JournalEntry j2 = JournalEntry.create("J2", Instant.now(), List.of(m1, m2));
        JournalEntry j3 = JournalEntry.create("J3", Instant.now(), List.of(m1, m2));

        journalEntries.addPending(j1, true);
        journalEntries.addFailing(j2, new RuntimeException("Simulated Database Error"));
        journalEntries.addPending(j3, true);

        SweepReport report = useCase.sweep(50);

        // processed=3, verified=2, failed=0, errored=1
        assertEquals(3, report.processed());
        assertEquals(2, report.verified());
        assertEquals(0, report.failed());
        assertEquals(1, report.errored());

        // Invariant holds
        assertEquals(report.processed(), report.verified() + report.failed() + report.errored());

        // j1 and j3 promoted, j2 remains in PENDING
        assertEquals(VerificationStatus.VERIFIED, journalEntries.findById(j1.getId()).get().getStatus());
        assertEquals(VerificationStatus.PENDING, journalEntries.findById(j2.getId()).get().getStatus());
        assertEquals(VerificationStatus.VERIFIED, journalEntries.findById(j3.getId()).get().getStatus());
    }

    // --- Fast, Mockito-free test fakes ---

    private static class FakeJournalEntries implements JournalEntries {
        private final Map<JournalEntryId, JournalEntry> byId = new LinkedHashMap<>();
        private final Map<JournalEntryId, Boolean> balancedMap = new HashMap<>();
        private final Map<JournalEntryId, RuntimeException> exceptionMap = new HashMap<>();

        public void addPending(JournalEntry entry, boolean balanced) {
            byId.put(entry.getId(), entry);
            balancedMap.put(entry.getId(), balanced);
        }

        public void addFailing(JournalEntry entry, RuntimeException ex) {
            byId.put(entry.getId(), entry);
            exceptionMap.put(entry.getId(), ex);
        }

        @Override
        public void save(JournalEntry journalEntry) {
            byId.put(journalEntry.getId(), journalEntry);
        }

        @Override
        public Optional<JournalEntry> findById(JournalEntryId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<JournalEntry> findByStatus(VerificationStatus status, int limit) {
            return byId.values().stream()
                    .filter(j -> j.getStatus() == status)
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean isBalanced(JournalEntryId id) {
            if (exceptionMap.containsKey(id)) {
                throw exceptionMap.get(id);
            }
            return balancedMap.getOrDefault(id, false);
        }
    }

    private static class FakeAccounts implements Accounts {
        private final Map<AccountId, Account> byId = new HashMap<>();
        private final Map<String, Account> byNumber = new HashMap<>();

        @Override
        public Optional<Account> findByNumber(String number) {
            return Optional.ofNullable(byNumber.get(number));
        }

        @Override
        public Optional<Account> findById(AccountId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Account save(Account account) {
            byId.put(account.getId(), account);
            byNumber.put(account.getNumber(), account);
            return account;
        }
    }
}
