#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
limit=${1:-100}
case "$limit" in ''|*[!0-9]*) printf '%s\n' 'Replay limit must be a positive integer.' >&2; exit 1;; esac
docker compose -f "$ROOT/compose.yaml" -f "$ROOT/compose-full.yaml" run --rm --no-deps app \
  --spring.main.web-application-type=none --spring.rabbitmq.listener.simple.auto-startup=false \
  --ledger.outbox.enabled=false --ledger.payments.replay=true --ledger.payments.replay-limit="$limit"
