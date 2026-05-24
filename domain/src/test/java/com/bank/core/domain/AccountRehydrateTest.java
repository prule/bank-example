package com.bank.core.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountRehydrateTest {

    private static final AccountId ID = AccountId.generate();
    private static final AccountNumber NUMBER = AccountNumber.of("ACC-001");
    private static final Money BALANCE = Money.of("123.45");

    @ParameterizedTest
    @EnumSource(AccountStatus.class)
    void roundTripsEachStatus(AccountStatus status) {
        Account account = Account.rehydrate(ID, NUMBER, BALANCE, status);

        assertSame(ID, account.id());
        assertSame(NUMBER, account.number());
        assertEquals(BALANCE, account.balance());
        assertSame(status, account.status());
    }

    @Test
    void rejectsNullId() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> Account.rehydrate(null, NUMBER, BALANCE, AccountStatus.ACTIVE));
        assertTrue(ex.getMessage().contains("id"), ex.getMessage());
    }

    @Test
    void rejectsNullNumber() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> Account.rehydrate(ID, null, BALANCE, AccountStatus.ACTIVE));
        assertTrue(ex.getMessage().contains("number"), ex.getMessage());
    }

    @Test
    void rejectsNullBalance() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> Account.rehydrate(ID, NUMBER, null, AccountStatus.ACTIVE));
        assertTrue(ex.getMessage().contains("balance"), ex.getMessage());
    }

    @Test
    void rejectsNullStatus() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> Account.rehydrate(ID, NUMBER, BALANCE, null));
        assertTrue(ex.getMessage().contains("status"), ex.getMessage());
    }

    @Test
    void doesNotForceActiveOnSuspendedRehydrate() {
        Account account = Account.rehydrate(ID, NUMBER, BALANCE, AccountStatus.SUSPENDED);
        assertSame(AccountStatus.SUSPENDED, account.status());
    }

    @Test
    void doesNotMintFreshId() {
        Account account = Account.rehydrate(ID, NUMBER, BALANCE, AccountStatus.ACTIVE);
        assertSame(ID, account.id());
    }
}
