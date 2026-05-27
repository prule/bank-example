# Load Generator

## Purpose

The bash + curl load generator shipped under `infrastructure/observability/generate-load.sh`. Owns the operator-facing tool that drives a mixed-outcome stream of transfer requests against the dev-seeded bank-core instance so that the Prometheus + Grafana stack has realistic traffic to render. Consumes the dev seed contract (see [[dev-data-seeding]]) and the transfer API contract (see [[fund-transfer]] and [[contract-first-api]]); produces traffic that exercises the metrics defined by [[metrics-exposure]].

## Requirements

### Requirement: Script location and shape

The repository SHALL contain an executable shell script at `infrastructure/observability/generate-load.sh` written in `bash` and invoking `curl` for every HTTP call. The script SHALL NOT introduce any other runtime dependency (no `jq`, `python`, `node`, `java`). The script SHALL be marked executable (`chmod +x`) in the working tree.

#### Scenario: Script exists and is executable

- **WHEN** an operator checks out the repository and runs `ls -l infrastructure/observability/generate-load.sh`
- **THEN** the file exists
- **AND** the file mode includes the user-executable bit (`-rwxr-xr-x` or similar)
- **AND** the first line is a `bash` shebang (e.g. `#!/usr/bin/env bash`)

#### Scenario: No extra runtime dependency

- **WHEN** the script's source is inspected for invocations of external binaries
- **THEN** the only non-builtin commands it calls are `curl`, `sleep`, `date`, `printf`, `echo`, `mktemp`, and shell builtins
- **AND** it does not invoke `jq`, `python`, `node`, `java`, or any other interpreter

### Requirement: Pre-flight reachability and seed-data checks

Before issuing any load traffic, the script SHALL verify that the target service is reachable and that the expected dev seed accounts exist. On either check failing, the script SHALL print a single human-readable error message naming the missing precondition and exit with a non-zero status.

#### Scenario: Service unreachable

- **WHEN** the script is invoked while no bank-core instance is listening on `$BANK_URL` (default `http://localhost:8080`)
- **THEN** the script prints a message containing `bank-core` and `$BANK_URL` and a remediation hint pointing at `./gradlew :bootstrap:bootRun`
- **AND** the script exits with status `1` or greater
- **AND** no `POST /api/v1/transfers` request is issued

#### Scenario: Seed accounts missing

- **WHEN** the service is reachable but `GET $BANK_URL/api/v1/accounts/CUST-1001` returns `404`
- **THEN** the script prints a message referencing the dev seed and recommending `SPRING_PROFILES_ACTIVE=dev`
- **AND** the script exits with non-zero status
- **AND** no `POST /api/v1/transfers` request is issued

### Requirement: Mixed-outcome request stream

The script SHALL issue an interleaved stream of transfer requests against the dev-seed accounts that produces, in expectation, the following category breakdown:

- ~70% **success**: from `CUST-1001` to `CUST-1002` for a small positive amount.
- ~15% **insufficient-funds**: from `CUST-1003` (zero opening balance) to `CUST-1001` for an amount greater than `CUST-1003`'s balance.
- ~15% **same-account-rejected**: from `CUST-1001` to `CUST-1001` (rejected by `SameAccountTransferException`).

The script SHALL NOT issue transfers involving any account other than `CUST-1001`, `CUST-1002`, `CUST-1003`, or `CLEARING-000`. Categorisation SHALL be made by the script per request (e.g. via `$RANDOM`) so the realised mix in a single run is approximately, not exactly, the configured ratio.

#### Scenario: All three categories appear in dry-run output

- **WHEN** the operator runs `./generate-load.sh --dry-run` with `DURATION_SECONDS=10` and `RATE=10`
- **THEN** the dry-run output lists at least one request for each of the three categories (success, insufficient_funds, same_account)
- **AND** every request has `sourceAccountNumber` and `destinationAccountNumber` drawn from the allow-list `{CUST-1001, CUST-1002, CUST-1003, CLEARING-000}`
- **AND** no request involves an account outside that allow-list

