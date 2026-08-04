[CmdletBinding()]
param([Parameter(Mandatory)][string]$BackupFile, [Parameter(Mandatory)][string]$TargetDatabase)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $BackupFile)) { throw "Backup not found: $BackupFile" }
pg_restore --clean --if-exists --no-owner --dbname $TargetDatabase $BackupFile
psql --dbname $TargetDatabase --command "SELECT version_num FROM public.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"
Write-Output 'Restore verification completed; run application smoke tests before cutover.'
