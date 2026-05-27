## Context

During local development and testing, starting with an empty database requires manual interaction to populate initial accounts. This design establishes an automated, configuration-driven, and environment-gated database seeder that runs at startup to establish a genesis clearing account and sequentially open customer accounts.

## Goals / Non-Goals

**Goals:**
- **Environment-Gated Construction**: Ensure that all seeding components (`SeedPlan`, `SeedData`, `SeedDataRunner`) are only registered in the Spring context if `bank.seed.enabled` is `true`.
- **Low-Precedence Alias**: Bind the environment variable/alias `SEED_DATA` to `bank.seed.enabled` with low precedence using a bootstrap `EnvironmentPostProcessor`.
- **Idempotency**: Prevent duplicate seeding runs by checking for the presence of the clearing account row.
- **Loud Partial Failures**: Abort startup and log a clear error when any customer seeding fails, while preserving any customer accounts created earlier in the sequence.
- **Framework Separation**: Coordinate customer creations through the Spring-free `OpenAccount` use case adapted via a clean `OpensAccount` interface.

**Non-Goals:**
- Exposing a web endpoint to trigger seeding at runtime.
- Whole-plan atomicity (individual customer creations are committed incrementally).

## Decisions

### 1. Spring EnvironmentPostProcessor for SEED_DATA
We will implement a custom `EnvironmentPostProcessor` to alias `SEED_DATA` to `bank.seed.enabled`.
- *Rationale*: Allows developers to set `SEED_DATA=true` in the environment to trigger seeding, while adding it to the end of the property sources (`addLast()`) ensures it acts as a low-precedence default that can be overridden by explicit property files.
- *Registration*: Registered in `META-INF/spring.factories` under `org.springframework.boot.env.EnvironmentPostProcessor`.

### 2. Configuration Properties and Validation
We will use `@ConfigurationProperties("bank.seed")` to populate a `SeedPlan` instance:
- `clearingAccountNumber` will fall back to `bank.clearing-account.number` or `CLEARING-000`.
- `clearingAccountOpeningBalance` defaults to `100000.00`.
- If `clearingAccountOpeningBalance` is `<= 0`, the constructor will throw an `IllegalArgumentException` with the required error message to abort startup.

### 3. Incremental Per-Account Transactions
The seeder will execute steps sequentially:
1. Save the clearing account aggregate directly using `accounts.save(clearingAccount)`. This bypasses standard transfer pathways as it represents the genesis balance creation.
2. Iterate through each customer seed, invoking `OpensAccount.open(...)`. Each invocation runs inside its own database transaction (provided by `OpenAccountService`'s `@Transactional`), ensuring that previously processed customers commit even if a subsequent customer fails.

### 4. Bypassing Mockito for JDK 25 Test Compatibility
To verify our requirements cleanly, we will design our unit and integration tests using clean hand-coded fakes and stubs where Mockito fails under JDK 25.

## Risks / Trade-offs

- **[Risk] Clearing Account Balance Exhaustion**: A customer seed list could exceed the clearing account's opening balance, causing `InsufficientFundsException`.
- *Mitigation*: The seeder will propagate this exception loudly, aborting startup and leaving the partially seeded state committed so that operators can inspect the database and correct the seed plan.
