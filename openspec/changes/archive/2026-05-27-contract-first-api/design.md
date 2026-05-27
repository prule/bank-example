## Context

The Bank Core application requires a contract-first API design where the OpenAPI contract is the authoritative source of truth. Java interfaces and DTOs will be generated at build time, preventing compiler drift. Additionally, the canonical contract must be served at `/v3/api-docs` with Swagger UI available in `dev` mode.

## Goals / Non-Goals

**Goals:**
- Maintain a modular, static OpenAPI 3.x document under `bootstrap/src/main/resources/openapi/` split cleanly via `$ref`s.
- Automate build-time interface and DTO generation in the `infrastructure` module via the `org.openapi.generator` Gradle plugin.
- Enforce clean architecture packages: generated types are confined to `com.bank.core.api` and `com.bank.core.dto`, with zero imports allowed in the `domain` or `application` layers (enforced by ArchUnit).
- Expose the assembled contract (with all `$ref`s fully resolved) at `GET /v3/api-docs` supporting JSON and YAML content negotiation.
- Expose static Swagger UI at `/swagger-ui.html` solely under the `dev` profile, pointing to `/v3/api-docs` without any reflection or runtime scanning.

**Non-Goals:**
- Writing actual controller implementations or business logic (e.g. transfers or lookup logic). Hand-written controllers will only be skeleton stub implementations if needed for testing.

## Decisions

### 1. OpenAPI Modular Structure
We organize the contract under `bootstrap/src/main/resources/openapi/` as follows:
- **`openapi.yaml`**: Main root definition specifying metadata, tags, and `$ref` bindings.
- **`paths/`**: Contains separate path definitions (e.g. `account-lookup.yaml`, `fund-transfer.yaml`).
- **`schemas/`**: Contains separate schema definitions (e.g. `error-envelope.yaml`, `account-response.yaml`, `transfer-request.yaml`).

*Rationale*: Prevents huge, single-file merge conflicts and allows separate capability changes to diff cleanly in git.

### 2. Gradle Integration and Code Generation
- Apply the `org.openapi.generator` plugin in `infrastructure/build.gradle.kts`.
- Configure the generator to output into `build/generated/openapi` and add it to the compilation source sets.
- Set configuration options:
  - `interfaceOnly = true` (only interfaces, no delegates or dummy controllers).
  - `useSpringBoot3 = true`.
  - `useTags = true` (groups operations into tag interfaces).
  - `openApiNullable = false`.

```kotlin
openApiGenerate {
    generatorName.set("spring")
    inputSpec.set("$rootDir/bootstrap/src/main/resources/openapi/openapi.yaml")
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    apiPackage.set("com.bank.core.api")
    modelPackage.set("com.bank.core.dto")
    configOptions.set(mapOf(
        "interfaceOnly" to "true",
        "useSpringBoot3" to "true",
        "useTags" to "true",
        "openApiNullable" to "false",
        "serializationLibrary" to "jackson"
    ))
}
```

### 3. Assembled Contract Endpoint with Swagger Parser
Instead of using springdoc-openapi (which performs runtime annotation reflection and violates contract-first principles), we will use the lightweight `io.swagger.parser.v3:swagger-parser` library.
At application boot, our `OpenApiDocService` parses the modular `openapi.yaml` resources once, inlining all `$ref` components into a single in-memory model.
A REST controller `OpenApiController` serves this parsed model:
- **YAML output**: Serves `application/yaml` using `io.swagger.v3.core.util.Yaml.pretty()`.
- **JSON output**: Serves `application/json` using `io.swagger.v3.core.util.Json.pretty()`.

*Alternatives Considered*: Compiling a single file at build time.
- *Rejected*: Harder to handle standard content negotiation dynamically for both JSON/YAML without double compilation files. Swagger Parser handles this seamlessly.

### 4. Gated Swagger UI via WebJars
We will add `org.webjars:swagger-ui` version `5.17.14` to the dependencies.
We implement a lightweight `SwaggerUiController` gated by `@Profile("dev")`:
```java
@Controller
@Profile("dev")
public class SwaggerUiController {
    @GetMapping("/swagger-ui.html")
    public String redirectToSwaggerUi() {
        return "redirect:/webjars/swagger-ui/index.html?url=/v3/api-docs";
    }
}
```

## Risks / Trade-offs

- **[Risk]** Parsing Overhead: Parsing the OpenAPI definition at application startup could theoretically increase boot time.
  - *Mitigation*: The OpenAPI schema is small and parsed once at context initialization, resulting in less than 50ms overhead.
- **[Risk]** WebJars Portability: Serving Swagger UI via WebJars requires assets mapping.
  - *Mitigation*: Spring Boot automatically maps `/webjars/**` requests to classpath resources out-of-the-box, ensuring zero configuration is required.
