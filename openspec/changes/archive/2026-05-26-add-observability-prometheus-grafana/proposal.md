## Why

The bank service runs Spring Boot Actuator but exposes only `/actuator/health`. There is no runtime visibility into transfer throughput, lock-contention timeouts, background-sweeper progress, drift incidents, or JVM health. Operators reviewing the study branches cannot compare versions on observable behaviour, and the audit components (journal verifier, balance drift detector) record their decisions only via logs — there is no metric to alert on a Suspended-account spike or a stalled sweep.

This change adds first-class observability so the running service can be scraped by Prometheus and visualised in Grafana, giving the project a defensible production-shape it currently lacks.

## What Changes

- Add Micrometer Prometheus registry as a bootstrap runtime dependency; expose `/actuator/prometheus` alongside existing `/actuator/health`.
- Expand actuator exposure to include `info`, `metrics`, and `prometheus` endpoints.
- Emit domain-specific custom metrics from the application layer (framework-free counters/timers passed in as a `MeterRegistry` port, kept out of the domain module):
  - `bank.transfer.executed` (counter, tags: `outcome=success|insufficient_funds|account_suspended|lock_timeout`)
  - `bank.transfer.duration` (timer)
  - `bank.lock.acquisition` (timer, tag: `strategy=jvm|db`)
  - `bank.journal.verification` (counter, tags: `outcome=verified|failed`)
  - `bank.journal.pending` (gauge — current Pending journal count)
  - `bank.balance-drift.detected` (counter)
  - `bank.account.suspended` (counter, tag: `cause=drift|journal_failure|manual`)
- Ship a Docker Compose stack under `infrastructure/observability/` containing Prometheus and Grafana, with:
  - Prometheus scrape config pointing at the host-published actuator endpoint.
  - Pre-provisioned Grafana datasource and a single dashboard JSON ("Bank Core") covering JVM, HTTP, and the custom domain metrics above.
- Document the workflow in `ReadMe.md` (a short "Observability" section: how to launch the stack, default ports, dashboard URL).
- Add an ArchUnit rule extending existing boundary discipline: domain module must not depend on Micrometer types.

No behavioural change to transfers, ledger, or audit semantics. No public API change. No breaking changes.

## Capabilities

### New Capabilities
- `metrics-exposure`: HTTP-scrape contract — which actuator endpoints are exposed, which custom metric names/tags the service emits, and the cardinality bounds those tags must respect.
- `observability-stack`: The Prometheus + Grafana compose stack shipped with the repo — scrape target wiring, dashboard provisioning, and the operator workflow to launch it.

### Modified Capabilities
<!-- None. The custom metric instrumentation is an implementation detail of existing capabilities (fund-transfer, transfer-locking, journal-verification, balance-drift-detection); their requirements do not change. -->

## Impact

- **Code**: new `MeterRegistry`-using collaborators in `application/` (or a small `infrastructure/metrics` package) wired into existing use cases and schedulers; no edits to domain entities.
- **Build**: one new dependency line in `bootstrap/build.gradle.kts` (`io.micrometer:micrometer-registry-prometheus`, version managed by Spring Boot BOM).
- **Config**: `bootstrap/src/main/resources/application.yaml` actuator exposure list expands; no profile-specific overrides required.
- **Ops**: new `infrastructure/observability/` directory with `docker-compose.yaml`, `prometheus.yml`, `grafana/provisioning/*`, `grafana/dashboards/bank-core.json`. Not started by the app — operator runs it.
- **Docs**: `ReadMe.md` gains an Observability section; no spec rewrites elsewhere.
- **Tests**: smoke test asserting `/actuator/prometheus` returns 200 and contains at least one custom `bank_*` metric; ArchUnit test enforcing domain ↛ Micrometer.
- **No impact on**: OpenAPI contract, HATEOAS responses, database schema, Flyway migrations, lock strategy, or seed data.
