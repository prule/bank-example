package com.bank.core.config;

import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

//@Configuration
public class SchedulingConfig implements SchedulingConfigurer {
    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        log.warn("Configuring schedulers");
        // Give the scheduler pool 3 dedicated concurrent workers
        taskRegistrar.setScheduler(Executors.newScheduledThreadPool(3));
    }
}