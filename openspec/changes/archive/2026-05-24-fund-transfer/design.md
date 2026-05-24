## Context

F06 is the first capability that *composes* multiple ports inside a single transactional, locked region. Every prior capability either added a primitive (F01 domain, F02 ledger, F07 locker), wired infrastructure (F00, F03, F04), or shipped a read endpoint (F05). F06 wires F01/F02/F05/F07 together into the service's primary write path.

Constraints inherited from prior changes:
- `domain` is JDK-only. The new `SameAccountTransferException` is plain Java extending `DomainException`.
- `application` is Spring-free. The new `TransferFunds` use case is plain Java, registered as a Spring bean from bootstrap via `@Bean`.
- `infrastructure` can use Spring. `TransferController`, `TransferRequestMapper`, and the four new `GlobalExceptionHandler` entries live here.
- `bootstrap` owns wiring. The `Clock` bean and the `TransferFunds` bean go in `BankCoreApplication`.
- F00's ArchUnit boundary tests + F07's confinement tests continue to pass.
- F02's `transactional-in-application` open decision is closed: no Spring in `application` production sources.
- F07's `AccountLocker` requires an active transaction at call time and releases locks on `afterCompletion` (commit or rollback).

The published spec at [openspec/specs/fund-transfer/spec.md](openspec/specs/fund-transfer/spec.md) commits to five requirements (POST endpoint shape, payload validation, business-rule error mapping, atomicity, journal description/status). This change implements all five and adds two more (lock ordering inside the use case, transactional boundary location) that document where F06 sits relative to F07 and F03.

Open decisions touched: `self-transfer` (manifest) — closed here in favour of "reject as BAD_REQUEST_PAYLOAD."

## Goals / Non-Goals

**Goals:**
- `POST /api/v1/transfers` returns 204 with empty body when a valid transfer between two Active accounts with sufficient funds commits. Both balances reflect the move on subsequent `GET /api/v1/accounts/...` reads. Exactly one `PENDING` journal entry with two movements (one DEBIT on source, one CREDIT on destination) is persisted.
- The endpoint never produces a partial effect. Crash between any of the four writes (source UPDATE, destination UPDATE, journal INSERT, movement INSERTs) leaves zero observable changes.
- Concurrent counter-direction transfers between the same account pair never deadlock — F07's canonical lock order is the guarantee, the use case just calls `withPairedLocks(source, destination, ...)` and lets the locker canonicalize.
- Every spec-named error condition maps to the canonical envelope at the documented HTTP status: missing fields/non-positive amount → 400 `BAD_REQUEST_PAYLOAD`; missing account → 404 `RESOURCE_NOT_FOUND`; inactive account → 400 `ACCOUNT_INACTIVE`; insufficient funds → 400 `INSUFFICIENT_FUNDS`. Self-transfer → 400 `BAD_REQUEST_PAYLOAD` (closes the open decision).
- The use case is unit-testable with mocked ports — no Spring needed for the orchestration tests.
- The use case loads aggregates *inside* the locked region (lock-then-load), so the aggregates can never be stale relative to the work that mutates them.
- F03's handler grows by four entries and the class-level Javadoc is trimmed of the now-fulfilled F06 TODOs.

**Non-Goals:**
- **Idempotency.** Manifest open decision, explicitly deferred. Retried POST requests today produce duplicate journal entries. A future change introduces `Idempotency-Key` header handling.
- **Authentication / authorization.** Consistent with the rest of the surface — no auth in this iteration.
- **Multi-currency transfers.** Money is currency-less per F01; this iteration does not change that.
- **Transfer history endpoint.** No `GET /api/v1/transfers` or similar — the ledger is the system of record; an explicit history endpoint can land in a future change.
- **Cross-currency / FX conversion.** Out of scope.
- **Scheduled / future-dated transfers.** Synchronous-only.
- **A new error code.** Reuses the F03 taxonomy (`BAD_REQUEST_PAYLOAD`, `RESOURCE_NOT_FOUND`, `ACCOUNT_INACTIVE`, `INSUFFICIENT_FUNDS`). Self-transfer reuses `BAD_REQUEST_PAYLOAD` rather than introducing `SAME_ACCOUNT_TRANSFER` — adding a new code requires updating the OpenAPI `ErrorEnvelope.code` enum, which is a bigger surface change than the spec needs.
- **Bulk transfers.** One source, one destination, one amount per request.
- **Account-level `@Version` optimistic locking.** F07's pessimistic locking is the primary mechanism. F07's spec mentions optimistic checks as "defence in depth"; F06 does not add `@Version` to `AccountEntity` — that can land in a follow-up if the operational need appears.
- **Transactional boundary on the use case.** The use case stays plain Java; the boundary lives on the controller (justified below).

