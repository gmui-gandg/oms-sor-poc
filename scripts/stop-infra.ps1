# Stop local infrastructure
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host "Stopping OMS-SOR local infrastructure..." -ForegroundColor Green

Set-Location infra\docker

docker-compose down

Write-Host "`nInfrastructure stopped." -ForegroundColor Green
Write-Host "To remove volumes, run: docker-compose down -v" -ForegroundColor Yellow

Set-Location ..\..
