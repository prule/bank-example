## 1. Application module — value records

- [x] 1.1 Create `application/src/main/java/com/bank/core/application/seed/CustomerSeed.java` as a record `(AccountNumber number, Money openingBalance)`. Compact constructor null-checks both fields with `Objects.requireNonNull`. Class Javadoc: "consumed by `SeedData`; zero opening balance accepted (matches F08's zero-open scenario); negatives impossible by `Money` invariant".
- [x] 1.2 Create `application/src/main/java/com/bank/core/application/seed/ClearingAccountSeed.java` as a record `(AccountNumber number, Money openingBalance)`. Compact constructor null-checks both fields. Compact constructor additionally rejects a zero opening balance: `if (openingBalance.isZero()) throw new IllegalArgumentException("clearing-account opening balance must be strictly positive — seeding exists to fund customers")`. Class Javadoc explains this precondition.
- [x] 1.3 Create `application/src/main/java/com/bank/core/application/seed/SeedPlan.java` as a record `(ClearingAccountSeed clearingAccount, List<CustomerSeed> customers)`. Compact constructor null-checks both fields, then defensively copies the list: `customers = List.copyOf(customers)`. Class Javadoc explains immutability and the consumed-by-`SeedData` contract.
- [x] 1.4 Create `application/src/main/java/com/bank/core/application/seed/SeedReport.java` as a sealed interface permitting two records: `Seeded(AccountNumber clearingAccountNumber, List<AccountNumber> customerAccountNumbers)` and `Skipped(String reason)`. `Seeded`'s compact constructor defensively copies `customerAccountNumbers` via `List.copyOf(...)`. Both records null-check all fields. Class Javadoc names the consumers (`SeedDataRunner` for log routing; tests for assertion).
- [x] 1.5 Add `application/src/test/java/com/bank/core/application/seed/CustomerSeedTest.java` — null `number` rejected, null `openingBalance` rejected, zero opening balance accepted (constructed successfully).
- [x] 1.6 Add `application/src/test/java/com/bank/core/application/seed/ClearingAccountSeedTest.java` — null `number` rejected, null `openingBalance` rejected, `Money.zero()` rejected with the documented message, positive opening balance accepted, accessor round-trip.
- [x] 1.7 Add `application/src/test/java/com/bank/core/application/seed/SeedPlanTest.java` — null `clearingAccount` rejected, null `customers` rejected, defensive copy verified (mutate the source list after construction and assert the plan's view is unchanged), empty customer list accepted.
- [x] 1.8 Add `application/src/test/java/com/bank/core/application/seed/SeedReportTest.java` — sealed-interface `switch` pattern-match round-trip for `Seeded` and `Skipped`; defensive copy on `Seeded.customerAccountNumbers`; null-field rejection on both.

## 2. Application module — `OpensAccount` adapter interface

- [x] 2.1 Create `application/src/main/java/com/bank/core/application/seed/OpensAccount.java` as `@FunctionalInterface public interface OpensAccount { Account open(OpenAccountCommand command); }`. Class Javadoc explains the inversion-of-control rationale: keeps the application module free of Spring annotations and free of `com.bank.core.infrastructure..*` imports while routing every call through F08's transactional facade (`OpenAccountService::open`).
- [x] 2.2 Confirm `grep -RE 'org\.springframework|jakarta\.persistence|com\.fasterxml\.jackson|org\.openapitools' application/src/main/java/com/bank/core/application/seed/` returns zero matches.

## 3. Application module — `SeedData` use case

- [x] 3.1 Create `application/src/main/java/com/bank/core/application/seed/SeedData.java`. Final class. Public constructor `SeedData(Accounts accounts, OpensAccount opensAccount, SeedPlan plan)`. All three params null-checked via `Objects.requireNonNull` with named messages. All three stored as final fields.
- [x] 3.2 Public method `SeedReport seed()`:
  1. If `accounts.findByNumber(plan.clearingAccount().number()).isPresent()`, return `new SeedReport.Skipped("clearing account already present")` immediately.
  2. `Account clearingAccount = Account.open(plan.clearingAccount().number(), plan.clearingAccount().openingBalance())`.
  3. `accounts.save(clearingAccount)`.
  4. `List<AccountNumber> openedCustomers = new ArrayList<>()`.
  5. For each `CustomerSeed customer : plan.customers()` (preserves declared order): call `opensAccount.open(new OpenAccountCommand(customer.number(), customer.openingBalance()))`; add `customer.number()` to `openedCustomers`. (If `opensAccount.open(...)` throws, the exception propagates without modification — the runner translates it to a log + abort.)
  6. Return `new SeedReport.Seeded(plan.clearingAccount().number(), List.copyOf(openedCustomers))`.
- [x] 3.3 Class-level Javadoc names: (a) the clearing-account pre-check as the sole idempotency guard; (b) per-customer (not whole-plan) atomicity inherited from F08; (c) the design.md decision that the clearing account is created via `Account.open + accounts.save` rather than through F08 (otherwise the F08 `ClearingAccountMissingException` precondition would block the first open).
- [x] 3.4 Add `application/src/test/java/com/bank/core/application/seed/SeedDataTest.java`. JUnit 5, Mockito `@ExtendWith`. Construct the use case with mocked `Accounts` and a mocked `OpensAccount`. Fixed `AccountNumber CLEARING = AccountNumber.of("CLEARING-000")` and three customer numbers.
- [x] 3.5 Test `freshDb_savesClearingFirstThenOpensCustomersInOrder`:
  - Stub `accounts.findByNumber(CLEARING)` returns empty.
  - Stub `opensAccount.open(...)` for each customer returns a freshly opened Active account at the funded balance.
  - Call `seed()`.
  - Verify `accounts.save(...)` called exactly once with an `Account` whose number equals CLEARING and balance equals the configured clearing-account opening balance (`Account.open(...)` always yields Active so status is checked too).
  - Verify `opensAccount.open(...)` called exactly N times (one per customer) in the declared order, each with a `OpenAccountCommand` whose `number()` matches the corresponding `CustomerSeed.number()` and `openingBalance()` matches.
  - Verify the return value is a `SeedReport.Seeded` with `clearingAccountNumber == CLEARING` and `customerAccountNumbers` equal in order to the configured list.
- [x] 3.6 Test `reRun_clearingPresent_returnsSkipped_withoutAnyWritesOrCustomerOpens`:
  - Stub `accounts.findByNumber(CLEARING)` returns an Active aggregate.
  - Call `seed()`.
  - Verify `accounts.save(...)` never invoked; `opensAccount.open(...)` never invoked.
  - Assert returned report is `SeedReport.Skipped` with reason `"clearing account already present"`.
- [x] 3.7 Test `failureMidwayOnCustomer_propagatesException_priorCustomersAlreadyOpened`:
  - Stub `accounts.findByNumber(CLEARING)` returns empty.
  - Stub `opensAccount.open(...)` for the first customer returns an account; for the second customer throws (e.g. `RuntimeException("simulated F06 failure")`).
  - Call `seed()` and assert the thrown exception is the simulated one (not wrapped).
  - Verify the first customer's `opensAccount.open(...)` was invoked exactly once before the failure (capture argument order to confirm).
  - Verify subsequent (third) customer's `opensAccount.open(...)` was never invoked.
- [x] 3.8 Test `customerNumberCollidesWithClearingNumber_propagatesDuplicateAccountException`:
  - Stub `accounts.findByNumber(CLEARING)` returns empty (clearing is created fresh).
  - Stub `opensAccount.open(...)` for the colliding customer throws `DuplicateAccountNumberException(CLEARING)` (simulating F08's reaction to the now-existing clearing row).
  - Call `seed()` and assert the thrown exception is the `DuplicateAccountNumberException`.
  - Verify the clearing-account `accounts.save(...)` ran exactly once.
- [x] 3.9 Test `constructor_rejectsNullArgs` — `Accounts`, `OpensAccount`, `SeedPlan` each null-rejected with matching messages.
- [x] 3.10 Confirm `grep -RE 'org\.springframework|jakarta\.persistence|com\.fasterxml\.jackson|org\.openapitools' application/src/main/java/com/bank/core/application/seed/` still returns zero matches.

## 4. Infrastructure module — `SeedProperties` DTO

- [x] 4.1 Create `infrastructure/src/main/java/com/bank/core/infrastructure/seed/SeedProperties.java` as a record `@ConfigurationProperties("bank.seed") public record SeedProperties(boolean enabled, String clearingAccountNumber, BigDecimal clearingAccountOpeningBalance, List<CustomerSeedProperty> customers) { ... }`. Defaults via the compact constructor: `enabled` defaults to the constructor argument (Spring binder supplies `false` when absent); `clearingAccountNumber` defaults to `null` (the plan factory falls back to `bank.clearing-account.number` when null); `clearingAccountOpeningBalance` defaults to `new BigDecimal("100000.00")` when null; `customers` defaults to `List.of()` when null (Spring's binder hands `null` for an absent list).
- [x] 4.2 Inside `SeedProperties`, declare nested record `public record CustomerSeedProperty(String number, BigDecimal openingBalance) {}` — no constraints (validated by `AccountNumber.of(...)` and `Money.of(...)` at plan-construction time).
- [x] 4.3 Class-level Javadoc explains: the DTO is a dumb data carrier; null-safe defaults applied here; all domain-level validation (positive money, non-blank account numbers) happens when `SeedPlan` and its records are constructed in `BankCoreApplication.seedPlan(...)`.
- [x] 4.4 Add `infrastructure/src/test/java/com/bank/core/infrastructure/seed/SeedPropertiesTest.java` — explicit constructor with all null sub-fields produces the documented defaults; explicit non-null values round-trip unchanged; defensive copy on the `customers` list is NOT required here because the DTO is reconstructed from properties on every refresh.

## 5. Infrastructure module — `SeedDataRunner`

- [x] 5.1 Create `infrastructure/src/main/java/com/bank/core/infrastructure/seed/SeedDataRunner.java`. Annotations: `@Component`, `@ConditionalOnProperty(name = "bank.seed.enabled", havingValue = "true")`. Implements `org.springframework.boot.ApplicationRunner`.
- [x] 5.2 Field: `private static final Logger LOG = LoggerFactory.getLogger(SeedDataRunner.class)`. Constructor injects a single `SeedData seedData` (null-checked).
- [x] 5.3 Public method `void run(ApplicationArguments args) throws Exception`:
  1. Call `SeedReport report = seedData.seed()`.
  2. Pattern-match the sealed report:
     - `SeedReport.Seeded seeded` → `LOG.info("dev seed complete: clearing={} customers={} (count={})", seeded.clearingAccountNumber().value(), seeded.customerAccountNumbers().stream().map(AccountNumber::value).toList(), seeded.customerAccountNumbers().size())`.
     - `SeedReport.Skipped skipped` → `LOG.info("dev seed skipped: {}", skipped.reason())`.
  3. Do NOT wrap `seedData.seed()` in `try / catch`. Any exception propagates out of `run(...)` so Spring Boot aborts startup (matches the spec's "loud failure" scenario). Before propagation, log one ERROR line via a thin wrapper that catches, logs, and rethrows: `try { ... } catch (RuntimeException ex) { LOG.error("dev seed failed: {} ({}: {})", probableFailingNumber(ex), ex.getClass().getSimpleName(), ex.getMessage()); throw ex; }`. `probableFailingNumber(ex)` extracts an `AccountNumber` from the exception when available (`DuplicateAccountNumberException`, `ClearingAccountMissingException`, `InsufficientFundsException`); otherwise returns `"<unknown>"`.
- [x] 5.4 Class-level Javadoc covers: `ApplicationRunner` chosen over `@PostConstruct` for proper proxy lifecycle (cite design.md Decision 1); `@ConditionalOnProperty` gates the bean so production is a strict no-op (Decision 2); per-customer atomicity is inherited from F08, not introduced here (Decision 3); the runner is the single integration point between seeding and Spring Boot's lifecycle.
- [x] 5.5 Add `infrastructure/src/test/java/com/bank/core/infrastructure/seed/SeedDataRunnerTest.java`:
  - Test `runWithSeededReport_emitsSingleInfoLine` — mock `SeedData.seed()` returns a `SeedReport.Seeded` with two customer numbers; call `runner.run(null)`; assert the captured INFO log contains exactly one line matching `dev seed complete: clearing=CLEARING-000 customers=[CUST-A, CUST-B] (count=2)`.
  - Test `runWithSkippedReport_emitsSingleSkipInfoLine` — mock returns `Skipped("clearing account already present")`; assert exactly one INFO line `dev seed skipped: clearing account already present`.
  - Test `runWithFailingSeed_logsErrorAndRethrows` — mock `SeedData.seed()` throws `DuplicateAccountNumberException(AccountNumber.of("CUST-X"))`; call `runner.run(null)` inside `assertThrows`; assert one ERROR line whose message contains `CUST-X` and `DuplicateAccountNumberException`; assert the thrown exception is the same instance.
  - Test `propertyName_isLiteral_bankSeedEnabled` — reflect on the class: `SeedDataRunner.class.getAnnotation(ConditionalOnProperty.class)`; assert `name()` equals `new String[]{"bank.seed.enabled"}` and `havingValue()` equals `"true"`. Guards against a refactor typo silently disabling seeding.

## 6. Bootstrap module — `SEED_DATA` environment-variable alias

- [x] 6.1 Create `bootstrap/src/main/java/com/bank/core/seed/SeedDataEnvironmentPostProcessor.java` implementing `org.springframework.boot.env.EnvironmentPostProcessor`. In `postProcessEnvironment(...)`, read `System.getenv("SEED_DATA")`; if present and the lowercase value parses as a boolean (`"true"` or `"false"`), add a low-precedence `MapPropertySource` named `seedDataAlias` that maps `"bank.seed.enabled"` to the parsed boolean. Use `environment.getPropertySources().addLast(...)` so any explicit `application*.yaml` value wins.
- [x] 6.2 Register the post-processor in `bootstrap/src/main/resources/META-INF/spring.factories` under key `org.springframework.boot.env.EnvironmentPostProcessor`. (If the file does not yet exist, create it with the single key/value.)
- [x] 6.3 Add `bootstrap/src/test/java/com/bank/core/seed/SeedDataEnvironmentPostProcessorTest.java`:
  - `seedDataTrue_setsBankSeedEnabledTrue` — construct an environment, simulate `SEED_DATA=true`, invoke the post-processor, assert `environment.getProperty("bank.seed.enabled", Boolean.class)` is `true`.
  - `seedDataFalse_setsBankSeedEnabledFalse` — similar with `false`.
  - `seedDataUnset_addsNoPropertySource` — assert no property source named `seedDataAlias` is registered.
  - `explicitYamlWinsOverAlias` — pre-load a higher-precedence source `application.yaml` setting `bank.seed.enabled=false`; simulate `SEED_DATA=true`; assert the resolved property is `false`.
  - Use a `MockEnvironment` plus a `Map`-based stand-in for `System.getenv` (inject via a small protected method on the post-processor that the test overrides — keeps the class easy to test without a real env-var change).

## 7. Bootstrap module — wiring

- [x] 7.1 Modify `bootstrap/src/main/java/com/bank/core/BankCoreApplication.java`. Add `SeedProperties.class` to the existing `@EnableConfigurationProperties({TransferLockingProperties.class, SeedProperties.class})` array.
- [x] 7.2 Add `@Bean @ConditionalOnProperty(name = "bank.seed.enabled", havingValue = "true") SeedPlan seedPlan(SeedProperties props, @Value("${bank.clearing-account.number}") String fallbackClearingNumber)`:
  - Resolve clearing-account number: `String resolved = (props.clearingAccountNumber() != null && !props.clearingAccountNumber().isBlank()) ? props.clearingAccountNumber() : fallbackClearingNumber`.
  - Construct `ClearingAccountSeed clearingSeed = new ClearingAccountSeed(AccountNumber.of(resolved), Money.of(props.clearingAccountOpeningBalance()))` (the records' compact constructors enforce positive money / non-blank number).
  - Map `props.customers()` to a `List<CustomerSeed>` via `new CustomerSeed(AccountNumber.of(p.number()), Money.of(p.openingBalance()))` per entry.
  - Return `new SeedPlan(clearingSeed, customers)`.
- [x] 7.3 Add `@Bean @ConditionalOnProperty(name = "bank.seed.enabled", havingValue = "true") SeedData seedData(Accounts accounts, OpenAccountService openAccountService, SeedPlan plan)`:
  - Return `new SeedData(accounts, openAccountService::open, plan)` — the method reference `openAccountService::open` satisfies the `OpensAccount` functional interface and routes every call through F08's `@Transactional` facade.
- [x] 7.4 Class-level Javadoc on the two new `@Bean` methods cites design.md: Decision 5 (`OpensAccount` adapter) for the method-reference choice; Decision 2 (`@ConditionalOnProperty`) for why every seed bean is gated, not just the runner.

## 8. Configuration files

- [x] 8.1 Modify `bootstrap/src/main/resources/application.yaml`. Under the existing top-level `bank:` key, append a `seed:` block. Set `enabled: false` (explicit production-safe default). Add a multi-line YAML comment above the block explaining: (a) the alias from `SEED_DATA`; (b) the precedence rule (explicit yaml wins); (c) the `dev` profile overrides this to `true`; (d) the customer plan structure is documented in `application-dev.yaml`. Do NOT populate `customers:` here — the empty/absent list is correct for production.
- [x] 8.2 Modify `bootstrap/src/main/resources/application-dev.yaml`. Append a `bank.seed:` block with `enabled: true`, `clearingAccountOpeningBalance: 100000.00`, and `customers:` list containing the three default entries `[{number: "CUST-1001", openingBalance: "500.00"}, {number: "CUST-1002", openingBalance: "250.00"}, {number: "CUST-1003", openingBalance: "0.00"}]`. Add a YAML comment naming the three intentional shapes (large funded, small funded, zero-open) so a future editor knows why each entry exists.
- [x] 8.3 Confirm `bootstrap/src/main/resources/application-test.yaml` does NOT yet exist (per current state); if any later change introduces it, the seed defaults must remain `enabled: false` for the test profile so individual tests opt in via `@TestPropertySource`.

## 9. Integration tests — seeding ON

- [x] 9.1 Create `bootstrap/src/test/java/com/bank/core/seed/SeedDataRunnerIntegrationTest.java` annotated `@SpringBootTest(properties = {"bank.seed.enabled=true", "bank.seed.clearingAccountOpeningBalance=1000.00", "bank.seed.customers[0].number=CUST-9001", "bank.seed.customers[0].openingBalance=100.00", "bank.seed.customers[1].number=CUST-9002", "bank.seed.customers[1].openingBalance=50.00", "bank.seed.customers[2].number=CUST-9003", "bank.seed.customers[2].openingBalance=0.00"})` with `@ActiveProfiles("test")`.
- [x] 9.2 Inject `Accounts`, `JdbcTemplate` (for raw row counts), and `SeedDataRunner`.
- [x] 9.3 Test `afterContextStarts_databaseReflectsConfiguredPlanExactly`:
  - Assert `accounts.findByNumber(CLEARING_NUMBER).get().balance()` equals `Money.of("850.00")` (1000 - 100 - 50 - 0).
  - Assert each customer exists Active at the configured balance.
  - Assert `journal_entry` row count is exactly 2 (one per positive-balance customer; the zero-balance customer creates no journal).
  - Assert `ledger_movement` row count is exactly 4 (2 per journal).
  - Assert the ledger movements for each journal have one DEBIT against the clearing account's id and one CREDIT against the customer's id, both at the customer's opening balance.
- [x] 9.4 Test `secondInvocation_skipsAndProducesNoNewRows`:
  - Capture pre-state row counts after context start.
  - Invoke `seedDataRunner.run(null)` a second time manually.
  - Assert post-second-invocation row counts equal pre-state counts for `account`, `journal_entry`, `ledger_movement`.
  - Capture the second-invocation log (use a Logback `ListAppender` attached to `SeedDataRunner`'s logger): assert exactly one INFO line matching `dev seed skipped: clearing account already present`.

## 10. Integration tests — seeding OFF

- [x] 10.1 Create `bootstrap/src/test/java/com/bank/core/seed/SeedDataOffIntegrationTest.java` annotated `@SpringBootTest` with `@ActiveProfiles("test")` and explicit `properties = {"bank.seed.enabled=false"}` (no other seed props).
- [x] 10.2 Inject `ApplicationContext` and `JdbcTemplate`.
- [x] 10.3 Test `seedBeansAreAbsent`:
  - Assert `context.getBeansOfType(SeedData.class)` is empty.
  - Assert `context.getBeansOfType(SeedDataRunner.class)` is empty.
  - Assert `context.getBeansOfType(SeedPlan.class)` is empty.
- [x] 10.4 Test `databaseRemainsEmpty`:
  - Assert `jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account", Integer.class)` equals `0`.
  - Assert `jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entry", Integer.class)` equals `0`.
- [x] 10.5 Test `noDevSeedLogLinesProducedDuringStartup`:
  - Attach a `ListAppender` to the seed-package logger root (`com.bank.core.infrastructure.seed`) before context start (use a `ApplicationContextInitializer` or a static `@BeforeAll` that installs the appender on Logback's `LoggerContext`).
  - Assert no captured log event's message contains the substring `dev seed`.

## 11. Integration test — failure semantics

- [x] 11.1 Create `bootstrap/src/test/java/com/bank/core/seed/SeedDataFailureIntegrationTest.java`. Use `@SpringBootTest(properties = {"bank.seed.enabled=true", "bank.seed.clearingAccountOpeningBalance=10.00", "bank.seed.customers[0].number=CUST-OK", "bank.seed.customers[0].openingBalance=5.00", "bank.seed.customers[1].number=CUST-FAIL", "bank.seed.customers[1].openingBalance=100.00", "bank.seed.customers[2].number=CUST-NEVER", "bank.seed.customers[2].openingBalance=1.00"})` with `@ActiveProfiles("test")`.
- [x] 11.2 Because `ApplicationRunner` failure aborts startup, this test must NOT use `@SpringBootTest`'s normal lifecycle. Instead annotate the test class with `@SpringBootTest(...) ` and override the `@Test` method to call `SpringApplication.run(...)` manually inside `assertThrows(Throwable.class, ...)` against a programmatic configuration. Alternative: declare the test in `bootstrap/src/test/java` and run it via `@ApplicationContextFailureExpected`-style helper. Pick whichever pattern is simplest given Spring Boot 3.x. Document the chosen pattern in the test's class-level Javadoc.
- [x] 11.3 Assert the thrown root cause is `InsufficientFundsException` (or whatever F06 throws when the clearing-account debit fails — confirm the class name from F06's spec/code) and the message references `CUST-FAIL`.
- [x] 11.4 Use a `ListAppender` attached to `SeedDataRunner`'s logger captured before startup to assert exactly one ERROR log line whose message contains `CUST-FAIL` and `InsufficientFundsException`.
- [x] 11.5 After the failed startup, separately bootstrap a minimal `JdbcTemplate` against the same H2 URL (`jdbc:h2:mem:bankcore;DB_CLOSE_DELAY=-1`) and assert: one row for the clearing account at balance `5.00` (10 − 5, committed before CUST-FAIL); one row for `CUST-OK` Active at `5.00`; zero rows for `CUST-FAIL` and `CUST-NEVER`; one row in `journal_entry`; two rows in `ledger_movement`.

## 12. ArchUnit / boundary verification

- [x] 12.1 Confirm the F00 ArchUnit suite still passes:
  - `applicationHasNoFrameworkDependencies` — assert no class under `com.bank.core.application..` imports `org.springframework..*`, `jakarta.persistence..*`, `com.fasterxml..*`, or `org.openapitools..*` (the existing rule covers the new `com.bank.core.application.seed` package automatically).
  - `domainIsJdkOnly` — F09 adds no domain types, so this passes trivially.
  - `webIsConfinedToWebPackage` — F09 introduces no web classes; passes trivially.
  - `entityIsConfinedToPersistencePackage` — F09 introduces no JPA entities; passes trivially.
- [x] 12.2 Add one targeted ArchUnit test in `bootstrap/src/test/java/com/bank/core/seed/SeedArchUnitTest.java` (or augment the existing F00 boundary test class): assert that `SeedDataRunner` resides under `com.bank.core.infrastructure.seed` and that `SeedProperties` resides there too — the F09 spec's "seed beans live in infrastructure" boundary is enforced rather than just documented.

## 13. Manual smoke test and end-of-change verification

- [x] 13.1 Run the full Gradle build: `./gradlew clean build`. All new unit tests, integration tests, and ArchUnit tests pass; the F00 ArchUnit suite still passes; no new Gradle dependency is required.
- [ ] 13.2 Run `./gradlew :bootstrap:bootRun --args='--spring.profiles.active=dev'`. Confirm the captured stdout contains exactly one line matching `dev seed complete: clearing=CLEARING-000 customers=[CUST-1001, CUST-1002, CUST-1003] (count=3)`. Hit `http://localhost:8080/api/v1/accounts/CUST-1001` and assert HTTP 200 with balance `500.00`.
- [ ] 13.3 Run `./gradlew :bootstrap:bootRun` (no profile, no env var). Confirm the captured stdout contains NO line whose message contains `dev seed`. Hit `http://localhost:8080/api/v1/accounts/CUST-1001` and assert HTTP 404 (the canonical not-found envelope, per `api-error-contract`).
- [ ] 13.4 Run `SEED_DATA=true ./gradlew :bootstrap:bootRun`. Confirm `dev seed complete` appears once; restart immediately (Ctrl-C, re-run with the same in-memory H2 — note H2 in-memory does NOT persist across JVM restarts, so this step actually demonstrates a fresh seed each time; to test idempotency, use `--spring.datasource.url=jdbc:h2:file:./build/h2-smoke` to give H2 a file-backed URL and restart, then confirm the second start emits `dev seed skipped: clearing account already present`).
- [x] 13.5 Run `openspec change validate dev-data-seeding --strict`. Confirm a clean `Change "dev-data-seeding" is valid` line and no warnings.
- [x] 13.6 Run `openspec validate --specs dev-data-seeding`. Confirm the modified spec validates against the OpenSpec schema (every `### Requirement:` has at least one `#### Scenario:`, every scenario has `WHEN`/`THEN`).
