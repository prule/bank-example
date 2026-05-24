## Why

F08 (`account-opening`) just shipped — `OpenAccountService.open(...)` now creates an `ACTIVE` account at zero and (for a positive opening balance) funds it via exactly one F06 transfer from the bank's well-known clearing account, all inside a single `@Transactional` boundary. The clearing-account number was made configurable in that change (`bank.clearing-account.number`, default `CLEARING-000`) precisely so F09 can seed the same row F08 funds from with no risk of name drift. Every other piece F09 needs is already in place: the `Accounts` port has `save(...)` (F05), `Account.open(...)` produces a zero-balance Active aggregate (F01), and `Money.of(...)` rejects negatives by construction (F01). F09 is a composition change, not a new primitive.

F09 is the next slot in the manifest's `[F08, F09]` build group. It introduces the env-gated startup runner that, on a fresh database, materialises the clearing account directly and then opens a configurable set of customer accounts through the F08 pipeline so each customer account is funded by a real journal entry from the clearing account. With F09 in place, a developer who runs the app with `SEED_DATA=true` immediately has a populated database to hit `GET /api/v1/accounts/{n}` and `POST /api/v1/transfers` against, and the integration test suite can exercise the full F05/F06/F08 stack end-to-end without bespoke setup. With `SEED_DATA` unset (the default — including production and CI), the seeder is silent: no beans, no log lines, no DB reads.

This change also pins the spec's high-level "env-gated seed" requirements into implementation-precise scenarios (bean wiring, log markers, transactional boundary, failure semantics) so the apply phase produces test-grade specifications, mirroring the refinement pattern F08 used for `account-opening`.

## What Changes

- Introduce the plain-Java `SeedData` use case in `application/src/main/java/com/bank/core/application/seed/`. Constructor takes `Accounts accounts`, `OpenAccount openAccount`, and a `SeedPlan plan` (see below). Single public method `SeedReport seed()` orchestrates:
  1. Pre-check: if `accounts.findByNumber(plan.clearingAccount().number()).isPresent()`, return `SeedReport.skipped(reason="clearing account already present")` without touching anything else. This is the idempotency guard — the clearing account is the unique sentinel; if it exists, a prior seed run committed and this call is a no-op.
  2. Create the clearing account aggregate directly: `Account clearing = Account.open(plan.clearingAccount().number(), plan.clearingAccount().openingBalance())`. The clearing account is the ONE legitimate place in the system where an account is created at a non-zero balance without a funding transfer — there is no prior funding source. Persist it via `accounts.save(clearing)`.
  3. For each `CustomerSeed customer` in `plan.customers()` (in declared order), call `openAccount.open(new OpenAccountCommand(customer.number(), customer.openingBalance()))`. F08's pipeline handles the create + fund-via-F06-transfer mechanics; the customer account ends up at `customer.openingBalance()` and the clearing account's balance decreases by the same amount.
  4. Return `SeedReport.seeded(clearingNumber, customerNumbers)` summarising what was created. The runner uses this report to emit a single structured log line.
