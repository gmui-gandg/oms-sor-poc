param(
    [switch]$NoInfra
)

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Definition
Push-Location $ScriptRoot\..\

if (-not $NoInfra) {
    Write-Host "Starting infra via scripts/start-infra.ps1..."
    if (Test-Path .\scripts\start-infra.ps1) {
        & .\scripts\start-infra.ps1
    }
    else {
        Write-Host "scripts/start-infra.ps1 not found; attempting docker compose..."
        docker compose -f infra/docker/docker-compose.yml up -d
    }
}

Write-Host "Building and running services/oms-ingest (skip tests)..."
cd services\oms-ingest
mvn -DskipTests spring-boot:run

Pop-Location
