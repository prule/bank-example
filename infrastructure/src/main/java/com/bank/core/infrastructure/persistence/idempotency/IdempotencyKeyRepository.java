package com.bank.core.infrastructure.persistence.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, String> {
}
