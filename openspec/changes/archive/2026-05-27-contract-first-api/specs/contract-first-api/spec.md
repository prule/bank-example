## ADDED Requirements

### Requirement: OpenAPI document as source of truth
The repository SHALL contain an OpenAPI 3.x document stored as static resources under `bootstrap/src/main/resources/openapi/`, with a single root document referencing per-path and per-schema files via `$ref` so that individual changes diff cleanly. The document SHALL be the authoritative definition of every path, request/response schema, error schema, and externally exposed enum.

#### Scenario: Contract lives as static resources
- **WHEN** the repository is inspected
- **THEN** `bootstrap/src/main/resources/openapi/openapi.yaml` exists as a checked-in static resource (not generated from server annotations), with `paths/` and `schemas/` sibling folders, and the root file `$ref`s into those folders for every path entry and every component schema

#### Scenario: Contract diverging from controllers fails the build
- **WHEN** a hand-written controller's method signature drifts from the generated interface it implements (e.g. parameter type or return type changes in the YAML and the controller is not updated)
- **THEN** `./gradlew build` fails at Java compilation because the controller no longer satisfies the generated interface

### Requirement: Build-time code generation
The Gradle build SHALL feed the OpenAPI document through a code generator that produces at minimum one Java interface per OpenAPI tag and one DTO class per schema, into a generated-sources output folder under `infrastructure/build/generated/openapi/src/main/java`. Code generation SHALL run automatically BEFORE Java compilation of the `infrastructure` module; a developer SHALL NOT need to run a separate step.

#### Scenario: Fresh checkout compiles without manual generation
- **WHEN** a developer clones the repository on a machine with only a JDK installed and runs `./gradlew build`
- **THEN** the OpenAPI generation task runs as a dependency of `compileJava`, produces the interfaces and DTOs into the generated-sources folder, and Java compilation succeeds in the same invocation with no separate generator command

#### Scenario: Editing the contract regenerates types
- **WHEN** the OpenAPI document is edited (e.g. a field added to a schema under `schemas/`) and `./gradlew build` is re-run
- **THEN** the generated Java DTO for that schema reflects the new field without any manual generator invocation, and any controller that no longer matches its interface fails compilation

#### Scenario: Deleting generated sources is recoverable
- **WHEN** the folder `infrastructure/build/generated/openapi` is deleted and `./gradlew build` is re-run
- **THEN** the generation task restores the generated sources and the build succeeds

### Requirement: Generated code is interface-only on the server side
Server-side generated code SHALL consist of interfaces (one per OpenAPI tag) under `com.bank.core.api` and DTOs (one per schema) under `com.bank.core.dto`. Generator-emitted controller default implementations and `*ApiDelegate` patterns SHALL NOT be used. Every endpoint exposed by the service SHALL be served by a hand-written controller class in `com.bank.core.infrastructure.web` that implements one of the generated interfaces.

#### Scenario: No generated controller bodies in use
- **WHEN** the codebase is inspected
- **THEN** the OpenAPI generator is configured with `interfaceOnly=true` and no `*ApiDelegate` or generated controller implementation is referenced by any Spring bean; every controller class under `com.bank.core.infrastructure.web` is hand-written and declares `implements <Generated>Api`

#### Scenario: Hand-written controller satisfies the generated interface
- **WHEN** the project is compiled
- **THEN** the hand-written controller class implements the generated interface from `com.bank.core.api`, with method signatures, parameter annotations, and return types matching the generated interface

### Requirement: Generated package separation
Generated DTOs and interfaces SHALL live in dedicated packages clearly separated from hand-written domain code. Code in `com.bank.core.domain` and `com.bank.core.application` SHALL NOT import from `com.bank.core.api` or `com.bank.core.dto`.

