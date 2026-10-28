#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compose() { docker compose -f "$ROOT/compose.yaml" -f "$ROOT/compose-full.yaml" "$@"; }
docker info >/dev/null
case "${1:-}" in
  --fresh)
    printf '%s\n' 'Fresh setup: deleting project containers and database/broker volumes.'
    compose down --volumes --remove-orphans
    ;;
  '') ;;
  *) printf '%s\n' 'Usage: sh run-all.sh [--fresh]' >&2; exit 1 ;;
esac
compose build app tests
# Image healthchecks validate HTTP health without requiring curl on the host.
compose up -d --wait --wait-timeout 300 app app-2
compose run --rm tests
printf '%s\n' 'Both application instances are healthy: http://localhost:8080 and http://localhost:8081. Tests passed.'
