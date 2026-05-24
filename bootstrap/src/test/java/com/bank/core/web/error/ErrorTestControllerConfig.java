package com.bank.core.web.error;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class ErrorTestControllerConfig {

    @Bean
    ErrorTestController errorTestController() {
        return new ErrorTestController();
    }
}
