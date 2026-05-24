package com.bank.core.infrastructure.persistence.ledger;

import com.bank.core.domain.MovementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "ledger_movement")
class LedgerMovementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    // FK column managed by JournalEntryEntity's @OneToMany @JoinColumn — see that
    // class for the relationship side. The column is exposed here as a plain field
    // so the sumSignedAmount JPQL query can filter on it without navigating an
    // association.
    @Column(name = "journal_entry_id", nullable = false, updatable = false, insertable = false)
    private UUID journalEntryId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, updatable = false, length = 8)
    private MovementType movementType;

    LedgerMovementEntity() {
        // JPA
    }

    LedgerMovementEntity(UUID accountId, BigDecimal amount, MovementType movementType) {
        this.accountId = accountId;
        this.amount = amount;
        this.movementType = movementType;
    }

    Long getId() {
        return id;
    }

    UUID getJournalEntryId() {
        return journalEntryId;
    }

    UUID getAccountId() {
        return accountId;
    }

    BigDecimal getAmount() {
        return amount;
    }

    MovementType getMovementType() {
        return movementType;
    }
}
