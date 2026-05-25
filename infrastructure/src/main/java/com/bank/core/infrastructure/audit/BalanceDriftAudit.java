package com.bank.core.infrastructure.audit;

import com.bank.core.application.audit.DetectBalanceDrift;
import com.bank.core.application.audit.DriftReport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Transactional shell for the F11 balance-drift audit. Same role
 * {@code OpenAccountService} plays for F08: the audit has no HTTP controller
 * and the application-module use case ({@link DetectBalanceDrift}) is
 * Spring-free, so the {@code @Transactional} boundary lives on this thin
 * infrastructure facade.
 *
 * <h2>Why one transaction per tick</h2>
 * The balance-drift-detection spec scenario "Checkpoint advances atomically
 * with suspensions" requires that the {@code audit_checkpoint.last_movement_id}
 * write commits in the same transaction as every account-status suspension
 * the same tick performed. A single {@code @Transactional} wrapping the
 * use case's full execution gives that guarantee.
 *
 * <h2>One method, one delegate</h2>
 * The facade is deliberately tiny: constructor, single public method, no
 * logging, no validation, no decision logic. Splitting the orchestration
 * across multiple methods would defeat the per-tick transactional guarantee
 * by giving Spring chances to commit mid-flow.
 */
@Service
@Transactional
public class BalanceDriftAudit {

    private final DetectBalanceDrift useCase;

    public BalanceDriftAudit(DetectBalanceDrift useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase cannot be null");
    }

    public DriftReport audit() {
        return useCase.audit();
    }
}
