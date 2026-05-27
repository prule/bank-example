package com.bank.core.bootstrap;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.account.OpenAccountCommand;
import com.bank.core.application.account.OpensAccount;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class SeedDataTest {

    private FakeAccounts accounts;
    private FakeOpensAccount opensAccount;
    private SeedData seedData;
    private SeedPlan plan;

    private static final String CLEARING_NUM = "CLEARING-000";

    @BeforeEach
    public void setUp() {
        accounts = new FakeAccounts();
        opensAccount = new FakeOpensAccount();
        seedData = new SeedData(accounts, opensAccount);

        plan = new SeedPlan();
        plan.setClearingAccountNumber(CLEARING_NUM);
        plan.setClearingAccountOpeningBalance(new BigDecimal("1000.00"));
    }

    @Test
    public void testIdempotencySkipsSeeding() {
        // Seed clearing account ahead of time
        accounts.save(Account.open(CLEARING_NUM, Money.of("1000.00")));

        plan.setCustomers(List.of(new SeedPlan.CustomerSeed("CUST-1", BigDecimal.TEN)));

        SeedReport report = seedData.seed(plan);

        assertTrue(report instanceof SeedReport.Skipped);
        assertEquals("clearing account already present", ((SeedReport.Skipped) report).reason());

        // Verify that no customer opens were triggered
        assertEquals(0, opensAccount.getInvokedCommands().size());
    }

    @Test
    public void testFreshDbSeedingSuccess() {
        plan.setCustomers(List.of(
                new SeedPlan.CustomerSeed("CUST-1", new BigDecimal("100.00")),
                new SeedPlan.CustomerSeed("CUST-2", BigDecimal.ZERO),
                new SeedPlan.CustomerSeed("CUST-3", new BigDecimal("50.00"))
        ));

        SeedReport report = seedData.seed(plan);

        assertTrue(report instanceof SeedReport.Seeded);
        assertEquals(3, ((SeedReport.Seeded) report).customerCount());

        // Verify clearing account was saved directly with full opening balance
        Optional<Account> clearing = accounts.findByNumber(CLEARING_NUM);
        assertTrue(clearing.isPresent());
        assertEquals(Money.of("1000.00"), clearing.get().getBalance());

        // Verify customer opens were called in order
        List<OpenAccountCommand> invoked = opensAccount.getInvokedCommands();
        assertEquals(3, invoked.size());

        assertEquals("CUST-1", invoked.get(0).number());
        assertEquals(Money.of("100.00"), invoked.get(0).openingBalance());

        assertEquals("CUST-2", invoked.get(1).number());
        assertEquals(Money.ZERO, invoked.get(1).openingBalance());

        assertEquals("CUST-3", invoked.get(2).number());
        assertEquals(Money.of("50.00"), invoked.get(2).openingBalance());
    }

    @Test
    public void testSeedingFailurePropagatesLoudly() {
        plan.setCustomers(List.of(
                new SeedPlan.CustomerSeed("CUST-1", new BigDecimal("100.00")),
                new SeedPlan.CustomerSeed("CUST-FAIL", new BigDecimal("200.00")),
                new SeedPlan.CustomerSeed("CUST-3", new BigDecimal("50.00"))
        ));

        opensAccount.setFailOnNumber("CUST-FAIL");

        // Running seed should propagate the exception
        RuntimeException ex = assertThrows(RuntimeException.class, () -> seedData.seed(plan));
        assertEquals("Simulated failure for CUST-FAIL", ex.getMessage());

        // Verify CUST-1 completed but CUST-3 was never invoked (stopped mid-way)
        List<OpenAccountCommand> invoked = opensAccount.getInvokedCommands();
        assertEquals(1, invoked.size());
        assertEquals("CUST-1", invoked.get(0).number());
    }

    // --- Fast, Mockito-free test fakes ---

    private static class FakeAccounts implements Accounts {
        private final Map<String, Account> byNumber = new HashMap<>();

        @Override
        public Optional<Account> findByNumber(String number) {
            return Optional.ofNullable(byNumber.get(number));
        }

        @Override
        public Optional<Account> findById(AccountId id) {
            return Optional.empty();
        }

        @Override
        public Account save(Account account) {
            byNumber.put(account.getNumber(), account);
            return account;
        }
    }

    private static class FakeOpensAccount implements OpensAccount {
        private final List<OpenAccountCommand> invokedCommands = new ArrayList<>();
        private String failOnNumber;

        public void setFailOnNumber(String failOnNumber) {
            this.failOnNumber = failOnNumber;
        }

        public List<OpenAccountCommand> getInvokedCommands() {
            return invokedCommands;
        }

        @Override
        public Account open(OpenAccountCommand command) {
            if (command.number().equals(failOnNumber)) {
                throw new RuntimeException("Simulated failure for " + failOnNumber);
            }
            invokedCommands.add(command);
            return Account.open(command.number(), command.openingBalance());
        }
    }
}
