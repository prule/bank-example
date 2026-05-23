package com.bank.core.config;

import java.sql.SQLException;
import java.util.Arrays;
import org.h2.tools.Server;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.Environment;

public class H2ServerInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {
  private static Server h2Server;

  @Override
  public void initialize(ConfigurableApplicationContext ctx) {
    Environment env = ctx.getEnvironment();
    if (!Arrays.asList(env.getActiveProfiles()).contains("dev")) {
      return;
    }
    try {
      h2Server = Server.createTcpServer("-tcp", "-tcpAllowOthers", "-tcpPort", "9092").start();

      ctx.addApplicationListener(
          (ContextClosedEvent e) -> {
            if (h2Server != null) h2Server.stop();
          });
    } catch (SQLException e) {
      throw new RuntimeException("H2 TCP server failed", e);
    }
  }
}
