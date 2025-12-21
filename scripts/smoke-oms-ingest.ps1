param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$OrdersPath = "/api/v1/orders",
    [string]$HealthPath = "/actuator/health",
    [switch]$StartService,
    [switch]$StopService,
    [string]$JarPath
)

$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    Write-Host "FAIL: $Message" -ForegroundColor Red
    exit 1
}

function Assert([bool]$Condition, [string]$Message) {
    if (-not $Condition) { Fail $Message }
}

function Read-ResponseBody([object]$WebResponse) {
    try {
        $stream = $WebResponse.GetResponseStream()
        if (-not $stream) { return $null }
        $sr = New-Object System.IO.StreamReader($stream)
        $content = $sr.ReadToEnd()
        $sr.Close()
        return $content
    }
    catch {
        return $null
    }
}

function Normalize-Content([object]$Content) {
    if ($null -eq $Content) { return $null }
    if ($Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Content)
    }
    return [string]$Content
}

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [hashtable]$Headers,
        [string]$ContentType,
        [string]$Body
    )

    try {
        $invokeParams = @{
            Method          = $Method
            Uri             = $Uri
            Headers         = $Headers
            UseBasicParsing = $true
        }
        if ($ContentType -and -not [string]::IsNullOrWhiteSpace($ContentType)) {
            $invokeParams.ContentType = $ContentType
        }
        if ($Body -and -not [string]::IsNullOrWhiteSpace($Body)) {
            $invokeParams.Body = $Body
        }

        $resp = Invoke-WebRequest @invokeParams
        $status = [int]$resp.StatusCode
        $content = Normalize-Content $resp.Content
        $json = $null
        if ($content -and $content.Trim().StartsWith('{')) {
            try { $json = $content | ConvertFrom-Json } catch {}
        }
        return [pscustomobject]@{ StatusCode = $status; Content = $content; Json = $json }
    }
    catch {
        $webResp = $_.Exception.Response
        if (-not $webResp) { throw }

        $status = $null
        try { $status = [int]$webResp.StatusCode } catch {
            try { $status = [int]$webResp.StatusCode.value__ } catch { $status = 0 }
        }

        $content = Read-ResponseBody $webResp
        $json = $null
        if ($content -and $content.Trim().StartsWith('{')) {
            try { $json = $content | ConvertFrom-Json } catch {}
        }

        return [pscustomobject]@{ StatusCode = $status; Content = $content; Json = $json }
    }
}

$healthUrl = ($BaseUrl.TrimEnd('/')) + $HealthPath
$ordersUrl = ($BaseUrl.TrimEnd('/')) + $OrdersPath

Write-Host "== oms-ingest smoke test ==" -ForegroundColor Cyan
Write-Host "BaseUrl: $BaseUrl"

$startedByScript = $false
$servicePid = $null

if ($StartService) {
    Write-Host "Starting oms-ingest..." -ForegroundColor Gray

    if ([string]::IsNullOrWhiteSpace($JarPath)) {
        $defaultTarget = Join-Path $PSScriptRoot "..\\services\\oms-ingest\\target"
        $jar = Get-ChildItem $defaultTarget -Filter "oms-ingest-*.jar" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
        if (-not $jar) {
            Fail "Jar not found under $defaultTarget. Build first: mvn -pl services/oms-ingest -am -DskipTests clean package"
        }
        $JarPath = $jar.FullName
    }

    if (-not (Test-Path $JarPath)) {
        Fail "JarPath does not exist: $JarPath"
    }

    # Stop anything already bound to port 8080 to avoid bind errors and Windows jar locks.
    try {
        $listeners = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
        if ($listeners) {
            $pids = $listeners | Select-Object -ExpandProperty OwningProcess -Unique
            foreach ($pid in $pids) {
                Write-Host "Stopping PID $pid listening on 8080" -ForegroundColor DarkGray
                Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
            }
        }
    }
    catch {
        # Ignore if Get-NetTCPConnection is unavailable / permission issues.
    }

    $jarDir = Split-Path -Parent $JarPath
    $stdout = Join-Path $jarDir "oms-ingest.smoke.stdout.log"
    $stderr = Join-Path $jarDir "oms-ingest.smoke.stderr.log"

    $proc = Start-Process -FilePath "java" -ArgumentList @("-jar", $JarPath) -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    $startedByScript = $true
    $servicePid = $proc.Id

    Write-Host "Started PID $servicePid" -ForegroundColor DarkGray
}

