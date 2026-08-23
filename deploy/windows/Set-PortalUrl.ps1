[CmdletBinding()]
param(
    [AllowEmptyString()] [string] $PortalUrl = '',
    [switch] $Disable
)

$ErrorActionPreference = 'Stop'
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this command from an Administrator PowerShell window.'
}

$PortalUrl = $PortalUrl.Trim()
if ($PortalUrl) {
    $parsedPortalUrl = $null
    if (-not [Uri]::TryCreate($PortalUrl, [UriKind]::Absolute, [ref] $parsedPortalUrl) -or
        $parsedPortalUrl.Scheme -notin @('http', 'https')) {
        throw 'PortalUrl must be an absolute HTTP or HTTPS URL.'
    }
}

$path = Join-Path $env:ProgramData 'RowePcBlocker\appsettings.Local.json'
$configuration = Get-Content -Raw $path | ConvertFrom-Json
$enabled = -not [bool]$Disable
$configuration.Blocker | Add-Member -NotePropertyName PortalEnabled -NotePropertyValue $enabled -Force
$configuration.Blocker | Add-Member -NotePropertyName PortalUrl -NotePropertyValue $PortalUrl -Force
# KeepSessionForPortal belongs to the kiosk UI, not to the portal: disabling
# the portal must never flip enforcement into account-disabling while the
# full-screen UI is still enabled.
$configuration.Blocker | Add-Member -NotePropertyName KeepSessionForPortal `
    -NotePropertyValue [bool]$configuration.Blocker.UserInterfaceEnabled -Force
$configuration | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $path

Restart-Service -Name 'RowePcBlocker'
Stop-ScheduledTask -TaskName 'Parental Device Activity' -ErrorAction SilentlyContinue
$child = [string]$configuration.Blocker.ChildLocalUsername
$loggedOn = (Get-CimInstance Win32_ComputerSystem).UserName
if ($loggedOn -and $loggedOn.EndsWith("\$child", [StringComparison]::OrdinalIgnoreCase)) {
    Start-ScheduledTask -TaskName 'Parental Device Activity'
}

Write-Host ("Points portal is now {0}." -f $(if ($enabled) { 'enabled' } else { 'disabled' }))
if ($enabled -and -not $PortalUrl) {
    Write-Host 'Using the built-in child portal from Home Assistant.'
}
