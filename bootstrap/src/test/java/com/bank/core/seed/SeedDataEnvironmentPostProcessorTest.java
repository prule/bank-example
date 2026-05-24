package com.bank.core.seed;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedDataEnvironmentPostProcessorTest {

    private static SeedDataEnvironmentPostProcessor withEnv(Map<String, String> env) {
        return new SeedDataEnvironmentPostProcessor() {
            @Override
            protected String readEnv(String name) {
                return env.get(name);
            }
        };
    }

    @Test
    void seedDataTrue_setsBankSeedEnabledTrue() {
        MockEnvironment env = new MockEnvironment();
        withEnv(Map.of("SEED_DATA", "true")).postProcessEnvironment(env, null);

        assertEquals(Boolean.TRUE, env.getProperty("bank.seed.enabled", Boolean.class));
    }

    @Test
    void seedDataFalse_setsBankSeedEnabledFalse() {
        MockEnvironment env = new MockEnvironment();
        withEnv(Map.of("SEED_DATA", "false")).postProcessEnvironment(env, null);

        assertEquals(Boolean.FALSE, env.getProperty("bank.seed.enabled", Boolean.class));
    }

    @Test
    void seedDataUnset_addsNoPropertySource() {
        MockEnvironment env = new MockEnvironment();
        withEnv(Map.of()).postProcessEnvironment(env, null);

        assertFalse(env.getPropertySources().contains(SeedDataEnvironmentPostProcessor.ALIAS_SOURCE_NAME));
        assertNull(env.getProperty("bank.seed.enabled"));
    }

    @Test
    void seedDataMalformed_addsNoPropertySource() {
        MockEnvironment env = new MockEnvironment();
        withEnv(Map.of("SEED_DATA", "maybe")).postProcessEnvironment(env, null);

        assertFalse(env.getPropertySources().contains(SeedDataEnvironmentPostProcessor.ALIAS_SOURCE_NAME));
        assertNull(env.getProperty("bank.seed.enabled"));
    }

    @Test
    void explicitYamlWinsOverAlias() {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource(
                "applicationYaml", Map.of("bank.seed.enabled", "false")));

        withEnv(Map.of("SEED_DATA", "true")).postProcessEnvironment(env, null);

        assertEquals(Boolean.FALSE, env.getProperty("bank.seed.enabled", Boolean.class),
                "explicit yaml/test/cli config must win over the SEED_DATA alias (alias is addLast, lowest precedence)");
        assertTrue(env.getPropertySources().contains(SeedDataEnvironmentPostProcessor.ALIAS_SOURCE_NAME),
                "alias source is still registered, just outranked");
    }

    @Test
    void seedDataMixedCase_isAccepted() {
        MockEnvironment env = new MockEnvironment();
        withEnv(Map.of("SEED_DATA", "TRUE")).postProcessEnvironment(env, null);

        assertEquals(Boolean.TRUE, env.getProperty("bank.seed.enabled", Boolean.class));
    }
}
