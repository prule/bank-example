package com.bank.core.application.seed;

import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomerSeedTest {

    private static final AccountNumber CUST = AccountNumber.of("CUST-1001");

    @Test
    void rejectsNullNumber() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new CustomerSeed(null, Money.ZERO));
        assertEquals("number cannot be null", ex.getMessage());
    }

    @Test
    void rejectsNullOpeningBalance() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new CustomerSeed(CUST, null));
        assertEquals("openingBalance cannot be null", ex.getMessage());
    }

    @Test
    void zeroOpeningBalanceIsAccepted() {
        CustomerSeed seed = new CustomerSeed(CUST, Money.ZERO);
        assertEquals(CUST, seed.number());
        assertEquals(Money.ZERO, seed.openingBalance());
    }

    @Test
    void positiveOpeningBalanceRoundTrips() {
        Money fifty = Money.of("50.00");
        CustomerSeed seed = new CustomerSeed(CUST, fifty);
        assertEquals(CUST, seed.number());
        assertEquals(fifty, seed.openingBalance());
    }
}
