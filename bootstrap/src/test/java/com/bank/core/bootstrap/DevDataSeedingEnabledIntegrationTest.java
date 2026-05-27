package com.bank.core.bootstrap;

import com.bank.core.application.account.Accounts;
import com.bank.core.domain.*;
import com.bank.core.infrastructure.persistence.account.AccountEntity;
import com.bank.core.infrastructure.persistence.account.AccountRepository;
import com.bank.core.infrastructure.persistence.ledger.JournalEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "bank.seed.enabled=true",
        "bank.seed.clearingAccountNumber=CLEARING-000",
        "bank.seed.clearingAccountOpeningBalance=1000.00",
        "bank.seed.customers[0].number=CUST-9001",
        "bank.seed.customers[0].openingBalance=100.00",
        "bank.seed.customers[1].number=CUST-9002",
        "bank.seed.customers[1].openingBalance=50.00",
        "bank.seed.customers[2].number=CUST-ZERO",
        "bank.seed.customers[2].openingBalance=0.00"
})
@ActiveProfiles("test")
public class DevDataSeedingEnabledIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private SeedPlan seedPlan;

    @Autowired
    private SeedData seedData;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private Accounts accounts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String CLEARING_NUM = "CLEARING-000";

    @Test
    public void testSeedingSuccessOnStartup() {
        // Assert that all seeding beans are present when switch is ON
        assertThat(applicationContext.getBeansOfType(SeedPlan.class)).isNotEmpty();
        assertThat(applicationContext.getBeansOfType(SeedData.class)).isNotEmpty();
        assertThat(applicationContext.getBeansOfType(SeedDataRunner.class)).isNotEmpty();

        // Verify database state populated from the properties
        AccountEntity clearing = accountRepository.findByAccountNumber(CLEARING_NUM).orElseThrow();
        AccountEntity cust1 = accountRepository.findByAccountNumber("CUST-9001").orElseThrow();
        AccountEntity cust2 = accountRepository.findByAccountNumber("CUST-9002").orElseThrow();
        AccountEntity custZero = accountRepository.findByAccountNumber("CUST-ZERO").orElseThrow();

        // 1000.00 - 100.00 - 50.00 = 850.00
        assertThat(clearing.getBalance()).isEqualByComparingTo(new BigDecimal("850.00"));
        assertThat(cust1.getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(cust2.getBalance()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(custZero.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // Verify active status
        assertThat(clearing.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(cust1.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(cust2.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(custZero.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        // Verify two journal entries generated (since CUST-ZERO has zero opening balance and has no journal entry)
        assertThat(journalEntryRepository.count()).isEqualTo(2);

        int movementsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_movement", Integer.class);
        assertThat(movementsCount).isEqualTo(4); // 2 per funded customer
    }

    @Test
    public void testSecondStartIdempotencySkip() {
        // Trigger seed execution again simulating a second startup restart
        SeedReport report = seedData.seed(seedPlan);

        assertThat(report).isInstanceOf(SeedReport.Skipped.class);
        assertThat(((SeedReport.Skipped) report).reason()).isEqualTo("clearing account already present");

        // Verify that database row counts remain exactly the same
        assertThat(journalEntryRepository.count()).isEqualTo(2);
        int movementsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_movement", Integer.class);
        assertThat(movementsCount).isEqualTo(4);
    }

    @Test
    public void testPartialFailureAndIncrementalRollback() {
        // Wipe the database completely to perform isolated seeding run
        jdbcTemplate.execute("DELETE FROM ledger_movement");
        jdbcTemplate.execute("DELETE FROM journal_entry");
        jdbcTemplate.execute("DELETE FROM account");

        // Construct a plan that will fail during customer B opening due to Insufficient Funds
        SeedPlan customPlan = new SeedPlan();
        customPlan.setClearingAccountNumber(CLEARING_NUM);
        customPlan.setClearingAccountOpeningBalance(new BigDecimal("10.00"));
        customPlan.setCustomers(List.of(
                new SeedPlan.CustomerSeed("CUST-A", new BigDecimal("5.00")),
                new SeedPlan.CustomerSeed("CUST-B", new BigDecimal("100.00")), // Will fail (insufficient funds)
                new SeedPlan.CustomerSeed("CUST-C", new BigDecimal("1.00"))     // Will never be reached
        ));

        // Attempt seeding and assert InsufficientFundsException is thrown loudly
        assertThrows(InsufficientFundsException.class, () -> seedData.seed(customPlan));

        // Verify that clearing account is successfully seeded and debited to 5.00
        AccountEntity clearing = accountRepository.findByAccountNumber(CLEARING_NUM).orElseThrow();
        assertThat(clearing.getBalance()).isEqualByComparingTo(new BigDecimal("5.00"));

        // Verify CUST-A is committed and exists
        AccountEntity custA = accountRepository.findByAccountNumber("CUST-A").orElseThrow();
        assertThat(custA.getBalance()).isEqualByComparingTo(new BigDecimal("5.00"));

        // Verify CUST-B failed and rolled back (does not exist in database)
        assertThat(accountRepository.findByAccountNumber("CUST-B")).isEmpty();

        // Verify CUST-C was never processed (does not exist in database)
        assertThat(accountRepository.findByAccountNumber("CUST-C")).isEmpty();

        // Verify exactly one journal entry exists (for CUST-A)
        assertThat(journalEntryRepository.count()).isEqualTo(1);
    }
}
