## Context

The service today is observable only through logs and `/actuator/health`. The bootstrap module already depends on `spring-boot-starter-actuator`, so the cheapest path to Prometheus scraping is adding the Micrometer Prometheus registry and widening actuator exposure. Custom domain metrics are the bigger design question: this project takes pride in clean-architecture boundaries (domain modules contain zero Spring/Hibernate annotations and ArchUnit enforces it), so a naïve `@Autowired MeterRegistry` sprinkled across the domain would be a regression.

The audit components (`balance-drift-detection`, `journal-verification`) and the transfer locker (`transfer-locking`) all produce events that operators currently only learn about post-incident, by greppping logs. Surfacing them as metrics is the highest-value part of the change.

Stakeholders: study-branch reader (wants to compare versions), operator running `./run.sh` (wants a dashboard), and the eventual `v3-clean` rebuild (wants the observability contract documented as a spec).

## Goals / Non-Goals

**Goals:**
- Scrape Prometheus metrics from a running bank-core instance over HTTP.
- Visualise JVM, HTTP, and the named domain metrics in a pre-provisioned Grafana dashboard.
- Keep domain types framework-free; instrumentation lives at the application/infrastructure boundary.
- Make the observability stack self-contained — one `docker compose up` brings up Prometheus + Grafana, pre-wired to scrape the host-published actuator endpoint.
- Bound metric-tag cardinality so the registry cannot be made to OOM by user input (e.g. tagging by account number is forbidden).

**Non-Goals:**
- No distributed tracing (OpenTelemetry / Tempo / Zipkin). Logs and metrics only.
- No log aggregation (Loki, ELK). Logs stay on stdout.
- No alerting rules in Prometheus or Alertmanager — dashboard only. (Alert thresholds are easier to design once we have real traffic data; deferred to a follow-up change.)
- No authentication on the actuator or Prometheus endpoints. The study app runs on localhost; production-style hardening is out of scope.
- No pushgateway. Pull-based scraping only.
- No replacement of existing logging.

## Decisions

### Decision 1: Micrometer Prometheus registry over a hand-rolled `/metrics` endpoint

Chose `io.micrometer:micrometer-registry-prometheus` (pulled transitively via the Spring Boot BOM, version-managed). Spring Boot Actuator auto-configures `MeterRegistry` and binds JVM/HTTP/Tomcat metrics for free.

**Alternatives considered:** writing a hand-rolled `/metrics` controller that exposes the OpenMetrics text format directly. Rejected — Spring Boot already ships the integration and gives free JVM/HTTP/datasource metrics; reinventing it would be study-noise.

### Decision 2: Custom metrics live in `application/`, not `domain/`

Domain classes (`Account`, `JournalEntry`, `Movement`) stay framework-free. Custom metric increments happen in:
- Application use cases (`TransferFundsUseCase`) — wrap the call to the domain in a `Timer.Sample` and increment the per-outcome counter based on the typed `TransferResult`.
- Scheduler-adjacent collaborators (`JournalVerifier`, `BalanceDriftDetector`) — increment counters when their plain-Java decision logic returns.
- Lock acquisition wrappers (`JvmAccountLocker`, `DbAccountLocker`) — already have a clean entry point; wrap with a `Timer` there.

Plain-Java decision components stay testable without a Spring context: they take a `MeterRegistry` (which is a Micrometer interface, not a Spring annotation) by constructor, and unit tests pass a `SimpleMeterRegistry`. Micrometer is a sufficiently neutral dependency to tolerate at the use-case layer without violating boundary discipline — but the ArchUnit rule forbids it from the `domain` module to make the line explicit.

**Alternatives considered:** Spring events with a single metrics listener. Rejected — adds an extra indirection (publish → listen → increment) for no benefit; events fire-and-forget makes failures invisible; harder to unit test causality.

### Decision 3: Gauge for `bank.journal.pending` is registered with a supplier, not polled by a scheduler

