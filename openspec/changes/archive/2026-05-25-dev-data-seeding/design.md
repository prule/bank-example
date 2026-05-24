## Context

The `dev-data-seeding` capability already has a spec at `openspec/specs/dev-data-seeding/spec.md`. It articulates four high-level requirements: env-gated switch, clearing-account-direct then customers-via-opening, idempotency, loud failure. F08 (`account-opening`) just shipped `OpenAccountService.open(...)` and made the clearing-account number configurable. F05 shipped `Accounts.save(...)`. F06 shipped the transfer pipeline. F01 shipped `Account.open(...)` and `Money` invariants. Everything F09 needs is already in place — F09's job is to wire a thin startup runner that uses these primitives to materialise a developer-usable database.

Constraints inherited from F00 and earlier changes:

- **Application module is Spring-free** (`applicationHasNoFrameworkDependencies` ArchUnit rule). No `@Service`, `@Component`, `@Value`, `@Autowired`, etc. in `application/src/main/java`. F09's application code obeys this.
- **Domain module is JDK-only**. F09 introduces no new domain types — `Account`, `AccountNumber`, `Money` are already enough.
- **Transactional boundaries live in infrastructure or controllers** (`transactional-in-application` precedent from F02 / F06 / F08). F09 does not introduce a new `@Transactional`.
- **One use case, one orchestration shell** (`orchestration-shells-thin`). The `SeedDataRunner` shell is a single delegation + log call.
- **F08's clearing-account number is the single source of truth** (`bank.clearing-account.number`). F09 must consume the same value with no name drift.

Stakeholders:
- The developer running the app with `--spring.profiles.active=dev` or `SEED_DATA=true` who wants real-looking data without a manual `INSERT`.
- The integration test author who wants a deterministic seeded fixture for end-to-end tests against `GET /api/v1/accounts/{n}` and `POST /api/v1/transfers`.
- The operator running production who needs to be confident F09 is dormant when the switch is off.

## Goals / Non-Goals

**Goals:**

- A single startup runner that, when explicitly enabled, materialises a clearing account and a configurable list of customer accounts so the dev/test database is immediately usable.
- Idempotent: restart with seeding on must be a no-op once a previous run committed at least the clearing account.
- Silent and zero-overhead when seeding is off (no beans, no log lines, no DB reads, no actuator surface).
- Customer funding flows through the F08 / F06 pipeline so every credit has a matching ledger movement — F09 introduces no shortcut around the ledger.
- Per-customer atomicity inherited from F08's `@Transactional` boundary; a failed funding transfer for one customer rolls back that customer's account creation without affecting prior successfully-opened customers.
- Production safety by construction: default disabled, conditional bean wiring, recognisably-named seed accounts.
- Implementation-precise spec scenarios (named log lines, bean conditions, property names) that the test suite can assert against directly.

**Non-Goals:**

- A `POST /api/v1/seed` HTTP endpoint or any operational tooling to trigger seeding at runtime — seeding is a startup-only, dev-only concern.
- Whole-plan atomicity (a single mega-transaction wrapping the clearing-create + every customer-open). We accept per-customer atomicity instead; see Decisions for rationale.
- Idempotency at the customer level inside a single seed run — if the clearing account is present, the whole seed is skipped; the runner does NOT try to merge "add missing customers to an existing partial seed". A partial-seed recovery requires operator intervention.
- A reusable "seed any environment" framework. F09 is dev-only; production seeding (if it ever exists) is a separate change.
- Production-grade configuration validation (e.g. uniqueness checks across customer numbers, character-set restrictions). The `AccountNumber.of(...)` invariant is sufficient at this stage; bad config fails fast at bean construction.

## Decisions

### Decision 1: Use an `ApplicationRunner` rather than a `CommandLineRunner` or `@PostConstruct`

`SeedDataRunner` implements `org.springframework.boot.ApplicationRunner`. Spring Boot guarantees `ApplicationRunner` beans fire exactly once after the full context (including JPA + transactional infrastructure + all other beans) is refreshed but before `SpringApplication.run(...)` returns. This is the correct point because:

