# TradeLens engine on Windows (PowerShell). First run:  Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
# Usage:  .\run_windows.ps1            (installs into .venv on first run, then starts the API on port 8000)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Test-Path ".venv")) {
    py -3.11 -m venv .venv
    .\.venv\Scripts\python -m pip install --upgrade pip
    .\.venv\Scripts\pip install -r requirements.txt
}
if (-not $env:TRADELENS_API_TOKEN) {
    if (Test-Path ".token") { $env:TRADELENS_API_TOKEN = Get-Content ".token" }
    else {
        $env:TRADELENS_API_TOKEN = (.\.venv\Scripts\python -m tradelens token)
        Set-Content ".token" $env:TRADELENS_API_TOKEN
        Write-Host "Generated API token (saved in .token) - paste it into the app: $env:TRADELENS_API_TOKEN"
    }
}
.\.venv\Scripts\python -m tradelens serve --host 127.0.0.1 --port 8000
