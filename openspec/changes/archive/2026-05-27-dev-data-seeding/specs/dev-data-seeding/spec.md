## MODIFIED Requirements

### Requirement: Seeding is gated by an environment-controlled switch
Seeding SHALL run only when the property `bank.seed.enabled` resolves to `true`. The property SHALL be settable from `application*.yaml`, from `--bank.seed.enabled=true` on the command line, from the `BANK_SEED_ENABLED` environment variable via Spring's standard relaxed binding, and from `SEED_DATA=true` via an explicit alias registered by a bootstrap `EnvironmentPostProcessor` (the alias maps `SEED_DATA` onto `bank.seed.enabled` with low precedence so any explicit `application*.yaml` value wins). When the property is absent or resolves to `false`, the seeding component SHALL NOT be constructed: the `SeedDataRunner` bean, the `SeedData` bean, and the `SeedPlan` bean SHALL all be absent from the Spring `ApplicationContext`, the seeding component SHALL produce no log lines during startup or runtime, and SHALL perform no reads or writes against the `account`, `journal_entry`, or `ledger_movement` tables.

#### Scenario: Switch OFF leaves an empty database empty and the context free of seed beans
- **WHEN** the service starts against an empty database with `bank.seed.enabled` unset (default profile, no `SEED_DATA` env var)
- **THEN** `applicationContext.getBeansOfType(SeedData.class)` returns an empty map, `applicationContext.getBeansOfType(SeedDataRunner.class)` returns an empty map, `applicationContext.getBeansOfType(SeedPlan.class)` returns an empty map, the `account` table row count is zero, the `journal_entry` table row count is zero, the captured log output from context startup contains no line whose message contains the substring `dev seed`

#### Scenario: Switch read from the SEED_DATA environment variable
- **WHEN** the service starts with the environment variable `SEED_DATA=true` and `bank.seed.enabled` unset in any property file
- **THEN** the `EnvironmentPostProcessor` resolves `bank.seed.enabled` to `true`, the `SeedDataRunner` bean is constructed, the `SeedData` bean is constructed, the `SeedPlan` bean is constructed, the seeding pipeline executes against the configured plan, and an INFO log line containing `dev seed complete` (for a fresh DB) or `dev seed skipped` (for a non-empty DB) is emitted exactly once

#### Scenario: Explicit application.yaml value wins over the SEED_DATA alias
- **WHEN** the service starts with the environment variable `SEED_DATA=true` AND a property file sets `bank.seed.enabled: false`
- **THEN** the explicit `false` from the property file wins, the `SeedData` and `SeedDataRunner` beans are NOT constructed, the `account` table row count remains unchanged, and no `dev seed` log line is emitted

#### Scenario: Property name typo is caught by a reflection test
- **WHEN** the `SeedDataRunner` class is inspected via reflection and its `@ConditionalOnProperty` annotation is read
- **THEN** the annotation's `name()` array equals exactly `["bank.seed.enabled"]` and its `havingValue()` equals exactly `"true"` — guarding against a refactor that misspells the property and silently disables seeding

### Requirement: Bootstrap creates clearing account directly, customers via opening
When `bank.seed.enabled=true` AND the configured clearing-account row is absent from the `account` table, the seeding component (`SeedData.seed()`) SHALL execute the following sequence inside the `SeedDataRunner.run(...)` callback, in order, with no other ordering permitted:

