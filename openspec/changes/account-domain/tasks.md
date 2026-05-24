## 1. Value objects

- [x] 1.1 `Money` final class with scale-2 / HALF_UP `BigDecimal`, `ZERO` constant, `of(BigDecimal)`/`of(String)` factories. Constructor refuses null and negative.
- [x] 1.2 `add`, `subtract` (throws `InvalidAmountException` on negative result), `isZero`, `isGreaterThan`, `compareTo`, `toBigDecimal`, `toString` canonical.
- [x] 1.3 `equals` uses `compareTo == 0`; `hashCode` strips trailing zeros so `Money.of("10")` and `Money.of("10.00")` are equal and hash equally.
- [x] 1.4 `AccountId` record wrapping `UUID` with non-null compact constructor, `generate()`/`of(UUID)` factories.
- [x] 1.5 `AccountNumber` record wrapping `String` with non-null + non-blank validation (`IllegalArgumentException`), `of(String)` factory.

## 2. Status enum

- [x] 2.1 `AccountStatus` enum with `ACTIVE`, `SUSPENDED`, `CLOSED`, plus `isActive()` and `isClosed()`.

## 3. Exception hierarchy

- [x] 3.1 `DomainException` abstract subclass of `RuntimeException` with `protected DomainException(String)` constructor.
- [x] 3.2 `InsufficientFundsException` with `accountId`, `attempted`, `available` accessors and informative message.
- [x] 3.3 `AccountInactiveException` with `accountId`, `status` accessors.
- [x] 3.4 `InvalidAmountException` with `reason` accessor.
- [x] 3.5 `IllegalStatusTransitionException` with `accountId`, `from`, `to` accessors.

## 4. Account aggregate

- [x] 4.1 `Account` is `public final class` with `private final` id+number and `private` mutable balance+status. Sole constructor is `private`.
- [x] 4.2 `Account.open(AccountNumber, Money)` factory mints `AccountId.generate()`, sets `ACTIVE`. Null-checks both args.
- [x] 4.3 Records-style accessors `id()`, `number()`, `balance()`, `status()`.
- [x] 4.4 `credit(Money)` checks active + positive, then `balance = balance.add(amount)`.
- [x] 4.5 `debit(Money)` adds the strict `isGreaterThan` check, throws `InsufficientFundsException` with full context.
- [x] 4.6 `suspend()` rejects from `CLOSED`, otherwise sets `SUSPENDED` (idempotent from `SUSPENDED` and `ACTIVE`).
- [x] 4.7 `reactivate()` rejects from `CLOSED`, otherwise sets `ACTIVE` (idempotent from `ACTIVE`).
- [x] 4.8 No `equals`/`hashCode` on `Account`.

## 5. Tests

- [x] 5.1 `MoneyTest` — 11 tests covering construction, rounding (`10.005 → 10.01`), trailing-zero equality, compareTo, add/subtract (including negative-result reject), isZero, isGreaterThan boundaries, canonical toString.
- [x] 5.2 `AccountIdTest` — generate yields distinct non-null UUIDs, of(null) rejects, equality follows UUID.
- [x] 5.3 `AccountNumberTest` — blank/whitespace/null rejected, valid accepted, case-sensitive equality.
- [x] 5.4 `AccountStatusTest` — isActive/isClosed for each value.
- [x] 5.5 `AccountTest` — 15 tests covering every published-spec scenario plus the `balance == amount` boundary, idempotency, exception payloads.
- [x] 5.6 `AccountReflectionTest` — asserts only-private-constructors, no setter methods, final identity fields, non-final balance/status, all mutators are public, `Account` is `final`.
- [x] 5.7 `DomainExceptionsTest` — every exception extends `DomainException`/`RuntimeException`, getters return passed-in values, messages are non-blank and contain key identifying values.

## 6. Verification

- [x] 6.1 `./gradlew :domain:test` — all tests pass.
- [x] 6.2 `./gradlew :bootstrap:test` — F00 `ModuleBoundaryTest`, F04 `NoApiDelegateTest`, and F03 handler tests all still pass.
- [x] 6.3 `./gradlew clean build` — full project green.
- [x] 6.4 `grep -rE "^import (org\\.springframework|jakarta\\.persistence|org\\.hibernate|com\\.fasterxml\\.jackson|org\\.openapitools|com\\.bank\\.core\\.(dto|api))" domain/src/main/java/` returns zero matches.
- [x] 6.5 `grep -n "public Account(" domain/src/main/java/com/bank/core/domain/Account.java` returns zero matches.
- [x] 6.6 `./gradlew :bootstrap:bootRun` + `curl /actuator/health` returns `200 UP` with `db: UP` — startup unaffected by adding domain classes.

## 7. Forward-compatibility notes

- [x] 7.1 `Account` class-level Javadoc documents the F02 (package-private rehydration constructor), F06 (debit/credit inside locking), and F11 (suspend on drift) forward paths.
- [x] 7.2 `Money` class-level Javadoc flags the single-implicit-currency assumption from `REQUIREMENTS.md`.
- [x] 7.3 No edit to `openspec/config.yaml`. The `debit-to-zero` open decision is closed in the proposal text; updating the manifest is left as a separate hygiene change.

## 8. Hygiene

- [x] 8.1 Deviation note below.
- [x] 8.2 `INTRODUCTION.md`'s "Rich domain model" paragraph already describes the intent F01 implements; no edit needed.

## Implementation notes / deviations from design

- **`AccountNumber` blank validation throws `IllegalArgumentException`**: chose JDK-standard `IllegalArgumentException` over a domain-vocabulary exception. Rationale carried from design.md: `AccountNumber.of(...)` is called from the application/infrastructure boundary where input validation is expected, and the API layer (F05/F08) will validate via Jakarta Bean Validation on DTOs before the value ever reaches the domain. `IllegalArgumentException` keeps the domain exception vocabulary reserved for business-rule violations (insufficient funds, inactive account, illegal transition, invalid amount).
- **`Account.closedAccount()` test helper uses reflection**: there is no public path to `CLOSED` status on this branch (F08 will introduce the close flow). The `AccountTest` helper sets the `status` field via reflection to set up tests for the terminal-state scenarios from the published spec. This is test-only; production code never bypasses the named mutators.
- **`Money.subtract` going negative throws `InvalidAmountException`**: design.md noted this as a defensive path since `Account.debit` already guards against it. Implementation matches the design — the test `subtractWouldGoNegativeThrows` documents the contract.
