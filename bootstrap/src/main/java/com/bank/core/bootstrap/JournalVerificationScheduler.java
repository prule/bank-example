package com.bank.core.bootstrap;

import com.bank.core.application.ledger.SweepReport;
import com.bank.core.application.ledger.VerifyPendingJournals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Scheduled component executing background periodic sweeps on pending journals.
 */
@Component
public class JournalVerificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(JournalVerificationScheduler.class);

    private final VerifyPendingJournals useCase;
    private final int pageSize;

    public JournalVerificationScheduler(
            VerifyPendingJournals useCase,
            @Value("${bank.journal-verification.page-size:50}") int pageSize) {
        this.useCase = Objects.requireNonNull(useCase, "VerifyPendingJournals use case must not be null");
        this.pageSize = pageSize;
    }

    /**
     * Periodic scheduled heartbeat verification sweep task.
     * Fires fixedDelayMs after completion of previous execution.
     */
    @Scheduled(
            fixedDelayString = "${bank.journal-verification.fixed-delay-ms:10000}",
            initialDelayString = "${bank.journal-verification.initial-delay-ms:5000}"
    )
    public void tick() {
        SweepReport report = useCase.sweep(pageSize);
        log.info("journal verification tick: processed={}, verified={}, failed={}, errored={}",
                report.processed(), report.verified(), report.failed(), report.errored());
    }
}
