[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$mavenWrapper = Join-Path $repositoryRoot 'mvnw.cmd'

if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
  throw "Maven Wrapper not found: $mavenWrapper"
}

Push-Location $repositoryRoot
try {
  & $mavenWrapper -B -pl control-plane -am verify
  if ($LASTEXITCODE -ne 0) {
    throw "G2-11 Testcontainers Maven verification failed with exit code $LASTEXITCODE"
  }
} finally {
  Pop-Location
}
