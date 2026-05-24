package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

    private static final AccountNumber NUMBER = AccountNumber.of("ACC-001");

    @Test
    void newAccountStartsActive() {
        Account a = Account.open(NUMBER, Money.of("100.00"));

        assertEquals(AccountStatus.ACTIVE, a.status());
        assertEquals(Money.of("100.00"), a.balance());
        assertEquals(NUMBER, a.number());
        assertNotNull(a.id());
    }

    @Test
    void openWithZeroBalanceAllowed() {
        Account a = Account.open(NUMBER, Money.ZERO);
        assertEquals(Money.ZERO, a.balance());
    }

    @Test
    void openWithNegativeBalanceRejectedAtMoneyBoundary() {
        assertThrows(InvalidAmountException.class,
                () -> Account.open(NUMBER, Money.of("-1.00")));
    }

    @Test
    void creditIncreasesBalance() {
        Account a = Account.open(NUMBER, Money.of("100.00"));
        a.credit(Money.of("50.00"));
        assertEquals(Money.of("150.00"), a.balance());
    }

    @Test
    void debitWithinFundsSucceeds() {
        Account a = Account.open(NUMBER, Money.of("100.00"));
        a.debit(Money.of("30.00"));
        assertEquals(Money.of("70.00"), a.balance());
    }

    @Test
    void debitToZeroIsRejected() {
        Account a = Account.open(NUMBER, Money.of("100.00"));
        InsufficientFundsException ex = assertThrows(InsufficientFundsException.class,
                () -> a.debit(Money.of("100.00")));
        assertEquals(Money.of("100.00"), a.balance(), "balance must not change");
        assertEquals(a.id(), ex.accountId());
        assertEquals(Money.of("100.00"), ex.attempted());
        assertEquals(Money.of("100.00"), ex.available());
    }

    @Test
    void debitBeyondBalanceIsRejected() {
        Account a = Account.open(NUMBER, Money.of("100.00"));
        assertThrows(InsufficientFundsException.class, () -> a.debit(Money.of("100.01")));
        assertEquals(Money.of("100.00"), a.balance());
    }

    @Test
    void zeroAmountRejected() {
        Account a = Account.open(NUMBER, Money.of("100.00"));
        assertThrows(InvalidAmountException.class, () -> a.credit(Money.ZERO));
        assertThrows(InvalidAmountException.class, () -> a.debit(Money.ZERO));
        assertEquals(Money.of("100.00"), a.balance());
    }

    @Test
    void nullAmountRejected() {
        Account a = Account.open(NUMBER, Money.of("100.00"));
        assertThrows(InvalidAmountException.class, () -> a.credit(null));
        assertThrows(InvalidAmountException.class, () -> a.debit(null));
    }

    @Test
    void activeCanBeSuspendedAndReactivated() {
        Account a = Account.open(NUMBER, Money.of("100.00"));
        a.suspend();
        assertEquals(AccountStatus.SUSPENDED, a.status());
        a.reactivate();
        assertEquals(AccountStatus.ACTIVE, a.status());
    }

    @Test
    void closedIsTerminal() {
        Account a = closedAccount();
        assertThrows(IllegalStatusTransitionException.class, a::suspend);
        assertThrows(IllegalStatusTransitionException.class, a::reactivate);
        assertEquals(AccountStatus.CLOSED, a.status());
    }

    @Test
    void suspendedRejectsDebit() {
        Account a = Account.open(NUMBER, Money.of("100.00"));
        a.suspend();
        AccountInactiveException ex = assertThrows(AccountInactiveException.class,
                () -> a.debit(Money.of("10.00")));
        assertEquals(AccountStatus.SUSPENDED, ex.status());
        assertEquals(Money.of("100.00"), a.balance());
    }

    @Test
    void closedRejectsCredit() {
        Account a = closedAccount();
        AccountInactiveException ex = assertThrows(AccountInactiveException.class,
                () -> a.credit(Money.of("10.00")));
        assertEquals(AccountStatus.CLOSED, ex.status());
    }

    @Test
    void suspendIsIdempotent() {
        Account a = Account.open(NUMBER, Money.of("100.00"));
        a.suspend();
        a.suspend();
        assertEquals(AccountStatus.SUSPENDED, a.status());
    }

    @Test
    void reactivateOnActiveIsIdempotent() {
        Account a = Account.open(NUMBER, Money.of("100.00"));
        a.reactivate();
        assertEquals(AccountStatus.ACTIVE, a.status());
    }

    private Account closedAccount() {
        Account a = Account.open(NUMBER, Money.of("100.00"));
        // No public path to Closed exists yet (deliberate per spec); use reflection
        // here only for test setup — F08 will introduce the close flow.
        try {
            java.lang.reflect.Field statusField = Account.class.getDeclaredField("status");
            statusField.setAccessible(true);
            statusField.set(a, AccountStatus.CLOSED);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return a;
    }
}
