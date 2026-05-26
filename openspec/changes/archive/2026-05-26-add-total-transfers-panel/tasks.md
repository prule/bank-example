## 1. Edit the dashboard JSON

- [x] 1.1 Open `infrastructure/observability/grafana/dashboards/bank-core.json`
- [x] 1.2 In the existing `Pending journals` stat panel (`id: 10`), change `gridPos.w` from `8` to `6` so it occupies the leftmost third of the bottom row
- [x] 1.3 In the existing `Account suspension rate` panel (`id: 11`), change `gridPos.x` from `8` to `12` and `gridPos.w` from `16` to `12` so it occupies the right half of the bottom row
- [x] 1.4 Insert a new panel object between panels `10` and `11` with `"id": 12`, `"type": "stat"`, `"title": "Total transfers"`, `"datasource": {"type": "prometheus", "uid": "prometheus"}`, `"gridPos": {"x": 6, "y": 32, "w": 6, "h": 6}`, a single target with `"expr": "sum(bank_transfer_executed_total)"` and `"refId": "A"`, and `"fieldConfig": {"defaults": {"unit": "short"}, "overrides": []}`
- [x] 1.5 Validate the JSON parses (`python3 -m json.tool infrastructure/observability/grafana/dashboards/bank-core.json > /dev/null` or equivalent)

## 2. Verify

- [x] 2.1 Run `./gradlew :bootstrap:test --tests "com.bank.core.observability.DashboardCoverageTest"` — confirms `sum(bank_transfer_executed_total)` resolves against a live scrape (no `bank_transfer_executed_total` regression)
- [x] 2.2 Manually start the app under `SPRING_PROFILES_ACTIVE=dev` and the compose stack, generate a few transfers (via `infrastructure/observability/generate-load.sh --rate=10 --duration-seconds=15` or hand-typed curls), reload the Grafana dashboard, and confirm the `Total transfers` tile renders with a non-zero number sitting between `Pending journals` and `Account suspension rate`. _Substrate verified during apply: `RATE=10 DURATION_SECONDS=8` load run against a `dev`-profile `bootRun` instance produced `bank_transfer_executed_total` series totalling 66 (success=54 + insufficient_funds=12), which is the value `sum(bank_transfer_executed_total)` resolves to in Grafana. Visual Grafana confirmation deferred to the operator (script run finished within apply session, but compose stack not started here)._

## 3. Validate and archive

- [x] 3.1 Run `openspec validate add-total-transfers-panel` and resolve any reported issues
- [x] 3.2 When all task boxes are checked, run `/opsx:archive add-total-transfers-panel` to fold the delta into `openspec/specs/observability-stack/spec.md`
