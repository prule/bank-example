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

### Decision 2: Custom metrics live at the **infrastructure boundary**, not inside `application/` use cases

This pivots from the initial proposal. Reading the code revealed that the `application/` module is currently zero-framework — `application/build.gradle.kts` declares only `:domain` and `slf4j-api`, and `ModuleBoundaryTest.applicationHasNoFrameworkDependencies` is an ArchUnit rule that already pins this discipline. Adding `io.micrometer:micrometer-core` to that module and threading `MeterRegistry` into `TransferFunds` / `VerifyPendingJournals` / `DetectBalanceDrift` would be a substantial widening of the framework footprint inside a module the project went out of its way to keep pure.

The existing codebase already shows the right pattern: `BalanceDriftAudit` is a thin Spring `@Service` in `infrastructure/audit/` that wraps the framework-free `DetectBalanceDrift` and owns the `@Transactional` boundary. Schedulers (`JournalVerificationScheduler`, `BalanceDriftScheduler`) sit in `infrastructure/scheduling/` and already receive `SweepReport` / `DriftReport` from their use cases. Lock adapters (`JvmAccountLocker`, `DbAccountLocker`) are in `infrastructure/concurrency/`.

So custom metric increments live entirely in `infrastructure/`:
- **Transfer**: `TransferController.createTransfer(...)` already owns the `@Transactional` boundary. Wrap the `transferFunds.transfer(command)` call with a `Timer.Sample`; classify the outcome by caught-exception type (`InsufficientFundsException` → `insufficient_funds`, `AccountInactiveException` → `account_suspended`, `LockAcquisitionTimeoutException` → `lock_timeout`, no exception → `success`); re-throw so error handling and rollback semantics stay unchanged.
- **Lock acquisition**: time the acquisition phase only (not the runnable). In `JvmAccountLocker`, wrap the two `acquire(...)` calls with a single `Timer` sample so the timer measures from "start acquiring first lock" to "second lock acquired (or timeout)". In `DbAccountLocker`, wrap the single `SELECT FOR UPDATE` `jdbcTemplate.query(...)` call.
- **Journal verification**: in `JournalVerificationScheduler.tick()`, after `useCase.sweep()` returns, increment `bank.journal.verification{outcome="verified"}` by `report.verified()` and `{outcome="failed"}` by `report.failed()`; increment `bank.account.suspended{cause="journal_failure"}` by `report.suspendedFromCascade()` (new SweepReport field — see below).
- **Balance drift**: in `BalanceDriftScheduler.tick()`, after `audit.audit()` returns, increment `bank.balance-drift.detected` and `bank.account.suspended{cause="drift"}` by `report.drifted()`.

This keeps `application/` framework-free; the only `application/` touch is extending `SweepReport` (a plain record) with one new int field (see Decision 8 below).

**Alternatives considered:**
- *Original plan: thread `MeterRegistry` into use cases.* Rejected on widening the framework footprint of `application/`. Decision 2 in the initial draft was wrong to accept this.
- *Spring events with a single metrics listener.* Rejected as before — extra indirection, fire-and-forget hides failures, harder to test.
- *A `MeteredTransferFunds` decorator in infrastructure wrapping `TransferFunds`.* Cleaner in isolation but adds a class and re-routes wiring in `BankCoreApplication`; the existing controller is already the right boundary because `@Transactional` already lives there.

### Decision 8: Extend `SweepReport` with `suspendedFromCascade` so the scheduler can surface journal-failure suspensions

`SweepReport` today carries `processed`, `verified`, `failed`, `errored`. The spec scenario "Journal failure cascades to suspension" requires `bank.account.suspended{cause="journal_failure"}` to tick by the number of accounts the cascade actually suspended — which today is counted nowhere. Adding a fifth field `suspendedFromCascade: int` is a one-line record extension; `VerifyPendingJournals.suspendIfActive(...)` returns a boolean that the caller can sum.

This is the only change to `application/` in the entire observability stack. No new dependency, no new framework annotation — just one more counter on an existing plain-Java record. Touches the SweepReport invariant only by adding an unrelated counter alongside the existing ones (the invariant `processed == verified + failed + errored` is unchanged).

### Decision 3: Gauge for `bank.journal.pending` is registered with a supplier, not polled by a scheduler

Micrometer's gauge takes a function; on scrape, the function is invoked. The supplier delegates to a new port method on `JournalEntries.countByStatus(VerificationStatus)` — a plain-Java method added to the existing application-layer port; the JPA adapter implements it with a single `COUNT(*)` query. No new scheduler. Scrape cadence (Prometheus default 15 s) is the polling cadence.

Registration happens in a small `JournalPendingGauge` `@Component` under `infrastructure/observability/` whose `@PostConstruct` registers a Micrometer gauge against `MeterRegistry`, with the supplier closure capturing the `JournalEntries` port. Keeps the gauge wiring out of the use case and the application module.

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
