package com.bank.core.persistence.transfer;

import org.springframework.test.context.TestPropertySource;

/**
 * Re-runs every scenario in {@link TransferAtomicityIntegrationTest} against
 * the database-backed locker strategy. Inherits all test methods unchanged —
 * the only difference is the {@code bank.transfer.locker=db} property and a
 * per-class unique H2 URL so row locks acquired here do not contend with
 * other integration tests sharing the JVM-wide {@code bankcore-test} instance.
 *
 * <p>The point of this test is to prove the strategy switch is a transparent
 * swap for F06: every atomicity guarantee the parent class asserts (commit
 * happy path, rollback on insufficient funds, rollback on inactive source,
 * etc.) must hold equally under the DB locker.
 */
@TestPropertySource(properties = {
        "bank.transfer.locker=db",
        "spring.datasource.url=jdbc:h2:mem:bankcore-dblock-atomic-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
class TransferAtomicityIntegrationDbLockerTest extends TransferAtomicityIntegrationTest {
}
