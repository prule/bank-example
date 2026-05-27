package com.bank.core.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AccountTest {

    @Test
    public void testOpenAccountSucceeds() {
        Money initialBalance = Money.of("100.00");
        Account account = Account.open("123456789", initialBalance);

        assertNotNull(account.getId());
        assertEquals("123456789", account.getNumber());
        assertEquals(initialBalance, account.getBalance());
        assertEquals(AccountStatus.ACTIVE, account.getStatus());
    }

    @Test
    public void testCreditSucceeds() {
        Account account = Account.open("123", Money.of("100.00"));
        account.credit(Money.of("50.00"));
        assertEquals(Money.of("150.00"), account.getBalance());
    }

    @Test
    public void testDebitSucceeds() {
        Account account = Account.open("123", Money.of("100.00"));
        account.debit(Money.of("40.00"));
        assertEquals(Money.of("60.00"), account.getBalance());
    }

    @Test
    public void testDebitToZeroOrNegativeRejected() {
        Account account = Account.open("123", Money.of("100.00"));

        // Debit exactly the balance (reaches 0) -> rejected
        InsufficientFundsException ex1 = assertThrows(InsufficientFundsException.class,
                () -> account.debit(Money.of("100.00")));
        assertEquals(account.getId(), ex1.getAccountId());
        assertEquals(Money.of("100.00"), ex1.getAttemptedAmount());
        assertEquals(Money.of("100.00"), ex1.getCurrentBalance());

        // Debit more than the balance -> rejected
        InsufficientFundsException ex2 = assertThrows(InsufficientFundsException.class,
                () -> account.debit(Money.of("150.00")));
        assertEquals(Money.of("150.00"), ex2.getAttemptedAmount());
        assertEquals(Money.of("100.00"), ex2.getCurrentBalance());

        // Verify balance unchanged
        assertEquals(Money.of("100.00"), account.getBalance());
    }

    @Test
    public void testNonPositiveAmountRejected() {
        Account account = Account.open("123", Money.of("100.00"));

        assertThrows(InvalidAmountException.class, () -> account.credit(null));
        assertThrows(InvalidAmountException.class, () -> account.credit(Money.ZERO));
        assertThrows(InvalidAmountException.class, () -> account.debit(null));
        assertThrows(InvalidAmountException.class, () -> account.debit(Money.ZERO));
    }

    @Test
    public void testSuspendedAccountRejectsMutations() {
        Account account = Account.open("123", Money.of("100.00"));
        account.suspend();

        AccountInactiveException ex1 = assertThrows(AccountInactiveException.class,
                () -> account.credit(Money.of("50.00")));
        assertEquals(account.getId(), ex1.getAccountId());
        assertEquals(AccountStatus.SUSPENDED, ex1.getStatus());

        assertThrows(AccountInactiveException.class, () -> account.debit(Money.of("50.00")));

        // Verify balance unchanged
        assertEquals(Money.of("100.00"), account.getBalance());
    }

    @Test
    public void testClosedAccountRejectsMutations() {
        Account account = Account.open("123", Money.of("100.00"));
        account.close();

        AccountInactiveException ex1 = assertThrows(AccountInactiveException.class,
                () -> account.credit(Money.of("50.00")));
        assertEquals(account.getId(), ex1.getAccountId());
        assertEquals(AccountStatus.CLOSED, ex1.getStatus());

        assertThrows(AccountInactiveException.class, () -> account.debit(Money.of("50.00")));

        // Verify balance unchanged
        assertEquals(Money.of("100.00"), account.getBalance());
    }

    @Test
    public void testLifecycleTransitions() {
        Account account = Account.open("123", Money.of("100.00"));
        assertEquals(AccountStatus.ACTIVE, account.getStatus());

        // Active to Suspended
        account.suspend();
        assertEquals(AccountStatus.SUSPENDED, account.getStatus());

        // Suspended is idempotent
        account.suspend();
        assertEquals(AccountStatus.SUSPENDED, account.getStatus());

        // Suspended to Active
        account.reactivate();
        assertEquals(AccountStatus.ACTIVE, account.getStatus());

        // Active is idempotent
        account.reactivate();
        assertEquals(AccountStatus.ACTIVE, account.getStatus());

        // Active to Closed
        account.close();
        assertEquals(AccountStatus.CLOSED, account.getStatus());
    }

    @Test
    public void testClosedIsTerminal() {
        Account account = Account.open("123", Money.of("100.00"));
        account.close();

        IllegalStatusTransitionException ex1 = assertThrows(IllegalStatusTransitionException.class,
                () -> account.suspend());
        assertEquals(account.getId(), ex1.getAccountId());
        assertEquals(AccountStatus.CLOSED, ex1.getCurrentStatus());
        assertEquals(AccountStatus.SUSPENDED, ex1.getTargetStatus());

        IllegalStatusTransitionException ex2 = assertThrows(IllegalStatusTransitionException.class,
                () -> account.reactivate());
        assertEquals(AccountStatus.CLOSED, ex2.getCurrentStatus());
        assertEquals(AccountStatus.ACTIVE, ex2.getTargetStatus());

        assertEquals(AccountStatus.CLOSED, account.getStatus());
    }

    @Test
    public void testExceptionHierarchy() {
        assertTrue(new InsufficientFundsException(AccountId.generate(), Money.ZERO, Money.ZERO) instanceof DomainException);
        assertTrue(new AccountInactiveException(AccountId.generate(), AccountStatus.SUSPENDED) instanceof DomainException);
        assertTrue(new InvalidAmountException(Money.ZERO) instanceof DomainException);
        assertTrue(new IllegalStatusTransitionException(AccountId.generate(), AccountStatus.ACTIVE, AccountStatus.CLOSED) instanceof DomainException);
    }
}
