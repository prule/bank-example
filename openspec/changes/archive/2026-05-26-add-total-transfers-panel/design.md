## Context

`bank-core.json` is a Grafana 11 dashboard with 11 panels arranged in five rows. The bottom row (y=32) currently has two panels: a `Pending journals` stat (8 wide) and an `Account suspension rate` timeseries (16 wide). The rest of the panel real estate is consumed by 8- and 12-wide rows. The natural home for one more single-number tile is alongside `Pending journals`.

Spring Boot Actuator exposes `bank_transfer_executed_total` as a Prometheus counter with one series per `outcome` label value. Summing across all outcomes gives the cumulative attempt count this instance has classified — exactly what an operator means by "total transfers".

## Goals / Non-Goals

**Goals:**
- One stat tile, one number, sitting next to `Pending journals` so the bottom-row "single-number summary" reads as a coherent group.
- Reuse the same `bank_transfer_executed_total` series the existing per-outcome rate panel reads, so no new instrumentation is added.
- Dashboard remains valid against `DashboardCoverageTest`'s metric-presence gate without further test changes.

**Non-Goals:**
- Per-outcome breakdown in this tile (the rate panel already covers it).
- A "successful transfers only" variant — operators reading a `Total transfers` tile in isolation almost always mean "all attempts"; the rate panel below it shows the split.
- Counters for non-transfer events (suspensions, drift) — those have their own panels.
- Re-styling the dashboard.

## Decisions

### Decision 1: `sum(bank_transfer_executed_total)`, not per-outcome stacking

A stat panel shows one number. The user-facing question is "how many transfers?" → cumulative count. `sum(...)` collapses every `outcome` label into a single value. A per-label stat (e.g. `bank_transfer_executed_total{outcome="success"}`) would silently undercount.

**Alternative considered**: `sum(rate(bank_transfer_executed_total[5m]))` to show "transfers per second". Rejected — the existing "Transfer outcome rate" panel already shows this; the new tile is meant to be the *cumulative* counterpart.

### Decision 2: Place the tile in the existing bottom row, shrink the `Account suspension rate` panel to fit

Current bottom row: `Pending journals` (x=0, w=8) + `Account suspension rate` (x=8, w=16). Total = 24 wide (full row).

New layout: `Pending journals` (x=0, w=6) + `Total transfers` (x=6, w=6) + `Account suspension rate` (x=12, w=12). Still 24 wide, all three tiles visible without scrolling. The suspension-rate panel goes from 16-wide → 12-wide, which is enough for its 2-line legend (`drift`, `journal_failure`).

**Alternative considered**: append a new row at the bottom (y=38). Rejected — adds vertical scrolling for a single tile and breaks the "single-number tiles share a row" mental grouping.

### Decision 3: Stat panel, not gauge or timeseries

`stat` is Grafana's idiomatic single-value display. Same panel type the `Pending journals` tile uses, so the two adjacent tiles look consistent.

## Risks / Trade-offs

- **Risk**: shrinking `Account suspension rate` from 16 to 12 wide cramps the legend → Mitigation: only two label values (`drift`, `journal_failure`); 12-wide is well within tested Grafana legend layouts.
- **Risk**: panel `id` collision if a future change adds panel 12 → Mitigation: the new panel gets `"id": 12`, the next available integer; standard Grafana practice.
- **Trade-off**: the dashboard JSON's `gridPos` for one existing panel changes. Diff is bigger than "just add a panel" but smaller than a full re-layout.
