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

@Profile("dev")
@ConditionalOnProperty(name = "bank-core.h2.tcp-server.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class H2ServerInitializer {
    private static final Logger log = LoggerFactory.getLogger(H2ServerInitializer.class);

    private Server server;

    @Value("${bank-core.h2.tcp-server.port:9092}")
    private int port;

    @PostConstruct
    public void start() throws SQLException {
        this.server = Server.createTcpServer("-tcp", "-tcpAllowOthers", "-tcpPort", String.valueOf(port)).start();
        log.info("H2 TCP server started on port {} with connection URL: jdbc:h2:tcp://localhost:{}/mem:bankcore", port, port);
    }

    @PreDestroy
    public void stop() {
        if (this.server != null) {
            log.info("Stopping H2 TCP server...");
            this.server.stop();
        }
    }
}
