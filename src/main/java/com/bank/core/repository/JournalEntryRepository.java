package com.bank.core.repository;

import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.JournalStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

  /**
   * UNIFIED DATABASE-LEVEL LEDGER CHECK Evaluates a specific journal inside the DB engine without
   * loading transactions into JVM memory. Returns 1 if balanced (Debits == Credits), 0 if
   * unbalanced.
   */
  @Query(
      value =
          """
        SELECT CASE WHEN SUM(CASE WHEN type = 'CREDIT' THEN amount ELSE -amount END) = 0
                    THEN 1 ELSE 0 END
        FROM ledger_transactions
        WHERE journal_entry_id = :journalId
    """,
      nativeQuery = true)
  int isJournalBalancedInDatabase(@Param("journalId") UUID journalId);

  List<JournalEntry> findByStatus(JournalStatus journalStatus, PageRequest pageRequest);
}
