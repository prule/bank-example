## 1. Environment and Configuration Alias

- [x] 1.1 Implement a custom `EnvironmentPostProcessor` in the `:bootstrap` module to map `SEED_DATA` to the property `bank.seed.enabled` as a low-precedence alias.
- [x] 1.2 Register the post-processor in `/bootstrap/src/main/resources/META-INF/spring.factories`.

## 2. Decoupled Adapter and Configuration Properties

- [x] 2.1 Define the `OpensAccount` interface in `:bootstrap` (or `:application`) to decouple the seeding module from direct framework facade references.
- [x] 2.2 Implement the `SeedPlan` configuration bean class, populated via `@ConfigurationProperties("bank.seed")`, ensuring that a zero or negative `clearingAccountOpeningBalance` throws `IllegalArgumentException` with the specific message.

## 3. Seeding Runner and Core Logic

- [x] 3.1 Implement the `SeedData` core class with the sequential database-seeding logic, utilizing `Accounts` and `OpensAccount` to save the clearing account directly and fund customer accounts sequentially.
- [x] 3.2 Implement `SeedDataRunner` as an `ApplicationRunner` bean inside `:bootstrap` conditional on `@ConditionalOnProperty(name = "bank.seed.enabled", havingValue = "true")`. Ensure it propagates exceptions loudly and logs status messages properly.

## 4. Verification and Testing

- [x] 4.1 Write comprehensive unit tests covering `SeedPlan` validation, environment post-processor relaxed binding, and `SeedData` sequential/idempotency flows using fast, Mockito-free test fakes.
- [x] 4.2 Write full integration tests in `:bootstrap` to verify configuration gating, fresh database seeding, idempotency skips, and per-account rollback on partial seed failures.
