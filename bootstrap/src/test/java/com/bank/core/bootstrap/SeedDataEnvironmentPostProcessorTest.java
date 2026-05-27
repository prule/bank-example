package com.bank.core.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.*;

public class SeedDataEnvironmentPostProcessorTest {

    @Test
    public void testPostProcessEnvironmentAliasMapping() {
        SeedDataEnvironmentPostProcessor postProcessor = new SeedDataEnvironmentPostProcessor();
        ConfigurableEnvironment environment = new StandardEnvironment();

        // 1. Initially both are absent
        assertNull(environment.getProperty("bank.seed.enabled"));

        // 2. Set SEED_DATA as a system property to simulate environment variable relaxed binding
        System.setProperty("SEED_DATA", "true");
        try {
            ConfigurableEnvironment env = new StandardEnvironment();
            postProcessor.postProcessEnvironment(env, null);

            // Verified alias mapping resolves bank.seed.enabled to true
            assertEquals("true", env.getProperty("bank.seed.enabled"));
        } finally {
            System.clearProperty("SEED_DATA");
        }
    }

    @Test
    public void testAliasDoesNotMapIfSeedDataAbsent() {
        SeedDataEnvironmentPostProcessor postProcessor = new SeedDataEnvironmentPostProcessor();
        ConfigurableEnvironment env = new StandardEnvironment();

        // Ensure system property is clean
        System.clearProperty("SEED_DATA");

        postProcessor.postProcessEnvironment(env, null);

        assertNull(env.getProperty("bank.seed.enabled"));
    }
}
