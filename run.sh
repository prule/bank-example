#!/usr/bin/env bash
# Convenience entry points for the bank-core build.
set -euo pipefail

cd "$(dirname "$0")"

usage() {
    cat <<'EOF'
Usage: ./run.sh <target>

Targets:
  build     ./gradlew build
  test      ./gradlew test
  run       ./gradlew :bootstrap:bootRun --args='--spring.profiles.active=dev'
  swagger   placeholder until F04 ships the OpenAPI pipeline
  h2        placeholder until the optional H2 TCP server is enabled in dev
EOF
}

case "${1:-}" in
    build)
        exec ./gradlew build
        ;;
    test)
        exec ./gradlew test
        ;;
    run)
        exec ./gradlew :bootstrap:bootRun --args='--spring.profiles.active=dev'
        ;;
    swagger)
        echo "swagger target is a placeholder — F04 wires the OpenAPI docs UI."
        exit 0
        ;;
    h2)
        echo "h2 target is a placeholder — enable bank-core.h2.tcp-server.enabled in application-dev.yaml to attach an external client."
        exit 0
        ;;
    -h|--help|"")
        usage
        ;;
    *)
        echo "Unknown target: $1" >&2
        usage
        exit 2
        ;;
esac
