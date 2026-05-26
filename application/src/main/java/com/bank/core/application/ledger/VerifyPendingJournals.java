package com.bank.core.application.ledger;

import com.bank.core.application.account.Accounts;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.Movement;
import com.bank.core.domain.VerificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * F10 use case. One {@link #sweep()} call processes at most {@code pageSize}
 * Pending {@link JournalEntry}s and drives each to a terminal status.
 *
 * <h2>Transactional model</h2>
 * Per design.md Decision 2, this class does NOT own a transaction. Every
 * read and write happens through the {@link com.bank.core.application.ledger.JournalEntries}
 * and {@link Accounts} ports whose adapter methods are already
 * {@code @Transactional} per call. A page-wide transaction would hold a
 * Hikari connection for the full tick, starving concurrent F06 transfers;
 * a per-journal transaction would add ceremony without changing the
 * spec-relevant atomicity (the per-adapter writes already commit
 * individually).
 *
 * <h2>Per-journal resilience</h2>
 * Each per-journal block runs inside a {@code try / catch (RuntimeException)}.
 * A caught exception is logged at WARN naming the journal id, the {@code errored}
 * counter is incremented, and the loop continues with the next journal. The
 * spec's "one bad journal does not stop the tick" requirement lives here.
 * An exception that escapes this method (e.g. {@code findByStatus} itself
 * throws) is allowed to propagate so Spring's {@code @Scheduled} infrastructure
 * logs it at WARN and re-fires the next tick.
 *
 * <h2>Suspend cascade</h2>
 * On a Failed journal, every distinct {@link AccountId} from
 * {@link JournalEntry#movements()} is loaded via {@link Accounts#findById(AccountId)}.
 * Already-Suspended and already-Closed accounts are silently skipped:
 * - Suspending a SUSPENDED account would be a redundant write (the domain
 *   permits it but a re-save is wasted I/O and would muddy "what changed?"
 *   observability).
 * - Suspending a CLOSED account would throw {@code IllegalStatusTransitionException}
 *   per F01 — we treat that as the operator's prior decision and respect it.
 * AccountIds are deduplicated by encounter order via {@link LinkedHashSet};
 * a malformed entry whose movements reference the same account twice
 * suspends it once, not twice.
 *
 * <h2>Defensive empty-account skip</h2>
 * If {@code accounts.findById(...)} returns {@code Optional.empty()} the
 * cascade silently skips that movement. Production F02 foreign-key constraints
 * make this unreachable; test fixtures that hand-craft a {@code JournalEntry}
 * via {@code rehydrate(...)} can hit it.
 *
 * <h2>SweepReport invariant</h2>
 * Per design.md Decision 5 the invariant
 * {@code report.processed() == report.verified() + report.failed() + report.errored()}
 * is maintained here, not on the record. Every iteration of the outer loop
 * increments exactly one of the three counters.
 */
public final class VerifyPendingJournals {

    private static final Logger LOG = LoggerFactory.getLogger(VerifyPendingJournals.class);

    private final JournalEntries journals;
    private final Accounts accounts;
    private final int pageSize;

    public VerifyPendingJournals(JournalEntries journals, Accounts accounts, int pageSize) {
        this.journals = Objects.requireNonNull(journals, "journals cannot be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts cannot be null");
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive (was: " + pageSize + ")");
        }
        this.pageSize = pageSize;
    }

    public SweepReport sweep() {
        List<JournalEntry> page = journals.findByStatus(VerificationStatus.PENDING, pageSize);
        int verified = 0;
        int failed = 0;
        int errored = 0;
        int suspendedFromCascade = 0;
        for (JournalEntry entry : page) {
            try {
                int cascadeCount = processOne(entry);
                suspendedFromCascade += cascadeCount;
                if (entry.status() == VerificationStatus.VERIFIED) {
                    verified++;
                } else {
                    failed++;
                }
            } catch (RuntimeException ex) {
                LOG.warn("journal verification failed on id={} ({}): {}",
                        entry.id(),
                        ex.getClass().getSimpleName(),
                        ex.getMessage());
                errored++;
            }
        }
        return new SweepReport(page.size(), verified, failed, errored, suspendedFromCascade);
    }

    /** Returns the count of accounts the FAILED-cascade actually suspended (0 for a VERIFIED outcome). */
    private int processOne(JournalEntry entry) {
        boolean balanced = journals.isBalanced(entry.id());
        if (balanced) {
            entry.markVerified();
            journals.save(entry);
            return 0;
        }
        entry.markFailed();
        journals.save(entry);
        LOG.error("journal verification failed: id={} — unbalanced; suspending touched accounts", entry.id());
        return suspendTouchedAccounts(entry);
    }

    private int suspendTouchedAccounts(JournalEntry entry) {
        LinkedHashSet<AccountId> uniqueIds = new LinkedHashSet<>();
        for (Movement movement : entry.movements()) {
            uniqueIds.add(movement.accountId());
        }
        int suspended = 0;
        for (AccountId id : uniqueIds) {
            // Optional<Account>.map(...).orElse(0) keeps the missing-account
            // defensive path silent and unbilled toward the cascade count.
            suspended += accounts.findById(id)
                    .map(this::suspendIfActive)
                    .orElse(0);
        }
        return suspended;
    }

    /** Returns 1 when this call actually flipped the account to SUSPENDED, 0 otherwise. */
    private int suspendIfActive(Account account) {
        if (account.status() == AccountStatus.ACTIVE) {
            account.suspend();
            accounts.save(account);
            return 1;
        }
        return 0;
    }
}
