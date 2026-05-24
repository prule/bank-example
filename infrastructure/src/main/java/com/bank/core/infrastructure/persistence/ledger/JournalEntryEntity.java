package com.bank.core.infrastructure.persistence.ledger;

import com.bank.core.domain.VerificationStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "journal_entry")
class JournalEntryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "entry_timestamp", nullable = false)
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 16)
    private VerificationStatus verificationStatus;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = false)
    @JoinColumn(name = "journal_entry_id", nullable = false, updatable = false)
    @OrderColumn(name = "movement_order")
    private List<LedgerMovementEntity> movements = new ArrayList<>();

    JournalEntryEntity() {
        // JPA
    }

    JournalEntryEntity(UUID id,
                       String description,
                       Instant timestamp,
                       VerificationStatus verificationStatus) {
        this.id = id;
        this.description = description;
        this.timestamp = timestamp;
        this.verificationStatus = verificationStatus;
    }

    UUID getId() {
        return id;
    }

    String getDescription() {
        return description;
    }

    Instant getTimestamp() {
        return timestamp;
    }

    VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    void setVerificationStatus(VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    List<LedgerMovementEntity> getMovements() {
        return movements;
    }

    void addMovement(LedgerMovementEntity movement) {
        movements.add(movement);
    }
}
