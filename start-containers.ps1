[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI was not found. Install/start Rancher Desktop with the Moby engine and reopen the terminal.'
}

& docker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) {
    throw 'Docker is unavailable. Start Rancher Desktop with the Moby engine and wait until it is ready.'
}

& docker compose version
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Compose is unavailable. Check the Rancher Desktop installation.'
}

& docker compose --file "$PSScriptRoot\compose.yaml" up --detach --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) {
    throw 'Container startup failed. Inspect: docker compose logs mysql rabbitmq'
}

& docker compose --file "$PSScriptRoot\compose.yaml" ps
if ($LASTEXITCODE -ne 0) {
    throw 'Could not read container status.'
}
