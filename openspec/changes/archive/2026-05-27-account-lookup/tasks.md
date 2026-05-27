## 1. Schema Migration and Domain Rehydration

- [x] 1.1 Create the Flyway database migration script `V3__account.sql` to provision the H2 `account` table.
- [x] 1.2 Implement the static `rehydrate()` factory in `com.bank.core.domain.Account` bypassing invariants, with class-level mapper Javadoc warnings.
- [x] 1.3 Implement the `ResourceNotFoundException` subclass of `DomainException` in `com.bank.core.domain`.

## 2. Decoupled Application Port

- [x] 2.1 Define the plain Java, Spring-free `Accounts` port interface in `com.bank.core.application.account`.

## 3. JPA Persistence Mappings and Adapter

- [x] 3.1 Map package-private JPA `AccountEntity` columns inside the `infrastructure` module.
- [x] 3.2 Implement `AccountsJpaAdapter` in `com.bank.core.infrastructure.persistence.account` utilizing `@Transactional(readOnly = true)`.

## 4. HTTP Surface, global exception handling, and HATEOAS HAL Mapping

- [x] 4.1 Update `GlobalExceptionHandler` to catch `ResourceNotFoundException` and map to HTTP `404 RESOURCE_NOT_FOUND` carrying the error envelope while logging info messages.
- [x] 4.2 Implement `AccountController` implementing the generated `AccountsApi` returning HAL `_links`.

## 5. Verification and Validation

- [x] 5.1 Write comprehensive unit and integration tests verifying schema migrations, exception mappings, HAL linkages, and boundary ArchUnit rules.
