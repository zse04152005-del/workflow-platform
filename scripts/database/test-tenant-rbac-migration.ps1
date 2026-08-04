[CmdletBinding()]
param(
  [ValidateSet('G2-04', 'G2-05', 'G2-06', 'G2-07', 'G2-08')]
  [string]$Suite = 'G2-04',
  [switch]$KeepContainers
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$migrationDirectory = (Resolve-Path (
    Join-Path $repositoryRoot 'control-plane\src\main\resources\db\migration'
  )).Path
$initDirectory = (Resolve-Path (
    Join-Path $repositoryRoot 'deploy\compose\postgres\init'
  )).Path
$testDirectory = (Resolve-Path (Join-Path $repositoryRoot 'tests\database')).Path

$postgresImage = 'postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296'
$flywayImage = 'redgate/flyway:11.7.2-alpine@sha256:8c25848de56085076096f8600c2babf6254cfe4dfc84d868bdef28c729067720'
$suiteName = $Suite.ToLowerInvariant()
$suiteConfiguration = switch ($Suite) {
  'G2-04' {
    [pscustomobject]@{
      UpgradeFromVersion = $null
      TargetVersion = '1'
      UpgradeFixture = $null
      CatalogTest = 'g2-04-catalog-test.sql'
      ApplicationTest = 'g2-04-app-role-test.sql'
      OperationsTest = 'g2-04-platform-admin-test.sql'
      Result = 'G2-04 PostgreSQL integration PASS: clean Flyway migration, repeat migration, catalog, constraints, RLS, cross-tenant isolation, least privilege, and controlled platform-admin bypass.'
    }
  }
  'G2-05' {
    [pscustomobject]@{
      UpgradeFromVersion = '1'
      TargetVersion = '2'
      UpgradeFixture = 'g2-05-v1-upgrade-fixture.sql'
      CatalogTest = 'g2-05-catalog-test.sql'
      ApplicationTest = 'g2-05-app-role-test.sql'
      OperationsTest = 'g2-05-platform-admin-test.sql'
      Result = 'G2-05 PostgreSQL integration PASS: V1-to-V2 upgrade, repeat migration, definition/version/trigger invariants, partial uniqueness, soft deletion, secret material safety, RLS, and immutability.'
    }
  }
  'G2-06' {
    [pscustomobject]@{
      UpgradeFromVersion = '2'
      TargetVersion = '3'
      UpgradeFixture = 'g2-06-v2-upgrade-fixture.sql'
      CatalogTest = 'g2-06-catalog-test.sql'
      ApplicationTest = 'g2-06-app-role-test.sql'
      OperationsTest = 'g2-06-platform-admin-test.sql'
      Result = 'G2-06 PostgreSQL integration PASS: V2-to-V3 upgrade, repeat migration, runtime/Cron snapshot invariants, one-way task finalization, tenant-leading indexes, RLS, cross-tenant isolation, and least privilege.'
    }
  }
  'G2-07' {
    [pscustomobject]@{
      UpgradeFromVersion = '3'
      TargetVersion = '4'
      UpgradeFixture = 'g2-07-v3-upgrade-fixture.sql'
      CatalogTest = 'g2-07-catalog-test.sql'
      ApplicationTest = 'g2-07-app-role-test.sql'
      OperationsTest = 'g2-07-platform-admin-test.sql'
      Result = 'G2-07 PostgreSQL integration PASS: V3-to-V4 upgrade, repeat migration, Outbox claim/publish, consumer deduplication, idempotency, dead-letter binding, Worker fencing/heartbeat, forced RLS, cross-tenant isolation, and least privilege.'
    }
  }
  'G2-08' {
    [pscustomobject]@{
      UpgradeFromVersion = '4'
      TargetVersion = '5'
      UpgradeFixture = 'g2-08-v4-upgrade-fixture.sql'
      CatalogTest = 'g2-08-catalog-test.sql'
      ApplicationTest = 'g2-08-app-role-test.sql'
      OperationsTest = 'g2-08-platform-admin-test.sql'
      Result = 'G2-08 PostgreSQL integration PASS: V4-to-V5 upgrade, repeat migration, first human decision/transfer/timeout guards, append-only tenant/platform audit, non-overlapping monotonic quota windows, forced RLS, cross-tenant isolation, and least privilege.'
    }
  }
}
$suffix = ([guid]::NewGuid().ToString('N')).Substring(0, 12)
$resourcePrefix = "workflow-$suiteName-"
$networkName = "$resourcePrefix$suffix-net"
$postgresContainer = "$resourcePrefix$suffix-postgres"

function New-EphemeralPassword {
  $bytes = New-Object byte[] 32
  $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $random.GetBytes($bytes)
  } finally {
    $random.Dispose()
  }
  return [Convert]::ToBase64String($bytes)
}

