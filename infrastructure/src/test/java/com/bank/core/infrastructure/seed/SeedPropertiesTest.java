package com.bank.core.infrastructure.seed;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class SeedPropertiesTest {

    @Test
    void nullClearingBalanceDefaultsTo100000() {
        SeedProperties props = new SeedProperties(false, null, null, null);
        assertEquals(new BigDecimal("100000.00"), props.clearingAccountOpeningBalance());
    }

    @Test
    void nullCustomersDefaultsToEmptyList() {
        SeedProperties props = new SeedProperties(false, null, null, null);
        assertEquals(List.of(), props.customers());
    }

    @Test
    void nullClearingAccountNumberIsPreserved() {
        SeedProperties props = new SeedProperties(false, null, null, null);
        assertNull(props.clearingAccountNumber(),
                "null clearing-account number is the documented sentinel for "
                        + "falling back to bank.clearing-account.number at plan construction");
    }

    @Test
    void enabledDefaultsToConstructorArgument() {
        SeedProperties off = new SeedProperties(false, null, null, null);
        assertFalse(off.enabled());
    }

    @Test
    void explicitValuesRoundTripUnchanged() {
        BigDecimal clearingBalance = new BigDecimal("500.00");
        List<SeedProperties.CustomerSeedProperty> customers = List.of(
                new SeedProperties.CustomerSeedProperty("CUST-A", new BigDecimal("10.00")),
                new SeedProperties.CustomerSeedProperty("CUST-B", new BigDecimal("20.00")));

        SeedProperties props = new SeedProperties(true, "MY-CLEARING", clearingBalance, customers);

        assertEquals(true, props.enabled());
        assertEquals("MY-CLEARING", props.clearingAccountNumber());
        assertEquals(clearingBalance, props.clearingAccountOpeningBalance());
        assertEquals(customers, props.customers());
    }
}
