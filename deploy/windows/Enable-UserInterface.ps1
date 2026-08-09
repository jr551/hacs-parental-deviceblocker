[CmdletBinding()]
param([switch] $Disable)

$ErrorActionPreference = 'Stop'
$path = Join-Path $env:ProgramData 'RowePcBlocker\appsettings.Local.json'
$configuration = Get-Content -Raw $path | ConvertFrom-Json
$configuration.Blocker.UserInterfaceEnabled = -not [bool]$Disable
$configuration | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $path

Stop-ScheduledTask -TaskName 'Parental Device Activity' -ErrorAction SilentlyContinue
$child = $configuration.Blocker.ChildLocalUsername
$loggedOn = (Get-CimInstance Win32_ComputerSystem).UserName
if ($loggedOn -and $loggedOn.EndsWith("\$child", [StringComparison]::OrdinalIgnoreCase)) {
    Start-ScheduledTask -TaskName 'Parental Device Activity'
}

Write-Host ("Parental device save-work screen is now {0}." -f $(if ($Disable) { 'disabled' } else { 'enabled' }))
