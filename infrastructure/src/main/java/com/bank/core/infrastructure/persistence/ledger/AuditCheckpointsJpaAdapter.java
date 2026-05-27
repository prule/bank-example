package com.bank.core.infrastructure.persistence.ledger;

import com.bank.core.application.ledger.AuditCheckpoints;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AuditCheckpointsJpaAdapter implements AuditCheckpoints {

    private final AuditCheckpointRepository repository;

    public AuditCheckpointsJpaAdapter(AuditCheckpointRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public long readOrZero(String auditName) {
        return repository.findById(auditName)
                .map(AuditCheckpointEntity::getLastMovementId)
                .orElse(0L);
    }

    @Override
    @Transactional
    public void save(String auditName, long lastMovementId) {
        AuditCheckpointEntity entity = repository.findById(auditName)
                .map(existing -> {
                    existing.setLastMovementId(lastMovementId);
                    return existing;
                })
                .orElseGet(() -> new AuditCheckpointEntity(auditName, lastMovementId));
        repository.save(entity);
    }
}
