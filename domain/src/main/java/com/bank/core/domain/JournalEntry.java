package com.bank.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class JournalEntry {
    private final JournalEntryId id;
    private final String description;
    private final Instant timestamp;
    private VerificationStatus status;
    private final List<Movement> movements;

    private JournalEntry(JournalEntryId id, String description, Instant timestamp, VerificationStatus status, List<Movement> movements) {
        this.id = Objects.requireNonNull(id, "Journal entry ID must not be null");
        this.description = Objects.requireNonNull(description, "Description must not be null");
        if (description.trim().isEmpty()) {
            throw new IllegalArgumentException("Description must not be empty");
        }
        this.timestamp = Objects.requireNonNull(timestamp, "Timestamp must not be null");
        this.status = Objects.requireNonNull(status, "Status must not be null");
        this.movements = List.copyOf(movements);
    }

    public static JournalEntry create(String description, Instant timestamp, List<Movement> movements) {
        Objects.requireNonNull(movements, "Movements list must not be null");
        if (movements.size() < 2) {
            throw new IllegalArgumentException("A balanced journal entry requires at least two movements");
        }

        Money creditSum = Money.ZERO;
        Money debitSum = Money.ZERO;
        for (Movement m : movements) {
            Objects.requireNonNull(m, "Movement in list must not be null");
            if (m.type() == MovementType.CREDIT) {
                creditSum = creditSum.plus(m.amount());
            } else {
                debitSum = debitSum.plus(m.amount());
            }
        }

        if (!creditSum.equals(debitSum)) {
            throw new UnbalancedJournalException(creditSum, debitSum);
        }

        return new JournalEntry(
                JournalEntryId.generate(),
                description,
                timestamp,
                VerificationStatus.PENDING,
                movements
        );
    }

    public static JournalEntry reconstitute(JournalEntryId id, String description, Instant timestamp, VerificationStatus status, List<Movement> movements) {
        return new JournalEntry(id, description, timestamp, status, movements);
    }

    public void markVerified() {
        if (this.status != VerificationStatus.PENDING) {
            throw new IllegalJournalStatusTransitionException(id, status, VerificationStatus.VERIFIED);
        }
        this.status = VerificationStatus.VERIFIED;
    }

    public void markFailed() {
        if (this.status != VerificationStatus.PENDING) {
            throw new IllegalJournalStatusTransitionException(id, status, VerificationStatus.FAILED);
        }
        this.status = VerificationStatus.FAILED;
    }

    public JournalEntryId getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    public List<Movement> getMovements() {
        return movements;
    }
}