## Decisions

### Transactional boundary lives on the controller method

The F07 `AccountLocker` requires an active transaction when called. The use case calls the locker. So the use case has to run inside a transaction. Options:

1. **`@Transactional` on the use case class** — forbidden, `application` is Spring-free.
2. **`@Transactional` on a Spring wrapper around the use case** in `infrastructure` — adds a layer of indirection for no behavioural benefit; the controller already lives in `infrastructure` and already runs in a request-scoped context.
3. **`@Transactional` on the controller method** — the simplest place to put it. Spring's `@Transactional` proxy works correctly on `@RestController` methods because Spring MVC dispatches through the proxy. The controller becomes the wiring point for the transactional boundary, the lock acquisition, and the response shape.
4. **Programmatic `TransactionTemplate` in the controller** — works, but `@Transactional` is idiomatic and reads more cleanly.

Chosen option 3. This is the *only* controller in this branch carrying `@Transactional`, which makes the choice visible — code review can spot it. F05's `AccountController` does not need `@Transactional` because the read adapter manages its own `@Transactional(readOnly = true)`. F06's write controller does because it orchestrates multiple ports inside paired locks.

Rejected option 2 (wrapper around use case): would introduce a `TransactionalTransferFunds` adapter in infrastructure that just delegates with `@Transactional` — pointless when the controller can carry the annotation directly.

### Use case is lock-then-load, not load-then-lock

The naive implementation reads source and destination first (to throw 404 early), then acquires locks, then re-uses the already-loaded aggregates. That's wrong: between the read and the lock, another concurrent transfer can mutate the same accounts; the in-memory aggregate is now stale. Saving the stale aggregate overwrites the concurrent change.

The correct order is:
1. Cheap pre-check: same-account rejection (no aggregate load needed).
2. Acquire paired locks via F07's canonical-order primitive.
3. *Inside the locked region:* load both aggregates from the `Accounts` port. If either is missing, throw `ResourceNotFoundException` — the locker's `afterCompletion` callback releases the locks on the rollback.
4. Mutate aggregates via domain mutators (`debit`, `credit`), which may throw `InsufficientFundsException` or `AccountInactiveException` — both propagate out of the locked region; locks release on rollback.
5. Persist both aggregates and the journal entry via the ports.
6. Locker's afterCompletion callback releases the locks on the commit.

The cost of loading inside the locked region instead of before is two extra round-trips per transfer happening while holding two locks. Under a stress test of contended pairs, this serialises transfers slightly more aggressively than the load-before-lock variant — but the load-before-lock variant is *wrong* for correctness, not slow, so the trade-off isn't worth re-litigating.

### Self-transfer rejection is a domain exception, not a controller-level check

Options for rejecting `source == destination`:
- Inline check in the controller throwing some Spring exception → couples controller to error mapping.
- Bean-validation annotation on `TransferRequest` → would require a custom validator class; not justified for a single check.
- Domain exception thrown by the use case → consistent with how every other business-rule rejection works (use case throws, F03 handler maps).

Chosen: introduce `SameAccountTransferException` in `com.bank.core.domain` extending `DomainException`. The use case throws it as the *first* check (before any port call, before the lock acquisition). F03's handler maps to `400 BAD_REQUEST_PAYLOAD`.

This matches F05's precedent: F05 introduced `ResourceNotFoundException` and added one handler entry; F06 introduces `SameAccountTransferException` plus three handler entries for the existing F01 exceptions. The total cost is one new exception + four handler entries, each <10 lines.

### Bean validation handles missing fields and non-positive amounts

The spec scenarios for `missing source`, `non-positive amount`, etc. are all 400 `BAD_REQUEST_PAYLOAD`. F03's `GlobalExceptionHandler` already maps `MethodArgumentNotValidException` and friends to that code with a field-naming message. The OpenAPI `transfer-request.yaml` declares:

