<#
.SYNOPSIS
  Start the local PIDEA stack (Postgres + API + worker via Docker Compose) and the Vite console.

.DESCRIPTION
  * Verifies Docker / Compose / Node are available.
  * Creates .env from .env.example ONLY if .env does not exist (random local DB passwords; never overwrites).
  * If the DB host port is not configured and 5432 is taken, picks a free host port for this run only
    (passed to Compose through the process environment; .env is not modified). The check is best-effort:
    another process could still grab the port before Docker binds it, in which case Compose fails loudly.
  * Waits (with a timeout) for the API health endpoint, then starts the frontend dev server in the background.

  Stop with scripts\dev-down.ps1 (keeps the database volume).
#>
[CmdletBinding()]
param(
  [int]$TimeoutSeconds = 240,
  [switch]$SkipFrontend
)
# 'Continue': Windows PowerShell 5.1 turns native-command stderr (docker progress output) into errors under
# 'Stop'. Every native call below is checked through $LASTEXITCODE instead.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Fail([string]$msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }
function Test-PortFree([int]$port) {
  $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
  try { $l.Start(); return $true } catch { return $false } finally { try { $l.Stop() } catch {} }
}
function Get-FreePort {
  $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
  $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop(); return $p
}
function Get-EnvValue([string]$name) {
  $v = [Environment]::GetEnvironmentVariable($name)
  if ($v) { return $v }
  if (Test-Path .env) {
    $line = Select-String -Path .env -Pattern "^\s*$name\s*=\s*(.*)$" | Select-Object -Last 1
    if ($line) { return $line.Matches[0].Groups[1].Value.Trim() }
  }
  return ''
}

# 1. Tooling
foreach ($tool in 'docker', 'node', 'npm') {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Fail "'$tool' was not found on PATH." }
}
docker info *> $null
if ($LASTEXITCODE -ne 0) { Fail 'Docker is installed but the engine is not reachable. Start Docker Desktop and retry.' }
docker compose version *> $null
if ($LASTEXITCODE -ne 0) { Fail 'Docker Compose v2 ("docker compose") is required.' }
$nodeMajor = [int]((node --version).TrimStart('v').Split('.')[0])
if ($nodeMajor -lt 20) { Fail "Node 20+ is required (found $(node --version))." }

# 2. Environment file (never overwritten)
if (-not (Test-Path .env)) {
  Write-Host 'No .env found: creating one from .env.example with random local database passwords.'
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  function New-Secret { $b = New-Object byte[] 24; $rng.GetBytes($b); ([Convert]::ToBase64String($b) -replace '[^A-Za-z0-9]', 'x') }
  $text = Get-Content .env.example -Raw
  foreach ($k in 'POSTGRES_PASSWORD', 'PIDEA_DB_PASSWORD', 'PIDEA_WORKER_DB_PASSWORD') {
    $text = [regex]::Replace($text, "(?m)^$k=.*$", "$k=$(New-Secret)")
  }
  Set-Content -Path .env -Value $text -Encoding ascii
}
$missing = @('POSTGRES_PASSWORD', 'PIDEA_DB_PASSWORD', 'PIDEA_WORKER_DB_PASSWORD') | Where-Object {
  $v = Get-EnvValue $_; -not $v -or $v -like '*CHANGE_ME*'
}
if ($missing) { Fail "Set these in .env before starting: $($missing -join ', ')" }
if (-not (Get-EnvValue 'PIDEA_ADMIN_PASSWORD_HASH')) {
  Write-Host 'WARNING: PIDEA_ADMIN_PASSWORD_HASH is empty, so nobody can sign in to the API/console.' -ForegroundColor Yellow
  Write-Host '         Generate a hash with:  py -3.12 .\scripts\new_password_hash.py   and put it in .env' -ForegroundColor Yellow
}

# 3. Host ports (only when not explicitly configured)
if (-not (Get-EnvValue 'PIDEA_DB_PORT') -or (Get-EnvValue 'PIDEA_DB_PORT') -eq '5432') {
  # Reuse the port of an already-running db container so re-running this script does not recreate it.
  $published = (docker compose port db 5432 2>$null) -replace '^.*:', ''
  if ($published -match '^\d+$') {
    $env:PIDEA_DB_PORT = $published
  } elseif (-not (Test-PortFree 5432)) {
    $env:PIDEA_DB_PORT = [string](Get-FreePort)
    Write-Host "Host port 5432 is in use; using $($env:PIDEA_DB_PORT) for PostgreSQL this run."
  }
}
$apiPort = if (Get-EnvValue 'PIDEA_API_PORT') { Get-EnvValue 'PIDEA_API_PORT' } else { '8080' }

# 4. Backend stack
Write-Host 'Starting PostgreSQL, API and worker (first run builds images and can take several minutes)...'
docker compose up --build -d
if ($LASTEXITCODE -ne 0) { Fail 'docker compose up failed. See the output above; "docker compose logs" has details.' }

$healthUrl = "http://127.0.0.1:$apiPort/actuator/health"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$up = $false
while ((Get-Date) -lt $deadline) {
  try {
    $r = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 3
    if ($r.StatusCode -eq 200) { $up = $true; break }  # actuator answers 503 unless the status is UP
  } catch { }
  Start-Sleep -Seconds 3
}
if (-not $up) {
  docker compose ps
  Fail "API did not become healthy within $TimeoutSeconds s. Inspect: docker compose logs api db"
}
Write-Host "API healthy: $healthUrl" -ForegroundColor Green

# 5. Frontend
if (-not $SkipFrontend) {
  Push-Location frontend
  try {
    if (-not (Test-Path node_modules)) { npm ci; if ($LASTEXITCODE -ne 0) { Fail 'npm ci failed.' } }
    if (-not (Test-PortFree 5173)) {
      Write-Host 'Port 5173 is already in use (console may already be running).' -ForegroundColor Yellow
    } else {
      $env:PIDEA_API_TARGET = "http://127.0.0.1:$apiPort"
      $log = Join-Path $root 'frontend\dev.log'
      Start-Process -FilePath 'npm.cmd' -ArgumentList 'run', 'dev' -WindowStyle Hidden `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" | Out-Null
      $deadline = (Get-Date).AddSeconds(60); $ok = $false
      while ((Get-Date) -lt $deadline) {
        try { Invoke-WebRequest -Uri 'http://127.0.0.1:5173/' -UseBasicParsing -TimeoutSec 2 | Out-Null; $ok = $true; break }
        catch { Start-Sleep -Seconds 1 }
      }
      if (-not $ok) { Fail "Console did not respond on http://127.0.0.1:5173. See frontend\dev.log" }
    }
  } finally { Pop-Location }
}

Write-Host ''
Write-Host "API:        http://127.0.0.1:$apiPort   (Swagger: /swagger-ui/index.html)"
if (-not $SkipFrontend) { Write-Host 'Console:    http://127.0.0.1:5173' }
Write-Host 'Logs:       docker compose logs -f api worker      (console: frontend\dev.log)'
Write-Host 'Stop:       .\scripts\dev-down.ps1                 (keeps the database volume)'
