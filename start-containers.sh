#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
docker info >/dev/null
docker compose -f "$ROOT/compose.yaml" up -d --wait --wait-timeout 240 mysql rabbitmq