- The F08 `OpenAccountService` `@Transactional` proxy is only fully wired after context refresh — `@PostConstruct` on a runner bean would fire too early and would not see the proxied service.
- `ApplicationRunner` failures bubble up to Spring Boot which logs them at ERROR level and aborts the startup. This matches the spec's "loud failure" requirement without any custom exception handler.
- `CommandLineRunner` would also work but its signature (`String... args`) is misleading — F09 doesn't consume command-line arguments. `ApplicationRunner.run(ApplicationArguments args)` is the semantically right choice; the `args` parameter is unused but documents the intent.

**Alternatives considered:**

- A `Flyway` "afterMigrate" callback or `R__seed.sql` script. Rejected: writing accounts directly via SQL bypasses `Account.open(...)`, bypasses F06's journal entry, and bypasses F08's `@Transactional` — every guarantee F09's spec requires would have to be re-implemented in SQL. Worse, the seed would run *every* migration, with no clean way to gate on `SEED_DATA`.
- A Spring `@EventListener(ApplicationReadyEvent.class)` method on a `@Component`. Rejected on stylistic grounds only: `ApplicationRunner` is the idiomatic Spring Boot hook for "do this once at startup", whereas event listeners are conventionally for cross-cutting reactive concerns. Behaviourally equivalent.
- A `@PostConstruct` on the `SeedDataRunner` bean itself. Rejected: fires before the context is fully refreshed; the `OpenAccountService` `@Transactional` proxy may or may not be available depending on bean-creation order, which is brittle.

### Decision 2: Use `@ConditionalOnProperty(name = "bank.seed.enabled", havingValue = "true")` on every seed bean

The `SeedDataRunner` `@Component`, the `@Bean SeedPlan` factory, and the `@Bean SeedData` factory are all annotated `@ConditionalOnProperty(name = "bank.seed.enabled", havingValue = "true")`. The property is read by Spring's relaxed-binding mechanism, which honours the `SEED_DATA` environment variable out of the box (Spring converts `SEED_DATA` → `seed.data` → `bank.seed.enabled`? — no; the explicit mapping is `SEED_DATA` → `seed.data` only when binding `seed.data`, so see Decision 4 for the chosen binding name).

**Why gate every bean rather than just the runner:**

- If `SeedData` were unconditionally constructed, it would appear in `actuator/beans`, in `BeanDefinitionRegistry` dumps, and in any diagnostic tooling — increasing the operational surface area of seeding even when off. The spec's "no log noise when off" requirement is in the same spirit.
- Constructing `SeedData` with a null `OpensAccount` would require the `OpensAccount` bean to also be available, which would in turn require `OpenAccountService` to be available — a dependency we want to keep loose. Gating every seed bean keeps the seed module a complete no-op when off, with zero references from the seed graph back into the F08 graph.

**Alternatives considered:**

- Gate only the runner and let `SeedData` be constructed always. Rejected — adds operational surface area for no benefit, and makes the "silent when off" scenarios harder to assert (you can no longer just check `getBeansOfType(SeedData.class).isEmpty()`).
- Use a Spring profile (`@Profile("dev")`) instead of a property. Rejected — the spec explicitly calls for an environment-controlled switch, not a profile-controlled one, because the test suite wants to enable seeding on a per-test basis via `@TestPropertySource` without activating the whole `dev` profile (which would also flip on Swagger UI, the H2 console, etc.). The `dev` profile *sets* `bank.seed.enabled=true`; the property is the gate.

### Decision 3: Per-customer atomicity, not whole-plan atomicity

The spec's "loud failure, no half-set-up state" requirement is honoured **per account-open**, not across the whole multi-customer plan. The clearing-account `accounts.save(...)` commits independently; each customer's `OpenAccountService.open(...)` commits independently inside its own F08-owned `@Transactional` boundary.

**Why not wrap the whole runner in a single `@Transactional`:**

