package com.bank.core.infrastructure.persistence.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

interface LedgerMovementRepository extends JpaRepository<LedgerMovementEntity, Long> {

    @Query("SELECT COALESCE(MAX(m.id), 0) FROM LedgerMovementEntity m")
    long currentCeiling();

    @Query("""
           SELECT DISTINCT m.accountId FROM LedgerMovementEntity m
           WHERE m.id > :floor AND m.id <= :ceiling
           ORDER BY m.accountId
           """)
    List<UUID> distinctAccountIdsInWindow(@Param("floor") long floor,
                                          @Param("ceiling") long ceiling);

    @Query("""
           SELECT COALESCE(SUM(CASE WHEN m.movementType = com.bank.core.domain.MovementType.CREDIT
                                    THEN m.amount ELSE -m.amount END), 0)
           FROM LedgerMovementEntity m
           WHERE m.accountId = :accountId
           """)
    BigDecimal sumSignedAmountForAccount(@Param("accountId") UUID accountId);
}
