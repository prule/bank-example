package com.bank.core.application.ledger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SweepReportTest {

    @Test
    void emptyReturnsAllZeroes() {
        SweepReport empty = SweepReport.empty();
        assertEquals(0, empty.processed());
        assertEquals(0, empty.verified());
        assertEquals(0, empty.failed());
        assertEquals(0, empty.errored());
        assertEquals(0, empty.suspendedFromCascade());
    }

    @Test
    void explicitConstructionRoundTripsAllFields() {
        SweepReport r = new SweepReport(5, 3, 1, 1, 2);
        assertEquals(5, r.processed());
        assertEquals(3, r.verified());
        assertEquals(1, r.failed());
        assertEquals(1, r.errored());
        assertEquals(2, r.suspendedFromCascade());
    }

    @Test
    void recordDoesNotEnforceInvariant_useCaseIsResponsible() {
        // Decision 5: the invariant `processed == verified + failed + errored` is maintained
        // by VerifyPendingJournals, not by this record. A misaligned construction is permitted
        // so that a use-case bug surfaces as a failed test rather than a generic IAE.
        SweepReport mismatched = new SweepReport(99, 0, 0, 0, 0);
        assertEquals(99, mismatched.processed());
    }
}
