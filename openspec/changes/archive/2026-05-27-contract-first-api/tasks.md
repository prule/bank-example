## 1. OpenAPI Modular Contract Definition

- [x] 1.1 Create the OpenAPI root document openapi.yaml under bootstrap/src/main/resources/openapi/.
- [x] 1.2 Create schemas (error-envelope.yaml, account-response.yaml, transfer-request.yaml) under the schemas/ subdirectory.
- [x] 1.3 Create path definition files (account-lookup.yaml, fund-transfer.yaml) under the paths/ subdirectory and reference them in openapi.yaml.

## 2. Gradle Integration & Code Generation

- [x] 2.1 Add the org.openapi.generator plugin to root build.gradle.kts and apply it inside infrastructure/build.gradle.kts.
- [x] 2.2 Configure openApiGenerate task in infrastructure module to emit interface-only stubs and DTOs into layout.buildDirectory.dir("generated/openapi").
- [x] 2.3 Add generated source directory to Java sourceSets in infrastructure/build.gradle.kts and verify compilation succeeds.

## 3. Assembled Contract Runtime Serving

- [x] 3.1 Add swagger-parser dependency to infrastructure and bootstrap build configurations.
- [x] 3.2 Implement OpenApiDocService to parse, assemble, and inline modular openapi.yaml paths and schemas at context initialization.
- [x] 3.3 Implement OpenApiController in infrastructure to serve the resolved JSON/YAML contract at GET /v3/api-docs with dynamic content negotiation.

## 4. Swagger UI Integration & Gate

- [x] 4.1 Add org.webjars:swagger-ui dependency to the bootstrap build configuration.
- [x] 4.2 Implement SwaggerUiController in infrastructure, gated by @Profile("dev") to redirect /swagger-ui.html to WebJars Swagger UI index page loading /v3/api-docs.
- [x] 4.3 Verify that GET /swagger-ui.html returns 404 in the default profile, but exposes the interactive page under dev profile.

## 5. Clean Architecture Package Verifications

- [x] 5.1 Update ArchUnit test suite in bootstrap/src/test/java/ to verify zero domain/application imports on generated packages com.bank.core.api and com.bank.core.dto.
