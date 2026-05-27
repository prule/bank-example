package com.bank.core.bootstrap;

import com.bank.core.application.account.DriftReport;
import com.bank.core.infrastructure.account.BalanceDriftAudit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Scheduled component executing periodic background balance drift audit sweeps.
 */
@Component
public class BalanceDriftScheduler {
    private static final Logger log = LoggerFactory.getLogger(BalanceDriftScheduler.class);

    private final BalanceDriftAudit balanceDriftAudit;

    public BalanceDriftScheduler(BalanceDriftAudit balanceDriftAudit) {
        this.balanceDriftAudit = Objects.requireNonNull(balanceDriftAudit, "BalanceDriftAudit service must not be null");
    }

    /**
     * Periodic scheduled balance drift audit task.
     * Runs continuously on a fixed delay between executions.
     */
    @Scheduled(
            fixedDelayString = "${bank.balance-drift.fixed-delay-ms:30000}",
            initialDelayString = "${bank.balance-drift.initial-delay-ms:15000}"
    )
    public void tick() {
        DriftReport report = balanceDriftAudit.audit();
        log.info("balance drift tick: floor={}, ceiling={}, inspected={}, drifted={}",
                report.floor(), report.ceiling(), report.inspected(), report.drifted());
    }
}
