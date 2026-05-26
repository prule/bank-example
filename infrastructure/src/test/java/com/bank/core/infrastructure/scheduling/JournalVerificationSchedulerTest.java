package com.bank.core.infrastructure.scheduling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bank.core.application.ledger.SweepReport;
import com.bank.core.application.ledger.VerifyPendingJournals;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JournalVerificationSchedulerTest {

    private VerifyPendingJournals useCase;
    private JournalVerificationScheduler scheduler;
    private ListAppender<ILoggingEvent> appender;
    private Logger schedulerLogger;

    @BeforeEach
    void setUp() {
        useCase = mock(VerifyPendingJournals.class);
        scheduler = new JournalVerificationScheduler(useCase, new SimpleMeterRegistry());

        appender = new ListAppender<>();
        appender.start();
        schedulerLogger = (Logger) LoggerFactory.getLogger(JournalVerificationScheduler.class);
        schedulerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        schedulerLogger.detachAppender(appender);
    }

    @Test
    void tick_callsUseCaseOnce_andLogsSummary() {
        when(useCase.sweep()).thenReturn(new SweepReport(2, 1, 1, 0, 2));

        scheduler.tick();

        List<ILoggingEvent> infos = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO).toList();
        assertEquals(1, infos.size(), "expected exactly one INFO line");
        assertEquals("journal verification tick: processed=2, verified=1, failed=1, errored=0, cascadeSuspended=2",
                infos.get(0).getFormattedMessage());
    }

    @Test
    void tick_emptySweep_emitsHeartbeat() {
        when(useCase.sweep()).thenReturn(SweepReport.empty());

        scheduler.tick();

        List<ILoggingEvent> infos = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO).toList();
        assertEquals(1, infos.size());
        assertEquals("journal verification tick: processed=0, verified=0, failed=0, errored=0, cascadeSuspended=0",
                infos.get(0).getFormattedMessage());
    }

    @Test
    void tick_useCaseThrows_propagates() {
        RuntimeException dbDown = new RuntimeException("DB down");
        when(useCase.sweep()).thenThrow(dbDown);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> scheduler.tick());
        assertSame(dbDown, thrown);

        boolean anySummary = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.startsWith("journal verification tick:"));
        assertTrue(!anySummary, "summary line must NOT be emitted when sweep() throws");
    }

    @Test
    void scheduledAnnotation_usesFixedDelayPlaceholder() throws NoSuchMethodException {
        Method tick = JournalVerificationScheduler.class.getMethod("tick");
        Scheduled annotation = tick.getAnnotation(Scheduled.class);

        assertEquals("${bank.journal-verification.fixed-delay-ms:10000}", annotation.fixedDelayString());
        assertEquals("${bank.journal-verification.initial-delay-ms:5000}", annotation.initialDelayString());
        assertEquals("", annotation.fixedRateString(),
                "refactor must not switch to fixedRate — overlapping ticks would amplify backlog");
    }
}
