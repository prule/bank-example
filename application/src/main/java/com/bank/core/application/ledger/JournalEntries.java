package com.bank.core.application.ledger;

import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.JournalEntryId;
import com.bank.core.domain.VerificationStatus;

import java.util.List;
import java.util.Optional;

public interface JournalEntries {
    void save(JournalEntry journalEntry);
    Optional<JournalEntry> findById(JournalEntryId id);
    List<JournalEntry> findByStatus(VerificationStatus status, int limit);
    boolean isBalanced(JournalEntryId id);
    long currentCeiling();
    List<com.bank.core.domain.AccountId> distinctAccountIdsInWindow(long floor, long ceiling);
    java.math.BigDecimal sumSignedAmountForAccount(com.bank.core.domain.AccountId id);
}
