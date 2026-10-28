[CmdletBinding()]
param([ValidateRange(1, 10000)][int]$Limit = 100)
$ErrorActionPreference = 'Stop'
& docker compose --file "$PSScriptRoot\compose.yaml" --file "$PSScriptRoot\compose-full.yaml" run --rm --no-deps app `
    --spring.main.web-application-type=none --spring.rabbitmq.listener.simple.auto-startup=false `
    --ledger.outbox.enabled=false --ledger.payments.replay=true "--ledger.payments.replay-limit=$Limit"
if ($LASTEXITCODE -ne 0) { throw 'Parked payment replay failed; inspect the logs before retrying.' }
