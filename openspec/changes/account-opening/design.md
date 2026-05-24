## Context

The slot before this one — F06 fund transfer — shipped the central HTTP write surface (`POST /api/v1/transfers`) and the underlying `TransferFunds` use case that composes F05's `Accounts` port, F02's `JournalEntries` port, F07's `AccountLocker`, and a `Clock`. That use case is plain Java; its transactional boundary lives on `TransferController.createTransfer(...)` (`@Transactional`). F08 is the next slot. It is **not** a new HTTP endpoint; it is an internal use case that lays the operational groundwork F09 (dev data seeding) needs and that any future "open account" HTTP endpoint will reuse.

The published spec at [openspec/specs/account-opening/spec.md](openspec/specs/account-opening/spec.md) commits to four behavioural requirements (open with explicit number/balance; funding flows through the clearing account; clearing account is a precondition for positive opens; atomic create + fund). This change implements those plus the wiring requirements it forces (a configurable clearing-account number, a transactional facade, two new domain exceptions, the plain-Java use case).

What this change has to introduce, not just satisfy the spec for:
- The `OpenAccount` plain-Java use case in `application/account/`.
- The `OpenAccountCommand` record.
- Two new domain exceptions (`DuplicateAccountNumberException`, `ClearingAccountMissingException`).
- A thin `@Service @Transactional` facade (`OpenAccountService`) in infrastructure.
- A `@Bean` factory method for `OpenAccount` in `BankCoreApplication`.
- The `bank.clearing-account.number` configuration property (and its symmetric declaration in the test profile).

Constraints inherited from F00 / F01 / F02 / F06:
- `domain` is JDK-only — the two new exceptions stay pure Java, extending `DomainException`.
- `application` is Spring-free — `OpenAccount` and `OpenAccountCommand` carry no Spring/JPA imports; `@Transactional` lives in `infrastructure`.
- `infrastructure` may hold `@Service`/`@Transactional` annotations and is the only place that depends on Spring's transaction-management types.
- `bootstrap` is the sole wiring shell — `@Bean` factories and `@Value` reads live here, not in the application module.
- Tests that need Spring wiring live under `bootstrap/src/test/...`; pure-Java tests live in `application/src/test/` or `domain/src/test/`.

Open decisions touched: none. F08 opens no new open question and does not re-litigate any prior decision. The previously-closed decisions (`self-transfer`, `lock-wait-timeout`, `account-status-enum-coverage`, `debit-to-zero`) all apply transitively through F06.

## Goals / Non-Goals

**Goals:**
- A caller invoking `OpenAccountService.open(command)` against an Active clearing account ends in one of three deterministic states: success (new Active account exists at the requested balance, exactly one journal entry produced if positive open, zero journal entries if zero open), domain rejection (`DuplicateAccountNumberException`, `ClearingAccountMissingException`, or any F06-propagated `AccountInactiveException` / `InsufficientFundsException`), or infrastructure failure (rolled back atomically). No partial state is ever observable.
- The clearing-account number is configurable via `bank.clearing-account.number` so test environments, dev environments, and future production environments can use different identifiers without code changes.
- The application module remains Spring-free; `OpenAccount` and `OpenAccountCommand` compile against the JDK + sibling domain types only. ArchUnit `applicationHasNoFrameworkDependencies` continues to pass.
- The transactional boundary is provided by an infrastructure facade (`@Service @Transactional`), so callers cannot accidentally invoke the use case outside a transaction. F09 (the immediate downstream consumer) injects the facade, not the use case.
- The use case re-uses F06 verbatim — no duplication of debit/credit/journal-create logic. F06's existing tests cover the transfer mechanics; F08's tests cover the orchestration layer only.
- `OpenAccount` returns the post-funding `Account` aggregate so callers get the funded balance without a second lookup call. The aggregate is freshly reloaded via the `Accounts` port to avoid in-memory staleness after F06's commit.

