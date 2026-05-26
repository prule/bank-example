package com.bank.core.infrastructure.observability;

import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.domain.VerificationStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Registers the {@code bank.journal.pending} Micrometer gauge whose supplier
 * delegates to {@link JournalEntries#countByStatus(VerificationStatus)} on
 * every scrape. Gauges in Micrometer are pull-based — the supplier is invoked
 * by the Prometheus scrape (default cadence 15s), not by a scheduler.
 *
 * <p>Lives in {@code infrastructure/observability/} so the framework-free
 * application module never sees Micrometer (the {@code JournalEntries} port
 * itself is application-layer; this class merely captures it in a closure).
 *
 * <p>Trade-off, documented in design.md Decision 3: every scrape issues a
 * cheap {@code COUNT(*) WHERE verification_status = 'PENDING'}. If profiling
 * shows this hot, replace the gauge with a push-on-tick mechanism that
 * publishes from {@code JournalVerificationScheduler}.
 */
@Component
public class JournalPendingGauge {

    private final JournalEntries journals;
    private final MeterRegistry registry;

    public JournalPendingGauge(JournalEntries journals, MeterRegistry registry) {
        this.journals = Objects.requireNonNull(journals, "journals cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
    }

    @PostConstruct
    void register() {
        Gauge.builder("bank.journal.pending",
                        journals,
                        j -> (double) j.countByStatus(VerificationStatus.PENDING))
                .description("Current count of journal entries in PENDING status.")
                .register(registry);
    }
}