Micrometer's gauge takes a function; on scrape, the function is invoked. The supplier delegates to `journalEntryRepository.countPending()`. No new scheduler. Scrape cadence (Prometheus default 15 s) is the polling cadence.

**Trade-off:** every scrape issues one cheap `COUNT(*) WHERE status = 'PENDING'` query. Acceptable; if this becomes hot the verifier itself can publish the value after each tick.

### Decision 4: Tag-cardinality discipline

Metric tags use bounded enums only:
- `outcome` (transfer): one of `success | insufficient_funds | account_suspended | lock_timeout` (matches the existing `TransferResult` sealed hierarchy).
- `outcome` (journal verification): `verified | failed`.
- `cause` (suspension): `drift | journal_failure | manual`.
- `strategy` (lock): `jvm | db`.

Forbidden tags: account number, journal id, customer name, anything user-controlled or unbounded. This is asserted by code review, not enforced mechanically — but the spec calls it out so the rule is documented.

### Decision 5: Observability stack lives in `infrastructure/observability/` and is launched by the operator, not the app

A `docker-compose.yaml` under `infrastructure/observability/` brings up Prometheus + Grafana. The Spring Boot app is **not** containerised by this change — Prometheus scrapes `host.docker.internal:8080/actuator/prometheus` (Linux: extra-hosts mapping required, documented in the README section).

**Alternatives considered:** Testcontainers-driven stack started during integration tests. Rejected — runtime cost, no operator-facing value; metrics smoke test can use a `SimpleMeterRegistry` instead.

### Decision 6: Dashboard provisioning is file-based, checked into git

Grafana provisioning uses `provisioning/datasources/prometheus.yaml` and `provisioning/dashboards/bank-core.yaml` pointing at `dashboards/bank-core.json`. Dashboard is hand-authored, not generated. Editable in the running Grafana but the JSON in the repo is the source of truth.

### Decision 7: Default actuator exposure

`management.endpoints.web.exposure.include` widens from `health` to `health,info,metrics,prometheus`. Detailed health stays on (already configured). No security on the endpoints — same as today.

## Risks / Trade-offs

- **Metric-tag cardinality blow-up** → Mitigated by Decision 4. Tags drawn from compile-time enums/sealed types; no user input flows in.
- **Pending-journal gauge issues a query per scrape** → Acceptable at 15 s default cadence; documented; reversible (move to push-on-tick if profile shows it hot).
- **`host.docker.internal` is Linux-fragile** → Compose file ships with `extra_hosts: host.docker.internal:host-gateway` for Linux compatibility; README calls this out.
- **Actuator endpoints exposed without auth** → Same posture as today's `/actuator/health`. Acceptable for the study app; flagged in the README so a downstream operator knows.
- **Drift between dashboard JSON and the metrics the code actually emits** → Mitigated by a smoke test that asserts each `bank_*` metric name referenced in the dashboard exists in `/actuator/prometheus`. Cheap parse step in CI.
- **Micrometer dependency reaches application/ module** → Acceptable boundary widening; explicit ArchUnit rule keeps it out of `domain/`. Documented in Decision 2.

## Migration Plan

1. Add `micrometer-registry-prometheus` to `bootstrap/build.gradle.kts` runtime classpath.
2. Widen actuator exposure in `application.yaml`.
3. Land instrumentation collaborators behind no-op `SimpleMeterRegistry` first; verify existing tests still pass.
4. Land the docker-compose stack and dashboard JSON.
5. Land smoke + ArchUnit tests.
6. Update `ReadMe.md`.

**Rollback:** revert the commits; no schema or data changes. Operator stops the compose stack with `docker compose down`. The runtime cost of leaving the actuator endpoint exposed if rollback is incomplete is negligible.

## Open Questions

- Should `bank.journal.pending` also have a sibling `bank.journal.failed` gauge? Current proposal: counter only, since FAILED is terminal and a running total + retention window covers it. Revisit after first dashboard review.
- Histogram buckets for `bank.transfer.duration` — start with Micrometer defaults; tune once we have a real distribution.