- F06's transfer (which F08 invokes for each positive customer open) reads the clearing-account row to validate its `ACTIVE` status. Inside a single mega-transaction, F06 would have to read a row written earlier in the same transaction — JPA's flush-before-query semantics make this work but only with care, and any future change that nests a non-JPA read (e.g. a `@Cacheable` lookup, a JDBC read for an audit log) inside the transfer pipeline would break F09 silently.
- F06 acquires F07 paired locks per transfer. Under a single mega-transaction, those locks would all be held until the runner commits at the very end — multiplying lock contention for unrelated startup traffic (e.g. concurrent JPA initialisations). Per-customer commit releases locks promptly.
- A whole-plan rollback would mean a single bad customer config (e.g. a typo in `bank.seed.customers[2].number`) blocks the whole seed, leaving the dev with a database that's empty *because of customer 2*. Per-customer commits leave customers 0 and 1 visibly seeded, with a clear ERROR log naming customer 2 — much more useful for the developer.

**Trade-off accepted:** a runner crash midway through customer opens leaves a partial seed (clearing + some customers, but not all). The spec's "no half-set-up state" requirement is then satisfied at the per-account level (each seeded account is fully Active and fully funded) but not at the plan level. The clearing-account-exists idempotency pre-check (Decision 6) means subsequent restarts will SKIP rather than try to fill in missing customers — the developer must either accept the partial seed, manually open the remaining customers via `OpenAccountService`, or drop the H2 schema and restart. This is explicitly documented in the spec and in the `SeedDataRunner` Javadoc.

### Decision 4: Property naming — `bank.seed.enabled` with `SEED_DATA` as a documented alias

