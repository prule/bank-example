package com.bank.core.application.ledger;

/**
 * Port interface for managing persistent, named sequence checkpoints over audited events.
 */
public interface AuditCheckpoints {

    /**
     * Reads the last processed movement ID checkpoint, or 0 if it does not exist yet.
     */
    long readOrZero(String auditName);

    /**
     * Saves or updates the named audit checkpoint offset.
     */
    void save(String auditName, long lastMovementId);
}