- Introduce a small set of plain-Java value records in the same `seed` package (no Spring annotations):
  - `SeedPlan(ClearingAccountSeed clearingAccount, List<CustomerSeed> customers)` — null-checked, customers list defensively copied as `List.copyOf(...)`.
  - `ClearingAccountSeed(AccountNumber number, Money openingBalance)` — non-null fields; positive (non-zero) opening balance enforced by a precondition (a zero clearing balance is technically legal but useless — the seed exists to fund customers, so a zero clearing seed is a config error).
  - `CustomerSeed(AccountNumber number, Money openingBalance)` — non-null fields; zero opening balance accepted (matches F08's "Open with zero balance" scenario); negatives impossible by `Money` invariant.
  - `SeedReport` sealed interface with two records: `Seeded(AccountNumber clearingAccountNumber, List<AccountNumber> customerAccountNumbers)` and `Skipped(String reason)`. The runner pattern-matches on this to choose the log message.
- Introduce the env-gated startup runner `SeedDataRunner` in `infrastructure/src/main/java/com/bank/core/infrastructure/seed/`. This is a `@Component` annotated `@ConditionalOnProperty(name = "bank.seed.enabled", havingValue = "true")` (the property is set from the `SEED_DATA` environment variable via Spring's standard relaxed binding; see configuration below). It implements `ApplicationRunner` so it runs once after the context is fully refreshed and the F08/F06 beans are ready. The runner injects `SeedData` (not `OpenAccountService` directly — `SeedData` is the use case; `OpenAccountService` is injected into `SeedData` by Spring DI) and the `SeedPlan` bean. The runner's `run(...)` method delegates: invokes `seedData.seed()`, then emits exactly one INFO log line:
  - `Seeded`: `"dev seed complete: clearing=<num> customers=[<n1>,<n2>,...] (count=<k>)"`
  - `Skipped`: `"dev seed skipped: <reason>"`
- The runner does NOT own a `@Transactional` boundary. The clearing-account create is one `accounts.save(...)` (its own implicit transaction inside the `AccountsJpaAdapter`), and each customer open is one full `OpenAccountService.open(...)` call (whose `@Transactional` was added in F08). Seeding is a sequence of independently-committed steps, NOT a single mega-transaction. This is the correct boundary because:
  - The clearing-account step must commit before the customer-open steps run (each customer-open's funding transfer reads the committed clearing-account row through the F06 pipeline). A single outer transaction would mean each customer-open's transfer-funds call would have to see uncommitted writes from the same transaction, which works in JPA but couples F09's correctness to JPA flush semantics rather than to F08's already-tested commit semantics.
  - Idempotency is provided by the "clearing account exists?" pre-check, not by transactional rollback. If the runner crashes after creating the clearing account but before opening the first customer, the next run sees the clearing account present and skips entirely — a manual operator step is then required to add the customers. The spec's "loud failure, no half-set-up state" requirement is preserved by the bounded log line plus the precondition-driven skip; this change explicitly accepts that "no half-set-up state" applies per-account-open (each is atomic via F08), not across the whole multi-account plan.
- Introduce externalised configuration `SeedProperties` (`@ConfigurationProperties("bank.seed")`) in `infrastructure/src/main/java/com/bank/core/infrastructure/seed/`. Fields:
  - `boolean enabled` (default `false`).
  - `String clearingAccountNumber` — defaulted from `bank.clearing-account.number` via a Spring `@Value` fallback inside the `@Bean SeedPlan` factory (NOT inside `SeedProperties` itself, so the property class stays a dumb DTO).
  - `BigDecimal clearingAccountOpeningBalance` (default `100000.00` — large enough to fund any sane dev plan; this is dev-only money).
  - `List<CustomerSeedProperty> customers` (default `[{number="CUST-1001", openingBalance="500.00"}, {number="CUST-1002", openingBalance="250.00"}, {number="CUST-1003", openingBalance="0.00"}]` — three customers, one funded large, one funded small, one at zero so a developer can exercise the zero-open path without editing config). `CustomerSeedProperty` is a nested record `(String number, BigDecimal openingBalance)`.
- Wire the use case and the plan in `bootstrap/src/main/java/com/bank/core/BankCoreApplication.java`:
  - Add `@EnableConfigurationProperties(SeedProperties.class)` to the existing list.
  - Add `@Bean SeedPlan seedPlan(SeedProperties props, @Value("${bank.clearing-account.number}") String clearingNumber)` — constructs the immutable `SeedPlan` from the property DTO. The clearing-account number is read from the F08 property (`bank.clearing-account.number`) by default; if `SeedProperties.clearingAccountNumber` is explicitly set, it overrides. This means a developer running the app with both F08 and F09 active gets the SAME clearing account out of both (no name drift) without having to set the same value twice.
  - Add `@Bean @ConditionalOnProperty(name = "bank.seed.enabled", havingValue = "true") SeedData seedData(Accounts accounts, OpenAccountService openAccountService, SeedPlan plan)` — the use case is only constructed when seeding is on. The bean wraps `OpenAccountService` (the transactional facade) inside an adapter (a lambda implementing `OpenAccount`-shaped functional interface — see Decisions in design.md) so the application module remains Spring-free.
  - The `@Component`-annotated `SeedDataRunner` is picked up automatically and is also gated by `@ConditionalOnProperty`, so it neither constructs nor instantiates when seeding is off.
- Add configuration entries:
  - `bootstrap/src/main/resources/application.yaml`: add a commented-out `bank.seed:` block under the existing top-level `bank:` key, documenting that the switch is wired through `SEED_DATA` (Spring's relaxed binding converts `SEED_DATA=true` to `bank.seed.enabled=true`). Default `enabled: false`. Production picks up nothing.
  - `bootstrap/src/main/resources/application-dev.yaml`: add a fully-populated `bank.seed:` block with `enabled: true`, the configured clearing balance, and the three default customers. So `--spring.profiles.active=dev` is sufficient to get a seeded database without any environment variable. `SEED_DATA=true` still works in any profile via the relaxed-binding alias.
- The seeder MUST be silent when off:
  - Both `SeedDataRunner` and `SeedData` are gated by `@ConditionalOnProperty(name = "bank.seed.enabled", havingValue = "true")` so they are not constructed at all when seeding is off. No bean → no constructor log, no debug line, no DB read.
  - The `SeedPlan` bean is also gated (so it does not appear in actuator's beans endpoint when off, keeping operational surface area zero).
- Tests:
  - **Application unit test** for `SeedData` (`SeedDataTest`, JUnit 5 + Mockito) with mocked `Accounts` and `OpenAccount`:
    - Fresh DB (clearing absent): calls `accounts.save(...)` exactly once for the clearing account (Active, balance = configured), then calls `openAccount.open(...)` once per customer in declared order with the right `OpenAccountCommand`, returns a `Seeded` report listing every account.
    - Re-run (clearing present): `accounts.save(...)` never invoked, `openAccount.open(...)` never invoked, returns `Skipped(reason="clearing account already present")`.
    - Argument-order guard: the `OpenAccountCommand` constructed for each customer has `number == customer.number()` and `openingBalance == customer.openingBalance()`. (Source/destination ordering inside the funding transfer is F08's concern and already guarded there.)
    - Null-rejection on `SeedPlan` constructor and on the use case constructor's parameters.
    - `ClearingAccountSeed` precondition: zero opening balance rejected at construction (with a clear message).
  - **Application unit test** for `SeedReport` (`SeedReportTest`) — sealed-interface pattern-match round-trips, defensive copy on `customerAccountNumbers`.
  - **Integration test** `bootstrap/src/test/java/com/bank/core/seed/SeedDataRunnerIntegrationTest` (`@SpringBootTest` with `properties = {"bank.seed.enabled=true", "bank.seed.customers[0].number=CUST-9001", "bank.seed.customers[0].openingBalance=10.00", "bank.seed.customers[1].number=CUST-9002", "bank.seed.customers[1].openingBalance=0.00"}`):
    - After context start, `account` table contains the clearing account at the configured balance minus 10.00 (one customer funded), plus `CUST-9001` Active at 10.00 and `CUST-9002` Active at 0.00.
    - Exactly one `journal_entry` row exists (one funded customer); two `ledger_movement` rows for that journal (DEBIT clearing, CREDIT CUST-9001).
    - Restart in the same H2 schema (call the runner's `run(...)` a second time manually via the bean) — the second pass produces zero new `account` rows, zero new `journal_entry` rows, zero new `ledger_movement` rows. Captured INFO log contains the "dev seed skipped" line exactly once for the second pass.
  - **Integration test** `bootstrap/src/test/java/com/bank/core/seed/SeedDataOffIntegrationTest` (`@SpringBootTest` with default properties; `bank.seed.enabled` unset):
    - `SeedDataRunner`, `SeedData`, and `SeedPlan` beans are absent from the context (`applicationContext.getBeansOfType(...)` returns an empty map for each).
    - `account` table row count is zero.
    - No log line containing the substring `"dev seed"` is captured during context startup.
  - **Integration test** `bootstrap/src/test/java/com/bank/core/seed/SeedDataFailureIntegrationTest`:
    - Configure the plan so that the clearing-account opening balance is `10.00` and one customer is configured at `100.00` (i.e. F08's funding transfer will fail because the clearing account can't cover it — F06 throws `InsufficientFundsException` from its debit). Assert the context still starts (the runner's failure must not abort the application), the captured ERROR log contains a single high-severity line naming the failing customer, the clearing account exists with its committed balance, and the failing customer's account does not exist (F08's `@Transactional` rolled back the partial open).
  - **ArchUnit / boundary verification**: F00's `applicationHasNoFrameworkDependencies` must still pass — `SeedData`, the seed value records, and `SeedReport` are framework-free. `SeedDataRunner`, `SeedProperties`, and the `@Bean SeedData` factory live under `com.bank.core.infrastructure..` / `com.bank.core` (bootstrap) so the entity / web confinement rules pass unchanged.

## Capabilities

### New Capabilities

None. The `dev-data-seeding` capability is already declared in `openspec/specs/dev-data-seeding/spec.md`; this change implements it.

### Modified Capabilities

- `dev-data-seeding`: refine the high-level "env-gated seed" requirements into implementation-precise scenarios — bean wiring under `@ConditionalOnProperty`, property names (`bank.seed.enabled`, `bank.seed.clearingAccountOpeningBalance`, `bank.seed.customers[]`), the relaxed-binding alias from `SEED_DATA`, the clearing-account precondition as idempotency guard, the per-account (not whole-plan) atomicity boundary, and the failure / silent-when-off log semantics. No requirement-level behaviour is removed; the existing four requirements are sharpened into testable form.

## Impact

- **Code**:
  - `application/src/main/java/com/bank/core/application/seed/SeedData.java` (new — plain-Java use case).
  - `application/src/main/java/com/bank/core/application/seed/SeedPlan.java` (new — plain-Java record).
  - `application/src/main/java/com/bank/core/application/seed/ClearingAccountSeed.java` (new — plain-Java record).
  - `application/src/main/java/com/bank/core/application/seed/CustomerSeed.java` (new — plain-Java record).
  - `application/src/main/java/com/bank/core/application/seed/SeedReport.java` (new — sealed interface + two records).
  - `application/src/main/java/com/bank/core/application/seed/OpensAccount.java` (new — single-method functional interface that the application use case calls; the infrastructure layer binds an instance backed by `OpenAccountService::open` via the `@Bean` factory in `BankCoreApplication`. Keeps the application module free of Spring stereotypes while letting Spring inject the transactional facade through the lambda).
  - `infrastructure/src/main/java/com/bank/core/infrastructure/seed/SeedDataRunner.java` (new — `@Component @ConditionalOnProperty ApplicationRunner`).
  - `infrastructure/src/main/java/com/bank/core/infrastructure/seed/SeedProperties.java` (new — `@ConfigurationProperties`).
  - `bootstrap/src/main/java/com/bank/core/BankCoreApplication.java` (modified — register `SeedProperties` via `@EnableConfigurationProperties`, add gated `@Bean SeedPlan` and `@Bean SeedData` factories).
- **Configuration**:
  - `bootstrap/src/main/resources/application.yaml` (modified — add commented `bank.seed:` block documenting the `SEED_DATA` relaxed-binding alias; default disabled).
  - `bootstrap/src/main/resources/application-dev.yaml` (modified — add fully populated `bank.seed:` block so the `dev` profile auto-seeds).
- **Schema / migrations**: none. No new tables, no new columns. Seeding uses the existing F05 `account` table and writes journal entries via the F02 / F06 pipeline.
- **OpenAPI**: none. F09 has no HTTP surface.
- **Build**: no new Gradle dependencies; everything F09 needs (Spring Boot conditional, configuration properties, JPA via `AccountsJpaAdapter`) is already on the classpath from F00 / F05.
- **Conventions**:
  - Reaffirms F00's "application is Spring-free": `SeedData`, the value records, and `SeedReport` have zero Spring/JPA/openapi imports. ArchUnit `applicationHasNoFrameworkDependencies` continues to pass.
  - Reaffirms F02's `transactional-in-application` precedent: the transactional boundary for the *per-customer-open* step lives on `OpenAccountService` (F08's facade); F09 does not introduce a new `@Transactional`. The clearing-account create is a single `accounts.save(...)` whose transactional scope is the implicit `AccountsJpaAdapter` write.
  - Reaffirms F00's "orchestration-shells-thin": `SeedDataRunner.run(...)` delegates immediately to `SeedData.seed()` and logs the report; all decision logic lives in the application use case.
- **Open decisions**:
  - **Unchanged / consumed**: `transactional-in-application` (carried forward — F09 wires its functional-interface adapter so the application module never imports `OpenAccountService`), `scheduler-config-externalised` (F10/F11 concern; F09 is event-driven via `ApplicationRunner`, not scheduled), `reactivation-playbook` (F11 concern), `idempotency` (F06 concern; F09's idempotency is at a different level — the clearing-account-exists pre-check — and is local to seeding).
  - **No new open decision opened by this change.**
- **Downstream**:
  - **F10** (`journal-verification`) sweeps PENDING journals; the journals F09 produces (one per funded customer at startup) are picked up by F10 like any other transfer. No change to F10's spec.
  - **F11** (`balance-drift-detection`) reconciles cached balances against the ledger; F09's writes go through F08 / F06 like any other transfer, so F11 sees consistent state from second one. F11's "exclude clearing account from suspension on drift" rule explicitly references the clearing-account row that F09 now creates — F09 makes that row's existence guaranteed in dev.
- **Backwards compat**: zero. Production (no `SEED_DATA`, default profile) sees no behavioural change: no new beans, no new log lines, no new DB activity. The `dev` profile now auto-seeds, which is the intended new behaviour and matches what a developer running `./gradlew :bootstrap:bootRun --args='--spring.profiles.active=dev'` expects.
- **Operational notes**:
  - Production safety: the `@ConditionalOnProperty` guard plus `enabled: false` default plus no entry in `application.yaml` means production never seeds. A malicious or accidental `SEED_DATA=true` in production would still try to write — but it would then be visibly skipped on every restart after the first (clearing account already present), and the seeded customer rows are obviously named (`CUST-1001` etc.) so an operator audit would catch them.
  - Failure behaviour: a mid-seed failure (e.g. F06 insufficient-funds on a customer open) logs ERROR, the application context still starts, the failing customer is rolled back by F08, and subsequent customer opens in the same run STOP (the runner does not swallow the exception — `ApplicationRunner` failures are surfaced by Spring Boot). Operators investigating see one clear failure log. This matches the spec's "loud failure" requirement; "no half-set-up state" is preserved per-account-open (F08's atomicity), and the runner's clearing-account pre-check means a subsequent restart will skip seeding entirely until an operator clears the partial state manually.
  - Test runtime: with `bank.seed.enabled=true` the default plan writes 1 clearing + 3 customers = 4 `account` rows + 2 journal entries + 4 ledger movements. Negligible startup overhead (~10 ms on H2-in-memory).
