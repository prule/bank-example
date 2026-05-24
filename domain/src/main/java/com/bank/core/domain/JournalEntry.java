package com.bank.core.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable double-entry accounting record. The aggregate is append-only with
 * respect to its movements, description, timestamp, and id; only the
 * {@link #status()} is mutable, and only through the named
 * {@link #markVerified()} / {@link #markFailed()} transitions, which both
 * require the current status to be {@link VerificationStatus#PENDING}.
 *
 * Constructed via {@link #create(String, Instant, List)} for new journals
 * (validates balance and at-least-two-movements invariants).
 *
 * The {@link #rehydrate(JournalEntryId, String, Instant, VerificationStatus,
 * List)} entry point is intended only for use by
 * {@code com.bank.core.infrastructure.persistence.ledger.JournalEntryMapper}
 * to reconstruct an aggregate from a persisted row without re-running domain
 * validation (which the database already enforces via constraints). It is
 * {@code public} so the mapper (which lives in a separate Gradle module and
 * Java package) can reach it; application/use-case code MUST NOT call it.
 */
public final class JournalEntry {

    private final JournalEntryId id;
    private final String description;
    private final Instant timestamp;
    private final List<Movement> movements;
    private VerificationStatus status;

    private JournalEntry(JournalEntryId id,
                         String description,
                         Instant timestamp,
                         VerificationStatus status,
                         List<Movement> movements) {
        this.id = id;
        this.description = description;
        this.timestamp = timestamp;
        this.status = status;
        this.movements = Collections.unmodifiableList(new ArrayList<>(movements));
    }

    public static JournalEntry create(String description, Instant timestamp, List<Movement> movements) {
        Objects.requireNonNull(description, "description cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(movements, "movements cannot be null");
        if (movements.size() < 2) {
            throw new IllegalArgumentException("a journal entry requires at least two movements");
        }
        Money creditSum = sumOf(movements, MovementType.CREDIT);
        Money debitSum = sumOf(movements, MovementType.DEBIT);
        if (!creditSum.equals(debitSum)) {
            throw new UnbalancedJournalException(creditSum, debitSum);
        }
        return new JournalEntry(
                JournalEntryId.generate(),
                description,
                timestamp,
                VerificationStatus.PENDING,
                movements);
    }

    public static JournalEntry rehydrate(JournalEntryId id,
                                         String description,
                                         Instant timestamp,
                                         VerificationStatus status,
                                         List<Movement> movements) {
        return new JournalEntry(id, description, timestamp, status, movements);
    }

    public JournalEntryId id() {
        return id;
    }

    public String description() {
        return description;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public VerificationStatus status() {
        return status;
    }

    public List<Movement> movements() {
        return movements;
    }

    public void markVerified() {
        transitionTo(VerificationStatus.VERIFIED);
    }

    public void markFailed() {
        transitionTo(VerificationStatus.FAILED);
    }

    private void transitionTo(VerificationStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalJournalStatusTransitionException(id, status, target);
        }
        this.status = target;
    }

    private static Money sumOf(List<Movement> movements, MovementType type) {
        Money total = Money.ZERO;
        for (Movement movement : movements) {
            if (movement.type() == type) {
                total = total.add(movement.amount());
            }
        }
        return total;
    }
}
