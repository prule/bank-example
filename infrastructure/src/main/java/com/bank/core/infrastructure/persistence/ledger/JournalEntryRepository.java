package com.bank.core.infrastructure.persistence.ledger;

import com.bank.core.domain.VerificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

interface JournalEntryRepository extends JpaRepository<JournalEntryEntity, UUID> {

    @Query("""
           SELECT j FROM JournalEntryEntity j
           WHERE j.verificationStatus = :status
           ORDER BY j.timestamp ASC, j.id ASC
           """)
    List<JournalEntryEntity> findByStatusOrdered(@Param("status") VerificationStatus status,
                                                 Pageable pageable);

    /**
     * Single-aggregate count used by the {@code bank.journal.pending}
     * Micrometer gauge. Spring Data derives the query from the method name
     * — no @Query needed.
     */
    long countByVerificationStatus(VerificationStatus status);

    @Query("""
           SELECT COALESCE(SUM(CASE WHEN m.movementType = com.bank.core.domain.MovementType.CREDIT
                                    THEN m.amount ELSE -m.amount END), 0)
           FROM LedgerMovementEntity m
           WHERE m.journalEntryId = :journalId
           """)
    BigDecimal sumSignedAmount(@Param("journalId") UUID journalId);
}
