[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$compose = @('compose', '--file', "$PSScriptRoot\compose.yaml", '--file', "$PSScriptRoot\compose-full.yaml")

& docker @compose build app tests
if ($LASTEXITCODE -ne 0) { throw 'Docker build failed.' }

& docker @compose up --detach --wait --wait-timeout 240 app app-2
if ($LASTEXITCODE -ne 0) { throw 'Application stack startup failed.' }

$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    try {
        $health = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 3
        if ($health.status -eq 'UP') { $ready = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $ready) { throw 'Application did not become healthy. Inspect docker compose logs app.' }

& docker @compose run --rm tests
if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed.' }

'Application is ready at http://localhost:8080. All tests passed.'
