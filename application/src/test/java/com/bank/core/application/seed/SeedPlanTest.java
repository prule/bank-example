package com.bank.core.application.seed;

import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.Money;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeedPlanTest {

    private static final ClearingAccountSeed CLEARING =
            new ClearingAccountSeed(AccountNumber.of("CLEARING-000"), Money.of("1000.00"));

    @Test
    void rejectsNullClearingAccount() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new SeedPlan(null, List.of()));
        assertEquals("clearingAccount cannot be null", ex.getMessage());
    }

    @Test
    void rejectsNullCustomers() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new SeedPlan(CLEARING, null));
        assertEquals("customers cannot be null", ex.getMessage());
    }

    @Test
    void emptyCustomerListIsAccepted() {
        SeedPlan plan = new SeedPlan(CLEARING, List.of());
        assertEquals(CLEARING, plan.clearingAccount());
        assertEquals(0, plan.customers().size());
    }

    @Test
    void customerListIsDefensivelyCopied() {
        List<CustomerSeed> source = new ArrayList<>();
        source.add(new CustomerSeed(AccountNumber.of("CUST-A"), Money.of("10.00")));

        SeedPlan plan = new SeedPlan(CLEARING, source);
        source.add(new CustomerSeed(AccountNumber.of("CUST-B"), Money.of("20.00")));

        assertEquals(1, plan.customers().size(),
                "post-construction mutation of the source list must not leak into the plan");
        assertEquals(AccountNumber.of("CUST-A"), plan.customers().get(0).number());
    }
}
