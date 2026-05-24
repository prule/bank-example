package com.bank.core.infrastructure.seed;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bank.core.application.seed.SeedData;
import com.bank.core.application.seed.SeedReport;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.DuplicateAccountNumberException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeedDataRunnerTest {

    private static final AccountNumber CLEARING = AccountNumber.of("CLEARING-000");

    private SeedData seedData;
    private SeedDataRunner runner;
    private ListAppender<ILoggingEvent> appender;
    private Logger runnerLogger;

    @BeforeEach
    void setUp() {
        seedData = mock(SeedData.class);
        runner = new SeedDataRunner(seedData);

        appender = new ListAppender<>();
        appender.start();
        runnerLogger = (Logger) LoggerFactory.getLogger(SeedDataRunner.class);
        runnerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        runnerLogger.detachAppender(appender);
    }

    @Test
    void runWithSeededReport_emitsSingleInfoLine() {
        when(seedData.seed()).thenReturn(new SeedReport.Seeded(
                CLEARING,
                List.of(AccountNumber.of("CUST-A"), AccountNumber.of("CUST-B"))));

        runner.run(null);

        List<ILoggingEvent> infos = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO).toList();
        assertEquals(1, infos.size(), "expected exactly one INFO line");
        String msg = infos.get(0).getFormattedMessage();
        assertEquals("dev seed complete: clearing=CLEARING-000 customers=[CUST-A, CUST-B] (count=2)", msg);
    }

    @Test
    void runWithSkippedReport_emitsSingleSkipInfoLine() {
        when(seedData.seed()).thenReturn(new SeedReport.Skipped("clearing account already present"));

        runner.run(null);

        List<ILoggingEvent> infos = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO).toList();
        assertEquals(1, infos.size());
        assertEquals("dev seed skipped: clearing account already present",
                infos.get(0).getFormattedMessage());
    }

    @Test
    void runWithFailingSeed_logsErrorAndRethrows() {
        DuplicateAccountNumberException dup = new DuplicateAccountNumberException(AccountNumber.of("CUST-X"));
        when(seedData.seed()).thenThrow(dup);

        DuplicateAccountNumberException thrown = assertThrows(DuplicateAccountNumberException.class,
                () -> runner.run(null));
        assertSame(dup, thrown);

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size(), "expected exactly one ERROR line");
        String msg = errors.get(0).getFormattedMessage();
        assertTrue(msg.contains("CUST-X"), "error message should name the failing account; was: " + msg);
        assertTrue(msg.contains("DuplicateAccountNumberException"),
                "error message should name the exception class; was: " + msg);
    }

    @Test
    void propertyName_isLiteral_bankSeedEnabled() {
        ConditionalOnProperty annotation = SeedDataRunner.class.getAnnotation(ConditionalOnProperty.class);
        assertArrayEquals(new String[]{"bank.seed.enabled"}, annotation.name(),
                "refactor must not rename the gate property — SEED_DATA alias and yaml config depend on it");
        assertEquals("true", annotation.havingValue());
    }
}
