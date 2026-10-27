<#
.SYNOPSIS
  Stop the local PIDEA stack without deleting data. Never uses `down -v`; the pidea-db and pidea-uploads volumes are kept.
#>
# 'Continue': Windows PowerShell 5.1 turns native-command stderr (docker progress output) into terminating errors under
# 'Stop'; the docker exit code is checked explicitly below.
$ErrorActionPreference = 'Continue'
Set-Location (Split-Path -Parent $PSScriptRoot)

# Stop only the Vite dev server that was started from this repo's frontend folder.
$frontend = (Resolve-Path frontend).Path
Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
  Where-Object { $_.CommandLine -like '*vite*' -and $_.CommandLine -like "*$frontend*" } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; Write-Host "Stopped console (pid $($_.ProcessId))." }

docker compose stop
if ($LASTEXITCODE -ne 0) { Write-Host 'ERROR: docker compose stop failed.' -ForegroundColor Red; exit 1 }
Write-Host 'Stopped. Resume with .\scripts\dev-up.ps1 or "docker compose start". Data volumes were not removed.'
