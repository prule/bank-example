package com.bank.core.seed;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bank.core.application.seed.SeedData;
import com.bank.core.application.seed.SeedPlan;
import com.bank.core.infrastructure.seed.SeedDataRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "bank.seed.enabled=false",
        // Per-class unique H2 URL so leftover rows from JVM-shared
        // bankcore-test (DB_CLOSE_DELAY=-1) cannot mask a real seed write.
        "spring.datasource.url=jdbc:h2:mem:bankcore-seed-off-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeedDataOffIntegrationTest {

    private static ListAppender<ILoggingEvent> appender;
    private static Logger seedPackageLogger;

    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void installAppenderBeforeStartup() {
        appender = new ListAppender<>();
        appender.start();
        seedPackageLogger = (Logger) LoggerFactory.getLogger("com.bank.core.infrastructure.seed");
        seedPackageLogger.addAppender(appender);
    }

    @Test
    void seedBeansAreAbsent() {
        assertThat(context.getBeansOfType(SeedData.class)).isEmpty();
        assertThat(context.getBeansOfType(SeedDataRunner.class)).isEmpty();
        assertThat(context.getBeansOfType(SeedPlan.class)).isEmpty();
    }

    @Test
    void databaseRemainsEmpty() {
        Integer accountRows = jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class);
        assertThat(accountRows).isZero();
        Integer journalRows = jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Integer.class);
        assertThat(journalRows).isZero();
    }

    @Test
    void noDevSeedLogLinesProducedDuringStartup() {
        boolean anyDevSeedLog = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.contains("dev seed"));
        assertThat(anyDevSeedLog)
                .as("seed pipeline must be silent when bank.seed.enabled=false")
                .isFalse();
    }
}
