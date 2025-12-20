# Start local infrastructure
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host "Starting OMS-SOR local infrastructure..." -ForegroundColor Green

Set-Location infra\docker

Write-Host "`nStarting Docker Compose services..." -ForegroundColor Yellow
docker-compose up -d

Write-Host "`nWaiting for services to be healthy..." -ForegroundColor Yellow
Start-Sleep -Seconds 10

Write-Host "`nChecking service status..." -ForegroundColor Cyan
docker-compose ps

Write-Host "`n" -NoNewline
Write-Host "Infrastructure is ready!" -ForegroundColor Green
Write-Host "`nService URLs:" -ForegroundColor Yellow
Write-Host "  PostgreSQL:  localhost:5432 (user: oms_user, password: changeme, db: oms)"
Write-Host "  Kafka:       localhost:9092"
Write-Host "  Redis:       localhost:6379"
Write-Host "  Keycloak:    http://localhost:8180 (admin/admin)"
Write-Host "  Prometheus:  http://localhost:9090"
Write-Host "  Grafana:     http://localhost:3000 (admin/admin)"

Set-Location ..\..
