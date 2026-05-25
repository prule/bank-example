package com.bank.core.infrastructure.scheduling;

import com.bank.core.application.audit.DriftReport;
import com.bank.core.infrastructure.audit.BalanceDriftAudit;
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
 */
@Component
public class BalanceDriftScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(BalanceDriftScheduler.class);

    private final BalanceDriftAudit audit;

    public BalanceDriftScheduler(BalanceDriftAudit audit) {
        this.audit = Objects.requireNonNull(audit, "audit cannot be null");
    }

    @Scheduled(
            fixedDelayString = "${bank.balance-drift.fixed-delay-ms:30000}",
            initialDelayString = "${bank.balance-drift.initial-delay-ms:15000}")
    public void tick() {
        DriftReport report = audit.audit();
        LOG.info("balance drift tick: floor={}, ceiling={}, inspected={}, drifted={}",
                report.floor(), report.ceiling(), report.inspected(), report.drifted());
    }
}
