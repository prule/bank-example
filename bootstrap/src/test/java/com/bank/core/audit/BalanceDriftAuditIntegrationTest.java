package com.bank.core.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bank.core.application.account.Accounts;
import com.bank.core.application.audit.AuditCheckpoints;
import com.bank.core.application.audit.DetectBalanceDrift;
import com.bank.core.application.transfer.TransferCommand;
import com.bank.core.application.transfer.TransferFunds;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.Money;
import com.bank.core.infrastructure.scheduling.BalanceDriftScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "bank.balance-drift.initial-delay-ms=0",
        "bank.balance-drift.fixed-delay-ms=200",
        // Per-class unique H2 URL so prior tests' rows from the JVM-shared
        // bankcore-test DB cannot interfere with the audit's checkpoint and
        // drift assertions here.
        "spring.datasource.url=jdbc:h2:mem:bankcore-bd-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class BalanceDriftAuditIntegrationTest {

    private static final AccountNumber CLEARING = AccountNumber.of("CLEARING-000");

    @Autowired Accounts accounts;
    @Autowired AuditCheckpoints checkpoints;
    @Autowired TransferFunds transferFunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired BalanceDriftScheduler scheduler;

    private ListAppender<ILoggingEvent> useCaseAppender;
    private ListAppender<ILoggingEvent> schedulerAppender;
    private Logger useCaseLogger;
    private Logger schedulerLogger;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_checkpoint");
        jdbc.update("DELETE FROM ledger_movement");
        jdbc.update("DELETE FROM journal_entry");
        jdbc.update("DELETE FROM account");

        useCaseAppender = new ListAppender<>();
        useCaseAppender.start();
        useCaseLogger = (Logger) LoggerFactory.getLogger(DetectBalanceDrift.class);
        useCaseLogger.addAppender(useCaseAppender);

        schedulerAppender = new ListAppender<>();
        schedulerAppender.start();
        schedulerLogger = (Logger) LoggerFactory.getLogger(BalanceDriftScheduler.class);
        schedulerLogger.addAppender(schedulerAppender);
    }

    @AfterEach
    void tearDown() {
        useCaseLogger.detachAppender(useCaseAppender);
        schedulerLogger.detachAppender(schedulerAppender);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    /**
     * Materialise the clearing account directly with a non-zero cached balance.
     * F11 exempts the clearing account from the audit, so the lack of a
     * matching ledger entry for its initial balance is OK.
     */
    private Account seedClearing(String balance) {
        Account a = Account.open(AccountNumber.of("CLEARING-000"), Money.of(balance));
        tx().executeWithoutResult(s -> accounts.save(a));
        return a;
    }

    /**
     * Seed a customer account at zero. Cached balance and ledger sum agree
     * (both are zero), so the audit sees no drift unless a test deliberately
     * fabricates one.
     */
    private Account seedCustomerAtZero(String number) {
        Account a = Account.open(AccountNumber.of(number), Money.ZERO);
        tx().executeWithoutResult(s -> accounts.save(a));
        return a;
    }

    private void commitTransfer(String fromNumber, String toNumber, String amount) {
        // TransferFunds.transfer() requires a Spring-managed transaction (production
        // boundary lives on TransferController per F02's transactional-in-application
        // precedent); wrap via TransactionTemplate when calling from a test.
        tx().executeWithoutResult(s -> transferFunds.transfer(new TransferCommand(
                AccountNumber.of(fromNumber),
                AccountNumber.of(toNumber),
                Money.of(amount))));
    }

    @Test
    void inBalanceTransfer_advancesCheckpoint_noDrift() {
        seedClearing("1000.00");
        seedCustomerAtZero("CUST-A");
        seedCustomerAtZero("CUST-B");
        // Fund CUST-A via a real transfer from clearing, then move 25 to CUST-B.
        commitTransfer("CLEARING-000", "CUST-A", "100.00");
        commitTransfer("CUST-A", "CUST-B", "25.00");

        await().atMost(5, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> checkpoints.readOrZero(DetectBalanceDrift.AUDIT_NAME) > 0L);

        assertThat(accounts.findByNumber(AccountNumber.of("CUST-A")).orElseThrow().status())
                .isEqualTo(AccountStatus.ACTIVE);
        assertThat(accounts.findByNumber(AccountNumber.of("CUST-B")).orElseThrow().status())
                .isEqualTo(AccountStatus.ACTIVE);

        boolean anyDriftedLine = schedulerAppender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.startsWith("balance drift tick:") && msg.contains("drifted=0"));
        assertThat(anyDriftedLine)
                .as("expected at least one summary tick with drifted=0")
                .isTrue();
    }

    @Test
    void driftOnActiveAccount_suspendsWithinOneTick() {
        seedClearing("1000.00");
        seedCustomerAtZero("CUST-DRIFT");
        seedCustomerAtZero("CUST-PARTNER");
        // Fund both via real transfers from clearing so cached balances match the ledger.
        commitTransfer("CLEARING-000", "CUST-PARTNER", "100.00");
        commitTransfer("CUST-PARTNER", "CUST-DRIFT", "10.00");
        // Now CUST-DRIFT cached=10.00, ledger=10.00 — in balance.

        // Fabricate drift: bump the cached balance out of sync with the ledger.
        jdbc.update("UPDATE account SET balance = ? WHERE account_number = ?",
                new BigDecimal("999.00"), "CUST-DRIFT");

        // Force a fresh movement touching CUST-DRIFT so it lands in the next audit window
        // (the previous transfer's movements may have already been processed by an earlier tick).
        commitTransfer("CUST-PARTNER", "CUST-DRIFT", "1.00");

        await().atMost(5, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> accounts.findByNumber(AccountNumber.of("CUST-DRIFT")).orElseThrow()
                        .status() == AccountStatus.SUSPENDED);

        boolean errorLineNamesAccount = useCaseAppender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.contains("CUST-DRIFT") && msg.contains("SUSPENDED"));
        assertThat(errorLineNamesAccount)
                .as("expected an ERROR log line naming CUST-DRIFT as SUSPENDED")
                .isTrue();
    }

    @Test
    void clearingAccountDrift_isCarveOut_neverSuspended() {
        seedClearing("1000.00");
        seedCustomerAtZero("CUST-X");

        // Open a new customer via a transfer that touches the clearing account
        // (puts the clearing account into the next audit window's candidates).
        commitTransfer("CLEARING-000", "CUST-X", "10.00");

        // Fabricate drift on the clearing account.
        jdbc.update("UPDATE account SET balance = balance + 50 WHERE account_number = ?", CLEARING.value());

        // Commit another transfer touching the clearing account so it falls into the next window.
        commitTransfer("CLEARING-000", "CUST-X", "1.00");

        // Wait for at least 3 ticks to be sure the audit had a chance to see the candidate.
        await().atMost(5, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).until(() -> {
            long carveOutLines = useCaseAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(msg -> msg.contains("clearing-account audit skipped: CLEARING-000"))
                    .count();
            return carveOutLines >= 1;
        });

        assertThat(accounts.findByNumber(CLEARING).orElseThrow().status())
                .as("clearing account must remain ACTIVE despite the drift")
                .isEqualTo(AccountStatus.ACTIVE);

        boolean anyErrorNamesClearing = useCaseAppender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.contains(CLEARING.value()));
        assertThat(anyErrorNamesClearing)
                .as("no ERROR line should name the clearing account")
                .isFalse();
    }

    @Test
    void restartSemantics_checkpointAdvancesAcrossTicks() {
        seedClearing("1000.00");
        seedCustomerAtZero("CUST-A");
        seedCustomerAtZero("CUST-B");
        commitTransfer("CLEARING-000", "CUST-A", "100.00");
        commitTransfer("CUST-A", "CUST-B", "1.00");

        await().atMost(5, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> checkpoints.readOrZero(DetectBalanceDrift.AUDIT_NAME) > 0L);
        long firstCheckpoint = checkpoints.readOrZero(DetectBalanceDrift.AUDIT_NAME);

        // Commit another transfer; assert the checkpoint advances to cover the new movements.
        commitTransfer("CUST-A", "CUST-B", "1.00");
        await().atMost(5, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> checkpoints.readOrZero(DetectBalanceDrift.AUDIT_NAME) > firstCheckpoint);

        long secondCheckpoint = checkpoints.readOrZero(DetectBalanceDrift.AUDIT_NAME);
        Long maxMovementId = jdbc.queryForObject("SELECT MAX(id) FROM ledger_movement", Long.class);
        assertThat(secondCheckpoint).isGreaterThanOrEqualTo(maxMovementId);
    }

    @Test
    void alreadySuspendedAccount_isNotReprocessed() {
        seedClearing("1000.00");
        seedCustomerAtZero("CUST-S");
        seedCustomerAtZero("CUST-OK");
        commitTransfer("CLEARING-000", "CUST-OK", "100.00");

        // Drive CUST-S into SUSPENDED via a drift detection.
        commitTransfer("CUST-OK", "CUST-S", "1.00");
        jdbc.update("UPDATE account SET balance = ? WHERE account_number = ?",
                new BigDecimal("500.00"), "CUST-S");
        commitTransfer("CUST-OK", "CUST-S", "1.00");

        await().atMost(5, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> accounts.findByNumber(AccountNumber.of("CUST-S")).orElseThrow()
                        .status() == AccountStatus.SUSPENDED);

        int errorCountBefore = (int) useCaseAppender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(msg -> msg.contains("CUST-S"))
                .count();

        // Wait for several more ticks; assert no new ERROR for CUST-S.
        try {
            Thread.sleep(700);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int errorCountAfter = (int) useCaseAppender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(msg -> msg.contains("CUST-S"))
                .count();
        assertThat(errorCountAfter)
                .as("no new ERROR lines should name CUST-S once it is SUSPENDED")
                .isEqualTo(errorCountBefore);

        assertThat(accounts.findByNumber(AccountNumber.of("CUST-S")).orElseThrow().status())
                .isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    void tickSummaryLogIsEmittedEachTick() {
        Pattern summary = Pattern.compile(
                "^balance drift tick: floor=\\d+, ceiling=\\d+, inspected=\\d+, drifted=\\d+$");

        await().atMost(5, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS).until(() -> {
            List<ILoggingEvent> infos = schedulerAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO).toList();
            long matching = infos.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(msg -> summary.matcher(msg).matches())
                    .count();
            return matching >= 3;
        });
    }
}
