## 1. Domain Types Setup

- [x] 1.1 Create the AccountStatus enum (ACTIVE, SUSPENDED, CLOSED) in com.bank.core.domain.
- [x] 1.2 Implement the immutable AccountId value object wrapping UUID.
- [x] 1.3 Implement the immutable Money value object wrapping BigDecimal rounded strictly to 2 decimal places using HALF_UP.

## 2. Rich Domain Exceptions

- [x] 2.1 Implement the DomainException runtime base class in com.bank.core.domain.
- [x] 2.2 Implement DomainException subclasses: InsufficientFundsException, AccountInactiveException, InvalidAmountException, and IllegalStatusTransitionException.

## 3. Account Model and Factory

- [x] 3.1 Implement the Account rich domain model class in com.bank.core.domain with private constructor and final identity fields.
- [x] 3.2 Implement public static Account.open(...) factory method validating non-negative opening balances.
- [x] 3.3 Implement credit and debit operations enforcing ACTIVE status, positive amounts, and strictly positive resulting balances.

## 4. Lifecycle and Status Transitions

- [x] 4.1 Implement named lifecycle methods: suspend(), reactivate(), close() on the Account class.
- [x] 4.2 Ensure suspend() and reactivate() are fully idempotent, and illegal transitions out of CLOSED throw IllegalStatusTransitionException.

## 5. Domain Boundary Verification

- [x] 5.1 Write comprehensive unit test suite in domain module (e.g. AccountTest, MoneyTest) to verify all credit/debit invariants, decimal roundings, and exception contexts under standard JDK executions.
