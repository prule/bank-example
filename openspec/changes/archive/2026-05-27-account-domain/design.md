## Context

The Bank Core system requires a highly decoupled rich domain model inside the pure Java `domain` module, fully decoupled from database mappers, Spring framework layers, and REST boundaries.
This design defines the `Money` value object encapsulating decimal scale, the `Account` domain model managing credit/debit operations and state, and a structured `DomainException` hierarchy containing contextual diagnostic details.

## Goals / Non-Goals

**Goals:**
- Implement an immutable `Money` value object wrapping `BigDecimal` with a scale of exactly 2 decimal places and `HALF_UP` rounding.
- Implement `Account` rich domain model encapsulating properties (`AccountId`, `accountNumber`, `balance`, `status`) with zero public setters, reflection-resilient private constructor, and factory creation (`Account.open(...)`).
- Implement strict credit and debit operations enforcing non-negative changes and non-zero balances.
- Define a lifecycle flow where accounts transition between `Active` and `Suspended` (idempotently), and `Closed` is a terminal state.
- Define a structured `DomainException` base class and explicit subclasses carrying strongly typed getters for audit logging.

**Non-Goals:**
- Implementing any database persistence mapping (JPA annotations, repositories) or Spring autowiring beans (reserved for other feature changes).
- Creating REST endpoints.

## Decisions

### 1. Immutable `Money` Value Object
To prevent floating-point rounding errors and precision leakage, we encapsulate all decimal maths inside a dedicated, immutable `Money` type:
- Wraps `BigDecimal` and strictly sets the scale to `2` using `RoundingMode.HALF_UP` on construction.
- Exposes immutable math primitives (`plus`, `minus`) and comparison builders (`isGreaterThan`, `isLessThan`, `isZero`).
- Rounds comparisons appropriately (`equals` and `hashCode` strip trailing zeros and use `compareTo` semantics).

*Rationale*: Ensures consistent monetary calculations across all modules.

### 2. Private Constructor and Factory Methods on `Account`
To prevent the creation of an invalid account state, we enforce:
- A single private constructor: `private Account(AccountId id, String accountNumber, Money balance, AccountStatus status)`.
- A public static factory method: `public static Account open(String accountNumber, Money initialBalance)`.
- Rejection of negative opening balances at the construction boundary.

*Rationale*: By keeping constructors private, we prevent any external code from initializing accounts in illegal states or bypassing business invariants.

### 3. Credit / Debit Balance Mutators
All balance changes are constrained to named domain mutators:
- `credit(Money amount)`: Rejects non-active accounts (`AccountInactiveException`), null/zero/negative values (`InvalidAmountException`), and increases the balance.
- `debit(Money amount)`: Rejects non-active accounts (`AccountInactiveException`), null/zero/negative values (`InvalidAmountException`), and debits if the resulting balance is **strictly greater than zero** (throwing `InsufficientFundsException` otherwise).

*Rationale*: Enforces core double-entry balance constraints inside the model.

### 4. Idempotent Lifecycle Operations
- `suspend()`: Moves from `Active` to `Suspended`. Idempotent if already `Suspended`. Throws `IllegalStatusTransitionException` if `Closed`.
- `reactivate()`: Moves from `Suspended` to `Active`. Idempotent if already `Active`. Throws `IllegalStatusTransitionException` if `Closed`.
- `close()`: Moves from `Active` or `Suspended` to `Closed`.

*Rationale*: Idempotence in `suspend` and `reactivate` allows background auditors (drift reconcilers) to execute without requiring complex external state checks.

### 5. Strongly Typed Exception Hierarchy
Domain exceptions extend `DomainException` which extends `RuntimeException`:
- `InsufficientFundsException`: Carries `AccountId`, `attemptedAmount`, and `currentBalance`.
- `AccountInactiveException`: Carries `AccountId` and `status`.
- `InvalidAmountException`: Carries `attemptedAmount`.
- `IllegalStatusTransitionException`: Carries `AccountId`, `currentStatus`, and `targetStatus`.

*Rationale*: Allows exceptions to be rich in diagnostic details for secure logging on the server, while remaining decoupled from REST HTTP wrappers.

## Risks / Trade-offs

- **[Risk]** Float Serialization Drift: When database layers or Jackson mapper layers convert `BigDecimal` to JSON/DB, scale might diverge from `2`.
  - *Mitigation*: The `Money` value object forces a scale of `2` inside its factory methods, guaranteeing that once a value enters the domain, it is immediately converted to standard precision.
