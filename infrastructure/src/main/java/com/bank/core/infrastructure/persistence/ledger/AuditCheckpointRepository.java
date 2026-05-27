package com.bank.core.infrastructure.persistence.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditCheckpointRepository extends JpaRepository<AuditCheckpointEntity, String> {
}
