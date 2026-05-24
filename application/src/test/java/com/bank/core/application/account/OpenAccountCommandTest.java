package com.bank.core.application.account;

import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAccountCommandTest {

    private static final AccountNumber NEW = AccountNumber.of("NEW-001");

    @Test
    void rejectsNullNumber() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new OpenAccountCommand(null, Money.ZERO));
        assertEquals("number cannot be null", ex.getMessage());
    }

    @Test
    void rejectsNullOpeningBalance() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new OpenAccountCommand(NEW, null));
        assertEquals("openingBalance cannot be null", ex.getMessage());
    }

    @Test
    void zeroOpeningBalanceIsAccepted() {
        OpenAccountCommand cmd = new OpenAccountCommand(NEW, Money.ZERO);
        assertEquals(NEW, cmd.number());
        assertEquals(Money.ZERO, cmd.openingBalance());
    }

    @Test
    void positiveOpeningBalanceRoundTrips() {
        Money fifty = Money.of("50.00");
        OpenAccountCommand cmd = new OpenAccountCommand(NEW, fifty);
        assertEquals(NEW, cmd.number());
        assertEquals(fifty, cmd.openingBalance());
    }
}
