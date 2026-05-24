## MODIFIED Requirements

### Requirement: Shared error schema across endpoints

The error envelope schema defined by [[api-error-contract]] SHALL be defined exactly once in the OpenAPI document, under `components.schemas.ErrorEnvelope`, and every `4xx`/`5xx` response declared anywhere in the document SHALL reference that schema via `$ref`. The contract-first capability owns the schema's location and the referencing pattern; the error-contract capability owns the code taxonomy that populates it.

The `code` property of `ErrorEnvelope` SHALL be declared with `type: string` and an `enum` constraint enumerating exactly the canonical taxonomy from [[api-error-contract]] (currently: `INSUFFICIENT_FUNDS`, `ACCOUNT_INACTIVE`, `RESOURCE_NOT_FOUND`, `BAD_REQUEST_PAYLOAD`, `INTERNAL_SERVER_ERROR`). The OpenAPI generator emits a typed nested enum `ErrorEnvelope.CodeEnum`; hand-written error handlers SHALL reference enum constants from the generated type, not string literals.

#### Scenario: One error schema referenced everywhere

- **WHEN** the OpenAPI document is inspected
- **THEN** the error envelope is defined exactly once under `components.schemas.ErrorEnvelope` (sourced from `schemas/error-envelope.yaml`), and every `4xx`/`5xx` response across every path references it via `$ref: '#/components/schemas/ErrorEnvelope'`

#### Scenario: Adding an inline error schema is forbidden by review

- **WHEN** a path file under `paths/` declares an inline schema for an error response instead of `$ref`ing `ErrorEnvelope`
- **THEN** the change is rejected at review (no automated check at this layer; the requirement is normative and reinforced by [[api-error-contract]] when codes are introduced)

#### Scenario: Error code field is a typed enum, not a free string

- **WHEN** the `components.schemas.ErrorEnvelope.properties.code` definition is inspected
- **THEN** the field declares `enum: [INSUFFICIENT_FUNDS, ACCOUNT_INACTIVE, RESOURCE_NOT_FOUND, BAD_REQUEST_PAYLOAD, INTERNAL_SERVER_ERROR]` and the regenerated `com.bank.core.dto.ErrorEnvelope` exposes a public nested `CodeEnum` with those five constants

#### Scenario: Adding a new code requires editing the schema first

- **WHEN** a new canonical error code is introduced by a future capability
- **THEN** the new value is added to the `enum` list in `schemas/error-envelope.yaml`, `./gradlew build` regenerates `ErrorEnvelope.CodeEnum`, and only then can a handler reference the new constant — preventing string-typed drift between the contract and the code
