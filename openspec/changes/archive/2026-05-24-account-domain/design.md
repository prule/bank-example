## Context

F00 shipped a `domain` Gradle module that is JDK-only (no Spring, no JPA, no Jackson, no Jakarta) and ArchUnit-policed by F00's [ModuleBoundaryTest](bootstrap/src/test/java/com/bank/core/architecture/ModuleBoundaryTest.java). F03 introduced the canonical error envelope and the `GlobalExceptionHandler` in `infrastructure`. F04 introduced the OpenAPI contract pipeline; the DTOs that downstream APIs use are generated, not hand-written, and the domain MUST NOT import them. The domain module today contains only a `.gitkeep` — every file F01 adds is the first business code on this branch.

The published spec at [openspec/specs/account-domain/spec.md](openspec/specs/account-domain/spec.md) names exactly four mutators (`credit`, `debit`, `suspend`, `reactivate`), three statuses (`Active`, `Suspended`, `Closed`), and one explicit rule (debit must leave the balance strictly greater than zero). The `debit-to-zero` open decision in [openspec/config.yaml](openspec/config.yaml) is closed by this change in favour of the spec wording.

Constraints carried forward:
- `BigDecimal` is the only acceptable numeric type for money; primitives leak rounding.
- `domain` is the dependency sink — everything points inward toward it. Application/infrastructure adapters convert DB rows and DTOs to/from domain types; the domain never knows about them.
- F02 will reference `AccountId` and `Money` from its own pure-Java types. Other capabilities don't yet exist, so F01 must not pre-commit to their shapes — it ships the minimum the published spec requires.

## Goals / Non-Goals

**Goals:**
- A single `Account` aggregate with private fields, package-private setters (none) and four named mutators that are the only path to changing state. Reflection-based access from production code (and from tests outside this package) is impossible without `setAccessible(true)`.
- The aggregate's identity (`AccountNumber`, `AccountId`) is fixed at construction. Neither field is mutable after creation.
- `Money` is a value object with `BigDecimal` precision 2 and `HALF_UP` rounding. Equality and `hashCode` honour `compareTo` semantics so `Money.of("10.00")` equals `Money.of("10.0")` equals `Money.of("10")`.
- Domain exceptions extend a common `DomainException` (unchecked) so future application-layer code or F03's handler can catch them in one place if the granularity ever matters.
- 100% line and branch coverage on `Account`, `Money`, and the exceptions, via JUnit 5 in `domain/src/test/java`. Reflection scan test asserts no production code path mutates `Account` fields outside the four mutators.
- F00's ArchUnit rules still pass — i.e. no Spring/JPA/Jackson/generated-DTO imports anywhere under `com.bank.core.domain`.

**Non-Goals:**
- Persistence. F02 introduces JPA entities in `infrastructure.persistence` and maps to/from these domain types — out of scope here.
- Repository interfaces or ports. F02 introduces those in `application` (against the published architectural convention that ports live in application). F01 stays in `domain`.
- Multi-currency. The published REQUIREMENTS doc says single implicit currency for the first iteration; `Money` is therefore currency-less, just an amount.
- Idempotency, audit trail, journal recording. F02 (ledger) and F06 (transfer) own those concerns and call `Account.debit/credit` inside them.
- Concurrency primitives. F07 introduces the canonical lock acquisition strategy; `Account` itself stays a plain aggregate and is not thread-safe (each transaction operates on a freshly loaded copy).

## Decisions

### Package layout under `com.bank.core.domain`

Flat, not sub-packaged. F01 introduces ~10 files; sub-packages (`com.bank.core.domain.account`, `com.bank.core.domain.money`, `com.bank.core.domain.exceptions`) would be premature when the whole module is this small. F00's package layout (manifest section `core_concepts` → `rich-domain-model`) sets the precedent: business rules live on the entities themselves; cross-cutting infrastructure is somewhere else entirely. Sub-packages can come later if/when the file count justifies them.

Rejected: separate `domain.account` package. Too granular for one aggregate. F02 will add `JournalEntry` to the same top-level package.

### `Account` shape

