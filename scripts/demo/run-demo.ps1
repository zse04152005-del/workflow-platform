[CmdletBinding()]
param([switch]$SkipSmoke)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$compose = Join-Path $root 'deploy/compose/start.ps1'
if (-not (Test-Path -LiteralPath $compose)) { throw "Compose start script not found: $compose" }
if ($SkipSmoke) { & $compose -SkipSmokeTest } else { & $compose }
Write-Output 'Demo stack is ready. Use the three examples in docs/demo/demo-script.md.'