#### Scenario: Generated types live in their own packages
- **WHEN** the codebase is inspected
- **THEN** generated interfaces live in `com.bank.core.api`, generated DTOs live in `com.bank.core.dto`, and ArchUnit confirms no class under `com.bank.core.domain` or `com.bank.core.application` imports from either generated package

#### Scenario: Adding a domain import of a DTO fails the build
- **WHEN** a developer adds an `import com.bank.core.dto.ErrorEnvelope;` line to a class under `com.bank.core.domain`
- **THEN** the ArchUnit boundary test from [[project-setup]] fails the build

### Requirement: Canonical contract served at runtime
The running service SHALL serve the canonical OpenAPI document at `GET /v3/api-docs` so docs UIs and external clients can fetch the same OpenAPI document the build was generated from. The served body SHALL be content-equivalent to the assembled contract on disk (root file with all `$ref`s resolved/inlined).

#### Scenario: Contract URL returns the canonical document
- **WHEN** the service is running and a client requests `GET http://localhost:8080/v3/api-docs`
- **THEN** the response status is `200`, the content type is `application/yaml` or `application/json` (per `Accept` negotiation), and the body is the OpenAPI document with the same `info`, `paths`, and `components.schemas` content as the assembled source files

#### Scenario: Dev docs UI loads from the served contract
- **WHEN** the service runs under the `dev` profile
- **THEN** Swagger UI is exposed at `/swagger-ui.html`, configured to load its definition from `/v3/api-docs`, and the page renders the tags, paths, and schemas declared in the contract without performing runtime annotation scanning

#### Scenario: Default profile does not expose Swagger UI
- **WHEN** the service runs with no active profile (i.e. `default`)
- **THEN** `GET /swagger-ui.html` returns `404`, but `GET /v3/api-docs` still returns the canonical document

### Requirement: Shared error schema across endpoints
The error envelope schema defined by [[api-error-contract]] SHALL be defined exactly once in the OpenAPI document, under `components.schemas.ErrorEnvelope`, and every `4xx`/`5xx` response declared anywhere in the document SHALL reference that schema via `$ref`. The contract-first capability owns the schema's location and the referencing pattern; the error-contract capability owns the code taxonomy that populates it.
The `code` property of `ErrorEnvelope` SHALL be declared with `type: string` and an `enum` constraint enumerating exactly the canonical taxonomy from [[api-error-contract]] (currently: `INSUFFICIENT_FUNDS`, `ACCOUNT_INACTIVE`, `RESOURCE_NOT_FOUND`, `BAD_REQUEST_PAYLOAD`, `INTERNAL_SERVER_ERROR`). The OpenAPI generator emits a typed nested enum `ErrorEnvelope.CodeEnum`; hand-written error handlers SHALL reference enum constants from the generated type, not string literals.

#### Scenario: One error schema referenced everywhere
- **WHEN** the OpenAPI document is inspected
- **THEN** the error envelope is defined exactly once under `components.schemas.ErrorEnvelope` (sourced from `schemas/error-envelope.yaml`), and every `4xx`/`5xx` response across every path references it via `$ref: '#/components/schemas/ErrorEnvelope'`

#### Scenario: Error code field is a typed enum, not a free string
- **WHEN** the `components.schemas.ErrorEnvelope.properties.code` definition is inspected
- **THEN** the field declares `enum: [INSUFFICIENT_FUNDS, ACCOUNT_INACTIVE, RESOURCE_NOT_FOUND, BAD_REQUEST_PAYLOAD, INTERNAL_SERVER_ERROR]` and the regenerated `com.bank.core.dto.ErrorEnvelope` exposes a public nested `CodeEnum` with those five constants

#### Scenario: Adding a new code requires editing the schema first
- **WHEN** a new canonical error code is introduced by a future capability
- **THEN** the new value is added to the `enum` list in `schemas/error-envelope.yaml`, `./gradlew build` regenerates `ErrorEnvelope.CodeEnum`, and only then can a handler reference the new constant — preventing string-typed drift between the contract and the code
