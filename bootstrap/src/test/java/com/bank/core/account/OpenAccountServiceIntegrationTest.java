package com.bank.core.account;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.account.OpenAccountCommand;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountInactiveException;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.ClearingAccountMissingException;
import com.bank.core.domain.DuplicateAccountNumberException;
import com.bank.core.domain.Money;
import com.bank.core.infrastructure.account.OpenAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class OpenAccountServiceIntegrationTest {

    private static final AccountNumber CLEARING = AccountNumber.of("CLEARING-000");
    private static final AccountNumber NEW = AccountNumber.of("NEW-001");

    @Autowired OpenAccountService openAccountService;
    @Autowired Accounts accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach
    void wipe() {
        jdbc.update("DELETE FROM ledger_movement");
        jdbc.update("DELETE FROM journal_entry");
        jdbc.update("DELETE FROM account");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private void seedClearing(String balance) {
        Account a = Account.open(CLEARING, Money.of(balance));
        tx().executeWithoutResult(s -> accounts.save(a));
    }

    private void seedSuspendedClearing(String balance) {
        Account a = Account.open(CLEARING, Money.of(balance));
        a.suspend();
        tx().executeWithoutResult(s -> accounts.save(a));
    }

    private int countAccounts() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class);
        return n == null ? 0 : n;
    }

    private int countJournals() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Integer.class);
        return n == null ? 0 : n;
    }

    private int countMovements() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_movement", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void zeroOpen_createsActiveAccountAtZero_noJournalEntry() {
        int accountsBefore = countAccounts();
        int journalsBefore = countJournals();

        Account result = openAccountService.open(new OpenAccountCommand(NEW, Money.ZERO));

        assertThat(countAccounts()).isEqualTo(accountsBefore + 1);
        assertThat(countJournals()).isEqualTo(journalsBefore);
        assertThat(result.number()).isEqualTo(NEW);
        assertThat(result.balance()).isEqualTo(Money.ZERO);
        assertThat(result.status()).isEqualTo(AccountStatus.ACTIVE);

        Optional<Account> loaded = accounts.findByNumber(NEW);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().balance()).isEqualTo(Money.ZERO);
        assertThat(loaded.get().status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void positiveOpen_createsAccount_fundsViaSingleJournalEntry() {
        seedClearing("1000.00");
        int accountsBefore = countAccounts();
        int journalsBefore = countJournals();
        int movementsBefore = countMovements();

        Account result = openAccountService.open(
                new OpenAccountCommand(NEW, Money.of(new BigDecimal("75.00"))));

        assertThat(countAccounts()).isEqualTo(accountsBefore + 1);
        assertThat(countJournals()).isEqualTo(journalsBefore + 1);
        assertThat(countMovements()).isEqualTo(movementsBefore + 2);

        assertThat(result.balance()).isEqualTo(Money.of("75.00"));
        assertThat(result.status()).isEqualTo(AccountStatus.ACTIVE);

        Account clearingAfter = accounts.findByNumber(CLEARING).orElseThrow();
        assertThat(clearingAfter.balance()).isEqualTo(Money.of("925.00"));
        assertThat(clearingAfter.status()).isEqualTo(AccountStatus.ACTIVE);

        Account newAfter = accounts.findByNumber(NEW).orElseThrow();
        UUID clearingId = clearingAfter.id().value();
        UUID newId = newAfter.id().value();

        Integer debitRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_movement WHERE account_id = ? AND movement_type = 'DEBIT' AND amount = 75.00",
                Integer.class, clearingId);
        assertThat(debitRows).isEqualTo(1);

        Integer creditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_movement WHERE account_id = ? AND movement_type = 'CREDIT' AND amount = 75.00",
                Integer.class, newId);
        assertThat(creditRows).isEqualTo(1);
    }

    @Test
    void positiveOpen_suspendedClearing_rollsBackEntireOperation() {
        seedSuspendedClearing("1000.00");
        int accountsBefore = countAccounts();
        int journalsBefore = countJournals();
        int movementsBefore = countMovements();
        Money clearingBalanceBefore = accounts.findByNumber(CLEARING).orElseThrow().balance();

        assertThatThrownBy(() -> openAccountService.open(
                new OpenAccountCommand(NEW, Money.of(new BigDecimal("50.00")))))
                .isInstanceOf(AccountInactiveException.class);

        assertThat(countAccounts()).isEqualTo(accountsBefore);
        assertThat(countJournals()).isEqualTo(journalsBefore);
        assertThat(countMovements()).isEqualTo(movementsBefore);
        assertThat(accounts.findByNumber(NEW)).isEmpty();

        Account clearingAfter = accounts.findByNumber(CLEARING).orElseThrow();
        assertThat(clearingAfter.balance()).isEqualTo(clearingBalanceBefore);
        assertThat(clearingAfter.status()).isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    void duplicateAccountNumber_throwsDuplicateException_noSideEffects() {
        seedClearing("1000.00");
        AccountNumber existing = AccountNumber.of("EXISTS-001");
        tx().executeWithoutResult(s -> accounts.save(Account.open(existing, Money.of("200.00"))));

        int accountsBefore = countAccounts();
        int journalsBefore = countJournals();
        Money clearingBefore = accounts.findByNumber(CLEARING).orElseThrow().balance();
        Money existingBefore = accounts.findByNumber(existing).orElseThrow().balance();

        assertThatThrownBy(() -> openAccountService.open(
                new OpenAccountCommand(existing, Money.of(new BigDecimal("10.00")))))
                .isInstanceOf(DuplicateAccountNumberException.class)
                .extracting(ex -> ((DuplicateAccountNumberException) ex).number())
                .isEqualTo(existing);

        assertThat(countAccounts()).isEqualTo(accountsBefore);
        assertThat(countJournals()).isEqualTo(journalsBefore);
        assertThat(accounts.findByNumber(CLEARING).orElseThrow().balance()).isEqualTo(clearingBefore);
        assertThat(accounts.findByNumber(existing).orElseThrow().balance()).isEqualTo(existingBefore);
    }

    @Test
    void missingClearingAccount_positiveOpen_throwsClearingMissingException_noSideEffects() {
        int accountsBefore = countAccounts();
        int journalsBefore = countJournals();

        assertThatThrownBy(() -> openAccountService.open(
                new OpenAccountCommand(NEW, Money.of(new BigDecimal("50.00")))))
                .isInstanceOf(ClearingAccountMissingException.class)
                .extracting(ex -> ((ClearingAccountMissingException) ex).clearingAccountNumber())
                .isEqualTo(CLEARING);

        assertThat(countAccounts()).isEqualTo(accountsBefore);
        assertThat(countJournals()).isEqualTo(journalsBefore);
        assertThat(accounts.findByNumber(NEW)).isEmpty();
    }

    @Test
    void missingClearingAccount_zeroOpen_isAllowed() {
        int accountsBefore = countAccounts();
        int journalsBefore = countJournals();

        Account result = openAccountService.open(
                new OpenAccountCommand(AccountNumber.of("NEW-002"), Money.ZERO));

        assertThat(countAccounts()).isEqualTo(accountsBefore + 1);
        assertThat(countJournals()).isEqualTo(journalsBefore);
        assertThat(result.balance()).isEqualTo(Money.ZERO);
        assertThat(result.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void transactionalAnnotationPresentOnFacade() {
        assertThat(OpenAccountService.class.isAnnotationPresent(Transactional.class))
                .as("OpenAccountService must carry @Transactional so the entire "
                        + "create+fund operation rolls back atomically on any failure")
                .isTrue();
    }
}
