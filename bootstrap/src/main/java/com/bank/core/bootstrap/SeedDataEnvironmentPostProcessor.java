package com.bank.core.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Custom Spring Boot EnvironmentPostProcessor that maps the SEED_DATA environment
 * variable or command-line option onto the standard bank.seed.enabled property.
 * Registers this property source with the lowest precedence so explicit configuration wins.
 */
public class SeedDataEnvironmentPostProcessor implements EnvironmentPostProcessor {
    private static final String PROPERTY_SOURCE_NAME = "seedDataAliasProperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.containsProperty("SEED_DATA")) {
            String seedDataValue = environment.getProperty("SEED_DATA");
            if (seedDataValue != null) {
                Map<String, Object> aliasProperties = new HashMap<>();
                aliasProperties.put("bank.seed.enabled", seedDataValue);

                PropertySource<?> source = new MapPropertySource(PROPERTY_SOURCE_NAME, aliasProperties);
                // Low precedence so that explicit settings in application.yaml take precedence
                environment.getPropertySources().addLast(source);
            }
        }
    }
}
