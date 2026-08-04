[CmdletBinding()]
param(
  [string]$EnvFile = (Join-Path $PSScriptRoot '.env')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$composeFile = Join-Path $PSScriptRoot 'compose.yml'
if (-not (Test-Path -LiteralPath $EnvFile)) {
  $EnvFile = Join-Path $PSScriptRoot '.env.example'
}

if (-not (Test-Path -LiteralPath $composeFile)) {
  throw "Compose file not found: $composeFile"
}
if (-not (Test-Path -LiteralPath $EnvFile)) {
  throw "Environment file not found: $EnvFile"
}

$envValues = @{}
foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
  if ($line -match '^\s*([^#][^=]*)=(.*)$') {
    $envValues[$Matches[1].Trim()] = $Matches[2].Trim()
  }
}

$composeBase = @('compose', '--env-file', $EnvFile, '-f', $composeFile)

function Invoke-Docker {
  param([Parameter(Mandatory)][string[]]$Arguments)

  $output = & docker @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "Docker command failed (exit $LASTEXITCODE): docker $($Arguments -join ' ')"
  }
  return $output
}

function Invoke-Compose {
  param([Parameter(Mandatory)][string[]]$Arguments)

  return Invoke-Docker -Arguments @($composeBase + $Arguments)
}

Invoke-Compose -Arguments @('config', '--quiet') | Out-Null

$longRunningServices = @('postgres', 'kafka', 'redis', 'minio', 'keycloak')
foreach ($service in $longRunningServices) {
  $containerId = (Invoke-Compose -Arguments @('ps', '-q', $service) | Select-Object -First 1)
  if ([string]::IsNullOrWhiteSpace($containerId)) {
    throw "No container found for service: $service"
  }

  $health = (Invoke-Docker -Arguments @(
      'inspect',
      '--format',
      '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}',
      $containerId
    )).Trim()
  if ($health -ne 'healthy') {
    throw "Service $service is not healthy (state: $health)"
  }
}

$initServices = @('kafka-init', 'minio-init')
foreach ($service in $initServices) {
  $containerId = (Invoke-Compose -Arguments @('ps', '-a', '-q', $service) | Select-Object -First 1)
  if ([string]::IsNullOrWhiteSpace($containerId)) {
    throw "No completed init container found for service: $service"
  }

  $exitCode = (Invoke-Docker -Arguments @('inspect', '--format', '{{.State.ExitCode}}', $containerId)).Trim()
  if ($exitCode -ne '0') {
    throw "Init service $service failed (exit: $exitCode)"
  }
}

$postgresUser = if ($envValues.ContainsKey('POSTGRES_APP_USER')) { $envValues['POSTGRES_APP_USER'] } else { 'workflow_app' }
$postgresPassword = $envValues['POSTGRES_APP_PASSWORD']
$postgresDatabase = if ($envValues.ContainsKey('POSTGRES_DB')) { $envValues['POSTGRES_DB'] } else { 'workflow_platform' }
$postgresResult = Invoke-Compose -Arguments @(
  'exec', '-T', '--env', "PGPASSWORD=$postgresPassword", 'postgres', 'psql',
  '--host', '127.0.0.1', '--username', $postgresUser, '--dbname', $postgresDatabase,
  '--tuples-only', '--no-align', '--command', 'SELECT current_user, current_database()'
)
$expectedPostgresIdentity = "$postgresUser|$postgresDatabase"
if (($postgresResult -join "`n").Trim() -ne $expectedPostgresIdentity) {
  throw "Unexpected PostgreSQL identity: $($postgresResult -join ' ')"
}

$redisPassword = $envValues['REDIS_PASSWORD']
$redisResult = Invoke-Compose -Arguments @(
  'exec', '-T', '--env', "REDISCLI_AUTH=$redisPassword", 'redis', 'redis-cli', 'ping'
)
if (($redisResult -join "`n").Trim() -ne 'PONG') {
  throw "Unexpected Redis response: $($redisResult -join ' ')"
}

$topics = @(Invoke-Compose -Arguments @(
    'exec', '-T', 'kafka', '/opt/kafka/bin/kafka-topics.sh',
    '--bootstrap-server', 'localhost:29092', '--list'
  ))