```java
public final class Account {
    private final AccountId id;
    private final AccountNumber number;
    private Money balance;
    private AccountStatus status;

    private Account(AccountId id, AccountNumber number, Money balance, AccountStatus status) { … }

    public static Account open(AccountNumber number, Money openingBalance) { … }

    public AccountId id() { … }
    public AccountNumber number() { … }
    public Money balance() { … }
    public AccountStatus status() { … }

    public void credit(Money amount) { … }
    public void debit(Money amount) { … }
    public void suspend() { … }
    public void reactivate() { … }
}
```

- `final class` so subclassing cannot bypass invariants.
- `private` constructor; `open` is the public factory. Java allows another constructor if needed (e.g. F02's adapter rehydrates an `Account` from a persisted row — that constructor will be added later as `package-private` so only the adapter package can call it; in F01 it does not exist).
- No `equals`/`hashCode` on `Account`. Identity comparison is by reference within a transaction; outside that, services compare by `AccountId`.
- Accessors are records-style methods (`id()`, `number()`, …) instead of `getX()`. Cleaner; consistent with modern Java. Spec doesn't constrain naming.

Rejected: a `record` for `Account`. Records have public constructors and `equals/hashCode` and no inheritance restrictions on records vs `final` — but the bigger issue is that records are inherently immutable, and `Account` needs `balance` and `status` to change. A class with `private` mutable fields and named mutators is the correct shape.

### `Money` shape

```java
public final class Money implements Comparable<Money> {
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE, ROUNDING));

    private final BigDecimal amount;

    private Money(BigDecimal amount) { … }
    public static Money of(BigDecimal amount) { … }
    public static Money of(String amount) { … }

    public Money add(Money other) { … }
    public Money subtract(Money other) { … }  // refuses negative result
    public boolean isZero() { … }
    public boolean isGreaterThan(Money other) { … }
    public BigDecimal toBigDecimal() { … }

    @Override public boolean equals(Object o) { … }  // compareTo == 0
    @Override public int hashCode() { … }            // stripped trailing zeros
    @Override public int compareTo(Money other) { … }
    @Override public String toString() { … }         // canonical "1234.56" form
}
```

- Constructor refuses null and negative.
- `of(BigDecimal)` rescales to 2 places with `HALF_UP`.
- `of(String)` is a convenience for tests and fixtures.
- `equals` uses `compareTo(other) == 0` so `Money.of("10.00")` equals `Money.of("10")` — usual `BigDecimal` gotcha avoided.
- `hashCode` strips trailing zeros first so equal money hashes equally.
- `subtract` that would produce a negative result throws `InvalidAmountException` (or a private precondition exception). Since `Account.debit` already guards against this, `Money.subtract` going negative is a programmer-error path and is therefore an `IllegalArgumentException`-style failure — let it propagate as `InvalidAmountException` to keep error vocabulary unified.

Alternatives considered:
- **`record Money(BigDecimal amount)`**: clean but lets clients hand in any scale, any signum. The factory pattern enforces invariants.
- **Joda Money / java-money**: external dependency, overkill for a study branch, and pulls a transitive into `domain` (F00 ArchUnit allows JDK only — Joda is fine licence-wise but the project's conventions ban any non-JDK dep in `domain`).
- **A separate `Currency` field**: scope creep. Single implicit currency per the REQUIREMENTS doc.

### `AccountStatus` enum

```java
public enum AccountStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED;

    public boolean isActive() { return this == ACTIVE; }
    public boolean isClosed() { return this == CLOSED; }
}
```

Spec uses title-case spellings (`Active`, `Suspended`, `Closed`). Java idiom is `ALL_CAPS` for enum constants. The two are equivalent at the spec level; the API surface (F05's `AccountResponse`) will translate via the OpenAPI contract's own enum. F01 doesn't deal with API serialisation.

### `AccountId` and `AccountNumber`

```java
public record AccountId(UUID value) {
    public AccountId { Objects.requireNonNull(value, "account id cannot be null"); }
    public static AccountId generate() { return new AccountId(UUID.randomUUID()); }
    public static AccountId of(UUID value) { return new AccountId(value); }
}

public record AccountNumber(String value) {
    public AccountNumber {
        Objects.requireNonNull(value, "account number cannot be null");
        if (value.isBlank()) {
            throw new InvalidAmountException("account number cannot be blank");
        }
    }
    public static AccountNumber of(String value) { return new AccountNumber(value); }
}
```

- Both are `record`s — they're pure value objects with no behaviour beyond construction validation. `record` is the right tool. Equals/hashCode come for free.
- `AccountId.generate()` mints a UUID. Persistence (F02/F08) does not assign the id; the domain owns it.
- `AccountNumber.of("")` throws an `InvalidAmountException` — wait, the exception name reads wrong for a blank account number. Better: use a separate `InvalidAccountNumberException` or reuse a generic `InvalidIdentifierException`. Decision: introduce a single `IllegalArgumentException` from the record constructor when the value is blank — the spec doesn't enumerate a code for "blank account number," and treating it as `IllegalArgumentException` is conventional Java for "your input is malformed" without overloading the domain's error vocabulary. The F03 handler's catch-all maps `IllegalArgumentException` to 500 — that's acceptable because account-number creation happens at the very edge (account-opening API in F08), which will validate before constructing. Final call recorded in `tasks.md` to either go with `IllegalArgumentException` or `InvalidAmountException`; default = `IllegalArgumentException`.

### Domain exceptions

All extend a common `RuntimeException`-derived `DomainException`:

```java
public abstract class DomainException extends RuntimeException {
    protected DomainException(String message) { super(message); }
}
```

Subclasses:
- `InsufficientFundsException(AccountId, Money attempted, Money available)` — carries the account id, attempted amount, and current balance, all immutable. Useful for logging; the F03 handler never echoes these to the client (only the canonical 400 envelope).
- `AccountInactiveException(AccountId, AccountStatus status)` — carries the id and current status. F03 handler does not echo.
- `InvalidAmountException(String reason)` — carries a short reason string for `Money` / `Account` validation failures. F03 handler maps this to 400 `BAD_REQUEST_PAYLOAD` (added when F06 lands).
- `IllegalStatusTransitionException(AccountId, AccountStatus from, AccountStatus to)` — programmer-error indicator; F03 handler's catch-all maps to 500.

All carry a generated `message` that includes the offending values; that text is for logs, not for the wire.

Rejected: checked exceptions. Java idiom for domain/business exceptions is `RuntimeException`. Forcing every caller to declare `throws InsufficientFundsException` clutters every later capability for zero safety gain.

### Mutator contracts

```java
public void credit(Money amount) {
    requireActive();
    requirePositive(amount);
    this.balance = this.balance.add(amount);
}

public void debit(Money amount) {
    requireActive();
    requirePositive(amount);
    if (!this.balance.isGreaterThan(amount)) {
        throw new InsufficientFundsException(id, amount, balance);
    }
    this.balance = this.balance.subtract(amount);
}

public void suspend() {
    if (status == AccountStatus.CLOSED) {
        throw new IllegalStatusTransitionException(id, status, AccountStatus.SUSPENDED);
    }
    this.status = AccountStatus.SUSPENDED;  // idempotent if already SUSPENDED
}

public void reactivate() {
    if (status == AccountStatus.CLOSED) {
        throw new IllegalStatusTransitionException(id, status, AccountStatus.ACTIVE);
    }
    this.status = AccountStatus.ACTIVE;  // idempotent if already ACTIVE
}
```

- `requireActive()` throws `AccountInactiveException` if status != ACTIVE.
- `requirePositive(Money)` throws `InvalidAmountException` if amount is null or `isZero()`. `Money`'s own constructor refuses negative, so this check covers null and zero only.
- `debit` uses `isGreaterThan` strictly — the spec says "strictly greater than zero after the debit," which is equivalent to "balance must be strictly greater than amount before the debit." Implementing as `if (!balance.isGreaterThan(amount)) throw` is the cleanest expression.
- `suspend`/`reactivate` are idempotent for the source state matching the target. The spec scenarios don't forbid this; idempotency is the operational-friendly default (F11's drift detector may call `suspend` on an already-suspended account during a race).

### "No path mutates balance outside credit/debit" scenario

A JUnit test inspects `Account.class.getDeclaredFields()` and asserts that `balance`, `status`, `id`, `number` are all `private` and the only `non-final` ones are `balance` and `status`. Then `Account.class.getDeclaredMethods()` is inspected for `setBalance`, `setStatus`, etc. — there must be none. Plus a separate test asserting reflection mutation with `setAccessible(true)` works (proving the test mechanism is valid), but production code is searched via a grep-style assertion on the class's bytecode — the JUnit test does the latter via reflection on method names alone.

Rejected: ArchUnit rule for this. F00's ArchUnit suite is module-boundary focused; adding a single-class field-shape rule there pollutes the boundary suite. A targeted JUnit test in `domain/src/test/java` is the right place.

## Risks / Trade-offs

- **`Money.equals` based on `compareTo`** → violates the strict `equals/hashCode` contract if used in `HashSet` alongside non-canonical-scale `BigDecimal` values. Mitigation: `Money` only ever holds scale-2 `BigDecimal`, set in the constructor; equals across different `Money` instances of equal value is correct.
- **`UUID.randomUUID()` in the domain** → injects non-determinism into a class that otherwise has none. Mitigation: only `Account.open(...)` and `AccountId.generate()` touch it; unit tests pass a pre-generated `AccountId` via the package-private constructor (which doesn't exist yet, but the test can use reflection to bypass; or `Account.open` returns an account whose id is captured by the test for further use). If tests need deterministic UUIDs later, an `IdGenerator` port enters `application`, not `domain`. Out of scope for F01.
- **`IllegalStatusTransitionException` for already-suspended `suspend()`?** Spec scenarios are silent on idempotency. Choosing idempotent makes F11 simpler; if business pushes back, the toggle is one line.
- **`Money.subtract` going negative path** → defensive: `Account.debit`'s precondition already prevents this. If `Money.subtract` is called from elsewhere with a larger subtrahend, an exception is thrown. The exception class is `InvalidAmountException`; F03 maps it to 400 in F06. For now it's effectively a programmer-error path.
- **Currency-free `Money` will not extend cleanly to multi-currency** → out-of-scope per REQUIREMENTS doc. When multi-currency lands, `Money` gains a `Currency` field and every constructor caller is touched — listed under the existing `multi-currency` risk in REQUIREMENTS §9.
- **No equals/hashCode on `Account`** → callers that need account-equality must use `account.id().equals(other.id())`. Documented in the class's source. F05/F06 work with one account per transaction anyway.

## Migration Plan

- **Deploy**: PR lands. `./gradlew :domain:test` passes the new tests. `./gradlew :bootstrap:test` still passes F00 ArchUnit (domain now has classes, but they have no forbidden imports). Full `./gradlew build` is green.
- **Rollback**: All additions. `git revert` removes them. No state in DBs, no API surface change — F01 ships no endpoints.
- **Forward path**: F02 introduces `JournalEntry` next to `Account` in `com.bank.core.domain`, plus persistence in `infrastructure.persistence`. F06 wires `Account.debit`/`Account.credit` from a use-case in `application` and adds `@ExceptionHandler` entries to F03's handler.

## Open Questions

- **Equality of `AccountNumber`**: records give us value equality automatically. Should it be case-sensitive? The published spec is silent. Default: case-sensitive (record default). If a stakeholder later wants case-insensitive numbers, that's a new requirement.
- **Persistence of `Money`** (F02 concern): JPA `BigDecimal` columns need a fixed scale. F02 should pick `NUMERIC(19,2)` and document it; F01 doesn't decide.
- **`AccountNumber` validation strictness**: today's check is "non-blank." A real bank constrains to a regex (e.g. 10 alphanumerics). The published REQUIREMENTS doc doesn't specify; defer to whichever spec introduces the API path that accepts an account number (F08).
- **Should the `Account` constructor / factory accept a status, or always force `Active`?** Spec scenario #1 says new accounts start `Active`. F01 implements `open(...)` as Active-only. F02 (when it adds a "rehydrate from persisted row" package-private constructor) will accept status as a parameter, since a loaded account might already be Suspended or Closed. F01 does not foreclose that.
