package com.bank.core.application.seed;

import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClearingAccountSeedTest {

    private static final AccountNumber CLEARING = AccountNumber.of("CLEARING-000");

    @Test
    void rejectsNullNumber() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new ClearingAccountSeed(null, Money.of("100.00")));
        assertEquals("number cannot be null", ex.getMessage());
    }

    @Test
    void rejectsNullOpeningBalance() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new ClearingAccountSeed(CLEARING, null));
        assertEquals("openingBalance cannot be null", ex.getMessage());
    }

    @Test
    void rejectsZeroOpeningBalance() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ClearingAccountSeed(CLEARING, Money.ZERO));
        assertTrue(ex.getMessage().contains("clearing-account opening balance must be strictly positive"),
                "message should explain the precondition; was: " + ex.getMessage());
    }

    @Test
    void positiveOpeningBalanceRoundTrips() {
        Money thousand = Money.of("1000.00");
        ClearingAccountSeed seed = new ClearingAccountSeed(CLEARING, thousand);
        assertEquals(CLEARING, seed.number());
        assertEquals(thousand, seed.openingBalance());
    }
}
