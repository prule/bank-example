package com.bank.core.infrastructure.concurrency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "bank.transfer")
public record TransferLockingProperties(
    @DefaultValue("jvm") String locker,
    @DefaultValue("5000") long lockWaitMs
) {
    public TransferLockingProperties {
        locker = locker.toLowerCase();
        if (!locker.equals("jvm") && !locker.equals("db")) {
            throw new IllegalArgumentException("bank.transfer.locker must be 'jvm' or 'db' (was: '" + locker + "')");
        }
    }
}
