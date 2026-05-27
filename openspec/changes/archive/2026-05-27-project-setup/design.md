## Context

The Bank Core project is a double-entry ledger study application. To compare different generation paradigms (Gemini in browser vs spec-driven Claude/Antigravity), we must establish a highly decoupled, clean, and testable scaffolding.
Currently, there is no source code or Gradle structure in the root of the repository. This design establishes a multi-module Gradle layout with four architectural layers, profile-aware datasources, Flyway-managed schema, health monitoring, and ArchUnit-enforced boundaries.

## Goals / Non-Goals

**Goals:**
- Define a 4-module Gradle build layout: `domain`, `application`, `infrastructure`, and `bootstrap`.
- Strict inward-pointing dependencies verified by ArchUnit tests.
- Isolate execution environments via Spring profiles: `default`, `dev` (with a local H2 TCP server), and `test`.
- Establish Flyway as the single source of schema truth and configure Hibernate with validation-only (`validate`) table checks.
- Expose Spring Boot Actuator health endpoint `/actuator/health` reporting DB status.
- Enable asynchronous and scheduled task processing from the main application entry point.

**Non-Goals:**
- Implementing actual ledger, transfers, or account management business logic (reserved for features F01 onward).
- Creating REST endpoints (other than `/actuator/health`) or concrete database repositories.

## Decisions

### 1. Multi-Module Gradle Layout
We organize the codebase into four modules:
- **`domain`**: Contains pure-Java rich domain models. Decoupled from frameworks (Spring, Hibernate, etc.).
- **`application`**: Contains use-cases, input/output boundary commands/results, and ports. Pure-Java with minimal lightweight logging (SLF4J).
- **`infrastructure`**: Contains framework-specific implementations (e.g., JPA repositories, controller mappers, OpenAPI generated stubs).
- **`bootstrap`**: Encompasses the Spring Boot Application class, wiring configuration, Flyway migrations, and the ArchUnit validation test suite.

*Rationale*: Forces boundary discipline at the build level. Developers cannot accidentally couple domain rules with framework implementations or import REST DTOs into the domain because the compiler/Gradle will prevent it.

*Alternatives Considered*: Single-module with package boundaries.
- *Rejected*: Harder to enforce strictly since classpath imports are open across the whole project, leading to accidental dependencies.

### 2. Profile-Aware Datasources and H2 TCP Server
We establish three profiles:
- **`default`**: Standard production-like config, using in-memory H2 without TCP server or console.
- **`dev`**: Starts a H2 TCP server (via custom `H2ServerInitializer` bean) listening on port `9092` by default. Exposes the `/h2-console` at web port `8080`.
- **`test`**: Runs with H2 in PostgreSQL compatibility mode for database tests, with the H2 TCP server and console disabled.

*H2 TCP Server Implementation*:
We define `H2ServerInitializer` in the `infrastructure` or `bootstrap` module. It starts H2's `org.h2.tools.Server` on application startup and stops it on shutdown.
```java
@Profile("dev")
@ConditionalOnProperty(name = "bank-core.h2.tcp-server.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class H2ServerInitializer {
    private Server server;
    
    @Value("${bank-core.h2.tcp-server.port:9092}")
    private int port;

    @PostConstruct
    public void start() throws SQLException {
        this.server = Server.createTcpServer("-tcp", "-tcpAllowOthers", "-tcpPort", String.valueOf(port)).start();
        log.info("H2 TCP server started on port {} with connection URL: jdbc:h2:tcp://localhost:{}/mem:bankcore", port, port);
    }

    @PreDestroy
    public void stop() {
        if (this.server != null) {
            this.server.stop();
        }
    }
}
```

*Alternatives Considered*: Spring Boot H2 Console only.
- *Rejected*: In-memory databases are isolated to the JVM process. Without the TCP server, a standard external IDE database tool (e.g., DataGrip, IntelliJ Database explorer) cannot connect to a running Spring Boot app's in-memory instance, limiting debuggability.

### 3. Flyway and JPA Validation
- JPA `ddl-auto` is configured as `validate` across all profiles.
- An initial migration `V1__init.sql` will ship in `bootstrap/src/main/resources/db/migration` to create the initial tables (`account`, `journal_entry`, `ledger_movement`, `audit_checkpoint`).

*Rationale*: Promotes schema changes to be explicit SQL files, preventing Hibernate from making uncontrolled schema alterations.

### 4. ArchUnit for Dependency Boundaries
- Run in the `bootstrap` module's test phase to verify dependency constraints.
- Tests will check that classes in `domain` and `application` do not import Spring classes, JPA annotations, or packages inside `infrastructure`/`bootstrap`.

*Rationale*: Acts as automated linting that physically blocks PRs/builds if clean architecture rules are violated.

## Risks / Trade-offs

- **[Risk]** TCP Server Port Contention: Running multiple JVM instances of the application under the `dev` profile might result in a port conflict on port `9092`.
  - *Mitigation*: Ensure the port is fully configurable via `bank-core.h2.tcp-server.port` and allow disabling the TCP server entirely by setting `bank-core.h2.tcp-server.enabled=false`.
- **[Risk]** Multi-Module Build Overhead: Compiling and managing dependencies across four separate sub-projects increases Gradle setup size and compile overhead slightly.
  - *Mitigation*: Configure root build cache and minimize custom build scripting by sharing build configurations or conventions using Gradle's `subprojects` block or plugins.