try {

    # 1) Health
    Write-Host "[1/4] Health check..." -ForegroundColor Gray
    $health = Invoke-JsonRequest -Method GET -Uri $healthUrl -Headers @{} -ContentType $null -Body $null
    Assert ($health.StatusCode -eq 200) "Health endpoint returned HTTP $($health.StatusCode). Is the service running?"
    if ($health.Json -and $health.Json.status) {
        Assert ($health.Json.status -eq 'UP') "Health JSON status is '$($health.Json.status)', expected 'UP'."
    }
    Write-Host "OK: health is UP" -ForegroundColor Green

    # 2) Create + replay + cross-channel create
    Write-Host "[2/4] Idempotency + created flag..." -ForegroundColor Gray
    $clientOrderId = "CO-SMOKE-" + (Get-Random -Minimum 100000 -Maximum 999999)
    $accountId = "A123"

    $orderBody = @{
        accountId     = $accountId
        clientOrderId = $clientOrderId
        symbol        = 'AAPL'
        side          = 'BUY'
        orderType     = 'LIMIT'
        quantity      = 1.0
        limitPrice    = 123.45
        timeInForce   = 'DAY'
    } | ConvertTo-Json

    $h1 = @{ 'X-OMS-Channel' = 'REST'; 'X-Request-Id' = 'smoke-req-1' }
    $r1 = Invoke-JsonRequest -Method POST -Uri $ordersUrl -Headers $h1 -ContentType 'application/json' -Body $orderBody
    Assert ($r1.StatusCode -eq 201) "Create should return 201, got $($r1.StatusCode). Body: $($r1.Content)"
    Assert ($r1.Json -ne $null) "Create response was not JSON. Body: $($r1.Content)"
    Assert ($r1.Json.created -eq $true) "Create response should have created=true. Body: $($r1.Content)"
    Assert (-not [string]::IsNullOrWhiteSpace([string]$r1.Json.orderId)) "Create response should include orderId. Body: $($r1.Content)"
    $firstOrderId = [string]$r1.Json.orderId

    $h2 = @{ 'X-OMS-Channel' = 'REST'; 'X-Request-Id' = 'smoke-req-2' }
    $r2 = Invoke-JsonRequest -Method POST -Uri $ordersUrl -Headers $h2 -ContentType 'application/json' -Body $orderBody
    Assert ($r2.StatusCode -eq 200) "Replay should return 200, got $($r2.StatusCode). Body: $($r2.Content)"
    Assert ($r2.Json -ne $null) "Replay response was not JSON. Body: $($r2.Content)"
    Assert ($r2.Json.created -eq $false) "Replay response should have created=false. Body: $($r2.Content)"
    Assert (([string]$r2.Json.orderId) -eq $firstOrderId) "Replay should return same orderId. Expected $firstOrderId, got $($r2.Json.orderId)."

    $h3 = @{ 'X-OMS-Channel' = 'FIX'; 'X-Request-Id' = 'smoke-req-3' }
    $r3 = Invoke-JsonRequest -Method POST -Uri $ordersUrl -Headers $h3 -ContentType 'application/json' -Body $orderBody
    Assert ($r3.StatusCode -eq 201) "Cross-channel create should return 201, got $($r3.StatusCode). Body: $($r3.Content)"
    Assert ($r3.Json -ne $null) "Cross-channel response was not JSON. Body: $($r3.Content)"
    Assert ($r3.Json.created -eq $true) "Cross-channel response should have created=true. Body: $($r3.Content)"
    Assert (([string]$r3.Json.orderId) -ne $firstOrderId) "Cross-channel create should produce a different orderId."
    Write-Host "OK: created flag + idempotency verified" -ForegroundColor Green

    # 3) Validation error returns OrderResponse (created=false)
    Write-Host "[3/4] Validation error shape..." -ForegroundColor Gray
    $badBody = @{
        accountId     = $accountId
        clientOrderId = ("CO-SMOKE-BAD-" + (Get-Random -Minimum 100000 -Maximum 999999))
        # symbol intentionally omitted
        side          = 'BUY'
        orderType     = 'LIMIT'
        quantity      = 1.0
        limitPrice    = 123.45
        timeInForce   = 'DAY'
    } | ConvertTo-Json

    $r4 = Invoke-JsonRequest -Method POST -Uri $ordersUrl -Headers @{ 'X-OMS-Channel' = 'REST'; 'X-Request-Id' = 'smoke-req-4' } -ContentType 'application/json' -Body $badBody
    Assert ($r4.StatusCode -eq 400) "Validation error should return 400, got $($r4.StatusCode). Body: $($r4.Content)"
    Assert ($r4.Json -ne $null) "Validation error response was not JSON. Body: $($r4.Content)"
    Assert ($r4.Json.created -eq $false) "Validation error should have created=false. Body: $($r4.Content)"
    Assert ($r4.Json.message -match '(?i)symbol') "Validation error message should mention 'symbol'. Body: $($r4.Content)"
    Write-Host "OK: validation errors return created=false" -ForegroundColor Green

    # 4) Malformed JSON returns OrderResponse (created=false)
    Write-Host "[4/4] Malformed JSON error shape..." -ForegroundColor Gray
    $r5 = Invoke-JsonRequest -Method POST -Uri $ordersUrl -Headers @{ 'X-OMS-Channel' = 'REST'; 'X-Request-Id' = 'smoke-req-5' } -ContentType 'application/json' -Body '{not json'
    Assert ($r5.StatusCode -eq 400) "Malformed JSON should return 400, got $($r5.StatusCode). Body: $($r5.Content)"
    Assert ($r5.Json -ne $null) "Malformed JSON response was not JSON. Body: $($r5.Content)"
    Assert ($r5.Json.created -eq $false) "Malformed JSON should have created=false. Body: $($r5.Content)"
    Assert ($r5.Json.message -match '(?i)malformed') "Malformed JSON message should mention 'Malformed'. Body: $($r5.Content)"
    Write-Host "OK: malformed JSON returns created=false" -ForegroundColor Green

    Write-Host "PASS: all smoke checks passed" -ForegroundColor Green

}
finally {
    if ($StopService) {
        if ($startedByScript -and $servicePid) {
            Write-Host "Stopping oms-ingest PID $servicePid..." -ForegroundColor Gray
            Stop-Process -Id $servicePid -Force -ErrorAction SilentlyContinue
        }
        elseif ($StartService) {
            # Defensive: if we attempted to start but didn't capture PID.
            try {
                $listeners = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
                if ($listeners) {
                    $pids = $listeners | Select-Object -ExpandProperty OwningProcess -Unique
                    foreach ($pid in $pids) {
                        Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
                    }
                }
            }
            catch {}
        }
    }
}
