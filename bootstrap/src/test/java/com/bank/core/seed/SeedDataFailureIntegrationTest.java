package com.bank.core.seed;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bank.core.BankCoreApplication;
import com.bank.core.domain.InsufficientFundsException;
import com.bank.core.infrastructure.seed.SeedDataRunner;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * F09 failure-semantics integration test. Because {@link org.springframework.boot.ApplicationRunner}
 * failures abort {@code SpringApplication.run(...)}, this test cannot use
 * {@code @SpringBootTest}'s standard injection lifecycle. Instead it
 * programmatically builds the Spring Boot application via
 * {@link SpringApplicationBuilder}, captures the thrown exception, then opens
 * a side-channel JDBC connection against the same H2 in-memory URL
 * ({@code DB_CLOSE_DELAY=-1} keeps the DB alive for the rest of the JVM run)
 * to inspect the committed partial-seed state.
 */
class SeedDataFailureIntegrationTest {

    private static final String H2_URL =
            "jdbc:h2:mem:bankcore-failure-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    @Test
    void failureMidwayThroughCustomerOpens_logsErrorAndAborts_leavesCommittedCustomersBehind() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();

        ApplicationListener<ContextRefreshedEvent> attachAppender = event -> {
            Logger runnerLogger = (Logger) LoggerFactory.getLogger(SeedDataRunner.class);
            runnerLogger.addAppender(appender);
        };

        String[] args = new String[]{
                "--spring.datasource.url=" + H2_URL,
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.flyway.enabled=true",
                "--spring.flyway.locations=classpath:db/migration",
                "--management.endpoints.web.exposure.include=health",
                "--server.port=0",
                "--bank.transfer.lock-wait-ms=500",
                "--bank.clearing-account.number=CLEARING-000",
                "--bank.seed.enabled=true",
                "--bank.seed.clearingAccountOpeningBalance=10.00",
                "--bank.seed.customers[0].number=CUST-OK",
                "--bank.seed.customers[0].openingBalance=5.00",
                "--bank.seed.customers[1].number=CUST-FAIL",
                "--bank.seed.customers[1].openingBalance=100.00",
                "--bank.seed.customers[2].number=CUST-NEVER",
                "--bank.seed.customers[2].openingBalance=1.00"
        };

        Throwable thrown = null;
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(BankCoreApplication.class)
                .listeners(attachAppender)
                .run(args)) {
            fail("expected context startup to fail at the CUST-FAIL seed step");
        } catch (Throwable t) {
            thrown = t;
        }

        InsufficientFundsException root = walkToInsufficientFunds(thrown);
        assertThat(root)
                .as("the root cause should be InsufficientFundsException from F06 debiting the clearing account")
                .isNotNull();

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .filter(e -> e.getFormattedMessage().contains("dev seed failed"))
                .toList();
        assertThat(errors)
                .as("expected exactly one 'dev seed failed' ERROR line")
                .hasSize(1);
        assertThat(errors.get(0).getFormattedMessage()).contains("InsufficientFundsException");

        JdbcTemplate jdbc = openSideChannel();
        Integer accountRows = jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class);
        assertThat(accountRows)
                .as("clearing + CUST-OK committed; CUST-FAIL rolled back; CUST-NEVER never attempted")
                .isEqualTo(2);

        Integer journalRows = jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Integer.class);
        assertThat(journalRows)
                .as("only the CUST-OK funding journal committed")
                .isEqualTo(1);

        Integer movementRows = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_movement", Integer.class);
        assertThat(movementRows).isEqualTo(2);

        Integer clearingRow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE account_number = 'CLEARING-000' AND balance = 5.00",
                Integer.class);
        assertThat(clearingRow).isEqualTo(1);

        Integer custOk = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE account_number = 'CUST-OK' AND balance = 5.00 AND status = 'ACTIVE'",
                Integer.class);
        assertThat(custOk).isEqualTo(1);

        Integer custFail = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE account_number = 'CUST-FAIL'", Integer.class);
        assertThat(custFail).as("CUST-FAIL rolled back by F08").isZero();

        Integer custNever = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE account_number = 'CUST-NEVER'", Integer.class);
        assertThat(custNever).as("CUST-NEVER never attempted after the abort").isZero();
    }

    private JdbcTemplate openSideChannel() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(H2_URL);
        ds.setUser("sa");
        ds.setPassword("");
        return new JdbcTemplate(ds);
    }

    private static InsufficientFundsException walkToInsufficientFunds(Throwable t) {
        Throwable cursor = t;
        while (cursor != null) {
            if (cursor instanceof InsufficientFundsException ife) {
                return ife;
            }
            cursor = cursor.getCause();
        }
        return null;
    }
}
