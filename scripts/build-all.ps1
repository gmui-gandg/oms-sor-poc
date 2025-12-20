# Build script for all services
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host "Building OMS-SOR POC..." -ForegroundColor Green

# Clean and build all modules
Write-Host "`nCleaning and building Maven project..." -ForegroundColor Yellow
mvn clean install -DskipTests

if ($LASTEXITCODE -eq 0) {
    Write-Host "`nBuild successful!" -ForegroundColor Green
    
    # Build Docker images
    Write-Host "`nBuilding Docker images..." -ForegroundColor Yellow
    
    $services = @("oms-ingest", "oms-core")
    foreach ($service in $services) {
        Write-Host "Building $service..." -ForegroundColor Cyan
        Set-Location "services\$service"
        docker build -t "oms/${service}:latest" .
        Set-Location ..\..
    }
    
    Write-Host "`nAll Docker images built successfully!" -ForegroundColor Green
} else {
    Write-Host "`nBuild failed!" -ForegroundColor Red
    exit 1
}
