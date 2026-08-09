[CmdletBinding()]
param([switch] $Disable)

$ErrorActionPreference = 'Stop'
$path = Join-Path $env:ProgramData 'RowePcBlocker\appsettings.Local.json'
$configuration = Get-Content -Raw $path | ConvertFrom-Json
$configuration.Blocker.EnforcementEnabled = -not [bool]$Disable
$configuration | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $path
Restart-Service -Name 'RowePcBlocker'
Write-Host ("Parental device enforcement is now {0}." -f $(if ($Disable) { 'disabled' } else { 'enabled' }))
