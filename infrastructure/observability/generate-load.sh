#!/usr/bin/env bash
#
# Dashboard load generator for bank-core.
#
# Issues a mixed stream of POST /api/v1/transfers requests against a running
# bank-core instance so every panel on the "Bank Core" Grafana dashboard
# (infrastructure/observability/grafana/dashboards/bank-core.json) lights up
# within ~30 seconds. The default 120s runtime gives the 5-minute rate
# panels enough samples to be visibly populated.
#
# Outcome mix (default ratios):
#   ~70%  success           CUST-1001 -> CUST-1002 (random small amount)
#   ~15%  insufficient_funds CUST-1003 -> CUST-1001 (over-large amount)
#   ~15%  same-account      CUST-1001 -> CUST-1001 (rejected with HTTP 400;
#                                                   exercises HTTP-rate
#                                                   panels but does NOT
#                                                   increment any classified
#                                                   bank.transfer.executed
#                                                   outcome series — per the
#                                                   metrics-exposure spec.)
#
# This is a demo/operator tool, NOT a load-testing harness. Point it at the
# dev instance only.
#
# Configuration (all env vars, all optional):
#   BANK_URL          base URL                 (default http://localhost:8080)
#   RATE              requests per second      (default 5; sane range 1..50)
#   DURATION_SECONDS  total runtime in seconds (default 120)
#   DRY_RUN           true/false               (default false; alias: --dry-run)
#
# Pre-flight: the script aborts before issuing any traffic if either
#   GET $BANK_URL/actuator/health          does not return 200, or
#   GET $BANK_URL/api/v1/accounts/CUST-1001 does not return 200
# so an operator with a not-yet-started or wrong-profile app gets a clear
# error and a remediation hint instead of a stream of curl failures.

set -euo pipefail

# ----------------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------------

BANK_URL="${BANK_URL:-http://localhost:8080}"
RATE="${RATE:-5}"
DURATION_SECONDS="${DURATION_SECONDS:-120}"
DRY_RUN="${DRY_RUN:-false}"

# Positional alias: --dry-run flips DRY_RUN=true.
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=true ;;
        -h|--help)
            sed -n '2,30p' "$0"
            exit 0
            ;;
        *)
            echo "unknown argument: $arg (expected --dry-run, -h, or no args)" >&2
            exit 2
            ;;
    esac
done

# ----------------------------------------------------------------------------
# Pre-flight checks
# ----------------------------------------------------------------------------

health_status=$(curl -s -o /dev/null -w "%{http_code}" "${BANK_URL}/actuator/health" || true)
if [ "${health_status}" != "200" ]; then
    cat >&2 <<EOF
bank-core not reachable at ${BANK_URL} (got HTTP ${health_status:-no-response}).
Start it with:
    SPRING_PROFILES_ACTIVE=dev ./gradlew :bootstrap:bootRun
and re-run this script.
EOF
    exit 1
fi

seed_status=$(curl -s -o /dev/null -w "%{http_code}" "${BANK_URL}/api/v1/accounts/CUST-1001" || true)
if [ "${seed_status}" != "200" ]; then
    cat >&2 <<EOF
dev seed missing: GET ${BANK_URL}/api/v1/accounts/CUST-1001 returned HTTP ${seed_status:-no-response}.
Start the app with SPRING_PROFILES_ACTIVE=dev so the F09 seed runs:
    SPRING_PROFILES_ACTIVE=dev ./gradlew :bootstrap:bootRun
EOF
    exit 1
fi

# ----------------------------------------------------------------------------
# Counters + summary trap
# ----------------------------------------------------------------------------

total=0
s2xx=0
s4xx=0
s5xx=0
sother=0
start_epoch=$(date +%s)

summary() {
    local end_epoch
    end_epoch=$(date +%s)
    local runtime=$((end_epoch - start_epoch))
    local effective_rate="0"
    if [ "${runtime}" -gt 0 ] && [ "${total}" -gt 0 ]; then
        # bash integer math; one decimal is enough for a demo summary.
        effective_rate=$(awk "BEGIN { printf \"%.1f\", ${total} / ${runtime} }")
    fi
    echo
    echo "total=${total} 2xx=${s2xx} 4xx=${s4xx} 5xx=${s5xx} other=${sother} runtime=${runtime}s rate=${effective_rate}/s"
}

# Print summary on Ctrl-C and on normal exit; non-zero exit on SIGINT.
trap 'summary; exit 130' INT
trap 'summary' EXIT

# ----------------------------------------------------------------------------
# Loop
# ----------------------------------------------------------------------------

iterations=$((RATE * DURATION_SECONDS))
# 1/RATE seconds between issues. awk for fractional sleep that bash can't do.
sleep_per_iter=$(awk "BEGIN { printf \"%.4f\", 1 / ${RATE} }")

if [ "${DRY_RUN}" = "true" ]; then
    echo "[dry-run] would issue ${iterations} requests at ${RATE}/s over ${DURATION_SECONDS}s against ${BANK_URL}"
else
    echo "starting load: ${iterations} requests at ~${RATE}/s over ${DURATION_SECONDS}s against ${BANK_URL}"
fi

for (( i=1; i<=iterations; i++ )); do
    # Bucket pick: 0..69 -> success, 70..84 -> insufficient_funds, 85..99 -> same_account.
    bucket=$(( RANDOM % 100 ))
    if [ "${bucket}" -lt 70 ]; then
        category="success"
        src="CUST-1001"
        dst="CUST-1002"
        # 0.01 .. 1.00 — small enough to not exhaust CUST-1001's seed balance.
        cents=$(( (RANDOM % 100) + 1 ))
        amount=$(awk "BEGIN { printf \"%.2f\", ${cents} / 100 }")
    elif [ "${bucket}" -lt 85 ]; then
        category="insufficient_funds"
        src="CUST-1003"
        dst="CUST-1001"
        amount="9999.99"
    else
        category="same_account"
        src="CUST-1001"
        dst="CUST-1001"
        amount="1.00"
    fi

    body=$(printf '{"sourceAccountNumber":"%s","destinationAccountNumber":"%s","amount":%s}' \
        "${src}" "${dst}" "${amount}")

    if [ "${DRY_RUN}" = "true" ]; then
        printf '[dry-run] (%-18s) POST %s/api/v1/transfers  body=%s\n' \
            "${category}" "${BANK_URL}" "${body}"
        total=$((total + 1))
        # No sleep in dry-run: it's an offline preview, finish promptly.
        continue
    fi

    status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST \
        -H "Content-Type: application/json" \
        -d "${body}" \
        "${BANK_URL}/api/v1/transfers" || true)

    total=$((total + 1))
    case "${status}" in
        2*) s2xx=$((s2xx + 1)) ;;
        4*) s4xx=$((s4xx + 1)) ;;
        5*) s5xx=$((s5xx + 1)) ;;
        *)  sother=$((sother + 1)) ;;
    esac

    sleep "${sleep_per_iter}"
done

# summary fires via the EXIT trap.