The canonical property is `bank.seed.enabled` (matches Spring's `bank.transfer.lock-wait-ms` style from F07 and the `bank.clearing-account.number` from F08 — all under the `bank.*` namespace). Spring's relaxed binding does NOT automatically map `SEED_DATA` → `bank.seed.enabled`; the natural environment-variable form of `bank.seed.enabled` is `BANK_SEED_ENABLED`.

To honour the spec's call for `SEED_DATA` specifically, we register `SEED_DATA` as an explicit alias via Spring Boot's `PropertySource` precedence: a tiny `EnvironmentPostProcessor` (in `META-INF/spring.factories`) reads `SEED_DATA` and, if set, copies its boolean value onto `bank.seed.enabled` with low precedence (so an explicit `bank.seed.enabled` in `application*.yaml` wins). This keeps the developer ergonomic of `SEED_DATA=true ./gradlew bootRun` while keeping the canonical property name aligned with the rest of the `bank.*` config.

**Alternatives considered:**

- Use `seed.data.enabled` so that `SEED_DATA_ENABLED` is the natural env var. Rejected — breaks the `bank.*` namespace convention; the spec example mentions `SEED_DATA` (no `_ENABLED`), so the alias path is required either way.
- Document only the canonical `BANK_SEED_ENABLED` and ignore `SEED_DATA` in the spec. Rejected — the spec's example is normative; the test scenarios assert that `SEED_DATA=true` enables seeding.

### Decision 5: Introduce an `OpensAccount` functional interface in the application module

`SeedData` (application module) calls into the open-account pipeline. The pipeline's `@Transactional` proxy lives on `OpenAccountService` (infrastructure module). The application module cannot import `OpenAccountService` (it cannot import infrastructure types) and must not import `org.springframework.transaction.annotation.Transactional` (Spring-free constraint).

The clean solution: introduce a single-method functional interface `OpensAccount` in `application/.../seed/`:

```java
@FunctionalInterface
public interface OpensAccount {
    Account open(OpenAccountCommand command);
}
```

`SeedData`'s constructor takes `OpensAccount` (not `OpenAccountService`, not `OpenAccount`). In the bootstrap layer, the `@Bean SeedData` factory passes `openAccountService::open` (a method reference that resolves to `Account open(OpenAccountCommand)`) as the `OpensAccount` implementation. Spring's DI calls the lambda; the lambda calls `OpenAccountService.open(...)`; that call is intercepted by Spring's `@Transactional` proxy.

**Why not just inject `OpenAccount` (the plain-Java use case) directly:**

- `OpenAccount` (the F08 use case) is already a bean. Injecting it into `SeedData` would bypass `OpenAccountService`'s `@Transactional` proxy — each customer open would run without a transactional boundary, breaking F09's per-customer atomicity guarantee and the spec scenario that asserts "funding transfer failure rolls back account creation".
- Adding a second `@Transactional` annotation to `OpenAccount` would put Spring annotations in the application module — explicit F00 violation.

**Why not inject `OpenAccountService` directly into `SeedData`:**

- Same F00 violation in reverse: `application/.../SeedData.java` cannot import `com.bank.core.infrastructure.account.OpenAccountService`. The dependency would flow application → infrastructure, inverted from the hexagonal architecture.

The `OpensAccount` interface is the inversion-of-control adapter that lets the application module stay clean while routing every call through the transactional facade.

### Decision 6: Idempotency by "clearing account exists?" pre-check, not by per-row upserts

`SeedData.seed()` starts by checking `accounts.findByNumber(plan.clearingAccount().number()).isPresent()`. If present, return `Skipped(reason="clearing account already present")` and exit. No other state is examined.

**Why this and not "check each row and skip the ones that exist":**

- The clearing account is the unique sentinel — F08 forbids any production code path from creating a clearing-account-balance non-zero row except F09. So "clearing account exists" is equivalent to "F09 has committed at least once" with high confidence.
- Per-row idempotency would let a half-seeded database (clearing + customers 1-2 from run 1, plus customers 3-5 from run 2 because the config grew) accumulate state across restarts. That's confusing — the developer can't predict the database state from any single config file. Skip-once semantics keeps the database state strictly determined by the *first successful seed*; subsequent config edits require a manual reset.
- Per-row idempotency would require careful handling of partial states: an `OpenAccountService.open(...)` for a customer that already exists at the seeded balance is a no-op, but for one that exists at a *different* balance it's an `DuplicateAccountNumberException` — the runner would have to decide whether to ignore, fail, or reconcile. Skip-once avoids the whole decision tree.

**Trade-off accepted:** if the dev changes `bank.seed.customers[]` after a successful seed, the new config has no effect until the developer wipes the H2 schema. The spec documents this explicitly; the H2 in-memory default makes "wipe and restart" trivial.

### Decision 7: Default customer plan in `application-dev.yaml`, not in `application.yaml`

The default customer plan (`CUST-1001` @ 500, `CUST-1002` @ 250, `CUST-1003` @ 0) lives in `application-dev.yaml` under a fully-populated `bank.seed:` block. `application.yaml` contains only commented documentation of the property structure; no values.

**Why:**

- Production (no profile, no `SEED_DATA`) sees no `bank.seed.customers` config at all — Spring sees an empty `List<CustomerSeedProperty>`, which is fine because `bank.seed.enabled=false` means `SeedData` is never constructed. No DTO ever materialises.
- The dev profile is the natural place for "developer wants a usable database immediately" defaults. A developer running `./gradlew :bootstrap:bootRun --args='--spring.profiles.active=dev'` gets seeding for free with no env var.
- A developer who wants seeding without the dev profile (e.g. an integration test) sets `bank.seed.enabled=true` plus an explicit `bank.seed.customers[...]` via `@TestPropertySource` or `properties = {...}` on `@SpringBootTest`. The defaults in `application-dev.yaml` are not inherited by the test profile, which is desirable — tests should declare their fixture explicitly.

### Decision 8: Clearing account is created via `Account.open(...)` + `accounts.save(...)`, NOT via `OpenAccount.open(...)`

For the clearing account, `SeedData` calls `Account.open(number, openingBalance)` directly and then `accounts.save(account)`. It does NOT use the F08 `OpenAccount.open(...)` pipeline for the clearing account.

**Why:**

- F08's `OpenAccount.open(...)` requires a clearing account to exist for any positive opening balance (the `ClearingAccountMissingException` check). Trying to open the clearing account through F08 with a positive balance would fail because the clearing account doesn't exist yet — chicken-and-egg.
- Routing the clearing account through F08 with `openingBalance = 0` and then manually crediting it would require either a non-existent "credit without a transfer" API (which we explicitly don't want — that violates the ledger guarantee) or a transfer from another bank-internal account (which doesn't exist).
- The spec is explicit: "Create the clearing account directly with a configured opening balance — this is the ONLY legitimate place in the system where an account is created with a non-zero balance without a funding transfer, because no clearing account yet exists to fund it." F09 is the legitimate exception.

**Trade-off:** the clearing account's opening balance has no matching ledger movement. F11 (`balance-drift-detection`) must therefore exclude the clearing account from "balance ≠ ledger sum" alerts on its first reconciliation pass — which F11's spec already provides via its "exclude clearing account from suspension on drift" rule.

## Risks / Trade-offs

[Risk] A bug in F09 produces a clearing account at the wrong balance → every customer open consumes from the wrong starting balance, masking F06 / F08 bugs in dev. → **Mitigation**: the `SeedDataRunnerIntegrationTest` asserts the clearing account's *exact* committed balance (configured balance minus the sum of funded customer balances), so a bug here fails the test suite immediately.

[Risk] `@ConditionalOnProperty` evaluation gotchas: a typo in the property name (e.g. `bank.seed.enable`) silently disables seeding even when the developer set `bank.seed.enabled=true`. → **Mitigation**: a unit test on `SeedDataRunner` reflects on its `@ConditionalOnProperty` annotation and asserts the literal string `bank.seed.enabled` so a typo introduced in a refactor fails the test.

[Risk] The `SEED_DATA` alias `EnvironmentPostProcessor` introduces a load-order subtlety — if a higher-precedence `PropertySource` (e.g. a Spring Cloud Config server, future) sets `bank.seed.enabled=false`, the alias is overridden silently. → **Mitigation accepted**: this is the desired precedence (explicit config wins over the env-var alias). Documented in the `application.yaml` commented block.

[Risk] A developer adds a customer to `bank.seed.customers[]` after a successful seed, observes no change, files a bug. → **Mitigation**: the spec documents the "wipe and restart" workflow; the INFO log line "dev seed skipped: clearing account already present" makes the skip visible at every startup. The H2-in-memory default makes a wipe trivial (restart the JVM).

[Risk] The `OpensAccount` functional interface is a thin abstraction over `OpenAccountService::open` — a reviewer might consider it overengineering. → **Mitigation**: the interface exists solely to keep `application/.../SeedData.java` free of `com.bank.core.infrastructure..*` imports (F00 boundary rule). Removing the interface would require either Spring annotations in the application module or an infra-to-app inverted dependency. The 5-line interface is the cleanest of the three options. Documented in the Javadoc on `OpensAccount`.

[Risk] Production safety relies on `bank.seed.enabled=false` being the default AND no `SEED_DATA=true` ever being set in production. A misconfigured deployment pipeline could leak the env var. → **Mitigation**: the spec scenario "Switch OFF leaves an empty database empty" is asserted by an integration test that runs with the default profile; the dev-seed CUST-* names are recognisable so an audit catches them; the `Skipped` log on subsequent restarts ensures an accidental seed is loud, not silent.

[Risk] The `dev` profile auto-enables seeding, so a developer who runs `--spring.profiles.active=dev` against a *production-like* external H2 file or external Postgres unexpectedly writes seed accounts. → **Mitigation accepted**: this is the documented behaviour of the dev profile. A developer pointing the dev profile at a shared database is misusing the profile; we will not solve this by neutering the seed default.

## Open Questions

None — all decisions documented above are bounded by existing spec requirements and the F00 / F08 conventions. No external dependency, schema, or HTTP-contract decisions remain.
