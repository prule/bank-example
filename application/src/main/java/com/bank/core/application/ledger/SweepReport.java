package com.bank.core.application.ledger;

/**
 * Outcome counts of a single {@link VerifyPendingJournals#sweep()} tick.
 *
 * <p>Maintains the class-level invariant
 * {@code processed == verified + failed + errored} by construction: the use
 * case is the only legitimate constructor and increments exactly one of the
 * three counters per processed journal. The invariant is asserted in the unit
 * test suite, not enforced inside this record — see design.md Decision 5: a
 * record-level check would either mask a use-case bug behind a generic
 * {@link IllegalArgumentException} or silently auto-correct the inputs.
 *
 * <p>Consumed by the {@code JournalVerificationScheduler} log line and by
 * tests asserting tick outcomes.
 */
public record SweepReport(int processed, int verified, int failed, int errored) {

    public static SweepReport empty() {
        return new SweepReport(0, 0, 0, 0);
    }
}
