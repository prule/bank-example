package com.bank.core.infrastructure.concurrency;

import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.domain.LockAcquisitionTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.PostConstruct;
import java.sql.SQLException;

@Component
@ConditionalOnProperty(name = "bank.transfer.locker", havingValue = "db")
public class DbAccountLocker implements AccountLocker {
    private static final Logger log = LoggerFactory.getLogger(DbAccountLocker.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransferLockingProperties properties;
    private boolean isH2 = true;

    public DbAccountLocker(JdbcTemplate jdbcTemplate, TransferLockingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        try {
            String dbName = jdbcTemplate.execute((ConnectionCallback<String>) conn -> conn.getMetaData().getDatabaseProductName());
            isH2 = dbName != null && dbName.toLowerCase().contains("h2");
            log.info("Initialized DbAccountLocker with wait timeout of {} ms on database: {}", properties.lockWaitMs(), dbName);
        } catch (Exception e) {
            log.warn("Failed to detect database product name, defaulting to H2 timeout settings", e);
            isH2 = true;
        }
    }

    @Override
    public long getWaitMs() {
        return properties.lockWaitMs();
    }

    @Override
    public void withPairedLocks(String a, String b, Runnable action) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Account numbers must not be null");
        }

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("No active transaction found for lock acquisition");
        }

        String first = a.compareTo(b) <= 0 ? a : b;
        String second = a.compareTo(b) <= 0 ? b : a;

        try {
            // Apply lock timeout
            if (isH2) {
                jdbcTemplate.execute("SET LOCK_TIMEOUT " + properties.lockWaitMs());
            } else {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '" + properties.lockWaitMs() + "ms'");
            }

            // Perform the SELECT FOR UPDATE
            if (first.equals(second)) {
                jdbcTemplate.queryForList("SELECT id FROM account WHERE account_number IN (?) FOR UPDATE", first);
            } else {
                jdbcTemplate.queryForList("SELECT id FROM account WHERE account_number IN (?, ?) ORDER BY account_number FOR UPDATE", first, second);
            }

            // Reset lock timeout for subsequent statements if H2
            if (isH2) {
                jdbcTemplate.execute("SET LOCK_TIMEOUT 1000");
            }
        } catch (Exception e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof SQLException sqlEx) {
                    String sqlState = sqlEx.getSQLState();
                    int errorCode = sqlEx.getErrorCode();
                    if ("HYT00".equals(sqlState) || errorCode == 50200 || "55P03".equals(sqlState)) {
                        throw new LockAcquisitionTimeoutException(first, second, properties.lockWaitMs());
                    }
                }
                cause = cause.getCause();
            }
            throw e;
        }

        action.run();
    }
}
