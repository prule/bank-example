## Context

The Bank Core application needs a read-only endpoint to check account details and balances. It also needs the foundational database table schema and persistence adapter mappings for account aggregates, decoupling domain rules from Hibernate/JPA annotations.

This design implements the `GET /api/v1/accounts/{accountNumber}` endpoint with HATEOAS HAL link mapping, standardizes a domain-level `ResourceNotFoundException` mapped to `404 RESOURCE_NOT_FOUND`, and introduces a clean Java application port `Accounts` mapped via a package-private JPA entity and adapter.

## Goals / Non-Goals

**Goals:**
- **Database Schema Migration**: Define the H2 `account` table via Flyway migration `V3__account.sql` enforcing primary key UUID, UNIQUE account numbers, balance bounds (balance >= 0), and status constraints.
- **Modularity Boundaries**: Decouple the domain model completely by mapping database records in the infrastructure layer via package-private `AccountEntity` and `AccountsJpaAdapter`.
- **Decoupled Application Port**: Define the plain Java port `Accounts` in `com.bank.core.application.account` with lookup (`findByNumber`, `findById`) and aggregate upsert (`save`) operations.
- **Domain Rehydration Factory**: Expose a static `rehydrate()` factory in `com.bank.core.domain.Account` for mapping.
- **OpenAPI Compliance**: Implement the generated `AccountsApi.lookupAccount` interface inside a new `AccountController`.
- **HAL Discovery**: Inject HATEOAS HAL link maps inside the response (`self`, `transfers`).
- **Support-Friendly 404 Logging**: Define domain-level `ResourceNotFoundException` and map it globally to `404 RESOURCE_NOT_FOUND` while logging details at `INFO` level.

**Non-Goals:**
- Direct manipulation of account balances inside the lookup endpoint (which is read-only).
- Direct modification of account state bypass pathways (except for mapping reconstitution).

## Decisions

### 1. Database Table Schema
We map accounts using a secure relational model via Flyway (`V3__account.sql`):
```sql
CREATE TABLE account (
    id VARCHAR(36) PRIMARY KEY,
    account_number VARCHAR(64) NOT NULL UNIQUE,
    balance NUMERIC(19, 2) NOT NULL CHECK (balance >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX idx_account_account_number ON account(account_number);
```
*Rationale*: Guarantees unique accounts and enforces basic numeric/lifecycle safety at the database engine boundary.

### 2. Spring-Free Application Port
- The port interface `Accounts` defines lookup and save operations without Spring/JPA dependencies.
- Signature:
  - `Optional<Account> findByNumber(String number);`
  - `Optional<Account> findById(AccountId id);`
  - `Account save(Account account);`

*Rationale*: Allows transactional write paths and background sweeps to load and save aggregates cleanly.

### 3. Package-Private Mappings and Domain Rehydration
- `AccountEntity` is strictly package-private and resides inside `com.bank.core.infrastructure.persistence.account`.
- To instantiate `Account` domain aggregates from JPA entities, we expose `Account.rehydrate(...)` inside `Account.java`, bypassing lifecycle invariants (so we can reconstitute `SUSPENDED` or `CLOSED` states without starting fresh). Class-level Javadoc documents that `rehydrate` is strictly for persistence mapper use.

*Rationale*: Confines Hibernate concerns to the infrastructure layer, maintaining clean domain boundaries.

### 4. Read-Only Transaction Boundaries
- `AccountsJpaAdapter` is marked with `@Transactional(readOnly = true)` for `findByNumber` and `findById`.

*Rationale*: Disables dirty-checking flush cycles on Hibernate for read-only routes, optimizing performance.

### 5. Standardized Global 404 Mapping
- `ResourceNotFoundException` is defined in `com.bank.core.domain` extending `DomainException`. It carries `resourceType` and `identifier`.
- The global handler translates it to an HTTP 404 payload and logs at `INFO` level to prevent security blindspots.

*Rationale*: Protects details against diagnostic leaks while supplying uniform support error structures.

### 6. HATEOAS HAL Compliance
- `AccountController` uses Spring HATEOAS or custom link construction to inject the HAL link map. Since our OpenAPI stub generates the standard HAL map schema, we bind values directly to the DTO's `_links` field.

*Rationale*: Promotes legal client state discovery at the REST boundary.

## Risks / Trade-offs

- **[Risk] Spring HATEOAS dependency**: Injecting Spring HATEOAS can bloat the build.
  - *Mitigation*: We construct HAL links manually or bind to the OpenAPI generated stub payload, avoiding JVM framework overhead.
