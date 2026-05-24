package com.bank.core.application.transfer;

import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.InvalidAmountException;
import com.bank.core.domain.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferCommandTest {

    private static final AccountNumber A = AccountNumber.of("ACC-A");
    private static final AccountNumber B = AccountNumber.of("ACC-B");
    private static final Money TEN = Money.of("10.00");

    @Test
    void constructsWithAllFields() {
        TransferCommand cmd = new TransferCommand(A, B, TEN);
        assertSame(A, cmd.source());
        assertSame(B, cmd.destination());
        assertEquals(TEN, cmd.amount());
    }

    @Test
    void rejectsNullSource() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new TransferCommand(null, B, TEN));
        assertTrue(ex.getMessage().contains("source"), ex.getMessage());
    }

    @Test
    void rejectsNullDestination() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new TransferCommand(A, null, TEN));
        assertTrue(ex.getMessage().contains("destination"), ex.getMessage());
    }

    @Test
    void rejectsNullAmount() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new TransferCommand(A, B, null));
        assertTrue(ex.getMessage().contains("amount"), ex.getMessage());
    }

    @Test
    void rejectsZeroAmount() {
        assertThrows(InvalidAmountException.class,
                () -> new TransferCommand(A, B, Money.ZERO));
    }
}