foreach ($expectedTopic in @('workflow.task.ready', 'workflow.events', 'workflow.dlq')) {
  if ($topics -notcontains $expectedTopic) {
    throw "Kafka topic is missing: $expectedTopic"
  }
}

Invoke-Compose -Arguments @(
  'run', '--rm', '--no-deps', 'minio-init'
) | Out-Null

$minioApiPort = if ($envValues.ContainsKey('MINIO_API_PORT')) { $envValues['MINIO_API_PORT'] } else { '19000' }
$minioBucket = if ($envValues.ContainsKey('MINIO_BUCKET')) { $envValues['MINIO_BUCKET'] } else { 'workflow-payloads' }
Add-Type -AssemblyName System.Net.Http
$httpClient = [System.Net.Http.HttpClient]::new()
$minioRequest = $null
$minioResponse = $null
try {
  $minioRequest = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::Head,
    "http://127.0.0.1:$minioApiPort/$minioBucket"
  )
  $minioResponse = $httpClient.SendAsync($minioRequest).GetAwaiter().GetResult()
  $minioAnonymousStatus = [int]$minioResponse.StatusCode
  if ($minioAnonymousStatus -notin @(401, 403)) {
    throw "MinIO bucket must reject anonymous access (HTTP: $minioAnonymousStatus)"
  }
}
finally {
  if ($null -ne $minioRequest) { $minioRequest.Dispose() }
  if ($null -ne $minioResponse) { $minioResponse.Dispose() }
  $httpClient.Dispose()
}

$keycloakPort = if ($envValues.ContainsKey('KEYCLOAK_PORT')) { $envValues['KEYCLOAK_PORT'] } else { '8081' }
$keycloakBaseUrl = "http://127.0.0.1:$keycloakPort"
$oidcMetadataUrl = "$keycloakBaseUrl/realms/workflow-platform/.well-known/openid-configuration"
$oidcMetadata = Invoke-RestMethod -Uri $oidcMetadataUrl -Method Get -TimeoutSec 15
if ($oidcMetadata.issuer -notmatch '/realms/workflow-platform$') {
  throw "Unexpected Keycloak issuer: $($oidcMetadata.issuer)"
}

$adminTokenRequest = @{
  Uri = "$keycloakBaseUrl/realms/master/protocol/openid-connect/token"
  Method = 'Post'
  ContentType = 'application/x-www-form-urlencoded'
  Body = @{
    client_id = 'admin-cli'
    grant_type = 'password'
    username = $envValues['KEYCLOAK_ADMIN_USERNAME']
    password = $envValues['KEYCLOAK_ADMIN_PASSWORD']
  }
  TimeoutSec = 15
}
$adminTokenResponse = Invoke-RestMethod @adminTokenRequest
$keycloakClientRequest = @{
  Uri = "$keycloakBaseUrl/admin/realms/workflow-platform/clients?clientId=workflow-web-console"
  Method = 'Get'
  Headers = @{ Authorization = "Bearer $($adminTokenResponse.access_token)" }
  TimeoutSec = 15
}
$keycloakClients = @(
  Invoke-RestMethod @keycloakClientRequest
)
if ($keycloakClients.Count -ne 1) {
  throw "Expected exactly one Keycloak workflow-web-console client (found: $($keycloakClients.Count))"
}
$webConsoleClient = $keycloakClients[0]
if (
  -not $webConsoleClient.publicClient -or
  -not $webConsoleClient.standardFlowEnabled -or
  $webConsoleClient.directAccessGrantsEnabled -or
  $webConsoleClient.attributes.'pkce.code.challenge.method' -ne 'S256'
) {
  throw 'Keycloak workflow-web-console client does not enforce the expected public Authorization Code + PKCE S256 policy'
}

[pscustomobject]@{
  Status = 'PASS'
  HealthyServices = $longRunningServices.Count
  CompletedInitServices = $initServices.Count
  PostgreSQL = ($postgresResult -join "`n").Trim()
  Redis = ($redisResult -join "`n").Trim()
  KafkaTopics = ($topics | Sort-Object) -join ','
  MinioBucket = $minioBucket
  MinioAnonymousHttp = $minioAnonymousStatus
  KeycloakIssuer = $oidcMetadata.issuer
  KeycloakClient = 'workflow-web-console (Authorization Code + PKCE S256)'
} | Format-List
