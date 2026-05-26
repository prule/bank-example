## MODIFIED Requirements

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
- **Total transfers — cumulative count summed across every `outcome` tag value (single-number stat tile)**
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
