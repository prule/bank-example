package com.bank.core.application.ledger;

import com.bank.core.application.account.Accounts;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.JournalEntryId;
import com.bank.core.domain.Money;
import com.bank.core.domain.Movement;
import com.bank.core.domain.MovementType;
import com.bank.core.domain.VerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VerifyPendingJournalsTest {

    private JournalEntries journals;
    private Accounts accounts;

    @BeforeEach
    void setUp() {
        journals = mock(JournalEntries.class);
        accounts = mock(Accounts.class);
    }

    private VerifyPendingJournals useCase(int pageSize) {
        return new VerifyPendingJournals(journals, accounts, pageSize);
    }

    private static Account activeAccount(String number) {
        return Account.open(AccountNumber.of(number), Money.ZERO);
    }

    private static Account suspendedAccount(String number) {
        Account a = Account.open(AccountNumber.of(number), Money.ZERO);
        a.suspend();
        return a;
    }

    private static Account closedAccount(String number) {
        // Account aggregate has no public close() mutator (per F01); use the persistence-only
        // rehydrate() factory to fabricate a CLOSED aggregate for the cascade-skip scenario.
        return Account.rehydrate(
                AccountId.generate(),
                AccountNumber.of(number),
                Money.ZERO,
                AccountStatus.CLOSED);
    }

    private static JournalEntry balancedEntry(AccountId src, AccountId dst, String amount) {
        return JournalEntry.create(
                "test-balanced",
                Instant.parse("2026-05-25T10:00:00Z"),
                List.of(
                        new Movement(src, Money.of(amount), MovementType.DEBIT),
                        new Movement(dst, Money.of(amount), MovementType.CREDIT)));
    }

    private static JournalEntry unbalancedEntry(AccountId src, AccountId dst, String debit, String credit) {
        return JournalEntry.rehydrate(
                JournalEntryId.generate(),
                "test-unbalanced",
                Instant.parse("2026-05-25T10:00:00Z"),
                VerificationStatus.PENDING,
                List.of(
                        new Movement(src, Money.of(debit), MovementType.DEBIT),
                        new Movement(dst, Money.of(credit), MovementType.CREDIT)));
    }

    private static JournalEntry unbalancedEntryWithDuplicateAccount(AccountId duplicateId, AccountId otherId) {
        return JournalEntry.rehydrate(
                JournalEntryId.generate(),
                "test-duplicate-account",
                Instant.parse("2026-05-25T10:00:00Z"),
                VerificationStatus.PENDING,
                List.of(
                        new Movement(duplicateId, Money.of("10.00"), MovementType.DEBIT),
                        new Movement(otherId, Money.of("3.00"), MovementType.CREDIT),
                        new Movement(duplicateId, Money.of("5.00"), MovementType.DEBIT)));
    }

    private static void assertInvariant(SweepReport report) {
        assertEquals(report.verified() + report.failed() + report.errored(), report.processed(),
                "SweepReport invariant: processed == verified + failed + errored");
    }

    @Test
    void constructor_rejectsNullPortsAndNonPositivePageSize() {
        assertEquals("journals cannot be null",
                assertThrows(NullPointerException.class,
                        () -> new VerifyPendingJournals(null, accounts, 50)).getMessage());
        assertEquals("accounts cannot be null",
                assertThrows(NullPointerException.class,
                        () -> new VerifyPendingJournals(journals, null, 50)).getMessage());
        assertEquals("pageSize must be positive (was: 0)",
                assertThrows(IllegalArgumentException.class,
                        () -> new VerifyPendingJournals(journals, accounts, 0)).getMessage());
        assertEquals("pageSize must be positive (was: -1)",
                assertThrows(IllegalArgumentException.class,
                        () -> new VerifyPendingJournals(journals, accounts, -1)).getMessage());
    }

    @Test
    void emptyPage_returnsZeroReport_noSaves() {
        when(journals.findByStatus(VerificationStatus.PENDING, 50)).thenReturn(List.of());

        SweepReport report = useCase(50).sweep();

        assertEquals(new SweepReport(0, 0, 0, 0), report);
        assertInvariant(report);
        verify(journals, never()).save(any());
        verify(accounts, never()).findById(any());
        verify(accounts, never()).save(any());
    }

    @Test
    void pageOfBalancedJournals_allVerified_noCascade() {
        Account a = activeAccount("ACC-A");
        Account b = activeAccount("ACC-B");
        JournalEntry j1 = balancedEntry(a.id(), b.id(), "10.00");
        JournalEntry j2 = balancedEntry(a.id(), b.id(), "20.00");
        JournalEntry j3 = balancedEntry(a.id(), b.id(), "30.00");

        when(journals.findByStatus(VerificationStatus.PENDING, 50)).thenReturn(List.of(j1, j2, j3));
        when(journals.isBalanced(j1.id())).thenReturn(true);
        when(journals.isBalanced(j2.id())).thenReturn(true);
        when(journals.isBalanced(j3.id())).thenReturn(true);

        SweepReport report = useCase(50).sweep();

        verify(journals).save(j1);
        verify(journals).save(j2);
        verify(journals).save(j3);
        verify(accounts, never()).findById(any());
        verify(accounts, never()).save(any());

        assertEquals(VerificationStatus.VERIFIED, j1.status());
        assertEquals(VerificationStatus.VERIFIED, j2.status());
        assertEquals(VerificationStatus.VERIFIED, j3.status());
        assertEquals(new SweepReport(3, 3, 0, 0), report);
        assertInvariant(report);
    }

    @Test
    void unbalancedJournal_failsAndSuspendsTouchedActiveAccounts() {
        Account a = activeAccount("ACC-A");
        Account b = activeAccount("ACC-B");
        JournalEntry entry = unbalancedEntry(a.id(), b.id(), "10.00", "5.00");

        when(journals.findByStatus(VerificationStatus.PENDING, 50)).thenReturn(List.of(entry));
        when(journals.isBalanced(entry.id())).thenReturn(false);
        when(accounts.findById(a.id())).thenReturn(Optional.of(a));
        when(accounts.findById(b.id())).thenReturn(Optional.of(b));

        SweepReport report = useCase(50).sweep();

        verify(journals).save(entry);
        assertEquals(VerificationStatus.FAILED, entry.status());
        verify(accounts).save(a);
        verify(accounts).save(b);
        assertEquals(AccountStatus.SUSPENDED, a.status());
        assertEquals(AccountStatus.SUSPENDED, b.status());
        assertEquals(new SweepReport(1, 0, 1, 0), report);
        assertInvariant(report);
    }

    @Test
    void unbalancedJournal_alreadySuspendedAccount_isNotResaved() {
        Account active = activeAccount("ACC-ACTIVE");
        Account alreadySuspended = suspendedAccount("ACC-SUSPENDED");
        JournalEntry entry = unbalancedEntry(active.id(), alreadySuspended.id(), "10.00", "5.00");

        when(journals.findByStatus(VerificationStatus.PENDING, 50)).thenReturn(List.of(entry));
        when(journals.isBalanced(entry.id())).thenReturn(false);
        when(accounts.findById(active.id())).thenReturn(Optional.of(active));
        when(accounts.findById(alreadySuspended.id())).thenReturn(Optional.of(alreadySuspended));

        SweepReport report = useCase(50).sweep();

        verify(accounts).save(active);
        verify(accounts, never()).save(alreadySuspended);
        assertEquals(AccountStatus.SUSPENDED, active.status());
        assertEquals(AccountStatus.SUSPENDED, alreadySuspended.status());
        assertEquals(new SweepReport(1, 0, 1, 0), report);
        assertInvariant(report);
    }

    @Test
    void unbalancedJournal_closedAccount_isSkipped() {
        Account active = activeAccount("ACC-ACTIVE");
        Account closed = closedAccount("ACC-CLOSED");
        JournalEntry entry = unbalancedEntry(active.id(), closed.id(), "10.00", "5.00");

        when(journals.findByStatus(VerificationStatus.PENDING, 50)).thenReturn(List.of(entry));
        when(journals.isBalanced(entry.id())).thenReturn(false);
        when(accounts.findById(active.id())).thenReturn(Optional.of(active));
        when(accounts.findById(closed.id())).thenReturn(Optional.of(closed));

        SweepReport report = useCase(50).sweep();

        verify(accounts).save(active);
        verify(accounts, never()).save(closed);
        assertEquals(AccountStatus.CLOSED, closed.status());
        assertEquals(new SweepReport(1, 0, 1, 0), report);
        assertInvariant(report);
    }

    @Test
    void unbalancedJournal_duplicateAccountIdAcrossMovements_suspendsOnce() {
        Account duplicated = activeAccount("ACC-DUP");
        Account other = activeAccount("ACC-OTHER");
        JournalEntry entry = unbalancedEntryWithDuplicateAccount(duplicated.id(), other.id());

        when(journals.findByStatus(VerificationStatus.PENDING, 50)).thenReturn(List.of(entry));
        when(journals.isBalanced(entry.id())).thenReturn(false);
        when(accounts.findById(duplicated.id())).thenReturn(Optional.of(duplicated));
        when(accounts.findById(other.id())).thenReturn(Optional.of(other));

        SweepReport report = useCase(50).sweep();

        verify(accounts, times(1)).findById(duplicated.id());
        verify(accounts, times(1)).save(duplicated);
        verify(accounts, times(1)).findById(other.id());
        verify(accounts, times(1)).save(other);
        assertEquals(new SweepReport(1, 0, 1, 0), report);
        assertInvariant(report);
    }

    @Test
    void unbalancedJournal_findByIdReturnsEmpty_isSilentlySkipped() {
        Account real = activeAccount("ACC-REAL");
        AccountId ghostId = AccountId.generate();
        JournalEntry entry = unbalancedEntry(real.id(), ghostId, "10.00", "5.00");

        when(journals.findByStatus(VerificationStatus.PENDING, 50)).thenReturn(List.of(entry));
        when(journals.isBalanced(entry.id())).thenReturn(false);
        when(accounts.findById(real.id())).thenReturn(Optional.of(real));
        when(accounts.findById(ghostId)).thenReturn(Optional.empty());

        SweepReport report = useCase(50).sweep();

        verify(accounts).save(real);
        verify(accounts, times(1)).findById(ghostId);
        assertEquals(AccountStatus.SUSPENDED, real.status());
        assertEquals(VerificationStatus.FAILED, entry.status());
        assertEquals(new SweepReport(1, 0, 1, 0), report);
        assertInvariant(report);
    }

    @Test
    void oneBadJournal_doesNotStopTick() {
        Account a = activeAccount("ACC-A");
        Account b = activeAccount("ACC-B");
        JournalEntry j1 = balancedEntry(a.id(), b.id(), "10.00");
        JournalEntry j2 = balancedEntry(a.id(), b.id(), "20.00");
        JournalEntry j3 = balancedEntry(a.id(), b.id(), "30.00");

        when(journals.findByStatus(VerificationStatus.PENDING, 50)).thenReturn(List.of(j1, j2, j3));
        when(journals.isBalanced(j1.id())).thenReturn(true);
        when(journals.isBalanced(j2.id())).thenThrow(new RuntimeException("simulated"));
        when(journals.isBalanced(j3.id())).thenReturn(true);

        SweepReport report = useCase(50).sweep();

        verify(journals).save(j1);
        verify(journals, never()).save(j2);
        verify(journals).save(j3);
        assertEquals(VerificationStatus.VERIFIED, j1.status());
        assertEquals(VerificationStatus.PENDING, j2.status());
        assertEquals(VerificationStatus.VERIFIED, j3.status());
        assertEquals(new SweepReport(3, 2, 0, 1), report);
        assertInvariant(report);
    }

    @Test
    void sweepEscapingException_propagates() {
        when(journals.findByStatus(VerificationStatus.PENDING, 50))
                .thenThrow(new RuntimeException("DB down"));

        VerifyPendingJournals uc = useCase(50);
        RuntimeException ex = assertThrows(RuntimeException.class, uc::sweep);
        assertEquals("DB down", ex.getMessage());
        verifyNoInteractions(accounts);
    }
}