1. Construct the clearing-account aggregate via `Account.open(plan.clearingAccount().number(), plan.clearingAccount().openingBalance())` and persist it via a single `accounts.save(clearingAccount)` call. This SHALL be the only execution path in the entire codebase that materialises an account row with a non-zero balance without a corresponding funding transfer; the clearing account exists for the explicit purpose of being the genesis row from which every other account is funded.
2. For each `CustomerSeed customer` in `plan.customers()`, in declared list order, invoke the `OpensAccount` adapter (which routes to `OpenAccountService.open(...)` and therefore inherits [[account-opening]]'s `@Transactional` boundary and the [[fund-transfer]] pipeline) with `new OpenAccountCommand(customer.number(), customer.openingBalance())`. [[account-opening]] SHALL therefore handle the create-then-fund mechanics for each customer; for any customer whose `openingBalance` is greater than zero, exactly one new `journal_entry` row and exactly two `ledger_movement` rows (DEBIT on the clearing account, CREDIT on the customer) SHALL be created.

The clearing account number SHALL default to the value of `bank.clearing-account.number` (the same property [[account-opening]] reads), and MAY be overridden by an explicit `bank.seed.clearingAccountNumber` value. The clearing account's opening balance SHALL come from the `bank.seed.clearingAccountOpeningBalance` property (default `100000.00`) and SHALL be strictly positive — a zero clearing balance SHALL be rejected at `ClearingAccountSeed` construction with a clear message. Customer account numbers and opening balances SHALL come from the `bank.seed.customers[]` list, each entry a `(number, openingBalance)` pair; customer opening balances MAY be zero.

#### Scenario: Empty DB with switch ON produces clearing + funded customers in declared order
- **WHEN** the service starts against an empty database with `bank.seed.enabled=true`, `bank.seed.clearingAccountOpeningBalance=1000.00`, `bank.seed.customers=[{number:"CUST-9001", openingBalance:"100.00"}, {number:"CUST-9002", openingBalance:"50.00"}]`, and the configured clearing account number `CLEARING-000`
- **THEN** `accounts.findByNumber("CLEARING-000").get()` returns an `ACTIVE` account at balance `1000.00 - 100.00 - 50.00 = 850.00`; `accounts.findByNumber("CUST-9001").get()` returns an `ACTIVE` account at balance `100.00`; `accounts.findByNumber("CUST-9002").get()` returns an `ACTIVE` account at balance `50.00`; the `journal_entry` table contains exactly two rows; the `ledger_movement` table contains exactly four rows (two per funded customer: one DEBIT on the clearing account's id, one CREDIT on the customer's id)

#### Scenario: Sum of customer fundings reduces clearing balance exactly
- **WHEN** seeding completes with clearing opening balance `C0` and customer plan `[c1, c2, ..., cn]` (where each customer's opening balance is non-negative)
- **THEN** the clearing account's committed balance equals `C0 - sum(ci.openingBalance for i in 1..n)` to the cent, with no rounding drift

#### Scenario: Zero-balance customer is opened without a journal entry
- **WHEN** seeding runs with a customer plan containing a single entry `{number:"CUST-ZERO", openingBalance:"0.00"}` and a clearing opening balance of `500.00`
- **THEN** the `CUST-ZERO` row exists with status `ACTIVE` and balance `0.00`; the clearing account's balance is unchanged at `500.00`; the `journal_entry` row count for that customer is zero; the `ledger_movement` row count for that customer is zero (consistent with [[account-opening]]'s "Zero opening balance does not invoke the transfer pipeline" scenario)

#### Scenario: Clearing account is created directly, not via the account-opening pipeline
- **WHEN** the runner executes against an empty database with `bank.seed.enabled=true`
- **THEN** the call sequence captured on the `OpensAccount` adapter does NOT include any `open(...)` invocation whose `command.number()` equals the configured clearing-account number; the clearing-account row is created via `accounts.save(...)` directly; no `journal_entry` row is created for the clearing account's opening balance

#### Scenario: Zero clearing-account opening balance is rejected at construction
- **WHEN** the application starts with `bank.seed.enabled=true` and `bank.seed.clearingAccountOpeningBalance=0.00`
- **THEN** the `SeedPlan` bean factory throws `IllegalArgumentException` at startup naming `clearingAccountOpeningBalance` and the message `"clearing-account opening balance must be strictly positive — seeding exists to fund customers"`; the application context fails to start; no row is added to `account`

#### Scenario: Customer plan is consumed in declared list order
- **WHEN** the customer plan is `[{number:"CUST-A"}, {number:"CUST-B"}, {number:"CUST-C"}]` (each with a positive opening balance) and the seeder runs against a fresh DB
- **THEN** the `journal_entry` rows ordered by their primary key reflect the order CUST-A, CUST-B, CUST-C (each entry has a unique monotonically-assigned id); a captured invocation log on the `OpensAccount` adapter shows three `open(...)` calls in the order CUST-A, CUST-B, CUST-C

#### Scenario: Customer with same number as clearing account is rejected by account-opening
- **WHEN** the customer plan contains an entry whose number equals the configured clearing-account number
- **THEN** the seed-clearing-create step completes (the clearing row now exists), the customer-open step for that conflicting entry throws `DuplicateAccountNumberException` from [[account-opening]], the runner logs a single ERROR line naming the conflicting number, subsequent customer entries in the plan are NOT processed (`ApplicationRunner` failure semantics), and the application context startup fails

### Requirement: Idempotent across restarts via clearing-account precondition
When `bank.seed.enabled=true` AND the configured clearing-account row is already present in the `account` table, `SeedData.seed()` SHALL return a `SeedReport.Skipped` instance with reason `"clearing account already present"` without examining the customer list, without calling `OpensAccount.open(...)`, and without writing any row to any table. `SeedDataRunner` SHALL emit exactly one INFO log line `"dev seed skipped: clearing account already present"` for the skip. Re-running the seeder against an already-seeded database SHALL therefore be a strict no-op at the database level (no new `account` rows, no new `journal_entry` rows, no new `ledger_movement` rows) regardless of whether the customer plan has grown, shrunk, or changed since the first successful seed.

#### Scenario: Second start with switch ON does not duplicate
- **WHEN** the service starts a second time with `bank.seed.enabled=true` against a database already populated by an earlier seed run (the clearing-account row is present)
- **THEN** the second-start `SeedData.seed()` invocation returns `SeedReport.Skipped("clearing account already present")`; the `OpensAccount` adapter's `open(...)` is never invoked; the `account` table row count is unchanged from after the first run; the `journal_entry` table row count is unchanged; the `ledger_movement` table row count is unchanged; the captured second-start log contains exactly one INFO line `"dev seed skipped: clearing account already present"`

#### Scenario: Customer plan change after first seed has no effect
- **WHEN** a first seed run committed with customer plan `[CUST-1001@500]`, the configuration is then edited to `[CUST-1001@500, CUST-9999@999]`, and the service is restarted with `bank.seed.enabled=true`
- **THEN** the second-run seeder skips entirely (clearing account already present); no row is added for `CUST-9999`; no warning or error is logged about the plan mismatch; the database state remains exactly what the first run committed

#### Scenario: Skip is determined solely by clearing-account presence
- **WHEN** an operator manually deletes all customer rows from a previously-seeded database but leaves the clearing-account row intact, then restarts the service with `bank.seed.enabled=true`
- **THEN** the runner emits `"dev seed skipped: clearing account already present"`; the customer plan is NOT replayed; the `account` table contains only the clearing-account row after startup; the developer must drop the clearing-account row (or wipe the schema) to trigger a fresh seed

### Requirement: Loud per-account failure with bounded partial state
When `bank.seed.enabled=true` AND seeding fails partway through (e.g. the [[fund-transfer]] step rejects a customer open because the clearing account cannot cover the requested balance, or the configured customer number collides with an existing row), the runner SHALL NOT silently swallow the exception. The failing customer-open call SHALL roll back its own create + fund inside [[account-opening]]'s `@Transactional` boundary so the partially-opened account leaves no `account`, `journal_entry`, or `ledger_movement` row. The runner SHALL emit exactly one ERROR-level log line identifying the failing customer number and the exception class, then propagate the exception out of `ApplicationRunner.run(...)` so Spring Boot surfaces the failure and aborts startup. Customer opens that committed earlier in the same run SHALL remain committed and visible — the spec accepts per-account atomicity, not whole-plan atomicity, because each `OpensAccount.open(...)` call is independently wrapped by [[account-opening]]'s `@Transactional` and commits before the next plan entry is processed.

#### Scenario: Funding-transfer failure rolls back the failing customer and aborts startup
- **WHEN** `bank.seed.enabled=true`, `bank.seed.clearingAccountOpeningBalance=10.00`, `bank.seed.customers=[{number:"CUST-A", openingBalance:"5.00"}, {number:"CUST-B", openingBalance:"100.00"}, {number:"CUST-C", openingBalance:"1.00"}]`, and the service starts against an empty database
- **THEN** the clearing-account row commits with balance `10.00` then `5.00` after the CUST-A funding transfer; CUST-A is committed Active at balance `5.00` with one journal entry; the CUST-B funding transfer fails (insufficient clearing funds — [[fund-transfer]] throws `InsufficientFundsException`) and the CUST-B create + fund is rolled back by [[account-opening]] (`accounts.findByNumber("CUST-B").isEmpty()` is true); the runner emits one ERROR line whose message contains `CUST-B` and `InsufficientFundsException`; the CUST-C plan entry is NOT processed (`accounts.findByNumber("CUST-C").isEmpty()` is true); the `SpringApplication.run(...)` call throws and the JVM exits with a non-zero status

#### Scenario: Mid-seed failure on the very first customer leaves the clearing account behind
- **WHEN** `bank.seed.enabled=true`, the clearing-account row commits successfully, and the first customer-open in the plan fails (e.g. `DuplicateAccountNumberException` because an operator pre-populated that customer number)
- **THEN** the failing customer's account is rolled back by [[account-opening]] (no new `account` row for that number beyond the pre-existing one); no `journal_entry` row exists for that customer; the clearing account remains Active at its full opening balance; the runner emits one ERROR line naming the customer; subsequent customers are NOT processed; on the next restart with `bank.seed.enabled=true` the runner SKIPS entirely because the clearing-account row is now present (the operator must wipe and restart, or finish the partial seed manually via `OpenAccountService`, to fully populate the database)

#### Scenario: Runtime failure during clearing-account save aborts startup before any customer is touched
- **WHEN** `bank.seed.enabled=true`, the database is empty, and `accounts.save(clearingAccount)` throws (e.g. JDBC connection drop)
- **THEN** no `account` row exists for the clearing account after the failure; no `OpensAccount.open(...)` call has been made for any customer; the runner emits one ERROR line whose message contains the clearing-account number and the exception class; `SpringApplication.run(...)` throws