```yaml
required: [sourceAccountNumber, destinationAccountNumber, amount]
properties:
  sourceAccountNumber:
    type: string
    minLength: 1
  destinationAccountNumber:
    type: string
    minLength: 1
  amount:
    type: number
    minimum: 0.01
```

The generator emits `@NotNull`, `@Size(min = 1)`, and `@DecimalMin("0.01")` on the DTO fields. Spring's request binder runs validation before the controller method, and any failure becomes `MethodArgumentNotValidException` which F03's handler already catches. So none of the "missing X" or "non-positive amount" scenarios require new handler code — they reuse F03's existing path.

The amount field is generated as `BigDecimal` (the `type: number` default in the openapi-generator's Spring config). Jackson's `BigDecimalDeserializer` preserves the input precision when reading JSON numbers — `100.00` becomes a `BigDecimal` with `scale == 2`, and `100` becomes a `BigDecimal` with `scale == 0`. The use case calls `Money.of(amount)` which rescales to 2 via `HALF_UP`, so the domain invariant holds regardless of input precision. A client sending `100.001` would silently round to `100.00` — acceptable behaviour, and consistent with the rest of the surface.

Rejected: emitting `amount` as `type: string` with a pattern. F05 chose `string` for `balance` to preserve trailing zeros on the *response* wire. For requests, the parsing direction is reversed and `BigDecimal` round-trips correctly through Jackson when declared as `type: number`. The asymmetry is intentional and matches the convention of most banking APIs.

### `Clock` is injected, not hardcoded

The journal entry needs a timestamp. Options:
- `Instant.now()` inline in the use case — fast, but tests can't assert exact timestamps.
- Inject a `java.time.Clock` — same defaults in production (`Clock.systemUTC()`), tests can swap to `Clock.fixed(...)` or a custom mutable clock for assertion.

Chosen: inject a `Clock`. Register the production bean in `BankCoreApplication`:

```java
@Bean
Clock systemClock() {
    return Clock.systemUTC();
}
```

The application unit test can construct `TransferFunds` directly with `Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC)` and assert the journal entry's timestamp matches exactly.

### One handler entry per exception type (no merged handlers)

F03's existing handler merges `MethodArgumentNotValidException`, `BindException`, `ConstraintViolationException`, etc. into one `@ExceptionHandler({...})` because they all map to the same code with the same message construction. The new F06 entries are distinct codes (`INSUFFICIENT_FUNDS`, `ACCOUNT_INACTIVE`, two `BAD_REQUEST_PAYLOAD` cases with different messages) — merging them would lose the per-type message. So each gets its own one-method `@ExceptionHandler`. Reads cleanly; each method is ≤8 lines including the log line.

Each new entry logs at INFO. These are *expected* business-rule rejections — a SUSPENDED account targeted by a transfer is a valid client error, not a service fault. Operators should not be paged. Logging at WARN/ERROR for expected business rejections is a classic alert-fatigue mistake; INFO is correct.

### `TransferFunds` is wired via `@Bean` in `BankCoreApplication`, not `@Component` in application

`@Component` on a class in `application` would import Spring into `application`'s production sources, breaking F00's `applicationHasNoFrameworkDependencies` ArchUnit rule. The wiring approach options:

1. `@Bean` factory method in `BankCoreApplication` — simplest; the wiring lives where every other piece of wiring lives.
2. New `@Configuration` class in `infrastructure.config` — adds a file for one method.
3. New `@Configuration` class in `bootstrap.config` — adds a file for one method.

Chosen option 1. Two lines in `BankCoreApplication`. The same file already grew an `@EnableConfigurationProperties` line for F07. The class is "wiring of last resort" by F00 convention, so it's the right home.

### `TransferRequestMapper` is a separate class

The mapping `TransferRequest → TransferCommand` is two-line (`AccountNumber.of(...)`, `Money.of(...)`) wrapped in a constructor call. Could be inlined in the controller. Pulling it into a `@Component` keeps the controller's three-line body literal:

```java
TransferCommand command = mapper.toCommand(request);
transferFunds.transfer(command);
return ResponseEntity.noContent().build();
```

…which is the F00 "orchestration-shells-thin" convention. Adding one tiny class is cheaper than reading a 7-line controller method.

## Risks / Trade-offs

- **Controller-level `@Transactional` is uncommon.** Most Spring apps put `@Transactional` on a service. F06 puts it on the controller because the use case is plain Java and the controller is the wiring point for the transactional boundary. → Mitigation: `TransferController` is the only controller carrying `@Transactional` in this branch; the class-level Javadoc names why. A reviewer surveying controllers can see at a glance that this one is special. A future "convert to service-bean wrapper" refactor is a single-file change that doesn't touch the use case or its tests.
- **Self-transfer rejection adds a new exception type for one use case.** Could feel like over-engineering for a single check. → Mitigation: it's three lines (constructor + accessor) and one handler entry, matching the F05 precedent (`ResourceNotFoundException`). The alternative — a controller-level check throwing a generic Spring exception — couples the controller to error formatting in a way the rest of the codebase doesn't.
- **Lock-then-load means a missing account is detected *after* lock acquisition.** A 404 path therefore goes through the locker. The locker takes the canonical-first lock on whatever `min(source, destination)` is — even if the account doesn't exist (the locker doesn't validate the number). → Mitigation: the locker is a JVM map keyed by `AccountNumber`, so taking a lock on a never-seen number creates an entry that's harmless. The `ResourceNotFoundException` rolls back the transaction, the locker releases on `afterCompletion`. Cost: one wasted `ReentrantLock` allocation per missing-account request. Worth the simpler use case (lock-then-load is correct; load-then-lock is racy).
- **The journal entry description includes plaintext account numbers.** If account numbers ever become sensitive (e.g. PII), this would leak them into the ledger. → Mitigation: account numbers are externally visible identifiers used in the URL path of `GET /api/v1/accounts/{accountNumber}`, so they are already non-secret by definition.
- **Crash exactly between `accounts.save(destination)` and `journals.save(entry)` rolls back through Hibernate's transaction manager** — Spring's `@Transactional` on the controller wraps the entire dispatch. All four writes share the same JDBC connection. → Mitigation: this is the standard Spring Boot + JPA pattern and the integration test `TransferAtomicityIntegrationTest` exercises it by spying on `JournalEntries.save(...)` to throw mid-flight and asserting no balance change and no journal row.
- **`Money.of(...)` rounds silently.** A client sending `amount: 100.001` gets the rounded `100.00` applied. → Mitigation: documented in the OpenAPI schema description (`"Amount; precision beyond two decimal places is rounded half-up to two decimals."`), and consistent with how `balance` is serialised in F05.
- **No `Idempotency-Key` header.** A retried request produces a duplicate journal entry. → Mitigation: manifest open decision, explicit non-goal of this change. Operators retrying transfers will need to scan the ledger for duplicates until the future idempotency change lands.