$adminUser = 'workflow_admin'
$databaseName = 'workflow_platform'
$migrationUser = 'workflow_migrator'
$applicationUser = 'workflow_app'
$platformAdminRole = 'workflow_platform_admin'
$operationsUser = 'workflow_ops'
$ephemeralValues = [pscustomobject]@{
  Admin = New-EphemeralPassword
  Migration = New-EphemeralPassword
  Application = New-EphemeralPassword
  Operations = New-EphemeralPassword
  Keycloak = New-EphemeralPassword
}

function Invoke-Docker {
  param(
    [Parameter(Mandatory)]
    [string[]]$Arguments,
    [switch]$AllowFailure,
    [switch]$Quiet
  )

  $output = & docker @Arguments 2>&1
  $exitCode = $LASTEXITCODE
  if (-not $Quiet) {
    foreach ($line in $output) {
      Write-Host $line
    }
  }
  if ($exitCode -ne 0 -and -not $AllowFailure) {
    throw "docker $($Arguments -join ' ') failed with exit code $exitCode"
  }
  return [pscustomobject]@{
    ExitCode = $exitCode
    Output = @($output)
  }
}

function Assert-EphemeralResourceName {
  param([Parameter(Mandatory)][string]$Name)

  if (-not $Name.StartsWith($resourcePrefix, [StringComparison]::Ordinal)) {
    throw "Refusing to clean up unexpected Docker resource: $Name"
  }
}

function Invoke-FlywayMigrate {
  param([Parameter(Mandatory)][string]$TargetVersion)

  Invoke-Docker -Arguments @(
    'run', '--rm',
    '--network', $networkName,
    '--mount', "type=bind,source=$migrationDirectory,target=/flyway/sql,readonly",
    $flywayImage,
    "-url=jdbc:postgresql://postgres:5432/$databaseName",
    "-user=$migrationUser",
    "-password=$($ephemeralValues.Migration)",
    '-connectRetries=30',
    '-locations=filesystem:/flyway/sql',
    '-validateMigrationNaming=true',
    "-target=$TargetVersion",
    "-placeholders.workflowApplicationRole=$applicationUser",
    "-placeholders.workflowPlatformAdminRole=$platformAdminRole",
    'migrate'
  ) | Out-Null
}

function Invoke-PsqlFile {
  param(
    [Parameter(Mandatory)][string]$User,
    [Parameter(Mandatory)][string]$Password,
    [Parameter(Mandatory)][string]$File
  )

  Invoke-Docker -Arguments @(
    'exec', '--env', "PGPASSWORD=$Password", $postgresContainer,
    'psql', '--host', '127.0.0.1', '--username', $User,
    '--dbname', $databaseName, '--file', "/tests/$File"
  ) | Out-Null
}

