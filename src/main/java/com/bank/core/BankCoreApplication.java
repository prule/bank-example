package com.bank.core;

import com.bank.core.config.H2ServerInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@SpringBootApplication
@Configuration
public class BankCoreApplication {
  public static void main(String[] args) throws Exception {
    SpringApplication app = new SpringApplication(BankCoreApplication.class);
    app.addInitializers(new H2ServerInitializer());
    app.run(args);
  }
}
