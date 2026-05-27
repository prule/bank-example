## Why

Currently, clients cannot query the state or balance of a specific bank account via HTTP, and there is no database persistence layer for account aggregates. Introducing the `account-lookup` capability establishes a secure, read-only GET endpoint to query account status and balance snapshots, and provides the foundational persistence layer (table schema, JPA mapping, port, and adapter) needed for all future transactional and background sweep paths.

## What Changes

- **Account Database Schema**: Introduce table `account` via Flyway migration `V3__account.sql` enforcing primary key UUID, UNIQUE account numbers, balance bounds (balance >= 0), and status constraint checks.
- **Spring-Free Application Port**: Create the `Accounts` interface in the application layer (`com.bank.core.application.account`) mapping domain lookups (`findByNumber`, `findById`) and aggregate upserts (`save`).
- **JPA Persistence Mapping**: Implement package-private `AccountEntity` and `AccountsJpaAdapter` utilizing standard Spring Data Jpa and dirty-checking, marked strictly with `@Transactional(readOnly = true)` for read pathways.
- **Domain Rehydration**: Update the `Account` domain model to expose a package-private/public `rehydrate` static factory strictly forpersistence mapper use, bypassing lifecycle invariants.
- **Read-Only HTTP GET Endpoint**: Implement the OpenAPI generated interface `AccountsApi.lookupAccount` inside `AccountController` returning HTTP 200 with standard HATEOAS HAL links (`self`, `transfers`).
- **Standardized 404 Error Mapping**: Create domain exception `ResourceNotFoundException` mapped globally to HTTP `404 RESOURCE_NOT_FOUND` carrying a Support-friendly error diagnostic envelope.

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `account-lookup`: Implement read-only GET lookup endpoint, HATEOAS HAL responses, H2 database schema migrations, plain-Java `Accounts` application port, package-private JPA persistence adapter, domain `rehydrate` factory, and domain-level `ResourceNotFoundException` mapping.

## Impact

- **Database Structure**: A new table `account` will be created inside H2.
- **REST Controller Integration**: Implement generated OpenAPI interfaces for account endpoints under the `infrastructure` layer.
- **Downstream Capabilities**: Provides the baseline persistence and lookup mechanisms required by future capabilities (`fund-transfer`, `account-opening`, `dev-data-seeding`, and `balance-drift-detection`).
