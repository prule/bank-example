package com.bank.core.scheduling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bank.core.application.account.Accounts;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.JournalEntryId;
import com.bank.core.domain.Money;
import com.bank.core.domain.Movement;
import com.bank.core.domain.MovementType;
import com.bank.core.domain.VerificationStatus;
import com.bank.core.infrastructure.scheduling.JournalVerificationScheduler;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "bank.journal-verification.initial-delay-ms=0",
        "bank.journal-verification.fixed-delay-ms=200",
        // Per-class unique H2 so prior tests' journal_entry rows from the JVM-shared
        // bankcore-test DB cannot pollute the seed-then-await assertions here.
        "spring.datasource.url=jdbc:h2:mem:bankcore-jv-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class JournalVerificationIntegrationTest {

    @Autowired Accounts accounts;
    @Autowired JournalEntries journals;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private ListAppender<ILoggingEvent> useCaseAppender;
    private ListAppender<ILoggingEvent> schedulerAppender;
    private Logger useCaseLogger;
    private Logger schedulerLogger;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM ledger_movement");
        jdbc.update("DELETE FROM journal_entry");
        jdbc.update("DELETE FROM account");

        useCaseAppender = new ListAppender<>();
        useCaseAppender.start();
        useCaseLogger = (Logger) LoggerFactory.getLogger("com.bank.core.application.ledger.VerifyPendingJournals");
        useCaseLogger.addAppender(useCaseAppender);

        schedulerAppender = new ListAppender<>();
        schedulerAppender.start();
        schedulerLogger = (Logger) LoggerFactory.getLogger(JournalVerificationScheduler.class);
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

    private Account seedAccount(String number, String balance) {
        Account a = Account.open(AccountNumber.of(number), Money.of(balance));
        tx().executeWithoutResult(s -> accounts.save(a));
        return a;
    }

    private JournalEntry seedBalancedJournal(Account src, Account dst, String amount) {
        JournalEntry entry = JournalEntry.create(
                "integration-test-balanced",
                Instant.parse("2026-05-25T11:00:00Z"),
                List.of(
                        new Movement(src.id(), Money.of(amount), MovementType.DEBIT),
                        new Movement(dst.id(), Money.of(amount), MovementType.CREDIT)));
        tx().executeWithoutResult(s -> journals.save(entry));
        return entry;
    }

    private JournalEntryId insertUnbalancedJournalDirect(Account src, Account dst,
                                                        String debitAmount, String creditAmount) {
        // Production code paths cannot create an unbalanced journal — JournalEntry.create()
        // refuses it. Insert directly via JDBC to fabricate exactly the corrupted state
        // F10 is designed to catch.
        UUID journalId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO journal_entry (id, description, entry_timestamp, verification_status) VALUES (?, ?, ?, ?)",
                journalId, "integration-test-unbalanced", Instant.parse("2026-05-25T11:00:01Z"), "PENDING");
        jdbc.update(
                "INSERT INTO ledger_movement (journal_entry_id, movement_order, account_id, amount, movement_type) "
                        + "VALUES (?, ?, ?, ?, ?)",
                journalId, 0, src.id().value(), new java.math.BigDecimal(debitAmount), "DEBIT");
        jdbc.update(
                "INSERT INTO ledger_movement (journal_entry_id, movement_order, account_id, amount, movement_type) "
                        + "VALUES (?, ?, ?, ?, ?)",
                journalId, 1, dst.id().value(), new java.math.BigDecimal(creditAmount), "CREDIT");
        return JournalEntryId.of(journalId);
    }

    private VerificationStatus statusOf(JournalEntryId id) {
        return VerificationStatus.valueOf(jdbc.queryForObject(
                "SELECT verification_status FROM journal_entry WHERE id = ?", String.class, id.value()));
    }

    @Test
    void balancedJournal_transitionsToVerifiedWithinOneTick() {
        Account a = seedAccount("ACC-V-A", "100.00");
        Account b = seedAccount("ACC-V-B", "100.00");
        JournalEntry entry = seedBalancedJournal(a, b, "25.00");

        await().atMost(5, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> statusOf(entry.id()) == VerificationStatus.VERIFIED);

        assertThat(accounts.findByNumber(AccountNumber.of("ACC-V-A")).orElseThrow().status())
                .as("verified path does not touch accounts")
                .isEqualTo(AccountStatus.ACTIVE);
        assertThat(accounts.findByNumber(AccountNumber.of("ACC-V-B")).orElseThrow().status())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void unbalancedJournal_transitionsToFailedAndSuspendsTouchedAccounts() {
        Account a = seedAccount("ACC-F-A", "100.00");
        Account b = seedAccount("ACC-F-B", "100.00");
        JournalEntryId journalId = insertUnbalancedJournalDirect(a, b, "10.00", "5.00");

        await().atMost(5, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> statusOf(journalId) == VerificationStatus.FAILED);

        assertThat(accounts.findByNumber(AccountNumber.of("ACC-F-A")).orElseThrow().status())
                .isEqualTo(AccountStatus.SUSPENDED);
        assertThat(accounts.findByNumber(AccountNumber.of("ACC-F-B")).orElseThrow().status())
                .isEqualTo(AccountStatus.SUSPENDED);

        // ERROR line from the use case names the journal id.
        boolean errorLineNamesJournal = useCaseAppender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.contains(journalId.value().toString()));
        assertThat(errorLineNamesJournal)
                .as("expected an ERROR log line naming the failed journal id %s", journalId.value())
                .isTrue();
    }

    @Test
    void mixedPage_oneBalancedOneUnbalanced_eachProcessedInSameTick() {
        Account a = seedAccount("ACC-M-A", "100.00");
        Account b = seedAccount("ACC-M-B", "100.00");
        Account c = seedAccount("ACC-M-C", "100.00");
        Account d = seedAccount("ACC-M-D", "100.00");

        JournalEntry balanced = seedBalancedJournal(a, b, "10.00");
        JournalEntryId unbalanced = insertUnbalancedJournalDirect(c, d, "7.00", "3.00");

        await().atMost(5, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> statusOf(balanced.id()) == VerificationStatus.VERIFIED
                        && statusOf(unbalanced) == VerificationStatus.FAILED);

        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM journal_entry WHERE verification_status = 'PENDING'", Integer.class);
        assertThat(pending).isZero();
    }

    @Test
    void verifiedAndFailedJournals_areNotRetriedOnLaterTicks() {
        Account a = seedAccount("ACC-N-A", "100.00");
        Account b = seedAccount("ACC-N-B", "100.00");

        JournalEntry balanced = seedBalancedJournal(a, b, "10.00");
        JournalEntryId unbalanced = insertUnbalancedJournalDirect(a, b, "3.00", "1.00");

        await().atMost(5, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> statusOf(balanced.id()) == VerificationStatus.VERIFIED
                        && statusOf(unbalanced) == VerificationStatus.FAILED);

        int accountsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class);
        int journalsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Integer.class);
        int movementsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_movement", Integer.class);

        // Wait roughly three ticks (3 * 200 ms).
        try {
            Thread.sleep(700);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class)).isEqualTo(accountsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Integer.class)).isEqualTo(journalsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_movement", Integer.class)).isEqualTo(movementsBefore);
        assertThat(statusOf(balanced.id())).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(statusOf(unbalanced)).isEqualTo(VerificationStatus.FAILED);
    }

    @Test
    void tickSummaryLogIsEmittedEachTick() {
        Pattern summary = Pattern.compile(
                "^journal verification tick: processed=\\d+, verified=\\d+, failed=\\d+, errored=\\d+, cascadeSuspended=\\d+$");

        await().atMost(5, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS).until(() -> {
            // Snapshot the appender's mutable list to avoid a
            // ConcurrentModificationException when the scheduler fires a new
            // tick mid-stream.
            java.util.List<ILoggingEvent> snapshot = new java.util.ArrayList<>(schedulerAppender.list);
            long heartbeats = snapshot.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(msg -> summary.matcher(msg).matches())
                    .count();
            return heartbeats >= 3;
        });
    }
}
