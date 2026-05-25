package com.bank.core.concurrency;

import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.infrastructure.concurrency.DbAccountLocker;
import com.bank.core.infrastructure.concurrency.JvmAccountLocker;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code bank.transfer.locker} property-based bean selection:
 * exactly one of {@link JvmAccountLocker} or {@link DbAccountLocker} is
 * constructed per Spring context based on the configured strategy.
 *
 * <p>Each nested class runs against its own Spring context (Spring caches
 * contexts per unique {@code @TestPropertySource}), so the two strategies
 * cannot pollute one another.
 */
class LockerStrategyWiringTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = "bank.transfer.locker=jvm")
    class JvmStrategyTest {

        @Autowired AccountLocker locker;
        @Autowired ApplicationContext context;

        @Test
        void jvmLockerIsTheOnlyAccountLockerBean() {
            assertThat(locker).isInstanceOf(JvmAccountLocker.class);
            assertThat(context.getBeansOfType(JvmAccountLocker.class)).hasSize(1);
            assertThat(context.getBeansOfType(DbAccountLocker.class)).isEmpty();
            assertThat(context.getBeansOfType(AccountLocker.class)).hasSize(1);
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "bank.transfer.locker=db",
            "spring.datasource.url=jdbc:h2:mem:bankcore-strategy-wire-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
    })
    class DbStrategyTest {

        @Autowired AccountLocker locker;
        @Autowired ApplicationContext context;

        @Test
        void dbLockerIsTheOnlyAccountLockerBean() {
            assertThat(locker).isInstanceOf(DbAccountLocker.class);
            assertThat(context.getBeansOfType(DbAccountLocker.class)).hasSize(1);
            assertThat(context.getBeansOfType(JvmAccountLocker.class)).isEmpty();
            assertThat(context.getBeansOfType(AccountLocker.class)).hasSize(1);
        }
    }
}