#### Scenario: Live run populates bank_transfer_executed_total for two outcomes

- **WHEN** the operator runs `./generate-load.sh` (defaults) against a running app for at least 30 seconds
- **AND** then queries Prometheus
- **THEN** `bank_transfer_executed_total{outcome="success"}` is at least 1
- **AND** `bank_transfer_executed_total{outcome="insufficient_funds"}` is at least 1

### Requirement: Configurable via environment variables

The script SHALL accept the following environment variables, defaulting per the proposal when unset:

- `BANK_URL` — base URL of the bank-core service (default `http://localhost:8080`).
- `RATE` — target requests per second (default `5`, integer; values `1`–`50` SHALL behave as documented).
- `DURATION_SECONDS` — total runtime budget in seconds (default `120`, positive integer).

The script SHALL NOT require any other configuration to run with defaults.

#### Scenario: Defaults run with no environment

- **WHEN** the script is invoked with no env vars set
- **THEN** it targets `http://localhost:8080`, issues at approximately 5 TPS, and stops after 120 seconds (plus or minus a small drift)

#### Scenario: Operator overrides rate and duration

- **WHEN** the operator runs `RATE=10 DURATION_SECONDS=30 ./generate-load.sh`
- **THEN** the summary line at the end reports a total request count of approximately `10 × 30 = 300` (allowing ±20% drift)
- **AND** the wall-clock runtime is approximately 30 seconds (plus pre-flight time)

### Requirement: Dry-run mode

The script SHALL accept a `--dry-run` flag (positional or `DRY_RUN=true` env var). In dry-run mode, the script SHALL print the JSON body and target URL of every request it would issue under the current configuration, but SHALL NOT make any network call after the initial pre-flight checks.

#### Scenario: Dry-run prints requests but issues none

- **WHEN** the operator runs `./generate-load.sh --dry-run` with `RATE=5 DURATION_SECONDS=4`
- **THEN** the output contains approximately 20 lines describing planned requests (4 seconds × 5 TPS)
- **AND** each planned request shows the target URL `POST $BANK_URL/api/v1/transfers` and a JSON body with `sourceAccountNumber`, `destinationAccountNumber`, and `amount`
- **AND** no `POST` request is actually issued (verifiable by absence of new ledger movements in the running app, if checked)

### Requirement: End-of-run summary

After completion (timer-driven termination or Ctrl-C), the script SHALL emit a single summary line to stdout reporting:

- Total requests issued.
- Count of responses by HTTP status (`2xx`, `4xx`, `5xx` bucketed; or per-status with counts).
- Wall-clock runtime in seconds.
- Effective rate (requests per second).

#### Scenario: Summary on normal completion

- **WHEN** the script completes its full `DURATION_SECONDS` budget
- **THEN** the final line of stdout matches a pattern containing the keywords `total`, `2xx`, `4xx`, `seconds`, and `rate`
- **AND** the numbers reported are consistent: `2xx + 4xx + 5xx == total` (±0)

#### Scenario: Summary on early Ctrl-C

- **WHEN** the operator sends SIGINT (Ctrl-C) before `DURATION_SECONDS` elapses
- **THEN** the script still emits the summary line for the requests it managed to issue
- **AND** exits with a non-zero status reflecting the interrupted state

### Requirement: README operator documentation

The repository `ReadMe.md` `Observability` section SHALL contain a subsection that names the script, gives the canonical invocation, and lists the three environment variables.

#### Scenario: README points at the script

- **WHEN** a reader opens `ReadMe.md` and locates the `Observability` section
- **THEN** the section contains at least one line referencing `infrastructure/observability/generate-load.sh`
- **AND** the section lists `BANK_URL`, `RATE`, and `DURATION_SECONDS` as configurable env vars
- **AND** the section names the dependency on `SPRING_PROFILES_ACTIVE=dev` for the seed accounts
