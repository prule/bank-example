package com.bank.core.persistence.account;

import com.bank.core.application.account.Accounts;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AccountsJpaAdapterTest {

    @Autowired
    Accounts accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    @BeforeEach
    void wipe() {
        jdbc.update("DELETE FROM account");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    @Test
    void saveThenFindByNumberRoundTripsActive() {
        Account opened = Account.open(AccountNumber.of("ACC-001"), Money.of("100.00"));
        tx().executeWithoutResult(s -> accounts.save(opened));

        Optional<Account> loaded = accounts.findByNumber(AccountNumber.of("ACC-001"));
        assertThat(loaded).isPresent();
        Account got = loaded.get();
        assertThat(got.id()).isEqualTo(opened.id());
        assertThat(got.number()).isEqualTo(AccountNumber.of("ACC-001"));
        assertThat(got.balance()).isEqualTo(Money.of("100.00"));
        assertThat(got.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void roundTripsSuspendedStatus() {
        Account a = Account.open(AccountNumber.of("ACC-S"), Money.of("50.00"));
        a.suspend();
        tx().executeWithoutResult(s -> accounts.save(a));

        Optional<Account> loaded = accounts.findByNumber(AccountNumber.of("ACC-S"));
        assertThat(loaded).isPresent();
        assertThat(loaded.get().status()).isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    void roundTripsClosedStatus() {
        Account a = Account.open(AccountNumber.of("ACC-C"), Money.of("0.01"));
        a.suspend();
        // Status forced to CLOSED via rehydrate path because the domain's
        // mutators only support ACTIVE ⇄ SUSPENDED; CLOSED is reachable only
        // via persistence rehydration (or future explicit close() in a later
        // capability). For this test we persist via rehydrate then re-load.
        Account closed = Account.rehydrate(a.id(), a.number(), a.balance(), AccountStatus.CLOSED);
        tx().executeWithoutResult(s -> accounts.save(closed));

        Optional<Account> loaded = accounts.findByNumber(AccountNumber.of("ACC-C"));
        assertThat(loaded).isPresent();
        assertThat(loaded.get().status()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    void findByMissingNumberReturnsEmpty() {
        Optional<Account> loaded = accounts.findByNumber(AccountNumber.of("DOES-NOT-EXIST"));
        assertThat(loaded).isEmpty();
    }

    @Test
    void duplicateAccountNumberRejected() {
        Account first = Account.open(AccountNumber.of("DUP-001"), Money.of("10.00"));
        Account second = Account.open(AccountNumber.of("DUP-001"), Money.of("20.00"));

        tx().executeWithoutResult(s -> accounts.save(first));

        assertThatThrownBy(() -> tx().executeWithoutResult(s -> accounts.save(second)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void saveExistingAggregateUpdatesInPlace() {
        Account a = Account.open(AccountNumber.of("UPD-001"), Money.of("100.00"));
        tx().executeWithoutResult(s -> accounts.save(a));

        a.credit(Money.of("50.00"));
        a.suspend();
        tx().executeWithoutResult(s -> accounts.save(a));

        Optional<Account> loaded = accounts.findByNumber(AccountNumber.of("UPD-001"));
        assertThat(loaded).isPresent();
        assertThat(loaded.get().balance()).isEqualTo(Money.of("150.00"));
        assertThat(loaded.get().status()).isEqualTo(AccountStatus.SUSPENDED);

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE account_number = ?",
                Integer.class, "UPD-001");
        assertThat(rowCount).isEqualTo(1);
    }
}
