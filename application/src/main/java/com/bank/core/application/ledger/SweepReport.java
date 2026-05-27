package com.bank.core.application.ledger;

/**
 * SweepReport represents the outcomes of a journal verification sweep page.
 */
public record SweepReport(int processed, int verified, int failed, int errored) {
    public SweepReport {
        if (processed != verified + failed + errored) {
            throw new IllegalArgumentException("processed must equal verified + failed + errored");
        }
    }
}
