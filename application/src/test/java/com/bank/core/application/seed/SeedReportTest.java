package com.bank.core.application.seed;

import com.bank.core.domain.AccountNumber;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class SeedReportTest {

    private static final AccountNumber CLEARING = AccountNumber.of("CLEARING-000");
    private static final AccountNumber CUST_A = AccountNumber.of("CUST-A");

    @Test
    void seededRecord_rejectsNullFields() {
        assertEquals("clearingAccountNumber cannot be null",
                assertThrows(NullPointerException.class,
                        () -> new SeedReport.Seeded(null, List.of(CUST_A))).getMessage());
        assertEquals("customerAccountNumbers cannot be null",
                assertThrows(NullPointerException.class,
                        () -> new SeedReport.Seeded(CLEARING, null)).getMessage());
    }

    @Test
    void seededRecord_defensivelyCopiesCustomerList() {
        List<AccountNumber> source = new ArrayList<>();
        source.add(CUST_A);

        SeedReport.Seeded report = new SeedReport.Seeded(CLEARING, source);
        source.add(AccountNumber.of("CUST-B"));

        assertEquals(1, report.customerAccountNumbers().size());
        assertEquals(CUST_A, report.customerAccountNumbers().get(0));
    }

    @Test
    void skippedRecord_rejectsNullReason() {
        assertEquals("reason cannot be null",
                assertThrows(NullPointerException.class,
                        () -> new SeedReport.Skipped(null)).getMessage());
    }

    @Test
    void seededBranch_isReachableViaPatternMatch() {
        SeedReport report = new SeedReport.Seeded(CLEARING, List.of(CUST_A));

        String result = switch (report) {
            case SeedReport.Seeded seeded -> "seeded:" + seeded.customerAccountNumbers().size();
            case SeedReport.Skipped skipped -> fail("expected Seeded, got Skipped: " + skipped.reason());
        };
        assertEquals("seeded:1", result);
    }

    @Test
    void skippedBranch_isReachableViaPatternMatch() {
        SeedReport report = new SeedReport.Skipped("clearing account already present");

        String result = switch (report) {
            case SeedReport.Seeded seeded -> fail("expected Skipped, got Seeded for " + seeded.clearingAccountNumber());
            case SeedReport.Skipped skipped -> "skipped:" + skipped.reason();
        };
        assertEquals("skipped:clearing account already present", result);
    }
}
