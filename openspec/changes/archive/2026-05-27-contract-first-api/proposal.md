## Why

The service currently has no API endpoints, DTO schemas, or OpenAPI generation tooling. This change introduces a contract-first API paradigm where a statically defined OpenAPI 3.x contract checked into resources acts as the authoritative source of truth. Build-time stub generation creates Java interfaces and DTOs, ensuring compiler-enforced compliance between server code and client expectations before any REST controllers are implemented.

## What Changes

- **OpenAPI Autoritative Contract**: Introduce static OpenAPI definition files under `bootstrap/src/main/resources/openapi/`, with a modular `$ref`-based structure split across sibling `paths/` and `schemas/` directories.
- **Build-Time Interface and DTO Generation**: Integrate the `org.openapi.generator` Gradle plugin in the `infrastructure` module to automatically compile DTOs in `com.bank.core.dto` and interface-only API tags in `com.bank.core.api` before any production classes compile.
- **Strict Package Decoupling**: Configure the generator to produce `interfaceOnly` definitions and DTOs. Verify that `domain` and `application` classes contain zero imports to generated API types using ArchUnit checks.
- **Runtime Contract Exposure**: Expose the full, assembled OpenAPI definition at `GET /v3/api-docs`.
- **Developer Documentation Interface**: Expose a self-contained Swagger UI at `/swagger-ui.html` solely under the `dev` profile to facilitate interactive endpoint testing.
- **Shared Error Envelope Schema**: Define `ErrorEnvelope` as a reusable component schema incorporating the canonical error code enum taxonomy (`INSUFFICIENT_FUNDS`, `ACCOUNT_INACTIVE`, `RESOURCE_NOT_FOUND`, `BAD_REQUEST_PAYLOAD`, `INTERNAL_SERVER_ERROR`).

## Capabilities

### New Capabilities
- `contract-first-api`: Authoritative OpenAPI contract checked-in as modular resources, build-time compilation of interfaces/DTOs, runtime /v3/api-docs exposure, dev Swagger UI, and typed error envelope schema.

### Modified Capabilities
<!-- None -->

## Impact

- **Build Flow**: Adds an OpenAPI generation step to the `./gradlew build` pipeline in the `infrastructure` module.
- **Web Layer Scaffolding**: Establishes `/v3/api-docs` and `/swagger-ui.html` paths, serving as the blueprint for all future controller developments.
- **API Boundary Enforcements**: All future REST controllers will be forced to implement the generated interface classes, ensuring hand-written Java signatures cannot drift from the contract.
