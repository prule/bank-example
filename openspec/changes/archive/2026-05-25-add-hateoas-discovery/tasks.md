## 1. OpenAPI contract changes

- [x] 1.1 Create `bootstrap/src/main/resources/openapi/schemas/link.yaml` defining the reusable `Link` schema (required `href`, optional `templated`, optional `title`)
- [x] 1.2 Create `bootstrap/src/main/resources/openapi/schemas/index-response.yaml` defining `IndexResponse` with a single required field `_links` (map of `Link`)
- [x] 1.3 Modify `bootstrap/src/main/resources/openapi/schemas/account-response.yaml` to add required `_links` field referencing the map-of-`Link` shape
- [x] 1.4 Create `bootstrap/src/main/resources/openapi/paths/index.yaml` with `get` operation: `operationId: getIndex`, 200 response → `IndexResponse`
- [x] 1.5 Update root `openapi.yaml`: register `/api/v1` path → `paths/index.yaml`, register `Link` and `IndexResponse` schemas under `components.schemas`, add tag `index`

## 2. Build dependency

- [x] 2.1 Add `org.springframework.boot:spring-boot-starter-hateoas` to `infrastructure/build.gradle.kts` (test scope NOT enough — needed at runtime)
- [x] 2.2 Run `./gradlew :infrastructure:compileJava` to confirm generated DTOs (`IndexResponse`, `Link`, updated `AccountResponse`) appear under `infrastructure/build/generated/openapi/`

## 3. Implementation

- [x] 3.1 Create `IndexController` in `com.bank.core.infrastructure.web` implementing the generated `IndexApi`; build links via `WebMvcLinkBuilder.linkTo(methodOn(...))` referencing `AccountController.lookupAccount`, `TransferController.createTransfer`, `OpenApiController.getOpenApiDocument`, and self
- [x] 3.2 Add a tiny helper (e.g. `LinkFactory`) that wraps `WebMvcLinkBuilder` and returns the generated `Link` DTO (not the Spring HATEOAS `Link` type) so controllers don't import Spring HATEOAS into method bodies
- [x] 3.3 Modify `AccountController.lookupAccount` to populate `AccountResponse._links` with `self` and `transfers` entries before returning
- [x] 3.4 Configure controllers to advertise both `application/hal+json` and `application/json` via `produces = { ... }` on the controller method (or via a content-negotiation config bean if cleaner) — IndexApi generated interface already emits `produces = { "application/hal+json", "application/json" }`
- [x] 3.5 Ensure the `accounts` index link is marked `templated: true` (URI template path, not a concrete URL)

## 4. Tests

- [x] 4.1 Create `IndexControllerTest` in `bootstrap/src/test/java/com/bank/core/web` — MockMvc, asserts 200, asserts exact 4 link relations and their hrefs, asserts `_links.accounts.templated == true`
- [x] 4.2 Extend `AccountLookupControllerTest` — assert `_links.self.href` and `_links.transfers.href` on a successful lookup; assert response remains 4 top-level keys
- [x] 4.3 Extend `OpenApiContractTest` — assert the served `/v3/api-docs` includes the `Link`, `IndexResponse` schemas; `AccountResponse.required` now lists `_links`
- [x] 4.4 Verify `Accept: application/hal+json` returns `Content-Type: application/hal+json` for `GET /api/v1` (asserted in `IndexControllerTest.halAcceptHeaderReturnsHalJsonContentType`)
- [x] 4.5 Verify 404 (missing account) response body shape is unchanged (no `_links` on error envelope) — existing `missingAccountReturns404WithResourceNotFoundEnvelope` already asserts exactly the 3 envelope fields

## 5. Documentation

- [x] 5.1 Add a "Discovery" section to `ReadMe.md` with a curl example of `GET /api/v1`
- [x] 5.2 Update `INTRODUCTION.md` if it references the API surface — `INTRODUCTION.md` already documents HATEOAS as a v2 direction at line 85, accurately reflecting the implemented state; no edit needed

## 6. Verify

- [x] 6.1 Run `./gradlew build` — all modules compile, all tests pass (full build green)
- [ ] 6.2 Boot the dev profile (`./gradlew :bootstrap:bootRun -Dspring.profiles.active=dev`), `curl http://localhost:8080/api/v1` and confirm the four links render — deferred, integration test covers the wire shape
- [ ] 6.3 `curl -H 'Accept: application/hal+json' http://localhost:8080/api/v1/accounts/<seeded-account>` — deferred, `IndexControllerTest.halAcceptHeaderReturnsHalJsonContentType` + `AccountLookupControllerTest` cover the same assertion
