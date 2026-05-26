package com.bank.core.application.ledger;

import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.JournalEntryId;
import com.bank.core.domain.VerificationStatus;

import java.util.List;
import java.util.Optional;

/**
 * Port for persisting and querying immutable double-entry journal entries.
 *
 * Plain Java by design: no Spring annotations, no JPA types, no openapi-generated
 * imports. F00's ArchUnit boundary rule pins the application module to this
 * shape. Implementations live in the infrastructure module.
 *
 * Downstream consumers:
 * <ul>
 *   <li>F06 (fund transfer) calls {@link #save(JournalEntry)} after debiting
 *       the source and crediting the destination in the same transaction.</li>
 *   <li>F08 (account opening) calls {@link #save(JournalEntry)} for the
 *       funding entry that moves the opening balance from the clearing
 *       account.</li>
 *   <li>F10 (journal verification) calls {@link #findByStatus(VerificationStatus, int)}
 *       on a schedule, then {@link #isBalanced(JournalEntryId)} per entry,
 *       then mutates the loaded aggregate and re-saves.</li>
 *   <li>F11 (balance drift detection) may add per-account aggregate query
 *       methods alongside this port.</li>
 * </ul>
 */
public interface JournalEntries {

    void save(JournalEntry entry);

    Optional<JournalEntry> findById(JournalEntryId id);

    List<JournalEntry> findByStatus(VerificationStatus status, int limit);

    /**
     * Count of journal entries currently in the given status. Read-only;
     * implemented by adapters as a single {@code COUNT(*)} query. Consumed
     * by the {@code bank.journal.pending} Micrometer gauge (registered in
     * the infrastructure module against a closure capturing this port).
     */
    long countByStatus(VerificationStatus status);

    boolean isBalanced(JournalEntryId id);
}
