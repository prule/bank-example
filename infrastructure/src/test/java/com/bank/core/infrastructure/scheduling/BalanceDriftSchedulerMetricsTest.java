package com.bank.core.infrastructure.scheduling;

import com.bank.core.application.audit.DriftReport;
import com.bank.core.infrastructure.audit.BalanceDriftAudit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link BalanceDriftScheduler}'s observability path.
 * Plain JUnit + Mockito + {@link SimpleMeterRegistry}; no Spring context.
 *
 * <p>{@code bank.balance-drift.detected} and {@code bank.account.suspended{cause=drift}}
 * both move by the same amount per tick (one drift detection → one suspension).
 */
class BalanceDriftSchedulerMetricsTest {

    private SimpleMeterRegistry registry;
    private BalanceDriftAudit audit;
    private BalanceDriftScheduler scheduler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        audit = mock(BalanceDriftAudit.class);
        scheduler = new BalanceDriftScheduler(audit, registry);
    }

    @Test
    void driftedAccountsIncrementBothCounters() {
        when(audit.audit()).thenReturn(new DriftReport(0L, 100L, 10, 2));

        scheduler.tick();

        assertEquals(2.0, driftDetectedCount());
        assertEquals(2.0, driftSuspendedCount());
    }

    @Test
    void noDriftedAccountsLeavesCountersAtZero() {
        when(audit.audit()).thenReturn(DriftReport.empty(0L, 0L));

        scheduler.tick();

        assertEquals(0.0, driftDetectedCount());
        assertEquals(0.0, driftSuspendedCount());
    }

    @Test
    void countersAreCumulativeAcrossTicks() {
        when(audit.audit())
                .thenReturn(new DriftReport(0L, 10L, 5, 1))
                .thenReturn(new DriftReport(10L, 20L, 5, 0))
                .thenReturn(new DriftReport(20L, 30L, 5, 3));

        scheduler.tick();
        scheduler.tick();
        scheduler.tick();

        assertEquals(4.0, driftDetectedCount());   // 1 + 0 + 3
        assertEquals(4.0, driftSuspendedCount());
    }

    private double driftDetectedCount() {
        Counter c = registry.find("bank.balance-drift.detected").counter();
        return c == null ? -1.0 : c.count();
    }

    private double driftSuspendedCount() {
        Counter c = registry.find("bank.account.suspended").tag("cause", "drift").counter();
        return c == null ? -1.0 : c.count();
    }
}
