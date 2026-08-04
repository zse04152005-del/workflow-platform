[CmdletBinding()]
param([string]$BackupFile = 'workflow-platform-backup.dump')
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:PGHOST)) { throw 'PGHOST must be supplied by the environment' }
pg_dump --format=custom --no-owner --file $BackupFile $env:PGDATABASE
if (-not (Test-Path -LiteralPath $BackupFile)) { throw 'backup file was not created' }
Write-Output "Backup created: $BackupFile"