**Non-Goals:**
- **No HTTP endpoint.** The published spec explicitly scopes this capability as internal (the legacy F08 doc names "no HTTP endpoint for account opening — opening is invoked internally"). A future `POST /api/v1/accounts` endpoint can be a thin controller over the existing facade.
- **No KYC / customer identity capture.** The opening operation deals in account numbers, not customers. A future change can add a customer aggregate that references accounts.
- **No closing of accounts.** Closed-account workflow is a separate future capability.
- **No bootstrap of the clearing account itself.** F08 *requires* the clearing account to exist for a positive open; *creating* the clearing account is F09's responsibility (a one-shot `Accounts.save(...)` of an `Account.open(clearingNumber, clearingSeedBalance)` aggregate during startup seeding, gated by `SEED_DATA=true`). F08's tests pre-seed the clearing account via the `Accounts` port directly.
- **No idempotency key on the use case.** F08 is internal; the F09 caller does its own "skip if exists" check before invoking `open(...)`, which composes cleanly with `DuplicateAccountNumberException`. A future HTTP wrapper can add an `Idempotency-Key` header at the controller boundary (the same open decision currently parked for F06).
- **No multi-clearing-account support.** The legacy spec's open question ("one clearing account per ledger purpose, or one global?") is parked at "one global". This change does not introduce a clearing-account-per-purpose registry.
- **No top-up automation for the clearing account.** The legacy spec's open question ("policy for topping up the clearing account in long-running environments") is operational and out of scope; today the seeded value is large enough for dev/test workloads.
- **No event publication on open.** This change does not publish a domain event for "account opened". The published spec does not require one; F09 logs its seed operations directly.

## Decisions

### `@Transactional` lives on a thin infrastructure facade, not on the application use case

F02's `transactional-in-application` precedent says the application module stays Spring-free. F06 honoured this by putting `@Transactional` on the *controller* (the infrastructure-side entry point to the use case). F08 has no controller. The two real choices are:

1. **Tag the use case `@Transactional` directly.** Trades the application-Spring-free convention for one annotation. Would force every future application-module use case to import `org.springframework.transaction.annotation.Transactional` once the precedent is set.
2. **Thin infrastructure facade.** A 5-line `@Service @Transactional` wrapper that delegates straight to the plain-Java use case. Keeps the application module Spring-free. Costs one extra class and one extra method invocation per call.

Choice: option 2. The facade is `OpenAccountService` in `com.bank.core.infrastructure.account`. The cost is trivial and the convention is preserved. F09 will inject the facade; F08's integration tests inject the facade; the application-module unit test injects the plain-Java use case directly (no transaction needed because the test uses Mockito stubs, not the JPA adapter).

Rejected alternative — programmatic `TransactionTemplate` inside the use case. The use case would have to take a `PlatformTransactionManager` dependency, breaking the Spring-free rule even more aggressively than `@Transactional`.

### The use case returns the post-funding `Account`, reloaded from the port

After `transferFunds.transfer(...)` commits the F06 debit/credit, the in-memory `newAccount` aggregate that F08 just saved is stale by one credit (F06 loaded and mutated its own copy through the port). The cleanest way to return the funded state is to reload via `accounts.findByNumber(command.number())` after the transfer call returns. The reload happens inside the same transactional boundary, so the second-level cache (if Hibernate ever adds one) cannot serve a stale view.

Rejected alternative — return the pre-funding aggregate at balance zero. Callers would have to do a second lookup to get the funded balance; F09's logging path would either print "account opened at 0.00 (will be funded)" or do its own lookup. Worse ergonomics.

Rejected alternative — credit the in-memory `newAccount` aggregate after the F06 transfer. Couples F08 to F06's mutation order and would silently break if F06's journal-write order changed.

Rejected alternative — return `void`. Loses the "what was actually created" signal that callers (especially logs and tests) want.

### The clearing-account number is a configuration property, defaulted to `CLEARING-000`

The legacy spec's "single, well-known internal account identified by a fixed account number" wants a stable identifier with operational visibility. Hardcoding `"CLEARING-000"` in the use case would force every environment to use the same string and would put a magic literal in the application module (Spring-free or not, magic literals are a smell). Reading from a property:

