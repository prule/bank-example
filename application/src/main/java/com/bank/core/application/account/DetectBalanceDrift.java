package com.bank.core.application.account;

import com.bank.core.application.ledger.AuditCheckpoints;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain-Java use case class executing core balance drift detection and suspension.
 */
public class DetectBalanceDrift {
    private static final Logger log = LoggerFactory.getLogger(DetectBalanceDrift.class);

    public static final String AUDIT_NAME = "balance_drift";

    private final AuditCheckpoints checkpoints;
    private final JournalEntries journalEntries;
    private final Accounts accounts;

    public DetectBalanceDrift(AuditCheckpoints checkpoints, JournalEntries journalEntries, Accounts accounts) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "AuditCheckpoints must not be null");
        this.journalEntries = Objects.requireNonNull(journalEntries, "JournalEntries must not be null");
        this.accounts = Objects.requireNonNull(accounts, "Accounts must not be null");
    }

    /**
     * Core audit execution logic. Runs a single checkpoint-bounded tick.
     */
    public DriftReport audit(String clearingAccountNumber) {
        Objects.requireNonNull(clearingAccountNumber, "clearingAccountNumber must not be null");

        long floor = checkpoints.readOrZero(AUDIT_NAME);
        long ceiling = journalEntries.currentCeiling();

        if (ceiling <= floor) {
            // No-op when no new movements. Advance checkpoint still, per design.
            checkpoints.save(AUDIT_NAME, ceiling);
            return new DriftReport(floor, ceiling, 0, 0);
        }

        List<AccountId> candidates = journalEntries.distinctAccountIdsInWindow(floor, ceiling);
        int inspected = 0;
        int drifted = 0;

        for (AccountId candidateId : candidates) {
            Optional<Account> accountOpt = accounts.findById(candidateId);
            if (accountOpt.isEmpty()) {
                // Candidate missing in DB (defensive)
                continue;
            }

            Account account = accountOpt.get();

            // Rule 2: Clearing account carve-out
            if (account.getNumber().equals(clearingAccountNumber)) {
                log.info("clearing-account audit skipped: {} (per balance-drift-detection spec carve-out)", clearingAccountNumber);
                continue;
            }

            // Rule 3: Skip already Closed or Suspended
            if (account.getStatus() != AccountStatus.ACTIVE) {
                inspected++;
                continue;
            }

            // Rule 4: Compare expected with cached balance
            BigDecimal sumSigned = journalEntries.sumSignedAmountForAccount(candidateId);
            // Money rejects negative values, so we use max(0, sum) defensively as a corruption signal
            BigDecimal expectedAmount = sumSigned.max(BigDecimal.ZERO);
            Money expected = Money.of(expectedAmount);

            if (expected.equals(account.getBalance())) {
                inspected++;
            } else {
                account.suspend();
                accounts.save(account);
                log.error("balance drift detected on account {} (cached={}, expected={}); account SUSPENDED",
                        account.getNumber(), account.getBalance(), expected);
                inspected++;
                drifted++;
            }
        }

        // Advance checkpoint atomically
        checkpoints.save(AUDIT_NAME, ceiling);

        return new DriftReport(floor, ceiling, inspected, drifted);
    }
}
