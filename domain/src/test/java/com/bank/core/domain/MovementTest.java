package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MovementTest {

    private static final AccountId ACC = AccountId.generate();

    @Test
    void nullAccountIdRejected() {
        assertThrows(NullPointerException.class,
                () -> new Movement(null, Money.of("10.00"), MovementType.CREDIT));
    }

    @Test
    void nullAmountRejected() {
        assertThrows(NullPointerException.class,
                () -> new Movement(ACC, null, MovementType.CREDIT));
    }

    @Test
    void nullTypeRejected() {
        assertThrows(NullPointerException.class,
                () -> new Movement(ACC, Money.of("10.00"), null));
    }

    @Test
    void zeroAmountRejected() {
        InvalidAmountException ex = assertThrows(InvalidAmountException.class,
                () -> new Movement(ACC, Money.ZERO, MovementType.CREDIT));
        assertTrue(ex.getMessage().contains("positive"));
    }

    @Test
    void validMovementRoundTrips() {
        Movement m = new Movement(ACC, Money.of("10.00"), MovementType.CREDIT);
        assertEquals(ACC, m.accountId());
        assertEquals(Money.of("10.00"), m.amount());
        assertEquals(MovementType.CREDIT, m.type());
    }

    @Test
    void equalityOnAllFields() {
        Movement a = new Movement(ACC, Money.of("10.00"), MovementType.CREDIT);
        Movement b = new Movement(ACC, Money.of("10.00"), MovementType.CREDIT);
        Movement c = new Movement(ACC, Money.of("10.00"), MovementType.DEBIT);
        assertEquals(a, b);
        assertNotEquals(a, c);
    }
}