- Lets test fixtures use the production default (no override needed) so behaviour is consistent across profiles.
- Lets ops set a different identifier per environment if needed (e.g. multi-tenant deployments) without code changes.
- Keeps the application module free of `@Value` — the bootstrap `@Bean` factory reads the property and passes the constructed `AccountNumber` into the use-case constructor.

Default `CLEARING-000`: short, all-uppercase, readable in any log line or table dump. Sorts before any realistic customer account number scheme (numeric, alphanumeric, IBAN). Operators inspecting `SELECT * FROM account ORDER BY account_number` see the clearing row first.

Rejected alternative — store the clearing-account ID instead of the number. The id is a random UUID that changes per environment; the number is stable across environments and is the natural human-facing identifier.

Rejected alternative — store the clearing-account number in a dedicated DB row (e.g. a `system_account_registry` table). Adds a table for one row; the property is simpler and is already the F00 convention for system-wide constants.

### `DuplicateAccountNumberException` carries the offending `AccountNumber`; pre-check guards the single-caller path; DB unique index guards the race

The published spec requires duplicate-number rejection. The cleanest application-layer rejection is a `findByNumber` pre-check followed by a domain exception throw. This avoids exposing Spring's `DataIntegrityViolationException` to callers and produces a deterministic exception type the test suite can assert against.

For the concurrent-write case (two callers racing to open the same number), the pre-check is not a real safety net — both callers' `findByNumber` could return empty before either `save` runs. The F05 unique index on `account.account_number` is the actual safety net. If a race fires it, the resulting `DataIntegrityViolationException` propagates out of the use case (un-translated) and rolls back the transaction; the call surfaces as a 500 to any HTTP caller and as an unhandled-exception log line to F09's seeder. F09 is single-threaded at startup so the race cannot occur in the immediate downstream consumer; if a future concurrent caller appears, the F05 adapter can be extended to wrap the integrity exception into `DuplicateAccountNumberException` then. YAGNI today.

Rejected alternative — wrap `DataIntegrityViolationException` in the F05 adapter now. Would require editing the published `account-lookup` spec (a Modified Capability), which adds scope for zero immediate benefit.

### Pre-check ordering: duplicate first, then clearing account, then create

The use case runs three guards before the irrevocable `Accounts.save(...)`:

1. Duplicate-number pre-check.
2. Clearing-account precondition (only when opening balance > 0).
3. New-account creation via `Account.open(...)` (pure, no I/O).

Ordering rationale: the duplicate check is the cheapest single-statement guard and applies to every call (zero and positive opens alike). The clearing-account check only applies to positive opens; running it after the duplicate check means a duplicate request against a misconfigured environment gets the duplicate error (the more actionable signal), not the clearing-account error. The spec's "Duplicate pre-check runs before the clearing-account precondition" scenario formalises this.

Rejected alternative — clearing-account check first. Would give a misleading error when a caller is making the *real* mistake (duplicate number). The current order surfaces the more actionable error first.

### The new `Account` is created via `Account.open(...)` at balance zero, then funded via F06

The published spec explicitly says "The new account SHALL be created with the chosen account number, status `Active`, and balance zero **before any funding step**". This is the cleanest interpretation: F08 never bypasses the ledger. Even the initial funding is a real F06 transfer with its own journal entry. The ledger is therefore the single source of truth for the new account's history — `SUM(movements)` over all `ledger_movement` rows for the account equals the account's cached `balance`, just like for every other account in the system.

This also means F11 (balance drift detection) needs no special case for newly opened accounts: the cached balance and the ledger sum agree from the first transaction.

Rejected alternative — pass the opening balance directly to `Account.open(...)` and skip the funding transfer for the initial credit. Faster (one fewer write) but defeats the audit-trail guarantee. Money would materialise on the new account without a ledger entry; F11 would see drift on every freshly opened funded account. Forbidden by the spec.

### `OpenAccountService` is a single-method, single-line facade

