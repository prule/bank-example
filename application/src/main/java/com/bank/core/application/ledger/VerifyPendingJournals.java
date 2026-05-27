package com.bank.core.application.ledger;

import com.bank.core.application.account.Accounts;
import com.bank.core.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Spring-free application usecase that swept, verifies pending ledger journal entries,
 * fails unbalanced ones, and cascades failure suspensions to affected accounts.
 */
public final class VerifyPendingJournals {
    private static final Logger log = LoggerFactory.getLogger(VerifyPendingJournals.class);

    private final JournalEntries journalEntries;
    private final Accounts accounts;

    public VerifyPendingJournals(JournalEntries journalEntries, Accounts accounts) {
        this.journalEntries = Objects.requireNonNull(journalEntries, "JournalEntries port must not be null");
        this.accounts = Objects.requireNonNull(accounts, "Accounts port must not be null");
    }

    /**
     * Sweeps a single page of Pending journal entries, promoting or failing them.
     *
     * @param pageSize maximum number of Pending journals to process in this sweep page
     * @return SweepReport summarizing counts for processed, verified, failed, and errored
     */
    public SweepReport sweep(int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("Page size must be strictly positive");
        }

        List<JournalEntry> pendingList = journalEntries.findByStatus(VerificationStatus.PENDING, pageSize);
        
        int processedCount = 0;
        int verifiedCount = 0;
        int failedCount = 0;
        int erroredCount = 0;

        for (JournalEntry entry : pendingList) {
            processedCount++;
            try {
                if (journalEntries.isBalanced(entry.getId())) {
                    entry.markVerified();
                    journalEntries.save(entry);
                    verifiedCount++;
                } else {
                    log.error("Journal verification failed: unbalanced entry {} detected", entry.getId().toString());
                    entry.markFailed();
                    journalEntries.save(entry);
                    failedCount++;

                    // Cascading suspend of every touched account
                    Set<AccountId> deduplicatedAccountIds = new LinkedHashSet<>();
                    for (Movement movement : entry.getMovements()) {
                        deduplicatedAccountIds.add(movement.accountId());
                    }

                    for (AccountId accountId : deduplicatedAccountIds) {
                        accounts.findById(accountId).ifPresent(account -> {
                            if (account.getStatus() == AccountStatus.ACTIVE) {
                                log.warn("Suspending account {} due to unbalanced journal entry {}", account.getNumber(), entry.getId().toString());
                                account.suspend();
                                accounts.save(account);
                            }
                        });
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("Error verifying journal entry {}: {}", entry.getId().toString(), ex.getClass().getName(), ex);
                erroredCount++;
            }
        }

        return new SweepReport(processedCount, verifiedCount, failedCount, erroredCount);
    }
}
