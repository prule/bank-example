package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DomainExceptionsTest {

    private static final AccountId ID = AccountId.generate();

    @Test
    void allExtendDomainExceptionAndRuntimeException() {
        DomainException[] all = {
                new InsufficientFundsException(ID, Money.of("10"), Money.of("5")),
                new AccountInactiveException(ID, AccountStatus.SUSPENDED),
                new InvalidAmountException("zero amount"),
                new IllegalStatusTransitionException(ID, AccountStatus.CLOSED, AccountStatus.ACTIVE)
        };
        for (DomainException ex : all) {
            assertInstanceOf(RuntimeException.class, ex);
            assertNotNull(ex.getMessage());
            assertFalse(ex.getMessage().isBlank());
        }
    }

    @Test
    void insufficientFundsCarriesContext() {
        InsufficientFundsException ex = new InsufficientFundsException(
                ID, Money.of("100"), Money.of("50"));
        assertEquals(ID, ex.accountId());
        assertEquals(Money.of("100"), ex.attempted());
        assertEquals(Money.of("50"), ex.available());
        assertTrue(ex.getMessage().contains(ID.toString()));
        assertTrue(ex.getMessage().contains("100"));
        assertTrue(ex.getMessage().contains("50"));
    }

    @Test
    void accountInactiveCarriesContext() {
        AccountInactiveException ex = new AccountInactiveException(ID, AccountStatus.CLOSED);
        assertEquals(ID, ex.accountId());
        assertEquals(AccountStatus.CLOSED, ex.status());
        assertTrue(ex.getMessage().contains("CLOSED"));
    }

    @Test
    void invalidAmountCarriesReason() {
        InvalidAmountException ex = new InvalidAmountException("amount must be positive");
        assertEquals("amount must be positive", ex.reason());
        assertEquals("amount must be positive", ex.getMessage());
    }

    @Test
    void illegalStatusTransitionCarriesFromAndTo() {
        IllegalStatusTransitionException ex = new IllegalStatusTransitionException(
                ID, AccountStatus.CLOSED, AccountStatus.ACTIVE);
        assertEquals(AccountStatus.CLOSED, ex.from());
        assertEquals(AccountStatus.ACTIVE, ex.to());
        assertTrue(ex.getMessage().contains("CLOSED"));
        assertTrue(ex.getMessage().contains("ACTIVE"));
    }
}
