package com.bank.core.infrastructure.persistence.ledger;

import com.bank.core.domain.VerificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface JournalEntryRepository extends JpaRepository<JournalEntryEntity, String> {
    List<JournalEntryEntity> findByStatusOrderByTimestampAscIdAsc(VerificationStatus status, Pageable pageable);

    @Query(value = "SELECT COALESCE(SUM(CASE WHEN type = 'CREDIT' THEN amount ELSE -amount END), 0) FROM ledger_movement WHERE journal_entry_id = :id", nativeQuery = true)
    BigDecimal calculateBalanceDifference(@Param("id") String id);
}
