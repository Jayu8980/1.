# TradeLens engine on Windows (PowerShell).
# First time:  Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
# Run:         .\run_windows.ps1          (phone on the same Wi-Fi connects to http://<this PC's IP>:8000)
#              .\run_windows.ps1 -Local   (only this PC, e.g. when using a Cloudflare/Tailscale tunnel)
param([switch]$Local, [int]$Port = 8000)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path ".venv")) {
    $py = $null
    foreach ($v in "-3.12", "-3.11", "-3") { try { & py $v --version *> $null; if ($LASTEXITCODE -eq 0) { $py = $v; break } } catch {} }
    if (-not $py) { throw "Python 3.11+ not found. Install it from python.org and tick 'Add python.exe to PATH'." }
    & py $py -m venv .venv
    .\.venv\Scripts\python -m pip install --upgrade pip
    .\.venv\Scripts\pip install -r requirements.txt
}

if (-not $env:TRADELENS_API_TOKEN) {
    if (-not (Test-Path ".token")) { (.\.venv\Scripts\python -m tradelens token) | Set-Content ".token" }
    $env:TRADELENS_API_TOKEN = (Get-Content ".token").Trim()
}

$bind = if ($Local) { "127.0.0.1" } else { "0.0.0.0" }
if (-not $Local) {
    # Allow the phone to reach this port on PRIVATE networks only (needs admin once).
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not (Get-NetFirewallRule -DisplayName "TradeLens engine $Port" -ErrorAction SilentlyContinue)) {
        if ($isAdmin) {
            New-NetFirewallRule -DisplayName "TradeLens engine $Port" -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private | Out-Null
            Write-Host "Firewall: opened TCP $Port for private networks."
        } else {
            Write-Warning "Run this script once 'As Administrator' (or allow Python when Windows asks) so the phone can reach port $Port."
        }
    }
    $profiles = Get-NetConnectionProfile | Where-Object { $_.NetworkCategory -eq "Public" }
    if ($profiles) { Write-Warning "Your Wi-Fi is set to 'Public'. Change it to 'Private' (Settings > Network > Wi-Fi > properties) or the firewall will block the phone." }
    $ips = Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -match '^(192\.168|10\.|172\.(1[6-9]|2\d|3[01]))' -and $_.PrefixOrigin -ne "WellKnown" } | Select-Object -ExpandProperty IPAddress
}

Write-Host ""
Write-Host "================ TradeLens engine ================"
if ($Local) { Write-Host " URL   : http://127.0.0.1:$Port  (expose it with a tunnel for the phone)" }
else { foreach ($ip in $ips) { Write-Host " URL   : http://${ip}:$Port   <- enter this in the app (Settings > Engine)" } }
Write-Host " Token : $env:TRADELENS_API_TOKEN"
Write-Host " Check : open http://<URL>/health in the PHONE's browser – it must show {""ok"":true}"
Write-Host "=================================================="
.\.venv\Scripts\python -m tradelens serve --host $bind --port $Port
