## ADDED Requirements

### Requirement: Account identity and state shape
An account SHALL have a unique externally visible account number, an internal identifier, a non-negative balance held to currency precision (two decimal places), and exactly one status from the set `Active`, `Suspended`, `Closed`. A newly created account SHALL be `Active`.

#### Scenario: New account starts Active
- **WHEN** an account is created via `Account.open(AccountNumber, Money)` with a non-negative opening balance
- **THEN** the account exists, its status is `Active`, its balance equals the opening amount, and a fresh `AccountId` (UUID) is assigned

#### Scenario: Negative opening balance rejected
- **WHEN** an account is created with a negative opening `Money`
- **THEN** construction is rejected at the `Money` boundary (it cannot be negative) and no account exists

#### Scenario: Balance scale is exactly two decimal places
- **WHEN** an account's balance is inspected
- **THEN** the underlying `BigDecimal` has `scale == 2`; rounding uses `HALF_UP`; `Money.of("10.00").equals(Money.of("10"))` is true

### Requirement: Credit and debit are the only balance mutators
An account SHALL expose `credit(Money)` and `debit(Money)` operations that take a positive amount; balance, status, and identifiers SHALL NOT be mutable through any other path (no public setter, no non-private constructor on production sources, no reflection-friendly factory). A debit SHALL decrease the balance only when the resulting balance remains strictly greater than zero; a credit SHALL increase the balance by exactly the amount.

#### Scenario: Credit increases balance
- **WHEN** an Active account with balance `B` is credited by a positive amount `A`
- **THEN** the account's balance equals `B + A`

#### Scenario: Debit within funds succeeds
- **WHEN** an Active account with balance `B` is debited by an amount `A` strictly less than `B`
- **THEN** the account's balance equals `B − A`

#### Scenario: Debit that would reach zero or below is rejected
- **WHEN** an Active account with balance `B` is debited by an amount `A ≥ B`
- **THEN** the operation throws `InsufficientFundsException` and the balance is unchanged

#### Scenario: Non-positive amount is rejected
- **WHEN** an Active account is debited or credited with an amount that is null or zero
- **THEN** the operation throws `InvalidAmountException` and the balance is unchanged

#### Scenario: No path mutates balance outside credit/debit
- **WHEN** the production sources under `com.bank.core.domain` are inspected reflectively
- **THEN** `Account` has no public setter method (`setBalance`, `setStatus`, `setId`, `setNumber`); `balance` and `status` are the only non-`final` fields; `id` and `number` are `final`; no public or package-private constructor exists (only a `private` constructor plus the `Account.open(...)` factory); production code outside `Account` cannot reach any mutable field without `setAccessible(true)`

### Requirement: Status transitions
An Account SHALL move between `Active` and `Suspended` in either direction via the named `suspend()` and `reactivate()` operations. Once `Closed`, an Account SHALL NOT transition to any other status. `suspend()` on an already-suspended account and `reactivate()` on an already-active account SHALL be idempotent (no-op, no exception) so background reconciliation can safely double-call.

#### Scenario: Active can be Suspended and Reactivated
- **WHEN** an Active account is suspended, then reactivated
- **THEN** its status moves `Active → Suspended → Active`

#### Scenario: Closed is terminal
- **WHEN** a Closed account is asked to suspend or reactivate
- **THEN** the operation throws `IllegalStatusTransitionException` and the status remains `Closed`

#### Scenario: Suspend is idempotent
- **WHEN** a Suspended account is suspended again
- **THEN** the status remains `Suspended` and no exception is thrown

### Requirement: Non-Active accounts reject mutations
Any attempt to debit or credit an account whose status is not `Active` SHALL throw `AccountInactiveException`, regardless of amount.

#### Scenario: Suspended account rejects debit
- **WHEN** a Suspended account is debited by any positive amount
- **THEN** the operation throws `AccountInactiveException` and the balance is unchanged

#### Scenario: Closed account rejects credit
- **WHEN** a Closed account is credited by any positive amount
- **THEN** the operation throws `AccountInactiveException` and the balance is unchanged

### Requirement: Domain exceptions form a single hierarchy
Domain rule violations SHALL be expressed as exception subclasses of a single `DomainException` base class. Subclasses SHALL carry the offending values (account id, attempted amount, current balance, attempted transition) for logging; the [[api-error-contract]] `GlobalExceptionHandler` SHALL map each subclass to the appropriate canonical error code without echoing internal field values to the response body.

#### Scenario: All domain exceptions share a common ancestor
- **WHEN** the production sources under `com.bank.core.domain` are inspected
- **THEN** `InsufficientFundsException`, `AccountInactiveException`, `InvalidAmountException`, and `IllegalStatusTransitionException` all extend `DomainException`, which extends `RuntimeException`

#### Scenario: Exceptions carry diagnostic context
- **WHEN** `InsufficientFundsException` is thrown by `Account.debit`
- **THEN** the exception exposes the `AccountId`, the attempted `Money` amount, and the current balance via getters, suitable for `INFO`/`ERROR` logging by the handler
