## 1. Value objects

- [ ] 1.1 Create `domain/src/main/java/com/bank/core/domain/Money.java` as a `final class` with a private `BigDecimal amount` field (scale `2`, `HALF_UP`). Static factories `of(BigDecimal)`, `of(String)`, and constant `ZERO`. Constructor refuses null and negative values.
- [ ] 1.2 Implement `add(Money)`, `subtract(Money)` (throws `InvalidAmountException` if result negative), `isZero()`, `isGreaterThan(Money)`, `compareTo(Money)`, `toBigDecimal()`, `toString()` (canonical scale-2 form).
- [ ] 1.3 Override `equals` and `hashCode` so values that compare equal are equal — strip trailing zeros for hashing, use `compareTo == 0` for equals.
- [ ] 1.4 Create `domain/src/main/java/com/bank/core/domain/AccountId.java` as a `record AccountId(UUID value)` with non-null compact constructor, plus `static generate()` and `static of(UUID)`.
- [ ] 1.5 Create `domain/src/main/java/com/bank/core/domain/AccountNumber.java` as a `record AccountNumber(String value)` with non-null and non-blank validation in the compact constructor (throw `IllegalArgumentException` on blank — preserves domain exception vocabulary for business-rule violations; structural validation is JDK-standard `IllegalArgumentException`), plus `static of(String)`.

## 2. Status enum

- [ ] 2.1 Create `domain/src/main/java/com/bank/core/domain/AccountStatus.java` enum with `ACTIVE`, `SUSPENDED`, `CLOSED`. Add `isActive()` and `isClosed()` convenience methods.

## 3. Exception hierarchy

- [ ] 3.1 Create `domain/src/main/java/com/bank/core/domain/DomainException.java` as a public abstract subclass of `RuntimeException` with a single `protected DomainException(String message)` constructor.
- [ ] 3.2 Create `InsufficientFundsException` extending `DomainException` with fields `AccountId accountId`, `Money attempted`, `Money available`; constructor takes all three and builds a message like `"Account <id> has balance <available> but debit attempted <attempted>"`. Add getters.
- [ ] 3.3 Create `AccountInactiveException` extending `DomainException` with fields `AccountId accountId`, `AccountStatus status`. Constructor builds message `"Account <id> is not Active (status: <status>)"`.
- [ ] 3.4 Create `InvalidAmountException` extending `DomainException` with a single `String reason` field, accessible via getter, plus a constructor that builds the message from the reason.
- [ ] 3.5 Create `IllegalStatusTransitionException` extending `DomainException` with fields `AccountId accountId`, `AccountStatus from`, `AccountStatus to`. Constructor builds message `"Account <id> cannot transition from <from> to <to>"`.

## 4. Account aggregate

- [ ] 4.1 Create `domain/src/main/java/com/bank/core/domain/Account.java` as a `public final class` with `private final AccountId id`, `private final AccountNumber number`, `private Money balance`, `private AccountStatus status`. Sole `private` constructor takes all four.
- [ ] 4.2 Add `public static Account open(AccountNumber number, Money openingBalance)` factory that mints `AccountId.generate()`, sets status to `AccountStatus.ACTIVE`, and returns the new aggregate. `Money` already refuses negative; the factory does not duplicate the check.
- [ ] 4.3 Add records-style accessors `id()`, `number()`, `balance()`, `status()`.
- [ ] 4.4 Implement `public void credit(Money amount)`: throws `AccountInactiveException` if not `ACTIVE`; throws `InvalidAmountException` if amount is null or zero (negative is impossible via `Money`); otherwise `this.balance = this.balance.add(amount)`.
- [ ] 4.5 Implement `public void debit(Money amount)`: same preconditions as `credit`; additionally throws `InsufficientFundsException` if `balance.isGreaterThan(amount)` is false (i.e. `balance ≤ amount`); otherwise `this.balance = this.balance.subtract(amount)`.
- [ ] 4.6 Implement `public void suspend()`: throws `IllegalStatusTransitionException` if `status == CLOSED`; otherwise sets `status = SUSPENDED` (idempotent if already suspended).
- [ ] 4.7 Implement `public void reactivate()`: throws `IllegalStatusTransitionException` if `status == CLOSED`; otherwise sets `status = ACTIVE` (idempotent if already active).
- [ ] 4.8 Do NOT add `equals`/`hashCode` on `Account`. Identity is by reference within a transaction; comparison across transactions uses `account.id().equals(other.id())`.

## 5. Tests

