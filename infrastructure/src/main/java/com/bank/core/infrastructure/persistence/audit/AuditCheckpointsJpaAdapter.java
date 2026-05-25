package com.bank.core.infrastructure.persistence.audit;

import com.bank.core.application.audit.AuditCheckpoints;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Adapter implementing the {@link AuditCheckpoints} application port over
 * {@code audit_checkpoint} (V4 Flyway migration).
 *
 * <h2>Intentionally not {@code @Transactional}</h2>
 * Both methods inherit the calling transaction from
 * {@code com.bank.core.infrastructure.audit.BalanceDriftAudit} (the
 * {@code @Service @Transactional} facade owned by F11). If this adapter
 * declared its own boundary, the checkpoint write would commit independently
 * of the account suspensions in the same tick — breaking the
 * balance-drift-detection spec scenario "Checkpoint advances atomically with
 * suspensions".
 *
 * <p>The {@code save(...)} call is an upsert by primary key
 * ({@code audit_name}): JPA's {@code save(entity)} performs an INSERT on a
 * new key and a managed-entity MERGE on an existing one.
 */
@Component
class AuditCheckpointsJpaAdapter implements AuditCheckpoints {

    private final AuditCheckpointRepository repository;

    AuditCheckpointsJpaAdapter(AuditCheckpointRepository repository) {
        this.repository = repository;
    }

    @Override
    public long readOrZero(String auditName) {
        return repository.findById(auditName)
                .map(AuditCheckpointEntity::getLastMovementId)
                .orElse(0L);
    }

    @Override
    public void save(String auditName, long lastMovementId) {
        Optional<AuditCheckpointEntity> existing = repository.findById(auditName);
        if (existing.isPresent()) {
            existing.get().setLastMovementId(lastMovementId);
        } else {
            repository.save(new AuditCheckpointEntity(auditName, lastMovementId));
        }
    }
}
