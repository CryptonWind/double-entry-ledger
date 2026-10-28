[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$MerchantAccountId,
    [string]$Amount = '125.50',
    [ValidateSet('EUR', 'USD', 'JPY')][string]$Currency = 'EUR',
    [string]$PaymentId = ([guid]::NewGuid().ToString()),
    [string]$EventId = ([guid]::NewGuid().ToString()),
    [string]$CorrelationId = ([guid]::NewGuid().ToString()),
    [string]$Traceparent = ('00-' + [guid]::NewGuid().ToString('N') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 16) + '-01')
)

$ErrorActionPreference = 'Stop'
$payload = @{
    eventId = $EventId
    paymentId = $PaymentId
    merchantAccountId = $MerchantAccountId
    amount = $Amount
    currency = $Currency
} | ConvertTo-Json -Compress
$body = @{
    properties = @{
        delivery_mode = 2; content_type = 'application/json'; message_id = $EventId
        correlation_id = $CorrelationId; headers = @{ traceparent = $Traceparent }
    }
    routing_key = 'payment.settled'
    payload = $payload
    payload_encoding = 'string'
} | ConvertTo-Json -Depth 5
$credentials = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('ledger:ledger'))
$result = Invoke-RestMethod -Method Post -Uri 'http://localhost:15672/api/exchanges/%2F/payments/publish' `
    -Headers @{ Authorization = "Basic $credentials" } -ContentType 'application/json' -Body $body
if (-not $result.routed) { throw 'Payment was not routed. Start the application to declare the queues first.' }
"Published paymentId=$PaymentId eventId=$EventId correlationId=$CorrelationId traceparent=$Traceparent"
