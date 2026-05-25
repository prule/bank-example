package com.bank.core.application.audit;

/**
 * Persistent cursor port for named background audits. F11 (balance drift) is
 * the only consumer today; the named-checkpoint shape leaves room for a
 * future second audit (e.g. an idle-account scan) to pick its own name.
 *
 * <p>Plain Java by design — no Spring, no JPA. The implementation lives in
 * {@code com.bank.core.infrastructure.persistence.audit.AuditCheckpointsJpaAdapter}
 * and is intentionally NOT annotated {@code @Transactional}: it inherits the
 * transactional context from the calling {@code BalanceDriftAudit} facade so
 * that the checkpoint advance commits in the same transaction as any
 * suspensions the audit performed (spec requirement
 * "checkpoint advances atomically with suspensions").
 */
public interface AuditCheckpoints {

    /**
     * @return the persisted {@code last_movement_id} for the named audit,
     * or {@code 0L} if no row exists for that name yet.
     */
    long readOrZero(String auditName);

    /**
     * Upsert the persisted {@code last_movement_id} for the named audit.
     * Callers MUST be inside a Spring-managed transaction so the write
     * commits together with any other audit-tick writes.
     */
    void save(String auditName, long lastMovementId);
}
