package com.bank.core.seed;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bank.core.application.account.Accounts;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.Money;
import com.bank.core.infrastructure.seed.SeedDataRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "bank.seed.enabled=true",
        "bank.seed.clearingAccountOpeningBalance=1000.00",
        "bank.seed.customers[0].number=CUST-9001",
        "bank.seed.customers[0].openingBalance=100.00",
        "bank.seed.customers[1].number=CUST-9002",
        "bank.seed.customers[1].openingBalance=50.00",
        "bank.seed.customers[2].number=CUST-9003",
        "bank.seed.customers[2].openingBalance=0.00"
})
@ActiveProfiles("test")
class SeedDataRunnerIntegrationTest {

    private static final AccountNumber CLEARING = AccountNumber.of("CLEARING-000");
    private static final AccountNumber CUST_9001 = AccountNumber.of("CUST-9001");
    private static final AccountNumber CUST_9002 = AccountNumber.of("CUST-9002");
    private static final AccountNumber CUST_9003 = AccountNumber.of("CUST-9003");

    @Autowired Accounts accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired SeedDataRunner seedDataRunner;

    private ListAppender<ILoggingEvent> appender;
    private Logger runnerLogger;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        runnerLogger = (Logger) LoggerFactory.getLogger(SeedDataRunner.class);
        runnerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        runnerLogger.detachAppender(appender);
    }

    private int rowCount(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void afterContextStarts_databaseReflectsConfiguredPlanExactly() {
        Account clearing = accounts.findByNumber(CLEARING).orElseThrow();
        assertThat(clearing.balance()).isEqualTo(Money.of("850.00"));
        assertThat(clearing.status()).isEqualTo(AccountStatus.ACTIVE);

        Account c9001 = accounts.findByNumber(CUST_9001).orElseThrow();
        assertThat(c9001.balance()).isEqualTo(Money.of("100.00"));
        assertThat(c9001.status()).isEqualTo(AccountStatus.ACTIVE);

        Account c9002 = accounts.findByNumber(CUST_9002).orElseThrow();
        assertThat(c9002.balance()).isEqualTo(Money.of("50.00"));

        Account c9003 = accounts.findByNumber(CUST_9003).orElseThrow();
        assertThat(c9003.balance()).isEqualTo(Money.ZERO);
        assertThat(c9003.status()).isEqualTo(AccountStatus.ACTIVE);

        assertThat(rowCount("journal_entry"))
                .as("two positive-balance opens produce two journal entries; the zero-open produces none")
                .isEqualTo(2);
        assertThat(rowCount("ledger_movement")).isEqualTo(4);

        UUID clearingId = clearing.id().value();
        Integer debit100 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_movement WHERE account_id = ? AND movement_type = 'DEBIT' AND amount = 100.00",
                Integer.class, clearingId);
        assertThat(debit100).isEqualTo(1);
        Integer debit50 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_movement WHERE account_id = ? AND movement_type = 'DEBIT' AND amount = 50.00",
                Integer.class, clearingId);
        assertThat(debit50).isEqualTo(1);

        UUID c9001Id = c9001.id().value();
        Integer credit100 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_movement WHERE account_id = ? AND movement_type = 'CREDIT' AND amount = 100.00",
                Integer.class, c9001Id);
        assertThat(credit100).isEqualTo(1);
        UUID c9002Id = c9002.id().value();
        Integer credit50 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_movement WHERE account_id = ? AND movement_type = 'CREDIT' AND amount = 50.00",
                Integer.class, c9002Id);
        assertThat(credit50).isEqualTo(1);
    }

    @Test
    void secondInvocation_skipsAndProducesNoNewRows() {
        int accountsBefore = rowCount("account");
        int journalsBefore = rowCount("journal_entry");
        int movementsBefore = rowCount("ledger_movement");

        seedDataRunner.run(null);

        assertThat(rowCount("account")).isEqualTo(accountsBefore);
        assertThat(rowCount("journal_entry")).isEqualTo(journalsBefore);
        assertThat(rowCount("ledger_movement")).isEqualTo(movementsBefore);

        List<ILoggingEvent> infos = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO).toList();
        assertThat(infos)
                .as("expected exactly one INFO line for the skip")
                .hasSize(1);
        assertThat(infos.get(0).getFormattedMessage())
                .isEqualTo("dev seed skipped: clearing account already present");
    }
}
