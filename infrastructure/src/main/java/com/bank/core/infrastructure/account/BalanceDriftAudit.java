package com.bank.core.infrastructure.account;

import com.bank.core.application.account.DetectBalanceDrift;
import com.bank.core.application.account.DriftReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Spring-managed transaction facade exposing a transactional boundary for the plain-Java use case.
 */
@Service
public class BalanceDriftAudit {

    private final DetectBalanceDrift useCase;
    private final String clearingAccountNumber;

    public BalanceDriftAudit(
            DetectBalanceDrift useCase,
            @Value("${bank.clearing-account.number:CLEARING-000}") String clearingAccountNumber) {
        this.useCase = Objects.requireNonNull(useCase, "DetectBalanceDrift use case must not be null");
        this.clearingAccountNumber = Objects.requireNonNull(clearingAccountNumber, "clearingAccountNumber must not be null");
    }

    /**
     * Executes the balance drift audit within a single transactional boundary.
     */
    @Transactional
    public DriftReport audit() {
        return useCase.audit(clearingAccountNumber);
    }
}
