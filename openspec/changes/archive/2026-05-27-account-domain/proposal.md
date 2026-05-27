## Why

Currently, there are no rich domain objects or core business logic definitions inside the `domain` module. This change introduces the `Account` rich domain model, encapsulate the balance scale (using a dedicated `Money` type with currency precision), define the account status states (`Active`, `Suspended`, `Closed`), and implement all business invariants (Credit/Debit constraints, status transitions, and exception hierarchies) in pure Java, establishing a decoupled domain boundary.

## What Changes

- **Account Identity & State**: Define the `Account` rich model containing a unique UUID `AccountId`, account number, non-negative balance, and status enum.
- **Dedicated Money Type**: Introduce an immutable `Money` value object encapsulating `BigDecimal` values rounded to 2 decimal places using `HALF_UP`.
- **Domain Mutators**: Implement named, strictly encapsulated methods on `Account` (`credit`, `debit`, `suspend`, `reactivate`, `close`) with zero public setters.
- **State Transition Safeguards**: Prevent any balance modifications on non-Active accounts, enforce Closed status as a terminal state, and implement idempotent suspended/active operations.
- **Domain Exception Hierarchy**: Define a single `DomainException` base class and explicit subclasses (`InsufficientFundsException`, `AccountInactiveException`, `InvalidAmountException`, `IllegalStatusTransitionException`) carrying rich diagnostic contexts.

## Capabilities

### New Capabilities
- `account-domain`: Rich domain entity Account, Money value object, Credit/Debit invariants, status lifecycle controls, and structured DomainException hierarchy.

### Modified Capabilities
<!-- None -->

## Impact

- **Decoupled Business Rules**: Establishes the framework-free core layer of the clean architecture layout in the `domain` module.
- **Subsequent Features**: All future API endpoints, JPA persistence mappers, fund transfers, and background auditing modules will reference these types directly.
