package com.bank.core.infrastructure.persistence.ledger;

import com.bank.core.application.audit.LedgerMovements;
import com.bank.core.domain.AccountId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adapter implementing the {@link LedgerMovements} application port for
 * F11's balance-drift audit. All three methods are read-only aggregate
 * queries against {@code ledger_movement} (the F02 append-only table) and
 * use the existing {@code idx_ledger_movement_account_id} index plus the
 * primary-key index on {@code id}.
 *
 * <p>{@link #distinctAccountIdsInWindow(long, long)} preserves the database
 * order returned by the JPA query (the query carries {@code ORDER BY
 * accountId}) by collecting into a {@link LinkedHashSet}, so the use case's
 * iteration order is deterministic for testing.
 */
@Component
class LedgerMovementsJpaAdapter implements LedgerMovements {

    private final LedgerMovementRepository repository;

    LedgerMovementsJpaAdapter(LedgerMovementRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public long currentCeiling() {
        return repository.currentCeiling();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<AccountId> distinctAccountIdsInWindow(long floorExclusive, long ceilingInclusive) {
        return repository.distinctAccountIdsInWindow(floorExclusive, ceilingInclusive).stream()
                .map(AccountId::of)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal sumSignedAmountForAccount(AccountId id) {
        return repository.sumSignedAmountForAccount(id.value());
    }
}
