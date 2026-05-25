package com.bank.core.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class H2ServerInitializerDevProfileTest {

    /**
     * Logback appender attached at class-load time so it captures the
     * {@code H2ServerInitializer} {@code @PostConstruct} log line that fires
     * during Spring context startup — well before any {@code @BeforeAll} or
     * {@code @BeforeEach} method runs.
     */
    private static final ListAppender<ILoggingEvent> APPENDER;
    private static final Logger INITIALIZER_LOGGER;

    private static final int TCP_PORT;

    static {
        try (ServerSocket socket = new ServerSocket(0)) {
            // OS-assigned free port; close immediately so H2 can rebind it.
            // Brief race window (microseconds) is accepted — see design.md.
            TCP_PORT = socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("could not pick a free port for H2 TCP test", e);
        }

        APPENDER = new ListAppender<>();
        APPENDER.start();
        INITIALIZER_LOGGER = (Logger) LoggerFactory.getLogger(H2ServerInitializer.class);
        INITIALIZER_LOGGER.addAppender(APPENDER);
    }

    @DynamicPropertySource
    static void overrideTcpPort(DynamicPropertyRegistry registry) {
        registry.add("bank-core.h2.tcp-server.port", () -> TCP_PORT);
    }

    @AfterAll
    static void detachAppender() {
        INITIALIZER_LOGGER.detachAppender(APPENDER);
    }

    @Autowired ApplicationContext context;

    @Test
    void tcpServerBeanIsConstructed() {
        assertThat(context.getBeansOfType(H2ServerInitializer.class))
                .as("dev profile + default bank-core.h2.tcp-server.enabled=true should construct the bean")
                .hasSize(1);
    }

    @Test
    void externalJdbcClientCanConnectAndQuery() throws Exception {
        String url = "jdbc:h2:tcp://localhost:" + TCP_PORT + "/mem:bankcore";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }

            // Flyway-managed tables visible through the TCP-exposed instance.
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM account");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                // Row count not asserted — depends on whether F09 seeded data
                // under the dev profile. The assertion is that the query
                // executes without error, proving the table exists in the
                // TCP-exposed instance.
            }
        }
    }

    @Test
    void startupLogIncludesConnectionString() {
        boolean foundLine = APPENDER.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.contains("H2 TCP server started on port " + TCP_PORT)
                        && msg.contains("jdbc:h2:tcp://localhost:" + TCP_PORT)
                        && msg.contains("mem:bankcore")
                        && msg.contains("user sa"));
        assertThat(foundLine)
                .as("expected H2ServerInitializer to log a connection-string line at @PostConstruct")
                .isTrue();
    }
}
