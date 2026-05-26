# Observability Stack

## Purpose

The Prometheus + Grafana Docker Compose stack shipped with the repository under `infrastructure/observability/`. Owns the scrape-target wiring (host-gateway resolution included), the pre-provisioned Grafana datasource and "Bank Core" dashboard, the dashboard/scrape coverage guarantee, and the operator workflow for launching and tearing the stack down. Consumes the contract defined by [[metrics-exposure]] — every dashboard panel queries metrics the service is required to emit.

## Requirements

### Requirement: Compose-managed Prometheus + Grafana stack

The repository SHALL contain a Docker Compose stack under `infrastructure/observability/` that, when launched with `docker compose up`, starts a Prometheus service and a Grafana service on the local machine.

#### Scenario: Stack starts cleanly
- **WHEN** an operator runs `docker compose -f infrastructure/observability/docker-compose.yaml up -d` on a host with the bank-core app running on port 8080
- **THEN** both `prometheus` and `grafana` containers SHALL reach the `running` state
- **AND** Prometheus SHALL be reachable on `http://localhost:9090`
- **AND** Grafana SHALL be reachable on `http://localhost:3000`

#### Scenario: Stack tears down cleanly
- **WHEN** an operator runs `docker compose -f infrastructure/observability/docker-compose.yaml down`
- **THEN** both containers SHALL stop and be removed
- **AND** no host port between 3000 and 9090 SHALL remain bound by either service

### Requirement: Prometheus scrape configuration

The shipped Prometheus configuration SHALL include a scrape job named `bank-core` that targets the host-published `/actuator/prometheus` endpoint of the application.

#### Scenario: Prometheus shows bank-core as an UP target
- **WHEN** the operator opens `http://localhost:9090/targets` with the app running on the host on port 8080
- **THEN** a target with job label `bank-core` SHALL be listed
- **AND** its `State` SHALL be `UP`

#### Scenario: Custom metric is queryable
- **WHEN** at least one successful transfer has been executed against the running app
- **AND** at least one Prometheus scrape has elapsed
- **THEN** the query `bank_transfer_executed_total{outcome="success"}` against `http://localhost:9090` SHALL return a value of at least 1

#### Scenario: Linux host networking
- **WHEN** the compose stack runs on a Linux host (no native `host.docker.internal`)
- **THEN** the compose file SHALL declare `extra_hosts: ["host.docker.internal:host-gateway"]` on the Prometheus service so the scrape target resolves

### Requirement: Pre-provisioned Grafana datasource

Grafana SHALL come up with a Prometheus datasource named `Prometheus` pre-configured as the default, pointing at the in-stack Prometheus service. No manual datasource configuration in the Grafana UI SHALL be required.

#### Scenario: Datasource visible on first login
- **WHEN** an operator logs into Grafana for the first time at `http://localhost:3000`
- **THEN** under `Connections → Data sources` a datasource named `Prometheus` SHALL be listed
- **AND** it SHALL be marked `default`
- **AND** its URL SHALL resolve to the in-stack Prometheus service

### Requirement: Pre-provisioned "Bank Core" dashboard

Grafana SHALL come up with a pre-provisioned dashboard titled `Bank Core` that contains panels for:
- HTTP request rate and p95 latency (from `http_server_requests_seconds`)
- JVM heap and non-heap memory usage
- JVM live thread count
- Transfer outcome rate, broken down by `outcome` tag
- Transfer p95 duration
- Lock acquisition p95 duration, broken down by `strategy` tag
- Journal verification rate, broken down by `outcome` tag
- Current pending journal gauge
- Total transfers — cumulative count summed across every `outcome` tag value (single-number stat tile)
- Account suspension rate, broken down by `cause` tag

#### Scenario: Dashboard is listed on first login
- **WHEN** an operator logs into Grafana at `http://localhost:3000`
- **THEN** under `Dashboards` a dashboard titled `Bank Core` SHALL be listed

#### Scenario: Dashboard panels resolve
- **WHEN** the operator opens the `Bank Core` dashboard with the app running
- **THEN** every panel SHALL render without a `No data` or `Datasource not found` error

#### Scenario: Total transfers tile shows cumulative attempt count
- **WHEN** the operator opens the `Bank Core` dashboard after at least one successful transfer has been issued
- **THEN** a stat panel titled `Total transfers` SHALL be visible
- **AND** its value SHALL equal `sum(bank_transfer_executed_total)` at the time of the scrape (i.e. the sum across every `outcome` tag value present in the registry)

### Requirement: Dashboard / scrape coverage consistency

Every Prometheus metric name referenced by a `Bank Core` dashboard panel query SHALL be present in the `/actuator/prometheus` scrape output of a freshly-started application instance.

#### Scenario: Coverage smoke test passes
- **WHEN** the dashboard-coverage smoke test parses `infrastructure/observability/grafana/dashboards/bank-core.json`, extracts every metric name referenced in panel queries, and scrapes a running app
- **THEN** every referenced metric name SHALL appear in the scrape output (either as a `# HELP <name>` line or as a sample line)

### Requirement: Operator documentation

The repository `ReadMe.md` SHALL contain an `Observability` section describing how to launch the stack, the default ports, the dashboard URL, and the known Linux host-networking caveat.

#### Scenario: README section is present
- **WHEN** a reader opens `ReadMe.md`
- **THEN** the file SHALL contain a level-2 heading `Observability`
- **AND** that section SHALL include the commands to start and stop the stack
- **AND** it SHALL list the default ports (`9090` for Prometheus, `3000` for Grafana) and the Grafana default login behaviour
- **AND** it SHALL note that scraping the host from inside the compose network relies on `host.docker.internal` (with the Linux caveat called out)
