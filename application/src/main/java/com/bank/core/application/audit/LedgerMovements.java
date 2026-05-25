package com.bank.core.application.audit;

import com.bank.core.domain.AccountId;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Movement-shaped read port consumed by the F11 balance-drift audit. Separate
 * from {@code JournalEntries} because the audit's queries are about
 * {@code ledger_movement} rows (the F11 unit) rather than {@code journal_entry}
 * rows (the F10 unit). Bundling these methods on {@code JournalEntries} would
 * awkwardly mix journal-shaped and movement-shaped operations on one interface.
 *
 * <p>Plain Java by design: no Spring annotations, no JPA types, no
 * openapi-generated imports. The implementation lives in
 * {@code com.bank.core.infrastructure.persistence.ledger.LedgerMovementsJpaAdapter}.
 *
 * <p>Sole consumer today: {@code DetectBalanceDrift}.
 */
public interface LedgerMovements {

    /**
     * @return {@code COALESCE(MAX(ledger_movement.id), 0)}. F11's audit cursor
     * captures this value once per tick to bound the window of new movements.
     * Returns {@code 0L} for an empty ledger.
     */
    long currentCeiling();

    /**
     * @return the distinct set of {@link AccountId}s touched by any
     * {@code ledger_movement} row whose id is strictly greater than
     * {@code floorExclusive} and less than or equal to {@code ceilingInclusive}.
     * The order of the returned set is preserved as the adapter receives it
     * (typically database row order); callers MAY rely on iteration order for
     * deterministic test fixtures.
     */
    Set<AccountId> distinctAccountIdsInWindow(long floorExclusive, long ceilingInclusive);

    /**
     * @return the sum of every movement ever recorded for the given account,
     * with CREDIT contributing positively and DEBIT negatively. F11's audit
     * compares this to the cached {@code account.balance} column to detect
     * drift. Returns zero for an account with no movements.
     */
    BigDecimal sumSignedAmountForAccount(AccountId id);
}
