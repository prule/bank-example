package com.bank.core.seed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Aliases the {@code SEED_DATA} environment variable onto
 * {@code bank.seed.enabled} with low precedence so that any explicit
 * {@code application*.yaml} value wins. Spring Boot's standard relaxed binding
 * would otherwise require {@code BANK_SEED_ENABLED}; the F09 spec calls for
 * the shorter {@code SEED_DATA} alias.
 *
 * <p>See design.md Decision 4 for the precedence rationale: the alias is added
 * via {@link org.springframework.core.env.MutablePropertySources#addLast} so a
 * user-set explicit value (yaml, command line, JVM args) overrides the env-var
 * fallback.
 */
public class SeedDataEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String ALIAS_SOURCE_NAME = "seedDataAlias";
    static final String TARGET_PROPERTY = "bank.seed.enabled";
    static final String SOURCE_ENV_VAR = "SEED_DATA";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = readEnv(SOURCE_ENV_VAR);
        if (raw == null) {
            return;
        }
        Boolean parsed = parseBoolean(raw);
        if (parsed == null) {
            return;
        }
        Map<String, Object> entries = Map.of(TARGET_PROPERTY, parsed);
        environment.getPropertySources().addLast(new MapPropertySource(ALIAS_SOURCE_NAME, entries));
    }

    /**
     * Indirection so tests can substitute environment-variable lookups without
     * mutating the JVM-wide {@code System.getenv} map.
     */
    protected String readEnv(String name) {
        return System.getenv(name);
    }

    private static Boolean parseBoolean(String raw) {
        String trimmed = raw.trim().toLowerCase();
        if ("true".equals(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equals(trimmed)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
