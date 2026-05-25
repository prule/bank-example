package com.bank.core.infrastructure.scheduling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bank.core.application.audit.DriftReport;
import com.bank.core.infrastructure.audit.BalanceDriftAudit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BalanceDriftSchedulerTest {

    private BalanceDriftAudit audit;
    private BalanceDriftScheduler scheduler;
    private ListAppender<ILoggingEvent> appender;
    private Logger schedulerLogger;

    @BeforeEach
    void setUp() {
        audit = mock(BalanceDriftAudit.class);
        scheduler = new BalanceDriftScheduler(audit);

        appender = new ListAppender<>();
        appender.start();
        schedulerLogger = (Logger) LoggerFactory.getLogger(BalanceDriftScheduler.class);
        schedulerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        schedulerLogger.detachAppender(appender);
    }

    @Test
    void tick_callsFacadeOnce_andLogsSummary() {
        when(audit.audit()).thenReturn(new DriftReport(0L, 100L, 5, 1));

        scheduler.tick();

        List<ILoggingEvent> infos = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO).toList();
        assertEquals(1, infos.size());
        assertEquals("balance drift tick: floor=0, ceiling=100, inspected=5, drifted=1",
                infos.get(0).getFormattedMessage());
    }

    @Test
    void tick_emptyWindow_emitsHeartbeat() {
        when(audit.audit()).thenReturn(DriftReport.empty(50L, 50L));

        scheduler.tick();

        List<ILoggingEvent> infos = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO).toList();
        assertEquals(1, infos.size());
        assertEquals("balance drift tick: floor=50, ceiling=50, inspected=0, drifted=0",
                infos.get(0).getFormattedMessage());
    }

    @Test
    void tick_facadeThrows_propagates_andNoSummaryEmitted() {
        RuntimeException boom = new RuntimeException("DB down");
        when(audit.audit()).thenThrow(boom);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> scheduler.tick());
        assertSame(boom, thrown);

        long summaryLines = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(msg -> msg.startsWith("balance drift tick:"))
                .count();
        assertEquals(0, summaryLines, "no summary line on a failed tick");
    }

    @Test
    void scheduledAnnotation_usesFixedDelayPlaceholder() throws NoSuchMethodException {
        Method tick = BalanceDriftScheduler.class.getMethod("tick");
        Scheduled scheduled = tick.getAnnotation(Scheduled.class);
        assertNotNull(scheduled);
        assertEquals("${bank.balance-drift.fixed-delay-ms:30000}", scheduled.fixedDelayString());
        assertEquals("${bank.balance-drift.initial-delay-ms:15000}", scheduled.initialDelayString());
        assertTrue(scheduled.fixedRateString().isEmpty(),
                "fixedRateString must be empty — fixedDelay is the documented choice");
    }
}
