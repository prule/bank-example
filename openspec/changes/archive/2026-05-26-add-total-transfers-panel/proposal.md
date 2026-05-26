## Why

The `Bank Core` dashboard has a "Transfer outcome rate" panel (per-second, broken down by outcome) and a "Transfer p95 duration" panel, but no single-number "how many transfers has this instance handled?" tile. That count is the most natural at-a-glance health signal for an operator scanning the dashboard — and the data is already in `/actuator/prometheus` as `bank_transfer_executed_total`. We just need a panel that reads it.

## What Changes

- Add a `Total transfers` stat panel to `infrastructure/observability/grafana/dashboards/bank-core.json`. The panel sums `bank_transfer_executed_total` across all `outcome` tag values, rendering one number — the cumulative count of every transfer attempt this instance has classified.
- Place it next to the existing `Pending journals` stat panel so the two single-number tiles sit side by side at the bottom of the dashboard.
- Update the `observability-stack` capability spec to list the new panel under the dashboard's panel inventory.

Out of scope:
- No new metric. `bank_transfer_executed_total` already exists and is emitted by `TransferMetrics`.
- No per-outcome breakdown inside this tile — that's already the "Transfer outcome rate" panel. This panel is the one-number summary.
- No alerting rule.

## Capabilities

### New Capabilities
<!-- None. This is a dashboard-only addition. -->

### Modified Capabilities
- `observability-stack`: amend the "Pre-provisioned 'Bank Core' dashboard" requirement to add `Total transfers` to the enumerated panel list.

## Impact

- **Code**: none.
- **Dashboard JSON**: one new panel entry in `bank-core.json`; layout-shift on the bottom row to fit the new tile next to `Pending journals`.
- **Spec**: one MODIFIED requirement in `openspec/changes/add-total-transfers-panel/specs/observability-stack/spec.md`, deltaing the dashboard-panel inventory.
- **Tests**: the existing `DashboardCoverageTest` automatically gates the panel's metric reference against the live scrape — no test edit required as long as the new query references `bank_transfer_executed_total`, which the scrape already exposes.
- **README**: no change. The README's metric table already lists `bank_transfer_executed_total`.
