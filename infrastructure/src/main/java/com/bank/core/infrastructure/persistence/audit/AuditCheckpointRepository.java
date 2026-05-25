package com.bank.core.infrastructure.persistence.audit;

import org.springframework.data.jpa.repository.JpaRepository;

interface AuditCheckpointRepository extends JpaRepository<AuditCheckpointEntity, String> {
}
