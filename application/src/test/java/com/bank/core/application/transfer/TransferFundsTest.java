package com.bank.core.application.transfer;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.Money;
import com.bank.core.domain.Movement;
import com.bank.core.domain.MovementType;
import com.bank.core.domain.ResourceNotFoundException;
import com.bank.core.domain.SameAccountTransferException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransferFundsTest {

    private static final AccountNumber SRC = AccountNumber.of("ACC-SRC");
    private static final AccountNumber DST = AccountNumber.of("ACC-DST");
    private static final Money TEN = Money.of("10.00");
    private static final Instant T = Instant.parse("2026-05-24T10:00:00Z");

    private Accounts accounts;
    private JournalEntries journals;
    private AccountLocker locker;
    private Clock clock;
    private TransferFunds useCase;

    private Account source;
    private Account destination;

    @BeforeEach
    void setUp() {
        accounts = mock(Accounts.class);
        journals = mock(JournalEntries.class);
        locker = mock(AccountLocker.class);
        clock = Clock.fixed(T, ZoneOffset.UTC);
        useCase = new TransferFunds(accounts, journals, locker, clock);

        source = Account.open(SRC, Money.of("100.00"));
        destination = Account.open(DST, Money.of("50.00"));

        // By default, the locker invokes its runnable inline (simulates an
        // active transaction with locks granted immediately).
        doAnswer(inv -> {
            Runnable r = inv.getArgument(2);
            r.run();
            return null;
        }).when(locker).withPairedLocks(any(), any(), any());
    }

    @Test
    void happyPathDebitsCreditsSavesAndProducesBalancedJournal() {
        when(accounts.findByNumber(SRC)).thenReturn(Optional.of(source));
        when(accounts.findByNumber(DST)).thenReturn(Optional.of(destination));

        useCase.transfer(new TransferCommand(SRC, DST, TEN));

        assertEquals(Money.of("90.00"), source.balance());
        assertEquals(Money.of("60.00"), destination.balance());

        verify(accounts).save(source);
        verify(accounts).save(destination);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journals, times(1)).save(captor.capture());
        JournalEntry entry = captor.getValue();
        assertEquals("Transfer from ACC-SRC to ACC-DST", entry.description());
        assertEquals(T, entry.timestamp());
        assertEquals(2, entry.movements().size());

        Movement debit = entry.movements().get(0);
        assertEquals(MovementType.DEBIT, debit.type());
        assertEquals(source.id(), debit.accountId());
        assertEquals(TEN, debit.amount());

        Movement credit = entry.movements().get(1);
        assertEquals(MovementType.CREDIT, credit.type());
        assertEquals(destination.id(), credit.accountId());
        assertEquals(TEN, credit.amount());
    }

    @Test
    void selfTransferShortCircuitsBeforeLockOrPortCalls() {
        SameAccountTransferException ex = assertThrows(SameAccountTransferException.class,
                () -> useCase.transfer(new TransferCommand(SRC, SRC, TEN)));
        assertSame(SRC, ex.account());

        verifyNoInteractions(locker, accounts, journals);
    }

    @Test
    void missingSourceThrowsResourceNotFoundAndDoesNotMutate() {
        when(accounts.findByNumber(SRC)).thenReturn(Optional.empty());
        when(accounts.findByNumber(DST)).thenReturn(Optional.of(destination));

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> useCase.transfer(new TransferCommand(SRC, DST, TEN)));
        assertEquals("ACC-SRC", ex.identifier());

        assertEquals(Money.of("50.00"), destination.balance());
        verify(accounts, never()).save(any());
        verifyNoInteractions(journals);
    }

    @Test
    void missingDestinationThrowsResourceNotFoundAndDoesNotMutate() {
        when(accounts.findByNumber(SRC)).thenReturn(Optional.of(source));
        when(accounts.findByNumber(DST)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> useCase.transfer(new TransferCommand(SRC, DST, TEN)));
        assertEquals("ACC-DST", ex.identifier());

        // Source was loaded but not yet mutated (debit happens after both loads).
        assertEquals(Money.of("100.00"), source.balance());
        verify(accounts, never()).save(any());
        verifyNoInteractions(journals);
    }

    @Test
    void lockerIsCalledBeforeAccountsAreLoaded() {
        when(accounts.findByNumber(SRC)).thenReturn(Optional.of(source));
        when(accounts.findByNumber(DST)).thenReturn(Optional.of(destination));

        useCase.transfer(new TransferCommand(SRC, DST, TEN));

        InOrder order = inOrder(locker, accounts);
        order.verify(locker).withPairedLocks(eq(SRC), eq(DST), any());
        order.verify(accounts).findByNumber(SRC);
        order.verify(accounts).findByNumber(DST);
    }

    @Test
    void argumentOrderIsPreservedIntoLockerWhenSourceComesFirst() {
        when(accounts.findByNumber(SRC)).thenReturn(Optional.of(source));
        when(accounts.findByNumber(DST)).thenReturn(Optional.of(destination));

        useCase.transfer(new TransferCommand(SRC, DST, TEN));

        verify(locker).withPairedLocks(eq(SRC), eq(DST), any());
    }

    @Test
    void argumentOrderIsPreservedIntoLockerWhenDestinationComesFirst() {
        when(accounts.findByNumber(DST)).thenReturn(Optional.of(destination));
        when(accounts.findByNumber(SRC)).thenReturn(Optional.of(source));

        // (destination, source) — destination is now source-of-transfer; that's
        // fine, the use case takes them as opaque names; what matters is the
        // locker sees them in caller order.
        useCase.transfer(new TransferCommand(DST, SRC, TEN));

        verify(locker).withPairedLocks(eq(DST), eq(SRC), any());
    }
}
