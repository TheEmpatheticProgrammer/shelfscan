<#
.SYNOPSIS
  Starts the Shelfsight server with the settings in backend\.env.

.DESCRIPTION
  Reads KEY=VALUE lines from backend\.env (git-ignored; start from .env.example), sets them for this
  window only, and runs the server on port 8001. Nothing to retype each time.

.EXAMPLE
  cd D:\shelfscan\shelfscan\backend
  .\start-server.ps1
  .\start-server.ps1 -LocalOnly     # reachable only through the tunnel, not from other devices on Wi-Fi

  If Windows says running scripts is disabled, run it once as:
  powershell -ExecutionPolicy Bypass -File .\start-server.ps1
#>
param(
    [int]$Port = 8001,
    [switch]$LocalOnly
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$envFile = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path $envFile)) {
    Write-Error "No backend\.env yet. Copy .env.example to .env and fill it in."
}
foreach ($line in Get-Content $envFile) {
    $line = $line.Trim()
    if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) { continue }
    $name, $value = $line.Split("=", 2)
    $value = $value.Trim().Trim('"').Trim("'")
    if ($value) { [Environment]::SetEnvironmentVariable($name.Trim(), $value, "Process") }
}

if (-not $env:APP_TOKEN) {
    Write-Warning "APP_TOKEN is empty. Set it before exposing this server through a tunnel, or anyone who finds the address can use your model."
}
if (-not $env:REDIS_URL) {
    Write-Host "REDIS_URL not set: recommendations won't be cached (fine for a quick test)."
}

$python = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
if (-not (Test-Path $python)) {
    Write-Error "No .venv here. Create it once:  D:\Python313\python.exe -m venv .venv ; .venv\Scripts\python -m pip install -r requirements.txt"
}

$bindHost = if ($LocalOnly) { "127.0.0.1" } else { "0.0.0.0" }
Write-Host "Shelfsight server on http://${bindHost}:$Port  (model: $env:LLM_MODEL via $env:LLM_BASE_URL)"
& $python -m uvicorn app.main:app --host $bindHost --port $Port
