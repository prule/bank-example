package com.bank.core.infrastructure.persistence.account;

import com.bank.core.domain.AccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "account")
class AccountEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, updatable = false, length = 64)
    private String accountNumber;

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status;

    AccountEntity() {
        // JPA
    }

    AccountEntity(UUID id, String accountNumber, BigDecimal balance, AccountStatus status) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.balance = balance;
        this.status = status;
    }

    UUID getId() {
        return id;
    }

    String getAccountNumber() {
        return accountNumber;
    }

    BigDecimal getBalance() {
        return balance;
    }

    AccountStatus getStatus() {
        return status;
    }

    void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    void setStatus(AccountStatus status) {
        this.status = status;
    }
}