The facade is deliberately tiny — `@Service @Transactional`, constructor injects the use case, one method that delegates. The class is the *only* thing in the F08 surface that lives in infrastructure beyond the bootstrap `@Bean` factory. Keeping it minimal makes the boundary obvious: F08's logic is in the application module, F08's transactionality is at this one point. A future HTTP controller will not call the application use case directly; it will call the facade.

The class-level Javadoc names the application use case as the only delegate and calls out that splitting the orchestration across multiple beans would defeat the single-transaction guarantee — a defensive note for future refactors.

### Logging is the facade's responsibility, not the use case's

The use case logs nothing — it returns or throws. The facade does *not* log on every call either (F09's caller logs at INFO per seeded account; a future HTTP controller would log at INFO per request). Domain exceptions thrown by the use case carry enough context (offending account number, configured clearing-account number) for the caller's log line. Centralising logging at the caller avoids double-logging when F09 logs its own seed result and avoids ERROR-level pollution for expected duplicate-number rejections.

### Test split: pure-Java unit tests for the use case, integration tests for the facade

The `OpenAccountTest` (application module, JUnit 5 + Mockito) covers all six branching cases: zero open, positive open happy path, duplicate rejection, missing clearing account on positive open, missing clearing account allowed on zero open, source/destination ordering on the F06 call. It does not need a Spring context.

The `OpenAccountServiceIntegrationTest` (`bootstrap` module, `@SpringBootTest`, real H2, Flyway-managed schema) covers the persistence-and-rollback truths: row counts, journal balance, atomicity under a suspended clearing account. It pre-seeds the clearing account via the `Accounts` port directly (the same path F09 will use), so the test is also a working example of how F09 will seed.

The two exception types get small dedicated tests (`DuplicateAccountNumberExceptionTest`, `ClearingAccountMissingExceptionTest`) in the domain module — same shape as F06's `SameAccountTransferExceptionTest`.

`OpenAccountCommandTest` in the application module covers the compact-constructor invariants. It does not need Mockito.

### No new ArchUnit rule

The existing rules (`applicationHasNoFrameworkDependencies`, `domainHasNoFrameworkDependencies`, `domainAndApplicationDoNotImportInfrastructureOrConfig`, `jpaEntitiesLiveInInfrastructurePersistence`) already enforce every constraint F08 needs: the use case and command stay Spring-free, the exceptions stay in the domain module, the facade lives in infrastructure. The check `OpenAccountService` is annotated `@Transactional` is in the spec test ("Atomicity is enforced by the infrastructure facade, not the application use case" scenario) rather than ArchUnit because it is a positive-presence assertion, not an absence-of-import rule.

## Risks / Trade-offs

- **The duplicate pre-check is a TOCTOU race.** Two concurrent callers opening the same number could both pass the pre-check and both attempt to save; the F05 unique index catches the loser with `DataIntegrityViolationException`, which the global handler maps to a generic 500. → Mitigation: the immediate caller (F09) is single-threaded at startup; HTTP traffic that could race does not exist (no controller). If a future concurrent caller appears, the F05 adapter wraps the integrity exception into `DuplicateAccountNumberException`; the spec already requires the unique index to remain the safety net, so this future change is additive.
- **`OpenAccount.open(...)` reloads the new account after F06 to return the post-funding aggregate.** Two `findByNumber` calls plus a `save` plus an F06 transfer is a fair amount of I/O for one logical operation. → Mitigation: the calls all run inside one JDBC connection (the facade's `@Transactional`); the reload is a single indexed lookup. Total cost is bounded and dominated by the F06 transfer itself.
- **The `OpenAccountService` facade is the only thing standing between a caller and an unwrapped use case.** A future bootstrap `@Bean` that exposes `OpenAccount` directly to a controller would lose the transactional guarantee. → Mitigation: class-level Javadoc on `OpenAccountService` calls out that direct injection of `OpenAccount` from anywhere outside the application-module test suite is a bug; a future HTTP controller MUST inject the facade.
- **`bank.clearing-account.number` is read at bean construction time.** Changing the property at runtime requires a restart. → Mitigation: the clearing-account number is not the kind of thing that should change at runtime; tying it to a property + restart is exactly what we want. Documented in the bootstrap `@Bean` Javadoc.
- **`OpenAccount` depends on `TransferFunds`, which depends on `AccountLocker`, `JournalEntries`, and `Clock`.** The dependency tree is now three layers deep for the funded-open case. → Mitigation: this is intentional — F08 reuses F06 verbatim rather than reimplementing. The Mockito unit test stubs only the direct dependencies (`Accounts` and `TransferFunds`); F06's behaviour is covered by F06's own tests.
- **`ClearingAccountMissingException` extends `DomainException` but signals a system-misconfiguration condition (not a per-request domain rule violation).** Some readers would expect it to be a `RuntimeException` outside the `DomainException` hierarchy. → Mitigation: the F03 `GlobalExceptionHandler` handles `ResourceNotFoundException` for missing customer-level resources; `ClearingAccountMissingException` is semantically distinct ("system precondition not met"), and putting it under `DomainException` keeps every business-rule rejection in one taxonomy. Operators monitoring for this class name get a clean alert.
- **Atomicity test relies on the `OpenAccountService` `@Transactional` actually being applied.** A future refactor that removes the annotation or splits the method across beans would silently break atomicity. → Mitigation: one spec scenario ("Atomicity is enforced by the infrastructure facade, not the application use case") asserts the presence of `@Transactional` on the facade by inspecting the source; the rollback integration test would also fail loudly if the annotation went missing.
- **No HTTP endpoint means no `OpenApiContractTest` extension in this change.** A future change that adds the endpoint will extend the contract; until then there is nothing for the OpenAPI surface to assert. → Mitigation: explicit non-goal; the published F04 spec ("the contract is the source of truth and grows with each capability") is consistent with growing the contract only when a capability adds an HTTP surface.

## Migration Plan

Pure addition. No data migration, no rolling deploy, no feature flag.

1. **Ship the use case, command, two exceptions, facade, and the bootstrap wiring together.** Splitting across changes would leave a window where the facade references a non-existent use case, or the bootstrap `@Bean` references a missing class.
2. **Ship the `bank.clearing-account.number` property in both `application.yaml` and `application-test.yaml` together.** Omitting it from `application.yaml` would fail boot in any non-test profile (`@Value` with no default); omitting it from the test profile would technically still resolve from the main YAML, but the symmetric declaration prevents future "why does this test pass with the prod default?" confusion.
3. **Default the property to `CLEARING-000`.** Any future change that introduces a different clearing-account identifier (multi-tenant, multi-purpose) can override the property without code changes.
4. **No data migration.** No schema change. F05's `account` table is the storage. The clearing account *row* will be seeded by F09; F08 does not seed it.
5. **Rollback** is `git revert` of the change. No persistent data depends on the use case existing; reverting drops the facade and the bootstrap `@Bean`, leaving the underlying F05 + F06 surfaces untouched. The property entry in `application.yaml` would become unused after rollback (Spring tolerates extra properties); harmless.
6. **Forward link**: F09 (dev data seeding) will inject `OpenAccountService` from `com.bank.core.infrastructure.account` and call it in a startup `ApplicationRunner` gated by `SEED_DATA=true`. F09 will also be responsible for seeding the clearing account itself via `Accounts.save(Account.open(clearingNumber, Money.zero()))` followed by an idempotency-protected top-up transfer (or a direct one-off opening of the clearing account at a fixed seed balance — F09's design will decide). A future HTTP `POST /api/v1/accounts` endpoint can be a thin controller over the facade with no application-module changes.

## Open Questions

None blocking F08. Carry-forward items from the legacy spec:

- **Top-up policy for the clearing account in long-running environments.** Operational; F08 does not solve it. F09 sets the initial seed balance.
- **Public HTTP endpoint for account opening.** Out of scope. A future change can add a controller over the existing facade.
- **One clearing account per ledger purpose vs. one global.** Currently global; a multi-purpose registry would require a different design (per-purpose property lookup or a `system_account_registry` table). Not needed today.
- **Authentication on the future HTTP endpoint.** A cross-cutting security capability will address this for all endpoints together.
