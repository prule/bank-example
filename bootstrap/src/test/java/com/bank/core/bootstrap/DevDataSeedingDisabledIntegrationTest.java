package com.bank.core.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = { "bank.seed.enabled=false" })
@ActiveProfiles("test")
public class DevDataSeedingDisabledIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    public void testSeedingBeansAbsentWhenDisabled() {
        // Assert that all seeding beans are completely absent from context when switch is OFF
        assertThat(applicationContext.getBeansOfType(SeedPlan.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(SeedData.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(SeedDataRunner.class)).isEmpty();
    }
}
