## Context

F00 set up Gradle (four modules, Flyway-managed schema, `ddl-auto=validate`, ArchUnit boundary tests). F01 shipped the pure-domain `Account`/`Money`/`AccountStatus`/`AccountNumber`/`AccountId` aggregate and exception hierarchy. F02 shipped the immutable ledger and set the precedent for persistence: `application` exposes a plain Java port, `infrastructure` provides the JPA adapter, `@Transactional` lives on the adapter, integration tests run from `bootstrap`. F03 shipped the canonical error envelope and `GlobalExceptionHandler`. F04 shipped the OpenAPI generation pipeline. F07 shipped the paired-lock primitive.

F05 sits in the manifest's `[F05, F06]` build slot after `[F07]`. The published spec at [openspec/specs/account-lookup/spec.md](openspec/specs/account-lookup/spec.md) commits to four requirements: 200 with current state on the GET endpoint, 404 `RESOURCE_NOT_FOUND` on missing account, the status enum is wide enough to represent every returnable status (including `Closed`), and the endpoint is read-only.

What this change has to introduce, not just satisfy the spec for:
- The `account` database table (no migration owns it yet).
- The `AccountEntity`/`AccountRepository`/`AccountMapper`/`AccountsJpaAdapter` (no JPA entity for accounts exists yet).
- The `Accounts` application port (no read or write port for accounts exists yet).
- The `Account.rehydrate(...)` static factory the mapper needs (F01's `Account` is private-constructor + `Account.open(...)` only).
- The `ResourceNotFoundException` domain type the 404 handler needs (F03's handler has a TODO referencing it).
- The 404 `@ExceptionHandler` method in `GlobalExceptionHandler` (F03 left a slot but no entry).
- The OpenAPI path file, the `AccountResponse` schema file, and the root-document wiring (F04 set up the pipeline but only `/v3/api-docs` is wired).

Constraints inherited from F00/F01/F02:
- `domain` is JDK-only — `Account.rehydrate(...)` stays pure Java, `ResourceNotFoundException` extends F01's `DomainException`.
- `application` may not import Spring in production sources — `Accounts` is a plain interface.
- `infrastructure` may import Spring/JPA — JPA entities go under `com.bank.core.infrastructure.persistence..`, REST controllers under `com.bank.core.infrastructure.web..`.
- Flyway owns the schema; `ddl-auto=validate` enforces match.
- Test profile uses H2 PostgreSQL-compat mode; default uses H2 vanilla. The migration must work in both.
- Tests that need Spring wiring live in `bootstrap/src/test/...` (F02 set the precedent — `infrastructure` has no `@SpringBootApplication`).

Open decisions touched: `account-status-enum-coverage` (manifest). F05 closes it by widening the `AccountResponse.status` enum to include `CLOSED`.

## Goals / Non-Goals

**Goals:**
- The lookup endpoint returns 200 with exactly the three documented fields (`accountNumber`, `balance`, `status`) when the account exists; the body shape matches the OpenAPI schema and the `OpenApiContractTest` keeps passing.
- The lookup endpoint returns 404 with `code = RESOURCE_NOT_FOUND` and a message naming the missing identifier when the account does not exist; the body matches the `ErrorEnvelope` schema (three fields only).
- A `Closed` account is representable in the response (status enum includes `CLOSED`) — the spec's "Closed account is representable" scenario passes without code changes.
- The endpoint is read-only: no journal entry created, no account row modified, no caching layer that could leak writes. Verified by row-count assertions in the integration test.
- The `account` table is introduced behind Flyway `V3__account.sql`, validates against the entity at boot, and the unique constraint on `account_number` rejects duplicate inserts.
- The `Accounts` port has both `findByNumber` (used now by F05) and `save` (used later by F06/F08/F09). Shipping both at once means F06 can be a pure stitch of F05+F07+F02.
- `Account.rehydrate(...)` is package-public on the `com.bank.core.domain` package (so mappers in `infrastructure.persistence.account` can call it) and documented as mapper-only via class-level Javadoc, mirroring F02's `JournalEntry.rehydrate(...)` precedent.
- `ResourceNotFoundException` becomes the single domain-level signal for a missing resource. F08/F11 and any later capability that needs to surface 404 can throw the same type; the 404 handler maps every instance the same way.
- ArchUnit rules (`domainHasNoFrameworkDependencies`, `applicationHasNoFrameworkDependencies`, `jpaEntitiesLiveInInfrastructurePersistence`, the F07 concurrency confinements) continue to pass.

**Non-Goals:**
- **Account creation / mutation HTTP endpoints.** F08 (account opening) owns the creation path; F05 only adds the underlying `save` port + adapter for downstream use. No `POST /api/v1/accounts` lands here.
- **Caching.** No HTTP cache headers, no in-memory cache. The spec scenario "Balance reflects committed transfers immediately" rules out staleness; the simplest implementation is no cache at all.
- **Pagination / search.** The endpoint is one-account-at-a-time by exact account number. Listing endpoints are out of scope.
- **Authentication / authorization.** The existing surface is unauthenticated; F05 stays consistent. A future security capability can add it to all endpoints at once.
- **Idempotency-key handling.** GET is idempotent by HTTP definition; the manifest's `idempotency` open question is about `POST /transfers` (F06).
- **F11's per-account ledger-sum query.** F05 ships the `Accounts` adapter but not the `LedgerMovementRepository.sumByAccountIdUpTo(...)` query F11 needs. That query lives in F11.
- **A separate `account_lock` table.** F07's lock map keys by `AccountNumber` and lives in the JVM; F05 does not need to add a DB-level lock table. F06 will use F07's locker through the port.
- **Compensating "soft-delete" or audit log on the account table.** Spec deliberately calls account a mutable aggregate; F05 keeps it that way (no append-only history table for accounts — the ledger is the history).
- **Currency code on the response.** F02 / F01 are currency-less today; F05 stays currency-less.

## Decisions

### `balance` serialises as a string with two decimals, not a JSON number

JSON's number type loses trailing zeros (`{"balance": 10.00}` is identical to `{"balance": 10}` after JSON parsing) and round-trips through floating point on some clients. The spec says "the underlying `BigDecimal` has `scale == 2`" — that fidelity needs to survive the wire. Choosing `type: string` with `pattern: "^\\d+\\.\\d{2}$"` keeps `"10.00"` as `"10.00"` everywhere. The DTO field is `String`; the mapper formats via `account.balance().value().toPlainString()` (which preserves trailing zeros because the `Money` invariant is `scale == 2`).

Rejected alternative — `type: number` with `multipleOf: 0.01`. Looks neat in the contract; clients still receive trailing zeros stripped (Jackson serialises `BigDecimal` without trailing zeros by default), so the wire shape doesn't match the domain invariant. The string approach is the convention that v1-basic and most real banking APIs use.

### `Account.rehydrate(...)` lives on the domain aggregate, parallel to `JournalEntry.rehydrate(...)`

F02 hit this same problem: a JPA mapper needs to construct a domain aggregate from persisted state without re-running the `open(...)` factory (which would mint a new `AccountId` and reset `status` to `ACTIVE`). F02's answer: add `public static rehydrate(...)` on `JournalEntry`, with class-level Javadoc explicitly stating the mapper is the only legitimate caller. F05 mirrors that pattern on `Account`. Method is `public static` (Java packages aren't truly modular; the mapper lives in `com.bank.core.infrastructure.persistence.account` so true package-private is impossible without putting the mapper in the domain package, which violates module boundaries).

Rejected alternative — reflection-based hydration in the mapper. Reflection bypasses the `final` field invariants and creates a runtime-only contract; introducing the rehydrate factory is the explicit, documented version of the same operation and stays type-safe.

Rejected alternative — a separate `AccountSnapshot` record passed to/from the adapter, with the adapter constructing the domain aggregate. Adds a fifth class for the mapper to translate through; F02 didn't do this for journals, so F05 stays consistent.

### `Accounts` port ships both `findByNumber` and `save` in this change

The lookup endpoint only needs `findByNumber`. Ship `save` anyway because:
- F06 (fund transfer) needs `save` for each of source and destination. F06 doesn't ship a port edit if F05 includes `save`.
- F08 (account opening) needs `save`.
- The port stays plain Java either way — no Spring leaks from including `save`.
- ArchUnit / module-boundary cost is zero.

Rejected alternative — ship only `findByNumber` now, add `save` in F06. Two-step API growth on a port pin'd by 12 specs is more disruption than the +5 LoC `save` method costs.

### `ResourceNotFoundException` is generic, not `AccountNotFoundException`

F03's handler TODO uses the name `ResourceNotFoundException` precisely because other capabilities will need the same 404 mapping (a missing journal id in F10, a missing transfer reference if one is ever added). One exception type that carries `(String resourceType, String identifier)` covers every case; one handler entry handles every case. The handler reads `resourceType` and `identifier` for the message but does not echo them as separate JSON fields — the `ErrorEnvelope` is fixed at three fields per F03's spec.

Rejected alternative — `AccountNotFoundException extends ResourceNotFoundException`. Adds a class hierarchy with no behavioural difference; one type is enough.

### OpenAPI: enum case is uppercase, separate from the domain enum case

F01's `AccountStatus` is `ACTIVE`, `SUSPENDED`, `CLOSED` (uppercase). The OpenAPI enum uses the same uppercase form. The mapper does a direct `name()`-to-string conversion. If a future change introduces a status with mixed case for any reason, the mapper would do the conversion explicitly rather than relying on `name()`.

Rejected alternative — declare the enum as title-cased (`Active`/`Suspended`/`Closed`) per the legacy spec wording. The published REQUIREMENTS doc uses both cases inconsistently; uppercase matches the existing `verification_status` enum convention F02 set on the database side, and matches the F01 Java enum constants.

### `AccountController` lives in `infrastructure.web.account`, not `infrastructure.web`

F04 set the precedent: `OpenApiController` lives in `com.bank.core.infrastructure.web` directly; the error-handler controller lives in `com.bank.core.infrastructure.web.error`. F05 adds a per-feature sub-package `infrastructure.web.account` so future per-account-related endpoints (e.g. F08's account opening) sit beside it. Keeps the controller layout aligned with the persistence layout (`persistence.ledger`, `persistence.account`).

### Adapter uses Spring Data JPA's `findByAccountNumber` derived query, not JPQL

The query is a single-field lookup with a unique index. `Optional<AccountEntity> findByAccountNumber(String accountNumber)` is generated automatically and uses the index. JPQL would add no value and one more thing to maintain.

### Read-only-ness is enforced two ways

The endpoint contract says it does not mutate state. F05 enforces this by:
1. `@Transactional(readOnly = true)` on `AccountsJpaAdapter.findByNumber` — Hibernate skips dirty-checking and flushing on commit, so an accidentally mutated aggregate would not be persisted.
2. An integration test that asserts `journal_entry` row count and `account` row state are unchanged across a `GET` request.

The two together catch both classes of bug: the test catches accidental writes from the controller layer; the `readOnly` annotation catches them at the JPA boundary if a future refactor adds a mutation pathway.

### Migration name and content

`bootstrap/src/main/resources/db/migration/V3__account.sql`:

```sql
CREATE TABLE account (
    id              UUID            NOT NULL PRIMARY KEY,
    account_number  VARCHAR(64)     NOT NULL,
    balance         NUMERIC(19, 2)  NOT NULL,
    status          VARCHAR(16)     NOT NULL,
    CONSTRAINT balance_non_negative CHECK (balance >= 0),
    CONSTRAINT status_valid CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);
CREATE UNIQUE INDEX idx_account_account_number ON account(account_number);
```

`account_number VARCHAR(64)` — generous; legacy F07 draft uses string comparison for canonical lock ordering and 64 chars covers IBAN (34) + 30 of slack. `balance NUMERIC(19, 2)` matches F02's `ledger_movement.amount` so future joins / aggregates don't fight types. UUID PK matches F01's `AccountId` and is portable across H2 vanilla and PostgreSQL-compat.

Rejected alternative — `balance NUMERIC(38, 2)` or `DECIMAL`. `NUMERIC(19,2)` covers ~$10^17 (well above any realistic balance) and keeps the schema tidy.

### Logging at the 404 boundary

The new 404 handler entry logs at INFO: `Account lookup for {} returned 404`. Operators monitoring for enumeration attacks (an attacker probing many account numbers) can build a count-by-IP alert on this log line. INFO is the right level because the request is *not* an error from the service's perspective — it's a normal "no such account" response.

Rejected alternative — DEBUG-level. Operators can't enable DEBUG in production without flooding the log. INFO + structured logging (existing F00 setup) is the right pairing for security-relevant events that aren't faults.

## Risks / Trade-offs

- **`Account.rehydrate(...)` is callable from any code that imports `com.bank.core.domain`.** A future application-layer caller could bypass `Account.open(...)` and re-create accounts at will, breaking the "new account starts Active" invariant. → Mitigation: class-level Javadoc names the mapper as the only legitimate caller; F02's `JournalEntry.rehydrate(...)` has been in place for one change without misuse and the precedent is well-understood. An ArchUnit rule restricting `rehydrate` callers to `com.bank.core.infrastructure.persistence.account` is possible but not yet justified — defer until a misuse appears.
- **`save` is shipped on the `Accounts` port but unused in F05.** A reader scanning the port might wonder why; the Javadoc explains the F06/F08/F09 future consumers. → Mitigation: Javadoc lists downstream consumers, parallel to F02's `JournalEntries` Javadoc.
- **`AccountResponse.balance` is a `String`, not a `BigDecimal`.** Generated DTO clients in Java would have to parse it back to `BigDecimal`. → Mitigation: the contract is explicit (`type: string`, `pattern` documenting the format), and the trade-off (fidelity over ergonomics) matches industry norms. Consumers in non-Java languages get a string consistently across all backends.
- **The unique constraint on `account_number` is the only mechanism preventing duplicates.** F08/F09 will rely on this. → Mitigation: the constraint is enforced at the DB layer (catches concurrent insert races); the adapter's `save` method propagates `DataIntegrityViolationException` upward when a duplicate occurs, where F08's use case can map it appropriately.
- **`ResourceNotFoundException` lives in the domain but is thrown by the controller (a `web` class).** A purist could argue the controller is throwing a domain type without using a domain method. → Mitigation: this is the same pattern F03's TODO documented for F05 in the first place; F01's exceptions (`InsufficientFundsException`, `AccountInactiveException`) will also be thrown by F06's controller path. The pattern is "controller wraps adapter lookup, throws domain exception, handler maps to envelope" and it's deliberate.
- **The integration tests need account rows to exist before the GET request.** Without F08/F09, tests must insert rows directly. → Mitigation: `AccountsJpaAdapter.save(...)` is part of this change; tests use it (with a `@Transactional` setUp method) rather than raw JDBC. This is the F02 pattern (use the adapter under test as the test fixture).
- **Spring will fail to start if Hibernate's `ddl-auto=validate` finds a column-type mismatch between the entity and the V3 migration.** → Mitigation: integration tests run with the test profile + Flyway-managed schema, and `clean build` runs them. A mismatch fails the build before the change can land.

## Migration Plan

Pure addition. No data migration, no rolling deploy, no feature flag.

1. **Ship the migration, entity, repository, mapper, adapter, port, and exception together.** Splitting across changes would leave a window where the entity references a non-existent table, or the adapter has no port to implement.
2. **Ship the controller, OpenAPI path file, schema file, and root-document wiring together.** Splitting would create a window where the generated `AccountsApi` interface exists but no controller implements it (Spring would fail to start), or the controller exists but the generator never produces the interface (compile error).
3. **Ship the `GlobalExceptionHandler` edit alongside `ResourceNotFoundException`.** Adding the handler entry without the exception type breaks compilation; shipping the exception without the handler entry leaves the 404 unmapped and tests would fail.
4. **Default config is safe.** No new property is needed. The default OpenAPI generation picks up the new files.
5. **Rollback** is `git revert` of the change: the table is dropped only if Flyway is set to "destructive rollback" (it isn't); a forward-only `V4__drop_account.sql` would be required if the rollback needs to undo the schema, but in practice F06/F08/F09 will land soon and the table will be used. Reverting the code is mechanically safe — no caller in the repository imports the new port yet (F05 is the first consumer); the table persists harmlessly.
6. **Forward link**: F06 will inject `Accounts` and use `findByNumber` + `save`; F08 will use `save` for fresh aggregates; F09 will use `save` for the clearing account + demo customers; F11 will use `findByNumber` to load aggregates for balance comparison.

## Open Questions

None blocking F05. Carry-forward items:

- **Authentication on the public endpoints.** Not in scope for F05. A future cross-cutting security change can apply auth to every endpoint at once.
- **Caching `GET /api/v1/accounts/{number}` responses.** Not in scope. The spec's "Balance reflects committed transfers immediately" requirement rules out client-side staleness without an explicit invalidation pathway; F05 ships no cache. If hot-account read traffic ever needs caching, it goes in its own change with explicit invalidation hooks tied to F06's commit boundary.
- **Account number format / validation rules.** The spec says the response carries "the matching account number" — no format rules are imposed. F01's `AccountNumber` rejects null and blank. If business rules ever demand a specific format (IBAN, ABA, alphanumeric only), a future change can extend `AccountNumber`'s constructor; the wire format stays `string`.
