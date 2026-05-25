package com.bank.core.application.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DriftReportTest {

    @Test
    void empty_returnsZeroWidthWindowRegardlessOfSuppliedCeiling() {
        DriftReport report = DriftReport.empty(42L, 999L);
        assertEquals(42L, report.floor(),
                "the no-op factory uses the supplied floor as both floor and ceiling");
        assertEquals(42L, report.ceiling(),
                "ceiling is forced to floor so the no-op marker shows a zero-width window");
        assertEquals(0, report.inspected());
        assertEquals(0, report.drifted());
    }

    @Test
    void explicitConstruction_roundTripsAllFields() {
        DriftReport report = new DriftReport(10L, 100L, 5, 1);
        assertEquals(10L, report.floor());
        assertEquals(100L, report.ceiling());
        assertEquals(5, report.inspected());
        assertEquals(1, report.drifted());
    }
}
