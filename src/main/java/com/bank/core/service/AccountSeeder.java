package com.bank.core.service;

import com.bank.core.domain.Account;
import com.bank.core.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
// Only instantiates this bean if the environment variable SEED_DATA=true
@ConditionalOnProperty(name = "app.features.seed-data", havingValue = "true")
public class AccountSeeder implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(AccountSeeder.class);
  private final AccountRepository accountRepository;
  private final AccountOpeningService accountOpeningService;

  public AccountSeeder(
      AccountRepository accountRepository, AccountOpeningService accountOpeningService) {
    this.accountRepository = accountRepository;
    this.accountOpeningService = accountOpeningService;
  }

  @Override
  public void run(String... args) throws Exception {
    log.info(
        "SEED_DATA environment variable detected as active. Initializing bank core data seeding...");

    if (accountRepository.count() == 0) {

      Account clearingAccount =
          new Account(
              UUID.randomUUID(),
              AccountOpeningService.CLEARING_ACCOUNT_NUM,
              new BigDecimal("10000000"));

      accountRepository.save(clearingAccount);

      Account devAccount1 =
          accountOpeningService.openNewAccount("ACC-1111", new BigDecimal("5000.00"));
      Account devAccount2 =
          accountOpeningService.openNewAccount("ACC-2222", new BigDecimal("250.50"));

      log.info("Successfully seeded 2 development accounts.");
      log.info(devAccount1.toString());
      log.info(devAccount2.toString());
    } else {
      log.info("Database already contains data. Skipping seeding phase to prevent duplicates.");
    }
  }
}
