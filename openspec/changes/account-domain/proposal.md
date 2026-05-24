## Why

F00 (project skeleton), F04 (contract-first API), and F03 (error contract) have shipped. The `domain` module is still empty — no business class, no exception, no enum. Every downstream capability presumes an `Account` aggregate exists: F02 (immutable ledger) needs amounts and account identity to record movements, F06 (fund transfer) needs `credit`/`debit` operations to atomically move money, F08 (account opening) needs a constructor with an opening balance, F11 (balance drift detection) needs a way to suspend the account when it drifts. F01 closes that gap by introducing the `Account` aggregate and its invariants — entirely in pure Java, with no Spring, no JPA, no Jackson — so every later capability can collaborate with it without re-arguing the rules. F01 is named alongside F02 in the manifest's `[F01, F02]` build slot because both are pure domain and can ship independently of each other; F01 ships now because every other slot in the build order depends on it.

## What Changes

- Introduce the `Account` aggregate under `com.bank.core.domain` with the four named operations the published spec mandates: `credit(Money)`, `debit(Money)`, `suspend()`, `reactivate()`. No public setters; balance, status, account number, and internal id are reachable only via getters and only mutable through those four methods.
- Introduce the `AccountStatus` enum (`Active`, `Suspended`, `Closed`) with the transition rules: `Active ⇄ Suspended` via `suspend`/`reactivate`; `Closed` is terminal. Any other transition raises `IllegalStatusTransitionException`.
- Introduce three value objects so the domain speaks money and identity precisely without leaking primitives:
  - `Money` — wraps `BigDecimal` at scale `2` with `HALF_UP` rounding; static `of(BigDecimal)` and `of(String)` factories; `add`, `subtract`, `isZero`, `isGreaterThan`, `compareTo`. Refuses construction with a negative value; refuses `subtract` that would produce a negative.
  - `AccountNumber` — wraps a non-blank `String`; static `of(String)` factory; refuses blank, `null`, or whitespace-only input.
  - `AccountId` — wraps a `UUID`; static `generate()` and `of(UUID)` factories. The domain mints its own identity at creation time (`generate()`); persistence does not assign it later.
- Introduce four domain exceptions, all extending a common `DomainException` so the F03 handler can map them in one place when F06 wires them:
  - `InsufficientFundsException` — thrown by `Account.debit` when the result would not be strictly greater than zero.
  - `AccountInactiveException` — thrown by `credit`/`debit` when the status is not `Active`.
  - `InvalidAmountException` — thrown by `credit`/`debit` when the amount is null, zero, or negative (defence-in-depth on top of `Money`'s own validation).
  - `IllegalStatusTransitionException` — thrown by `suspend`/`reactivate` when the source status is `Closed`.
- Add a creation entry point: a static factory `Account.open(AccountNumber number, Money openingBalance)` that mints a fresh `AccountId`, sets the status to `Active`, and refuses a negative opening balance (`Money` will already reject this, but the factory's documented contract is clearer than relying on the VO's exception type bubbling up).
- Ship JUnit 5 tests covering every scenario in the published spec: starting state, credit/debit success, debit-to-zero-or-below rejection, non-positive amount rejection, no-mutation-outside-named-methods (via reflection scan), Active⇄Suspended transitions, Closed terminal, Suspended/Closed reject mutations.
- Surface the `debit-to-zero` open decision from `openspec/config.yaml` explicitly: the published spec says "Debit that would reach zero or below is rejected." This change implements that rule as written; the open decision becomes "closed: confirmed by spec wording, debit-to-exactly-zero is rejected." A future spec revision can revisit if needed.

No persistence, no transactions, no REST, no Spring — the domain module remains JDK-only (F00 ArchUnit enforces this).

## Capabilities

### New Capabilities
- `account-domain`: Pure-Java `Account` aggregate with identity (`AccountId` UUID + `AccountNumber` string), `Money`-typed non-negative balance held at 2-decimal precision, `AccountStatus` lifecycle (`Active ⇄ Suspended`, `Closed` terminal), and four named mutators (`credit`, `debit`, `suspend`, `reactivate`) that are the *only* way to change state. Domain exceptions (`InsufficientFundsException`, `AccountInactiveException`, `InvalidAmountException`, `IllegalStatusTransitionException`) communicate rule violations to the application layer.

### Modified Capabilities
None. F01 is the first capability to populate the `domain` module on this branch; nothing existing changes in spec terms. F03's `GlobalExceptionHandler` will later grow `@ExceptionHandler` entries for `InsufficientFundsException` and `AccountInactiveException`, but that is F06's edit (the transfer capability that throws them at endpoint boundaries), not F01's. F01 ships the exception types; F03's handler is untouched until F06 lands.

## Impact

- **Code**: Adds `Account.java`, `AccountStatus.java`, `AccountId.java`, `AccountNumber.java`, `Money.java`, `DomainException.java`, `InsufficientFundsException.java`, `AccountInactiveException.java`, `InvalidAmountException.java`, `IllegalStatusTransitionException.java` under `domain/src/main/java/com/bank/core/domain/`. Adds matching JUnit 5 tests under `domain/src/test/java/com/bank/core/domain/`.
- **Build**: No new dependencies. The `domain` module's existing `testImplementation("org.junit.jupiter:junit-jupiter")` from F00 covers the tests. `BigDecimal` and `UUID` are JDK 25 standard library.
- **Conventions**: Honours F00's "domain MUST NOT depend on Spring/JPA/Jackson/openapi-generated" ArchUnit rule. No annotations except `@Override`. No external library imports of any kind.
- **Downstream**:
  - **F02** (immutable ledger) will reference `AccountId` and `Money` when recording movements. F02's persistence (JPA entities in `infrastructure`) maps to/from these VOs in adapters; the domain stays pristine.
  - **F06** (fund transfer) will orchestrate `source.debit(amount)` and `destination.credit(amount)` inside the locking strategy from F07, and add `@ExceptionHandler(InsufficientFundsException.class)` + `@ExceptionHandler(AccountInactiveException.class)` entries to F03's `GlobalExceptionHandler`.
  - **F05** (account lookup) will read an `Account` and surface its number, balance, and status as a DTO; `Account.getBalance()` returns a `Money`, the DTO mapper formats it as a number.
  - **F08** (account opening) will call `Account.open(...)` inside a transactional boundary that also creates the funding journal entry.
  - **F11** (balance drift detection) will call `account.suspend()` on drift; the spec carves out the clearing account, which F11 enforces, not F01.
- **Open decision (debit-to-zero)**: closed in favour of the spec wording. `debit` rejects when the result is not strictly greater than zero. If a stakeholder later wants debit-to-exactly-zero allowed, that becomes a new change with a new spec delta.
- **No backwards-compat concern**: nothing currently uses `Account`; the domain module is empty. Pure addition.
