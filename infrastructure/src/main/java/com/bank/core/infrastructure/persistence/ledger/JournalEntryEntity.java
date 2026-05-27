package com.bank.core.infrastructure.persistence.ledger;

import com.bank.core.domain.VerificationStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "journal_entry")
public class JournalEntryEntity {
    @Id
    @Column(name = "id", nullable = false, length = 100)
    private String id;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant timestamp;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private VerificationStatus status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private List<LedgerMovementEntity> movements = new ArrayList<>();

    protected JournalEntryEntity() {}

    public JournalEntryEntity(String id, String description, Instant timestamp, VerificationStatus status, List<LedgerMovementEntity> movements) {
        this.id = id;
        this.description = description;
        this.timestamp = timestamp;
        this.status = status;
        this.movements.addAll(movements);
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public Instant getTimestamp() { return timestamp; }
    public VerificationStatus getStatus() { return status; }
    public List<LedgerMovementEntity> getMovements() { return movements; }

    public void setStatus(VerificationStatus status) {
        this.status = status;
    }
}
