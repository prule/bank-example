package com.bank.core.infrastructure.persistence.ledger;

import com.bank.core.domain.MovementType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ledger_movement")
public class LedgerMovementEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "account_number", nullable = false, length = 50)
    private String accountNumber;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "type", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private MovementType type;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerMovementEntity() {}

    public LedgerMovementEntity(String accountNumber, BigDecimal amount, MovementType type, Instant createdAt) {
        this.accountNumber = accountNumber;
        this.amount = amount;
        this.type = type;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public BigDecimal getAmount() { return amount; }
    public MovementType getType() { return type; }
    public Instant getCreatedAt() { return createdAt; }
}
