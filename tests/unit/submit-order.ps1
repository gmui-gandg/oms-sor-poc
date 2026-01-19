param(
    [Parameter(Mandatory = $true)]
    [string]$JsonFile,
    [string]$Target = "http://localhost:8080/api/v1/orders",
    [string]$Channel = "REST",
    [string]$RequestId = $(New-Guid).Guid
)

if (-not (Test-Path $JsonFile)) {
    Write-Error "Input file not found: $JsonFile"
    exit 2
}

$body = Get-Content -Raw -Path $JsonFile
$headers = @{ 'X-OMS-Channel' = $Channel; 'X-Request-Id' = $RequestId }

Write-Host "Submitting order from file: $JsonFile to $Target" -ForegroundColor Cyan
try {
    $resp = Invoke-WebRequest -Uri $Target -Method Post -Body $body -ContentType 'application/json' -Headers $headers -UseBasicParsing -ErrorAction Stop
    Write-Host "HTTP/$($resp.StatusCode) - $($resp.StatusDescription)" -ForegroundColor Green
    if ($resp.Content) { 
        Write-Host $resp.Content
    }
    exit 0
}
catch {
    Write-Host "Request failed:" -ForegroundColor Red
    if ($_.Exception.Response) {
        $r = $_.Exception.Response
        $sr = New-Object System.IO.StreamReader($r.GetResponseStream())
        $bodyResp = $sr.ReadToEnd()
        Write-Host "HTTP/$($r.StatusCode) $($r.StatusDescription)" -ForegroundColor Yellow
        Write-Host $bodyResp
    }
    else {
        Write-Host $_.Exception.Message
    }
    exit 1
}