## Migration Plan

Pure addition. No data migration, no rolling deploy, no feature flag.

1. **Ship the use case, the command record, and the controller together.** Without the controller, the bean has no caller; without the use case, the controller has nothing to call.
2. **Ship the four `GlobalExceptionHandler` entries alongside the use case.** Without the handlers, the F01 exceptions propagate to the catch-all 500 handler, breaking the spec's "business rule rejections map per error contract" requirement.
3. **Ship `SameAccountTransferException` together with its handler entry.** Independently they're useless; together they close the self-transfer open decision.
4. **Ship the OpenAPI path/schema additions and the controller together.** The generator produces `TransfersApi` from the contract; without the contract additions, `TransferController` can't implement the interface.
5. **No config changes required.** F07's `bank.transfer.lock-wait-ms` covers F06's lock waits transparently.
6. **Rollback** is `git revert` of the change. The endpoint disappears; the OpenAPI document shrinks; no schema change to undo; no caller in the repository imports the use case (F06 is the first consumer).
7. **Forward link**: F08 (account opening) will inject `TransferFunds` to fund newly opened accounts from the clearing account. F10 (journal verification) will sweep the `PENDING` journal entries F06 produces.

## Open Questions

None blocking F06. Carry-forward items:

- **Idempotency on `POST /api/v1/transfers`.** Manifest open decision, deferred. A future change introduces `Idempotency-Key` header handling and a uniqueness check on the key.
- **Should F08's account-opening flow call `TransferFunds.transfer(...)` directly or duplicate the orchestration internally?** F08's design will decide; F06's use case is composable either way.
- **Authentication.** No auth on any endpoint. A future cross-cutting security change can add it.
- **Should the response carry the new balances?** Spec says 204 with empty body — callers re-read via F05's lookup. A future change could add a 200-with-balances response if round-trip latency becomes a concern.
