package com.bank.core.application.audit;

/**
 * Outcome of one {@code DetectBalanceDrift.audit()} tick. Consumed by
 * {@code BalanceDriftScheduler} for the per-tick INFO summary log line.
 *
 * <p>Invariant maintained by the producer (not enforced by this record):
 * {@code drifted <= inspected}. Both counters are incremented only inside the
 * use case's per-candidate loop; the clearing-account carve-out and the
 * "missing account" defensive skip do NOT increment {@code inspected}.
 *
 * <p>{@link #empty(long, long)} returns a no-op marker with {@code ceiling}
 * forced to {@code floor} — the no-op factory always reports a zero-width
 * window because that is the semantics callers care about (no work done).
 */
public record DriftReport(long floor, long ceiling, int inspected, int drifted) {

    public static DriftReport empty(long floor, long ceiling) {
        return new DriftReport(floor, floor, 0, 0);
    }
}
