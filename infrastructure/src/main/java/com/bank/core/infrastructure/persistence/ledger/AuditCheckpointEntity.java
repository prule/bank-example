package com.bank.core.infrastructure.persistence.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_checkpoint")
public class AuditCheckpointEntity {

    @Id
    @Column(name = "audit_name", length = 64)
    private String auditName;

    @Column(name = "last_movement_id", nullable = false)
    private long lastMovementId;

    protected AuditCheckpointEntity() {}

    public AuditCheckpointEntity(String auditName, long lastMovementId) {
        this.auditName = auditName;
        this.lastMovementId = lastMovementId;
    }

    public String getAuditName() {
        return auditName;
    }

    public long getLastMovementId() {
        return lastMovementId;
    }

    public void setLastMovementId(long lastMovementId) {
        this.lastMovementId = lastMovementId;
    }
}
