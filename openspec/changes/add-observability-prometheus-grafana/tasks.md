## 1. Dependencies and configuration

- [ ] 1.1 Add `io.micrometer:micrometer-registry-prometheus` (BOM-managed, no explicit version) as a `runtimeOnly` dependency in `bootstrap/build.gradle.kts`
- [ ] 1.2 Widen `management.endpoints.web.exposure.include` in `bootstrap/src/main/resources/application.yaml` to `health,info,metrics,prometheus`
- [ ] 1.3 Verify the existing `application-test.yaml` does not narrow exposure; if it does, mirror the widened list there

## 2. Instrumentation port and wiring

- [ ] 2.1 Identify the existing `TransferFundsUseCase` entry point in `application/` and add a constructor-injected `io.micrometer.core.instrument.MeterRegistry`
- [ ] 2.2 Wrap the use case body with `Timer.Sample.start(registry)` and on completion stop the sample into `bank.transfer.duration`
- [ ] 2.3 Increment `bank.transfer.executed` with the appropriate `outcome` tag derived from the `TransferResult` variant (`success`, `insufficient_funds`, `account_suspended`, `lock_timeout`)
- [ ] 2.4 In `JvmAccountLocker` and `DbAccountLocker` (or the shared `AccountLocker` interface call site), wrap acquisition with a `Timer` named `bank.lock.acquisition`, tagged `strategy=jvm` or `strategy=db` respectively
- [ ] 2.5 In `JournalVerifier`, increment `bank.journal.verification{outcome="verified"}` on a verified promotion and `{outcome="failed"}` on a failed promotion; on FAILED, increment `bank.account.suspended{cause="journal_failure"}` once per cascaded account
- [ ] 2.6 In the `JournalVerifier` collaborator (or a dedicated `JournalMetricsBinder`), register a Micrometer gauge `bank.journal.pending` whose supplier delegates to `JournalEntryRepository.countPending()`
- [ ] 2.7 In `BalanceDriftDetector`, increment `bank.balance-drift.detected` and `bank.account.suspended{cause="drift"}` once per account flagged in a tick
- [ ] 2.8 Wire `MeterRegistry` into the bean factory methods in `BankCoreApplication` for any plain-Java collaborator that now requires it (Spring auto-configures the bean)

## 3. Observability compose stack

- [ ] 3.1 Create `infrastructure/observability/docker-compose.yaml` with `prometheus` (port 9090) and `grafana` (port 3000) services and a shared network
- [ ] 3.2 Configure the `prometheus` service with `extra_hosts: ["host.docker.internal:host-gateway"]` for Linux compatibility
- [ ] 3.3 Create `infrastructure/observability/prometheus/prometheus.yml` with a scrape job named `bank-core` targeting `host.docker.internal:8080` and path `/actuator/prometheus`, scrape interval 15s
- [ ] 3.4 Create `infrastructure/observability/grafana/provisioning/datasources/prometheus.yaml` declaring a default Prometheus datasource at `http://prometheus:9090`
- [ ] 3.5 Create `infrastructure/observability/grafana/provisioning/dashboards/bank-core.yaml` declaring a file-based dashboard provider pointing at `/etc/grafana/dashboards`
- [ ] 3.6 Author `infrastructure/observability/grafana/dashboards/bank-core.json` with panels: HTTP rate, HTTP p95, JVM heap, JVM non-heap, JVM threads, transfer outcome rate (broken down by `outcome`), transfer p95, lock acquisition p95 (broken down by `strategy`), journal verification rate (broken down by `outcome`), pending journal gauge, account suspension rate (broken down by `cause`)
- [ ] 3.7 Mount provisioning and dashboards into the Grafana container via volume bindings in the compose file
- [ ] 3.8 Disable Grafana anonymous-auth admin only if defaults are inconvenient; otherwise accept the default `admin`/`admin` first-login flow and document it

## 4. Boundary enforcement

- [ ] 4.1 Add an ArchUnit rule to the existing boundary-discipline test class asserting that no class under `com.bank.core.domain..` depends on any class under `io.micrometer..`
- [ ] 4.2 Run the ArchUnit suite and confirm it passes

## 5. Smoke and integration tests

- [ ] 5.1 Add a `@SpringBootTest`-based test that hits `/actuator/prometheus` and asserts status 200, `Content-Type` text/plain, and presence of at least one `bank_` series
- [ ] 5.2 Add unit tests for `TransferFundsUseCase` instrumentation using `SimpleMeterRegistry` and asserting per-outcome counter increments and a non-zero `bank.transfer.duration` sample count
- [ ] 5.3 Add a unit test for the lock-timer wrappers (`Jvm` and `Db`) using `SimpleMeterRegistry`
- [ ] 5.4 Add a unit test for `JournalVerifier` and `BalanceDriftDetector` covering verified/failed/drift counter behaviour
- [ ] 5.5 Add a dashboard-coverage test: parse `infrastructure/observability/grafana/dashboards/bank-core.json`, extract metric names from panel `targets[*].expr`, boot the app, scrape `/actuator/prometheus`, and assert every referenced metric name appears in the output
- [ ] 5.6 Add a `@SpringBootTest` assertion that `GET /actuator/env` returns 404 (verifying non-exposed endpoints stay hidden)

## 6. Documentation

- [ ] 6.1 Add a level-2 `## Observability` section to `ReadMe.md` covering: launch command, default ports (9090, 3000), Grafana first-login flow, dashboard name, and Linux `host.docker.internal` caveat
- [ ] 6.2 Cross-link the section to the new `metrics-exposure` and `observability-stack` capability folders under `openspec/specs/`

## 7. Verify and archive

- [ ] 7.1 Run `./gradlew build` and confirm it passes
- [ ] 7.2 Manually start the app and the compose stack; confirm Prometheus shows the `bank-core` target `UP` and the `Bank Core` dashboard renders without `No data` on the JVM panels
- [ ] 7.3 Issue at least one successful transfer; confirm `bank_transfer_executed_total{outcome="success"}` appears in Prometheus and ticks the dashboard panel
- [ ] 7.4 Run `openspec validate add-observability-prometheus-grafana` and resolve any reported issues
- [ ] 7.5 When all task boxes are checked, run `/opsx:archive add-observability-prometheus-grafana`
