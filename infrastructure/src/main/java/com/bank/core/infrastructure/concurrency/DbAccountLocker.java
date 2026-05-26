package com.bank.core.infrastructure.concurrency;

import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.LockAcquisitionTimeoutException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Database-backed {@link AccountLocker} that acquires row-level exclusive
 * locks on both accounts via a single {@code SELECT ... FOR UPDATE} statement
 * against the {@code account} table, in canonical {@code account_number} order.
 *
 * <h2>Cross-instance correctness</h2>
 * The database is the single arbiter of lock order — every JVM in the cluster
 * issues the same SQL against the same row, so two opposite-direction
 * transfers initiated on two different JVMs serialise without deadlock or
 * cached-balance corruption. This adapter holds NO JVM-local lock state
 * (no {@code Map}, no {@code ReentrantLock}, no {@code Semaphore}); its
 * correctness reduces entirely to "the database serialises {@code FOR UPDATE}
 * acquisitions per row".
 *
 * <h2>Why one SQL statement</h2>
 * The canonical-order acquisition is achieved by {@code ORDER BY
 * account_number} followed by {@code FOR UPDATE} in a single statement.
 * Two sequential per-account {@code FOR UPDATE} statements would risk a
 * two-step deadlock observable by the DB only after its deadlock detector
 * fires — the single-statement approach prevents the interleaving entirely.
 *
 * <h2>Why explicit timeout reset (H2)</h2>
 * H2's {@code SET LOCK_TIMEOUT} is session-scoped, so a non-default value
 * would persist on the pooled connection after the locker returns and silently
 * affect the next user. The adapter sets the per-call bound immediately before
 * the {@code FOR UPDATE} and resets to {@code 0} immediately after, so the
 * timeout applies only to lock acquisition and not to any subsequent
 * statement inside {@code inTransaction.run()}.
 *
 * <h2>Why no afterCompletion hook</h2>
 * Unlike {@code JvmAccountLocker}, this adapter does NOT register a
 * {@link org.springframework.transaction.support.TransactionSynchronization}
 * callback. The database releases the row locks at the surrounding
 * transaction's COMMIT or ROLLBACK; an application-side hook would be
 * redundant and couple the implementation to Spring's transaction-
 * synchronisation machinery without adding any guarantee.
 *
 * <h2>Dialect support</h2>
 * H2 ({@code SET LOCK_TIMEOUT <ms>}) and PostgreSQL ({@code SET LOCAL
 * lock_timeout = '<ms>ms'}) are supported. The dialect is detected once on
 * first call via {@link java.sql.DatabaseMetaData#getDatabaseProductName()}
 * and cached. Other dialects fall back to the H2 SQL on a best-effort basis.
 */
@Component
@ConditionalOnProperty(name = "bank.transfer.locker", havingValue = "db")
public final class DbAccountLocker implements AccountLocker {

    private static final Logger log = LoggerFactory.getLogger(DbAccountLocker.class);

    /**
     * H2 timeout SQLSTATE (statement timeout / lock timeout surface).
     */
    private static final String H2_TIMEOUT_SQLSTATE = "HYT00";

    /**
     * H2 LOCK_TIMEOUT_1 error code (from {@code org.h2.api.ErrorCode}).
     */
    private static final int H2_LOCK_TIMEOUT_ERROR_CODE = 50200;

    /**
     * PostgreSQL lock_not_available SQLSTATE.
     */
    private static final String PG_LOCK_TIMEOUT_SQLSTATE = "55P03";

    /**
     * H2's default LOCK_TIMEOUT (ms). We reset to this rather than {@code 0}
     * after acquisition so any subsequent statement inside the runnable (or
     * any Hibernate housekeeping during transaction completion) inherits a
     * sensible default rather than "fail immediately on any contention".
     */
    private static final long DEFAULT_LOCK_TIMEOUT_MS = 1_000L;

    private final long waitMs;
    private final JdbcTemplate jdbcTemplate;
    private final Timer acquisitionTimer;
    private volatile String dialect; // populated lazily on first call

    public DbAccountLocker(TransferLockingProperties properties,
                           JdbcTemplate jdbcTemplate,
                           MeterRegistry registry) {
        Objects.requireNonNull(properties, "properties cannot be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
        Objects.requireNonNull(registry, "registry cannot be null");
        this.waitMs = properties.lockWaitMs();
        // Timer measures the single SELECT ... FOR UPDATE round-trip
        // (acquisition only; not the runnable). Records on both success
        // and the timeout-classification path.
        this.acquisitionTimer = Timer.builder("bank.lock.acquisition")
                .description("Wall-clock duration of paired-lock acquisition.")
                .tag("strategy", "db")
                .register(registry);
    }

    public long waitMs() {
        return waitMs;
    }

    @Override
    public void withPairedLocks(AccountNumber a, AccountNumber b, Runnable inTransaction) {
        Objects.requireNonNull(a, "first account cannot be null");
        Objects.requireNonNull(b, "second account cannot be null");
        Objects.requireNonNull(inTransaction, "runnable cannot be null");

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "paired locks require an active transaction (no synchronization registered for thread "
                            + Thread.currentThread().getName() + ")");
        }

        AccountNumber first;
        AccountNumber second;
        List<String> nums;
        if (a.equals(b)) {
            first = a;
            second = a;
            nums = List.of(a.value());
        } else if (a.value().compareTo(b.value()) <= 0) {
            first = a;
            second = b;
            nums = List.of(a.value(), b.value());
        } else {
            first = b;
            second = a;
            nums = List.of(b.value(), a.value());
        }

        ensureDialectDetected();
        setLockTimeout(waitMs);

        String placeholders = nums.size() == 1 ? "?" : "?, ?";
        String sql = "SELECT id FROM account WHERE account_number IN ("
                + placeholders + ") ORDER BY account_number FOR UPDATE";

        Timer.Sample acquisitionSample = Timer.start();
        try {
            jdbcTemplate.query(sql, ps -> {
                for (int i = 0; i < nums.size(); i++) {
                    ps.setString(i + 1, nums.get(i));
                }
            }, rs -> null);
            acquisitionSample.stop(acquisitionTimer);
        } catch (DataAccessException ex) {
            acquisitionSample.stop(acquisitionTimer);
            if (isLockTimeout(ex)) {
                LockAcquisitionTimeoutException lat =
                        new LockAcquisitionTimeoutException(first, second, waitMs);
                // Mark the current Spring transaction as rollback-only so the
                // surrounding transaction manager skips a clean commit attempt.
                // Best-effort: in some test contexts there is no resolvable
                // transaction status to set (it's set via the AOP interceptor),
                // in which case we skip and let Spring's normal rollback path run.
                try {
                    if (TransactionAspectSupport.currentTransactionStatus() != null) {
                        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                    }
                } catch (NoTransactionException ignored) {
                    // not running under TransactionInterceptor — that's fine
                }
                // Best-effort: reset the connection's LOCK_TIMEOUT before propagating
                // so a subsequent ROLLBACK does not also fight against the bound.
                try {
                    setLockTimeout(DEFAULT_LOCK_TIMEOUT_MS);
                } catch (DataAccessException ignored) {
                    // connection may already be in a state that rejects further
                    // statements; the surrounding transaction rollback handles cleanup
                }
                throw lat;
            }
            throw ex;
        }

        // Reset timeout immediately so a pooled connection does not carry our
        // bound into the next user's session, and so any subsequent statement
        // inside inTransaction.run() runs under the H2 default (1000 ms).
        // Avoid SET LOCK_TIMEOUT 0 which means "fail immediately on contention"
        // — that breaks Hibernate's transaction-completion housekeeping.
        setLockTimeout(DEFAULT_LOCK_TIMEOUT_MS);

        log.debug("acquired DB paired locks on {}, {} (waitMs={}, dialect={})",
                first, second, waitMs, dialect);

        inTransaction.run();
        // No afterCompletion hook — the DB releases the row locks on COMMIT/ROLLBACK.
    }

    private void ensureDialectDetected() {
        if (dialect != null) {
            return;
        }
        Boolean unused = jdbcTemplate.execute((java.sql.Connection conn) -> {
            dialect = conn.getMetaData().getDatabaseProductName().toLowerCase();
            return Boolean.TRUE;
        });
    }

    private void setLockTimeout(long ms) {
        if (dialect != null && dialect.contains("postgresql")) {
            // SET LOCAL is transaction-scoped and auto-resets at commit.
            jdbcTemplate.execute("SET LOCAL lock_timeout = '" + ms + "ms'");
        } else {
            // H2 (and best-effort fallback for unknown dialects).
            jdbcTemplate.execute("SET LOCK_TIMEOUT " + ms);
        }
    }

    private static boolean isLockTimeout(DataAccessException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (!(cause instanceof SQLException sql)) {
            return false;
        }
        String state = sql.getSQLState();
        int code = sql.getErrorCode();
        return H2_TIMEOUT_SQLSTATE.equals(state)
                || PG_LOCK_TIMEOUT_SQLSTATE.equals(state)
                || code == H2_LOCK_TIMEOUT_ERROR_CODE;
    }
}
