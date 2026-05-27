package com.bank.core.bootstrap;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SeedPlanTest {

    @Test
    public void testValidPlan() {
        SeedPlan plan = new SeedPlan();
        plan.setClearingAccountNumber("CLEARING-999");
        plan.setClearingAccountOpeningBalance(new BigDecimal("150.00"));
        plan.setCustomers(List.of(new SeedPlan.CustomerSeed("CUST-A", new BigDecimal("10.00"))));

        // Trigger validation (manually mimicking @PostConstruct lifecycle)
        plan.validate();

        assertEquals("CLEARING-999", plan.getClearingAccountNumber());
        assertEquals(new BigDecimal("150.00"), plan.getClearingAccountOpeningBalance());
        assertEquals(1, plan.getCustomers().size());
    }

    @Test
    public void testZeroClearingBalanceThrows() {
        SeedPlan plan = new SeedPlan();
        plan.setClearingAccountOpeningBalance(BigDecimal.ZERO);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, plan::validate);
        assertEquals("clearing-account opening balance must be strictly positive — seeding exists to fund customers", ex.getMessage());
    }

    @Test
    public void testNegativeClearingBalanceThrows() {
        SeedPlan plan = new SeedPlan();
        plan.setClearingAccountOpeningBalance(new BigDecimal("-100.00"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, plan::validate);
        assertEquals("clearing-account opening balance must be strictly positive — seeding exists to fund customers", ex.getMessage());
    }

    @Test
    public void testNullClearingBalanceThrows() {
        SeedPlan plan = new SeedPlan();
        plan.setClearingAccountOpeningBalance(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, plan::validate);
        assertEquals("clearing-account opening balance must be strictly positive — seeding exists to fund customers", ex.getMessage());
    }

    @Test
    public void testCustomerValidationBounds() {
        // Null number
        assertThrows(IllegalArgumentException.class, () -> new SeedPlan.CustomerSeed(null, BigDecimal.TEN));
        // Empty number
        assertThrows(IllegalArgumentException.class, () -> new SeedPlan.CustomerSeed("   ", BigDecimal.TEN));
        // Null balance
        assertThrows(IllegalArgumentException.class, () -> new SeedPlan.CustomerSeed("CUST-1", null));
        // Negative balance
        assertThrows(IllegalArgumentException.class, () -> new SeedPlan.CustomerSeed("CUST-1", new BigDecimal("-0.01")));
    }
}
