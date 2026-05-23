package com.bank.core.repository;

import com.bank.core.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

  Optional<Account> findByAccountNumber(String accountNumber);

  // Explicit Pessimistic Lock for high-contention operations like transfers
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM Account a WHERE a.accountNumber = :accountNumber")
  Optional<Account> findByAccountNumberForUpdate(@Param("accountNumber") String accountNumber);

  @Query("SELECT MAX(lt.id) FROM LedgerTransaction lt")
  java.util.Optional<Long> findMaxTransactionId();

  /**
   * BOUNDED RANGE SEQUENCE-DRIVEN AUDIT QUERY Locks the upper processing bound to eliminate the
   * race condition window entirely.
   */
  @Query(
      value =
"""
    SELECT a.* FROM accounts a
    JOIN (
        -- Step 1: Calculate history ONLY for accounts touched within our explicit ID window slice
        SELECT lt.account_id,
               SUM(CASE WHEN lt.type = 'CREDIT' THEN lt.amount ELSE -lt.amount END) AS calculated_sum
        FROM ledger_transactions lt
        WHERE lt.account_id IN (
            SELECT DISTINCT inner_lt.account_id
            FROM ledger_transactions inner_lt
            WHERE inner_lt.id > :lastProcessedTxId
              AND inner_lt.id <= :maxTxId
        )
        GROUP BY lt.account_id
    ) audit_trail ON a.id = audit_trail.account_id
    WHERE a.status = 'ACTIVE' AND a.balance <> audit_trail.calculated_sum
""",
      nativeQuery = true)
  List<Account> findAccountsWithBalanceDriftInSegment(
      @Param("lastProcessedTxId") Long lastProcessedTxId, @Param("maxTxId") Long maxTxId);
}
