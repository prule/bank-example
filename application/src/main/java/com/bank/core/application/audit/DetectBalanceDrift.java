package com.bank.core.application.audit;

import com.bank.core.application.account.Accounts;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the F11 balance-drift audit: for every account that had a
 * ledger movement since the last tick, recompute the canonical balance as the
 * signed sum of its movements and compare it against the cached
 * {@code account.balance}; Suspend any Active account whose two values
 * disagree (except the clearing account, which is exempt by spec).
 *
 * <h2>Transactional boundary lives on the facade</h2>
 * This class does not own its transaction. Per F02's
 * {@code transactional-in-application} precedent the application module is
 * Spring-free; the {@code @Transactional} sits on
 * {@code com.bank.core.infrastructure.audit.BalanceDriftAudit}. That boundary
 * wraps the entire {@link #audit()} call so the checkpoint advance and every
 * suspension commit together or roll back together — spec scenario
 * "Checkpoint advances atomically with suspensions".
 *
 * <h2>Sum is across all time, not just the window</h2>
 * The cached {@code account.balance} represents the total of every movement
 * ever for that account. To detect drift we compare like-with-like, so
 * {@link LedgerMovements#sumSignedAmountForAccount(AccountId)} is intentionally
 * unwindowed. The {@code (floor, ceiling]} window only governs which accounts
 * are CANDIDATES for audit this tick — F11 audits only accounts that saw a
 * movement since the last tick, not every account in the database.
 *
 * <h2>Clearing-account carve-out</h2>
 * If a candidate's account number equals {@code clearingAccountNumber}, the
 * audit logs INFO and skips it. The skip happens BEFORE
 * {@code inspected++} so the clearing account is treated as outside the
 * audit's normal flow — suspending it would block every F08 open and F09
 * seed, halting customer onboarding entirely.
 *
 * <h2>No per-candidate try/catch</h2>
 * Unlike F10's per-journal resilience, F11 does NOT wrap per-candidate work
 * in try/catch. Any exception propagates out of {@link #audit()} and the
 * surrounding {@code @Transactional} rolls everything back — including the
 * checkpoint advance — so the next tick starts at the same floor and retries
 * the same candidates. See design.md Decision 8 for the asymmetry rationale.
 *
 * <h2>Carve-out and missing accounts do not count toward inspected</h2>
 * The {@code inspected} counter reflects "non-clearing accounts the audit
 * actually compared". Carved-out clearing-account candidates and accounts
 * for which {@link Accounts#findById(AccountId)} returns empty (defensive —
 * impossible in production) do NOT increment {@code inspected}.
 */
public final class DetectBalanceDrift {

    public static final String AUDIT_NAME = "balance_drift";

    private static final Logger LOG = LoggerFactory.getLogger(DetectBalanceDrift.class);

    private final LedgerMovements movements;
    private final Accounts accounts;
    private final AuditCheckpoints checkpoints;
    private final AccountNumber clearingAccountNumber;

    public DetectBalanceDrift(LedgerMovements movements,
                              Accounts accounts,
                              AuditCheckpoints checkpoints,
                              AccountNumber clearingAccountNumber) {
        this.movements = Objects.requireNonNull(movements, "movements cannot be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts cannot be null");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints cannot be null");
        this.clearingAccountNumber = Objects.requireNonNull(clearingAccountNumber, "clearingAccountNumber cannot be null");
    }

    public DriftReport audit() {
        long floor = checkpoints.readOrZero(AUDIT_NAME);
        long ceiling = movements.currentCeiling();

        if (ceiling <= floor) {
            checkpoints.save(AUDIT_NAME, ceiling);
            return DriftReport.empty(floor, ceiling);
        }

        Set<AccountId> candidates = movements.distinctAccountIdsInWindow(floor, ceiling);

        int inspected = 0;
        int drifted = 0;
        for (AccountId id : candidates) {
            Optional<Account> maybe = accounts.findById(id);
            if (maybe.isEmpty()) {
                continue; // defensive — FK constraint makes this unreachable in production
            }
            Account account = maybe.get();

            if (account.number().equals(clearingAccountNumber)) {
                LOG.info("clearing-account audit skipped: {} (per balance-drift-detection spec carve-out)",
                        clearingAccountNumber.value());
                continue; // carve-out — does NOT count toward inspected
            }

            if (account.status() != AccountStatus.ACTIVE) {
                inspected++;
                continue;
            }

            BigDecimal raw = movements.sumSignedAmountForAccount(id);
            BigDecimal nonNegative = raw.signum() < 0 ? BigDecimal.ZERO : raw;
            Money expected = Money.of(nonNegative);

            if (expected.equals(account.balance())) {
                inspected++;
                continue;
            }

            LOG.error("balance drift detected on account {} (cached={}, expected={}); account SUSPENDED",
                    account.number().value(), account.balance(), expected);
            account.suspend();
            accounts.save(account);
            inspected++;
            drifted++;
        }

        checkpoints.save(AUDIT_NAME, ceiling);
        return new DriftReport(floor, ceiling, inspected, drifted);
    }
}
