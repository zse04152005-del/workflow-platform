[CmdletBinding()]
param(
  [string]$EnvFile = (Join-Path $PSScriptRoot '.env'),
  [switch]$SkipSmokeTest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$composeFile = Join-Path $PSScriptRoot 'compose.yml'
$smokeTest = Join-Path $PSScriptRoot 'smoke-test.ps1'
if (-not (Test-Path -LiteralPath $EnvFile)) {
  $EnvFile = Join-Path $PSScriptRoot '.env.example'
}

foreach ($requiredFile in @($composeFile, $EnvFile, $smokeTest)) {
  if (-not (Test-Path -LiteralPath $requiredFile)) {
    throw "Required file not found: $requiredFile"
  }
}

$composeBase = @('compose', '--env-file', $EnvFile, '-f', $composeFile)

function Invoke-Docker {
  param([Parameter(Mandatory)][string[]]$Arguments)

  & docker @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "Docker command failed (exit $LASTEXITCODE): docker $($Arguments -join ' ')"
  }
}

function Invoke-Compose {
  param([Parameter(Mandatory)][string[]]$Arguments)

  Invoke-Docker -Arguments @($composeBase + $Arguments)
}

Invoke-Compose -Arguments @('config', '--quiet')

# Compose --wait expects containers to remain running, so wait only for long-running services.
Invoke-Compose -Arguments @(
  'up', '-d', '--wait', 'postgres', 'kafka', 'redis', 'minio', 'keycloak'
)

# Init services are intentionally one-shot and idempotent; recreate them on every start.
Invoke-Compose -Arguments @(
  'up', '--no-deps', '--force-recreate', 'kafka-init', 'minio-init'
)

if (-not $SkipSmokeTest) {
  & $smokeTest -EnvFile $EnvFile
}
