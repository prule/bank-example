# F04 — Contract-First API

## Summary

The public HTTP API is defined as a versioned OpenAPI contract. Server-side request/response types and handler interfaces are generated from that contract during the build. The contract is the source of truth; hand-written controllers must implement the generated interfaces, not invent their own signatures.

## User story

As a consumer of this service (and as the team maintaining it), I want one authoritative description of the API that lives independently of server-side code, so that the contract cannot silently drift from the implementation and so that clients, docs, and tests all read from the same source.

## In scope

- Where the contract lives in the repo.
- How the contract is structured (split files, single root).
- How server stubs and DTOs are generated and integrated into the build.
- How the contract is served at runtime (for docs UI and for client downloads).

## Out of scope

- The contents of individual endpoints — those live in F05 / F06 / F08 / future endpoint specs.
- Code generation for SDKs in other languages (out of scope for first iteration).
- Authentication scheme (no auth in first iteration).

## Functional requirements

- The contract is an OpenAPI 3.x document stored as static resources in the repo, with a single root document referencing per-path and per-schema files (so individual changes diff cleanly).
- The contract is the authoritative version of: every path, every request/response schema, every error schema, and the list of valid enum values exposed externally.
- During the build, the contract is fed through an OpenAPI generator that produces, at minimum, one Java interface per tag (group of endpoints) and one DTO class per schema. Generated sources are placed in a generated-sources output folder.
- Code generation must run BEFORE Java compilation, automatically. A developer must never have to run a separate step to get the generated code.
- Generated code is interface-only on the server side — generated default implementations are not used. Hand-written controllers implement those interfaces.
- The runtime serves the same contract file at a stable URL so the docs UI and external clients can fetch the canonical OpenAPI.
- The docs UI (Swagger UI or equivalent) is enabled by default in dev and points at the served contract, NOT at runtime-scanned annotations.
- Generated DTOs and interfaces live in dedicated packages (clearly separated from hand-written domain code) so it is obvious which classes are generated.

## Acceptance criteria

1. A fresh checkout, with no IDE pre-build, can run the build command and compile successfully because code generation has happened first.
2. Editing the contract and rebuilding regenerates the corresponding Java types without manual intervention.
3. Deleting all generated sources and rebuilding restores them.
4. Hand-written controllers fail to compile if their method signatures drift from the generated interfaces.
5. The running service exposes the canonical contract at a stable URL.
6. The running service exposes a docs UI in dev that loads from the served contract URL.
7. Generated DTOs are in a separate package from domain entities; nothing in the domain package references generated DTOs directly.
8. The error response schema (F03) is defined once in the contract and referenced by every error response across every endpoint.

## Dependencies

None. Foundation spec.

## Open questions

- Should the contract be enforced in CI with a "contract did not change without a version bump" check? Useful but not in current build.
- Where does the contract get versioned (`/api/v1/`) vs document-versioned in the OpenAPI `info.version`? Current code uses path-based versioning; confirm policy.
