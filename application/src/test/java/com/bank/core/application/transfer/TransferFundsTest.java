package com.bank.core.application.transfer;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TransferFundsTest {

    @Mock
    private Accounts accounts;

    @Mock
    private JournalEntries journalEntries;

    @Mock
    private AccountLocker locker;

    private Clock clock;
    private TransferFunds transferFunds;

    private static final String SRC_NUM = "ACC-001";
    private static final String DST_NUM = "ACC-002";
    private final Instant fixedInstant = Instant.parse("2026-05-24T10:00:00Z");

    @BeforeEach
    public void setUp() {
        clock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));
        transferFunds = new TransferFunds(accounts, journalEntries, locker, clock);
    }

    @Test
    public void testEarlySameAccountShortCircuit() {
        TransferCommand command = new TransferCommand(SRC_NUM, SRC_NUM, Money.of("25.00"));

        SameAccountTransferException ex = assertThrows(SameAccountTransferException.class, () -> {
            transferFunds.transfer(command);
        });

        assertEquals(SRC_NUM, ex.account());
        verifyNoInteractions(locker);
        verifyNoInteractions(accounts);
        verifyNoInteractions(journalEntries);
    }

    @Test
    public void testSuccessfulTransferFlow() {
        // Set up accounts
        Account srcAccount = Account.open(SRC_NUM, Money.of("100.00"));
        Account dstAccount = Account.open(DST_NUM, Money.of("50.00"));

        when(accounts.findByNumber(SRC_NUM)).thenReturn(Optional.of(srcAccount));
        when(accounts.findByNumber(DST_NUM)).thenReturn(Optional.of(dstAccount));

        // Configure locker to execute the runnable synchronously
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(2);
            action.run();
            return null;
        }).when(locker).withPairedLocks(anyString(), anyString(), any(Runnable.class));

        TransferCommand command = new TransferCommand(SRC_NUM, DST_NUM, Money.of("25.00"));

        // Run transfer
        transferFunds.transfer(command);

        // Verify balance adjustments
        assertEquals(Money.of("75.00"), srcAccount.getBalance());
        assertEquals(Money.of("75.00"), dstAccount.getBalance());

        // Verify accounts saved
        verify(accounts).save(srcAccount);
        verify(accounts).save(dstAccount);

        // Verify journal saved
        verify(journalEntries).save(argThat(journal -> {
            assertEquals("Transfer from ACC-001 to ACC-002", journal.getDescription());
            assertEquals(fixedInstant, journal.getTimestamp());
            assertEquals(VerificationStatus.PENDING, journal.getStatus());
            assertEquals(2, journal.getMovements().size());

            Movement m1 = journal.getMovements().get(0);
            assertEquals(srcAccount.getId(), m1.accountId());
            assertEquals(Money.of("25.00"), m1.amount());
            assertEquals(MovementType.DEBIT, m1.type());

            Movement m2 = journal.getMovements().get(1);
            assertEquals(dstAccount.getId(), m2.accountId());
            assertEquals(Money.of("25.00"), m2.amount());
            assertEquals(MovementType.CREDIT, m2.type());

            return true;
        }));
    }

    @Test
    public void testLockAcquiredBeforeLoadAndOrderingPreserved() {
        Account srcAccount = Account.open(SRC_NUM, Money.of("100.00"));
        Account dstAccount = Account.open(DST_NUM, Money.of("50.00"));

        when(accounts.findByNumber(SRC_NUM)).thenReturn(Optional.of(srcAccount));
        when(accounts.findByNumber(DST_NUM)).thenReturn(Optional.of(dstAccount));

        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(2);
            action.run();
            return null;
        }).when(locker).withPairedLocks(anyString(), anyString(), any(Runnable.class));

        TransferCommand command = new TransferCommand(SRC_NUM, DST_NUM, Money.of("10.00"));

        transferFunds.transfer(command);

        // Verify lock-then-load sequence using InOrder
        InOrder inOrder = inOrder(locker, accounts);
        inOrder.verify(locker).withPairedLocks(eq(SRC_NUM), eq(DST_NUM), any(Runnable.class));
        inOrder.verify(accounts).findByNumber(SRC_NUM);
        inOrder.verify(accounts).findByNumber(DST_NUM);
    }
}
