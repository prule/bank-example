package com.bank.core.domain;

/**
 * Thrown by {@link JournalEntry#markVerified()} and {@link JournalEntry#markFailed()}
 * when called on a journal whose status is not {@link VerificationStatus#PENDING}.
 *
 * Parallel to F01's {@code IllegalStatusTransitionException} which is account-status
 * typed. Kept as a separate exception so the {@code from}/{@code to} fields remain
 * strongly typed to {@link VerificationStatus}.
 */
public final class IllegalJournalStatusTransitionException extends DomainException {

    private final JournalEntryId journalEntryId;
    private final VerificationStatus from;
    private final VerificationStatus to;

    public IllegalJournalStatusTransitionException(JournalEntryId journalEntryId,
                                                   VerificationStatus from,
                                                   VerificationStatus to) {
        super("Journal " + journalEntryId + " cannot transition from " + from + " to " + to);
        this.journalEntryId = journalEntryId;
        this.from = from;
        this.to = to;
    }

    public JournalEntryId journalEntryId() {
        return journalEntryId;
    }

    public VerificationStatus from() {
        return from;
    }

    public VerificationStatus to() {
        return to;
    }
}
