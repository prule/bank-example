package com.bank.core.infrastructure.persistence.account;

import com.bank.core.domain.AccountStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account")
public class AccountEntity {
    @Id
    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "id", nullable = false, unique = true)
    private String id; // AccountId UUID string

    @Column(name = "balance", nullable = false)
    private BigDecimal balance;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountEntity() {}

    public AccountEntity(String accountNumber, String id, BigDecimal balance, AccountStatus status, Instant createdAt) {
        this.accountNumber = accountNumber;
        this.id = id;
        this.balance = balance;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getAccountNumber() { return accountNumber; }
    public String getId() { return id; }
    public BigDecimal getBalance() { return balance; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
