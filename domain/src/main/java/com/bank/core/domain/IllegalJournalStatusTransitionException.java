package com.bank.core.domain;

public final class IllegalJournalStatusTransitionException extends DomainException {
    private final JournalEntryId journalId;
    private final VerificationStatus currentStatus;
    private final VerificationStatus targetStatus;

    public IllegalJournalStatusTransitionException(JournalEntryId journalId, VerificationStatus currentStatus, VerificationStatus targetStatus) {
        super(String.format("Illegal status transition for journal %s: %s -> %s", journalId, currentStatus, targetStatus));
        this.journalId = journalId;
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public JournalEntryId getJournalId() {
        return journalId;
    }

    public VerificationStatus getCurrentStatus() {
        return currentStatus;
    }

    public VerificationStatus getTargetStatus() {
        return targetStatus;
    }
}
