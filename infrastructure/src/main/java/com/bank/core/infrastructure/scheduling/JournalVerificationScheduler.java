package com.bank.core.infrastructure.scheduling;

import com.bank.core.application.ledger.SweepReport;
import com.bank.core.application.ledger.VerifyPendingJournals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * F10 background scheduler. Drives one {@link VerifyPendingJournals#sweep()}
 * call per tick on a fixed delay and emits exactly one INFO summary line per
 * tick.
 *
 * <h2>{@code fixedDelay}, not {@code fixedRate}</h2>
 * Per design.md Decision 1, {@code @Scheduled(fixedDelayString=...)} means
 * the next tick fires {@code fixedDelayMs} after the previous tick completes,
 * so a slow tick (e.g. processing a full 50-journal page) never overlaps
 * itself. {@code fixedRate} would amplify backlog under sustained load by
 * firing back-to-back ticks; we explicitly want bounded latency-per-tick
 * over throughput catch-up.
 *
 * <h2>No transactional boundary, no exception handling</h2>
 * Per design.md Decisions 2 and 3, this scheduler owns neither a
 * {@code @Transactional} nor a try/catch. Per-call transactions live on the
 * adapter methods invoked through {@code JournalEntries} and {@code Accounts}.
 * Per-journal exceptions are caught inside the use case. An exception that
 * escapes {@link #tick()} propagates to Spring's {@code TaskScheduler}, which
 * logs it at WARN and re-fires the next tick at the configured delay — the
 * correct surfacing for a deeper failure like a DataSource outage.
 *
 * <h2>One log line per tick</h2>
 * Even an empty tick emits the summary, so absence of the line is itself a
 * signal that the scheduler stopped.
 */
@Component
public class JournalVerificationScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(JournalVerificationScheduler.class);

    private final VerifyPendingJournals useCase;

    public JournalVerificationScheduler(VerifyPendingJournals useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase cannot be null");
    }

    @Scheduled(
            fixedDelayString = "${bank.journal-verification.fixed-delay-ms:10000}",
            initialDelayString = "${bank.journal-verification.initial-delay-ms:5000}")
    public void tick() {
        SweepReport report = useCase.sweep();
        LOG.info("journal verification tick: processed={}, verified={}, failed={}, errored={}",
                report.processed(), report.verified(), report.failed(), report.errored());
    }
}
