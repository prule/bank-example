package com.bank.core.infrastructure.scheduling;

import com.bank.core.application.ledger.SweepReport;
import com.bank.core.application.ledger.VerifyPendingJournals;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link JournalVerificationScheduler}'s observability path.
 * Plain JUnit + Mockito + {@link SimpleMeterRegistry}; no Spring context.
 *
 * Drives one or more ticks against a mocked {@link VerifyPendingJournals},
 * asserting the verified / failed / cascade counters all increment by the
 * exact amounts present in the returned {@link SweepReport}.
 */
class JournalVerificationSchedulerMetricsTest {

    private SimpleMeterRegistry registry;
    private VerifyPendingJournals useCase;
    private JournalVerificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        useCase = mock(VerifyPendingJournals.class);
        scheduler = new JournalVerificationScheduler(useCase, registry);
    }

    @Test
    void singleTickIncrementsAllThreeCountersFromTheReport() {
        when(useCase.sweep()).thenReturn(new SweepReport(7, 5, 2, 0, 3));

        scheduler.tick();

        assertEquals(5.0, verifiedCount());
        assertEquals(2.0, failedCount());
        assertEquals(3.0, journalFailureSuspendedCount());
    }

    @Test
    void emptyTickDoesNotMoveCounters() {
        when(useCase.sweep()).thenReturn(SweepReport.empty());

        scheduler.tick();

        assertEquals(0.0, verifiedCount());
        assertEquals(0.0, failedCount());
        assertEquals(0.0, journalFailureSuspendedCount());
    }

    @Test
    void countersAreCumulativeAcrossTicks() {
        when(useCase.sweep())
                .thenReturn(new SweepReport(3, 3, 0, 0, 0))
                .thenReturn(new SweepReport(2, 0, 2, 0, 4))
                .thenReturn(new SweepReport(1, 1, 0, 0, 0));

        scheduler.tick();
        scheduler.tick();
        scheduler.tick();

        assertEquals(4.0, verifiedCount());                 // 3 + 0 + 1
        assertEquals(2.0, failedCount());                   // 0 + 2 + 0
        assertEquals(4.0, journalFailureSuspendedCount());  // 0 + 4 + 0
    }

    private double verifiedCount() {
        Counter c = registry.find("bank.journal.verification").tag("outcome", "verified").counter();
        return c == null ? -1.0 : c.count();
    }

    private double failedCount() {
        Counter c = registry.find("bank.journal.verification").tag("outcome", "failed").counter();
        return c == null ? -1.0 : c.count();
    }

    private double journalFailureSuspendedCount() {
        Counter c = registry.find("bank.account.suspended").tag("cause", "journal_failure").counter();
        return c == null ? -1.0 : c.count();
    }
}
