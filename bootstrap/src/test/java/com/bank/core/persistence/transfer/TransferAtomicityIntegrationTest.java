package com.bank.core.persistence.transfer;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.application.transfer.TransferCommand;
import com.bank.core.application.transfer.TransferFunds;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class TransferAtomicityIntegrationTest {

    @Autowired TransferFunds transferFunds;
    @Autowired Accounts accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    @MockitoSpyBean
    JournalEntries journals;

    @BeforeEach
    void wipe() {
        jdbc.update("DELETE FROM ledger_movement");
        jdbc.update("DELETE FROM journal_entry");
        jdbc.update("DELETE FROM account");
        Mockito.reset(journals);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private Account seed(String number, String balance) {
        Account a = Account.open(AccountNumber.of(number), Money.of(balance));
        tx().executeWithoutResult(s -> accounts.save(a));
        return a;
    }

    @Test
    void happyPathProducesOneBalancedJournal() {
        Account source = seed("ATM-SRC", "100.00");
        Account destination = seed("ATM-DST", "10.00");

        tx().executeWithoutResult(s -> transferFunds.transfer(
                new TransferCommand(source.number(), destination.number(), Money.of("25.00"))));

        assertThat(jdbc.queryForObject("SELECT balance::varchar FROM account WHERE account_number = ?",
                String.class, "ATM-SRC")).isEqualTo("75.00");
        assertThat(jdbc.queryForObject("SELECT balance::varchar FROM account WHERE account_number = ?",
                String.class, "ATM-DST")).isEqualTo("35.00");

        Integer journalCount = jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Integer.class);
        assertThat(journalCount).isEqualTo(1);

        String status = jdbc.queryForObject(
                "SELECT verification_status FROM journal_entry", String.class);
        assertThat(status).isEqualTo("PENDING");

        Integer movementCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_movement", Integer.class);
        assertThat(movementCount).isEqualTo(2);

        Integer debits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_movement WHERE movement_type = 'DEBIT' AND account_id = ?",
                Integer.class, source.id().value());
        Integer credits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_movement WHERE movement_type = 'CREDIT' AND account_id = ?",
                Integer.class, destination.id().value());
        assertThat(debits).isEqualTo(1);
        assertThat(credits).isEqualTo(1);

        BigDecimal sumSignedAmount = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN movement_type = 'CREDIT' THEN amount ELSE -amount END), 0) "
                        + "FROM ledger_movement", BigDecimal.class);
        assertThat(sumSignedAmount.signum()).as("journal balances").isZero();

        String description = jdbc.queryForObject(
                "SELECT description FROM journal_entry", String.class);
        assertThat(description).isEqualTo("Transfer from ATM-SRC to ATM-DST");
    }

    @Test
    void failureMidFlightLeavesNoPartialState() {
        Account source = seed("FAIL-SRC", "100.00");
        Account destination = seed("FAIL-DST", "10.00");

        // Spy throws inside JournalEntries.save AFTER both aggregate saves
        // have executed inside the same transaction.
        doThrow(new RuntimeException("simulated journal persistence failure"))
                .when(journals).save(any(JournalEntry.class));

        assertThatThrownBy(() -> tx().executeWithoutResult(s -> transferFunds.transfer(
                new TransferCommand(source.number(), destination.number(), Money.of("25.00")))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated journal persistence failure");

        // Both balances revert via transaction rollback.
        assertThat(jdbc.queryForObject("SELECT balance::varchar FROM account WHERE account_number = ?",
                String.class, "FAIL-SRC")).isEqualTo("100.00");
        assertThat(jdbc.queryForObject("SELECT balance::varchar FROM account WHERE account_number = ?",
                String.class, "FAIL-DST")).isEqualTo("10.00");

        // No journal row, no movement rows.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_movement", Integer.class)).isZero();
    }
}