- [ ] 5.1 `domain/src/test/java/com/bank/core/domain/MoneyTest.java`: cover construction (null/negative reject), rounding (`Money.of("10.005")` rounds to `"10.01"`), equality (`"10"` equals `"10.00"`), `compareTo`, `add`, `subtract` (success path and negative-result reject), `isZero`, `isGreaterThan`, `toString` canonical form.
- [ ] 5.2 `domain/src/test/java/com/bank/core/domain/AccountIdTest.java`: `generate()` returns non-null distinct ids; `of(null)` throws; equals/hashCode follow the underlying UUID.
- [ ] 5.3 `domain/src/test/java/com/bank/core/domain/AccountNumberTest.java`: `of("")`, `of(" ")`, `of(null)` throw; `of("ACC-001")` succeeds; equality is case-sensitive.
- [ ] 5.4 `domain/src/test/java/com/bank/core/domain/AccountStatusTest.java`: `isActive()` and `isClosed()` for each enum value.
- [ ] 5.5 `domain/src/test/java/com/bank/core/domain/AccountTest.java`: cover every published-spec scenario explicitly — new account starts Active, credit increases balance, debit within funds succeeds, debit to zero rejected (test `balance == amount` boundary), debit beyond balance rejected, non-positive amount rejected (null and zero), Active→Suspended→Active transitions, Closed terminal (suspend/reactivate both throw), Suspended rejects debit, Closed rejects credit, suspend idempotency.
- [ ] 5.6 `domain/src/test/java/com/bank/core/domain/AccountReflectionTest.java`: assert via reflection on `Account.class` that there is no `set*` method on production sources; `balance` and `status` are non-final, `id` and `number` are final; the only constructor is `private`; no `Account(...)` constructor with parameter count >= 1 is `public`.
- [ ] 5.7 Add a unit test for each exception class that asserts (a) it extends `DomainException` which extends `RuntimeException`, (b) the getters return the values passed in, (c) `getMessage()` is non-blank and contains the relevant identifying values for log readability.

## 6. Verification

- [ ] 6.1 `./gradlew :domain:test` — all new tests pass.
- [ ] 6.2 `./gradlew :bootstrap:test` — F00 `ModuleBoundaryTest` still passes (domain now contains real classes; the test must still find zero forbidden imports). F04 `NoApiDelegateTest` still passes. F03 tests still pass.
- [ ] 6.3 `./gradlew clean build` — full project green.
- [ ] 6.4 Grep `domain/src/main/java/` for any import starting with `org.springframework`, `jakarta.persistence`, `org.hibernate`, `com.fasterxml.jackson`, `org.openapitools`, `com.bank.core.dto`, or `com.bank.core.api`. Expected: zero matches.
- [ ] 6.5 Grep `domain/src/main/java/com/bank/core/domain/Account.java` for the substring `public Account(` — expected: zero matches (constructor is `private`).
- [ ] 6.6 Boot the service with `./gradlew :bootstrap:bootRun` and confirm `GET /actuator/health` still returns `200 UP`. F01 ships no endpoints, so no new runtime path to test beyond startup.

## 7. Forward-compatibility notes

- [ ] 7.1 Add a short class-level Javadoc on `Account` noting: persistence/rehydration (F02) will add a package-private constructor; F06's transfer flow calls `debit`/`credit` inside a transactional boundary that also writes a `JournalEntry`; F11's drift detector calls `suspend()` directly. No other capability mutates `Account` state.
- [ ] 7.2 Add a Javadoc note on `Money` flagging the single-currency assumption from the REQUIREMENTS doc; a future multi-currency change will need to revisit every `Money` call site.
- [ ] 7.3 Update `openspec/config.yaml`'s `open_decisions` section? Out of scope for the implementation step. The proposal documents that the `debit-to-zero` open decision is closed in favour of the spec wording. If a stakeholder wants to formally close the entry, that's a separate hygiene change. **No edit to `config.yaml` in this change.**

## 8. Hygiene

- [ ] 8.1 If any deviation from this design is necessary, record it in an "Implementation notes / deviations from design" section at the bottom of this file (mirror the F00/F03/F04 archive pattern).
- [ ] 8.2 Skim `INTRODUCTION.md`'s "Rich domain model" paragraph (manifest core concept). It describes intent ("Business rules live on the entities themselves; no public setters; named mutators"). No edit needed — F01 implements exactly that.

## Implementation notes / deviations from design

<!-- Fill in during /opsx:apply. -->
