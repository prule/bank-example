# Contract-First API

## Purpose

The public HTTP API is defined as a versioned OpenAPI contract checked into the repo. Server-side request/response types and handler interfaces are generated from that contract during the build; the contract is the source of truth and hand-written controllers must implement the generated interfaces, not invent their own signatures.

## Requirements

### Requirement: OpenAPI document as source of truth

The repository SHALL contain an OpenAPI 3.x document stored as static resources, with a single root document referencing per-path and per-schema files so that individual changes diff cleanly. The document SHALL be the authoritative definition of every path, request/response schema, error schema, and externally exposed enum.

#### Scenario: Contract lives as static resources
- **WHEN** the repository is inspected
- **THEN** the OpenAPI 3.x document lives under a stable path as checked-in static resources (not generated from server annotations), with a root file that `$ref`s per-path and per-schema files

#### Scenario: Contract diverging from controllers fails the build
- **WHEN** a hand-written controller's method signature drifts from the generated interface it implements
- **THEN** Java compilation fails because the controller no longer satisfies the generated interface

### Requirement: Build-time code generation

The build SHALL feed the OpenAPI document through a code generator that produces at minimum one Java interface per tag and one DTO class per schema, into a generated-sources output folder. Code generation SHALL run automatically BEFORE Java compilation; a developer SHALL NOT need to run a separate step.

#### Scenario: Fresh checkout compiles without manual generation
- **WHEN** a developer runs `./gradlew build` on a fresh checkout with no prior IDE setup
- **THEN** code generation runs, produces the interfaces and DTOs, and Java compilation succeeds in the same invocation

#### Scenario: Editing the contract regenerates types
- **WHEN** the contract is edited (e.g. a field added to a response schema) and `./gradlew build` is re-run
- **THEN** the generated Java type for that schema reflects the new field without any manual generator invocation

#### Scenario: Deleting generated sources is recoverable
- **WHEN** the generated-sources output folder is deleted and `./gradlew build` is re-run
- **THEN** the generated sources are restored and the build succeeds

### Requirement: Generated code is interface-only on the server side

Server-side generated code SHALL consist of interfaces (one per tag) and DTOs (one per schema). Generated default implementations SHALL NOT be used. Hand-written controllers SHALL implement those interfaces.

#### Scenario: No generated controller bodies in use
- **WHEN** the codebase is inspected
- **THEN** generated default implementations are either not produced or not wired into Spring; every endpoint is served by a hand-written controller class that implements a generated interface

### Requirement: Generated package separation

Generated DTOs and interfaces SHALL live in dedicated packages clearly separated from hand-written domain code. Domain types SHALL NOT reference generated DTOs.

#### Scenario: Generated types live in their own packages
- **WHEN** the codebase is inspected
- **THEN** generated interfaces live in `com.bank.core.api`, generated DTOs live in `com.bank.core.dto`, and no class under `com.bank.core.domain` or `com.bank.core.application` imports from either generated package (see [[project-setup]])

### Requirement: Canonical contract served at runtime

The runtime SHALL serve the canonical contract file at a stable URL so docs UIs and external clients can fetch the same OpenAPI document the build was generated from.

#### Scenario: Contract URL returns the canonical document
- **WHEN** the service is running and a client requests the canonical contract URL
- **THEN** the response is the same OpenAPI document the build's code generator consumed (byte-equivalent or content-equivalent)

#### Scenario: Dev docs UI loads from the served contract
- **WHEN** the service runs under the `dev` profile (see [[project-setup]])
- **THEN** a docs UI (Swagger UI or equivalent) is exposed and loads its definition from the canonical contract URL, not from runtime annotation scanning

### Requirement: Shared error schema across endpoints

The error envelope defined by [[api-error-contract]] SHALL be defined exactly once in the OpenAPI document and referenced by every error response across every endpoint.

#### Scenario: One error schema referenced everywhere
- **WHEN** the OpenAPI document is inspected
- **THEN** the error envelope is defined once under `components.schemas` and every `4xx`/`5xx` response across every path references it via `$ref`