try {
  Invoke-Docker -Arguments @('version', '--format', '{{.Server.Version}}') -Quiet | Out-Null
  Invoke-Docker -Arguments @('image', 'inspect', $postgresImage) -Quiet | Out-Null
  Invoke-Docker -Arguments @('image', 'inspect', $flywayImage) -Quiet | Out-Null

  Invoke-Docker -Arguments @('network', 'create', $networkName) -Quiet | Out-Null
  Invoke-Docker -Arguments @(
    'run', '--detach',
    '--name', $postgresContainer,
    '--network', $networkName,
    '--network-alias', 'postgres',
    '--env', "POSTGRES_USER=$adminUser",
    '--env', "POSTGRES_PASSWORD=$($ephemeralValues.Admin)",
    '--env', "POSTGRES_DB=$databaseName",
    '--env', "POSTGRES_MIGRATION_USER=$migrationUser",
    '--env', "POSTGRES_MIGRATION_PASSWORD=$($ephemeralValues.Migration)",
    '--env', "POSTGRES_APP_USER=$applicationUser",
    '--env', "POSTGRES_APP_PASSWORD=$($ephemeralValues.Application)",
    '--env', "POSTGRES_PLATFORM_ADMIN_ROLE=$platformAdminRole",
    '--env', 'KEYCLOAK_DB=keycloak',
    '--env', 'KEYCLOAK_DB_USER=keycloak',
    '--env', "KEYCLOAK_DB_PASSWORD=$($ephemeralValues.Keycloak)",
    '--mount', "type=bind,source=$initDirectory,target=/docker-entrypoint-initdb.d,readonly",
    '--mount', "type=bind,source=$testDirectory,target=/tests,readonly",
    $postgresImage
  ) -Quiet | Out-Null

  $ready = $false
  for ($attempt = 1; $attempt -le 60; $attempt += 1) {
    $probe = Invoke-Docker -Arguments @(
      'exec', $postgresContainer,
      'pg_isready', '--username', $adminUser, '--dbname', $databaseName
    ) -AllowFailure -Quiet
    if ($probe.ExitCode -eq 0) {
      $ready = $true
      break
    }
    Start-Sleep -Milliseconds 500
  }
  if (-not $ready) {
    Invoke-Docker -Arguments @('logs', $postgresContainer) -AllowFailure | Out-Null
    throw 'PostgreSQL test container did not become ready'
  }

  if ($suiteConfiguration.UpgradeFixture) {
    Invoke-FlywayMigrate -TargetVersion $suiteConfiguration.UpgradeFromVersion
    Invoke-PsqlFile -User $applicationUser -Password $ephemeralValues.Application -File $suiteConfiguration.UpgradeFixture
  }
  Invoke-FlywayMigrate -TargetVersion $suiteConfiguration.TargetVersion
  Invoke-FlywayMigrate -TargetVersion $suiteConfiguration.TargetVersion

  $createOperationsRole = @"
CREATE ROLE $operationsUser LOGIN PASSWORD '$($ephemeralValues.Operations)'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT $platformAdminRole TO $operationsUser;
GRANT CONNECT ON DATABASE $databaseName TO $operationsUser;
"@
  Invoke-Docker -Arguments @(
    'exec', '--env', "PGPASSWORD=$($ephemeralValues.Admin)", $postgresContainer,
    'psql', '--host', '127.0.0.1', '--username', $adminUser,
    '--dbname', $databaseName, '--set', 'ON_ERROR_STOP=1',
    '--command', $createOperationsRole
  ) | Out-Null

  Invoke-PsqlFile -User $applicationUser -Password $ephemeralValues.Application -File $suiteConfiguration.ApplicationTest
  Invoke-PsqlFile -User $migrationUser -Password $ephemeralValues.Migration -File $suiteConfiguration.CatalogTest
  Invoke-PsqlFile -User $operationsUser -Password $ephemeralValues.Operations -File $suiteConfiguration.OperationsTest

  Write-Host $suiteConfiguration.Result
} finally {
  if (-not $KeepContainers) {
    Assert-EphemeralResourceName -Name $postgresContainer
    Assert-EphemeralResourceName -Name $networkName
    Invoke-Docker -Arguments @('rm', '--force', $postgresContainer) -AllowFailure -Quiet | Out-Null
    Invoke-Docker -Arguments @('network', 'rm', $networkName) -AllowFailure -Quiet | Out-Null
  } else {
    Write-Host "Kept test resources: container=$postgresContainer network=$networkName"
  }
}
