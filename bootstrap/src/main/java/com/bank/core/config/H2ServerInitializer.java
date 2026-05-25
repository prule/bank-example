package com.bank.core.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "bank-core.h2.tcp-server", name = "enabled", havingValue = "true")
public class H2ServerInitializer {

    private static final Logger log = LoggerFactory.getLogger(H2ServerInitializer.class);

    private final int port;
    private Server server;

    public H2ServerInitializer(@Value("${bank-core.h2.tcp-server.port:9092}") int port) {
        this.port = port;
    }

    @PostConstruct
    void start() throws SQLException {
        server = Server.createTcpServer("-tcp", "-tcpAllowOthers", "-tcpPort", Integer.toString(port)).start();
        log.info("H2 TCP server started on port {} (connect with: jdbc:h2:tcp://localhost:{}/mem:bankcore, user sa, no password)",
                port, port);
    }

    @PreDestroy
    void stop() {
        if (server != null) {
            server.stop();
            log.info("H2 TCP server stopped");
        }
    }
}
