#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ "$#" -lt 1 ]; then
  printf '%s\n' 'Usage: sh send-payment.sh MERCHANT_ACCOUNT_ID [AMOUNT] [CURRENCY] [PAYMENT_ID]' >&2
  exit 1
fi
merchant=$1 amount=${2:-125.50} currency=${3:-EUR} payment=${4:-payment-$(date +%s)-$$}
case "$merchant:$amount:$currency:$payment" in *[!A-Za-z0-9._:-]*) printf '%s\n' 'Arguments contain unsupported characters.' >&2; exit 1;; esac
event=event-$(date +%s)-$$
# Execute curl inside the app image; no host curl, jq, Python or Maven is needed.
docker compose -f "$ROOT/compose.yaml" -f "$ROOT/compose-full.yaml" exec -T app \
  curl --fail --silent --show-error -u ledger:ledger -H 'Content-Type: application/json' \
  -d "{\"properties\":{\"delivery_mode\":2,\"content_type\":\"application/json\",\"correlation_id\":\"$payment\"},\"routing_key\":\"payment.settled\",\"payload_encoding\":\"string\",\"payload\":\"{\\\"eventId\\\":\\\"$event\\\",\\\"paymentId\\\":\\\"$payment\\\",\\\"merchantAccountId\\\":\\\"$merchant\\\",\\\"amount\\\":\\\"$amount\\\",\\\"currency\\\":\\\"$currency\\\"}\"}" \
  http://rabbitmq:15672/api/exchanges/%2F/payments/publish
printf '\n'
