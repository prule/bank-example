package com.bank.core.infrastructure.persistence.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_checkpoint")
class AuditCheckpointEntity {

    @Id
    @Column(name = "audit_name", nullable = false, length = 64)
    private String auditName;

    @Column(name = "last_movement_id", nullable = false)
    private long lastMovementId;

    AuditCheckpointEntity() {
        // JPA
    }

    AuditCheckpointEntity(String auditName, long lastMovementId) {
        this.auditName = auditName;
        this.lastMovementId = lastMovementId;
    }

    String getAuditName() {
        return auditName;
    }

    long getLastMovementId() {
        return lastMovementId;
    }

    void setLastMovementId(long lastMovementId) {
        this.lastMovementId = lastMovementId;
    }
}
