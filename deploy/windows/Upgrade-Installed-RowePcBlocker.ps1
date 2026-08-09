[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $ArchivePath,
    [switch] $EnableEnforcement,
    [switch] $EnableUserInterface,
    [switch] $EnablePortal,
    [AllowEmptyString()] [string] $PortalUrl
)

$ErrorActionPreference = 'Stop'
$configurationPath = Join-Path $env:ProgramData 'RowePcBlocker\appsettings.Local.json'
if (-not (Test-Path $configurationPath)) {
    throw 'No existing parental device blocker configuration was found.'
}

$configuration = Get-Content -Raw $configurationPath | ConvertFrom-Json
$blocker = $configuration.Blocker
$enforcementEnabled = if ($PSBoundParameters.ContainsKey('EnableEnforcement')) {
    [bool]$EnableEnforcement
} else {
    [bool]$blocker.EnforcementEnabled
}
$userInterfaceEnabled = if ($PSBoundParameters.ContainsKey('EnableUserInterface')) {
    [bool]$EnableUserInterface
} else {
    [bool]$blocker.UserInterfaceEnabled
}
$portalEnabled = if ($PSBoundParameters.ContainsKey('EnablePortal')) {
    [bool]$EnablePortal
} else {
    [bool]$blocker.PortalEnabled
}
$resolvedPortalUrl = if ($PSBoundParameters.ContainsKey('PortalUrl')) {
    $PortalUrl
} else {
    [string]$blocker.PortalUrl
}
$work = Join-Path $env:TEMP 'RowePcBlockerUpgrade'
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $work | Out-Null
Expand-Archive -Path (Resolve-Path $ArchivePath) -DestinationPath $work -Force
$installer = Get-ChildItem -Path $work -Filter 'Install-RowePcBlocker.ps1' -Recurse | Select-Object -First 1
if (-not $installer) {
    throw 'The archive does not contain Install-RowePcBlocker.ps1.'
}

& $installer.FullName `
    -HomeAssistantUrl ([string] $blocker.HomeAssistantUrl) `
    -DeviceId ([string] $blocker.DeviceId) `
    -DeviceApiKey ([string] $blocker.DeviceApiKey) `
    -ChildUsername ([string] $blocker.ChildLocalUsername) `
    -EnableEnforcement:$enforcementEnabled `
    -EnableUserInterface:$userInterfaceEnabled `
    -EnablePortal:$portalEnabled `
    -PortalUrl $resolvedPortalUrl
