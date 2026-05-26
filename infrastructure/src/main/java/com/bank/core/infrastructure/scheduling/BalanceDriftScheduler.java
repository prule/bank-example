package com.bank.core.infrastructure.scheduling;

import com.bank.core.application.audit.DriftReport;
import com.bank.core.infrastructure.audit.BalanceDriftAudit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * F11 startup shell. Fires on a fixed delay after the application context is
 * fully refreshed (so all JPA / @Transactional infrastructure is in place)
 * and delegates one tick to {@link BalanceDriftAudit}, emitting one INFO
 * summary log line per outcome and letting any failure abort the tick.
 *
 * <h2>{@code fixedDelayString}, not {@code fixedRateString}</h2>
 * A slow audit tick never overlaps with the next — see F10's design.md
 * Decision 1 for the same rationale.
 *
 * <h2>Injects the facade, not the use case</h2>
 * The transactional boundary lives on {@link BalanceDriftAudit} (per F11
 * design.md Decision 3). Injecting the plain-Java
 * {@code DetectBalanceDrift} here would route around the transaction and
 * break the spec's atomic-checkpoint-and-suspensions guarantee.
 *
 * <h2>No exception handling</h2>
 * Same precedent as F10's {@code JournalVerificationScheduler}: Spring's
 * {@code TaskScheduler} logs uncaught exceptions at WARN and re-fires the
 * next tick at the configured delay. A try/catch here would swallow the
 * signal an operator needs.
 *
 * <h2>Observability counters</h2>
 * Counters are registered once in the constructor and incremented from the
 * {@link DriftReport} after each tick (not inside {@code DetectBalanceDrift})
 * so the framework-free application module never sees Micrometer.
 */
@Component
public class BalanceDriftScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(BalanceDriftScheduler.class);

    private final BalanceDriftAudit audit;
    private final Counter driftDetectedCounter;
    private final Counter driftSuspendedCounter;

    public BalanceDriftScheduler(BalanceDriftAudit audit, MeterRegistry registry) {
        this.audit = Objects.requireNonNull(audit, "audit cannot be null");
        Objects.requireNonNull(registry, "registry cannot be null");
        this.driftDetectedCounter = Counter.builder("bank.balance-drift.detected")
                .description("Accounts flagged by the balance-drift detector.")
                .register(registry);
        this.driftSuspendedCounter = Counter.builder("bank.account.suspended")
                .description("Accounts suspended by a system-driven cause.")
                .tag("cause", "drift")
                .register(registry);
    }

    @Scheduled(
            fixedDelayString = "${bank.balance-drift.fixed-delay-ms:30000}",
            initialDelayString = "${bank.balance-drift.initial-delay-ms:15000}")
    public void tick() {
        DriftReport report = audit.audit();
        driftDetectedCounter.increment(report.drifted());
        driftSuspendedCounter.increment(report.drifted());
        LOG.info("balance drift tick: floor={}, ceiling={}, inspected={}, drifted={}",
                report.floor(), report.ceiling(), report.inspected(), report.drifted());
    }
}
