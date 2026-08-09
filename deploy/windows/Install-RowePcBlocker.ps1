[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $HomeAssistantUrl,
    [Parameter(Mandatory)] [ValidatePattern('^[a-z0-9_-]+$')] [string] $DeviceId,
    [Parameter(Mandatory)] [string] $DeviceApiKey,
    [Parameter(Mandatory)] [ValidatePattern('^[A-Za-z0-9_. -]+$')] [string] $ChildUsername,
    [switch] $EnableEnforcement,
    [switch] $EnableUserInterface,
    [switch] $EnablePortal,
    [AllowEmptyString()] [string] $PortalUrl = ''
)

$ErrorActionPreference = 'Stop'
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this installer from an Administrator PowerShell window.'
}

$ChildUsername = $ChildUsername.Trim()
$child = Get-LocalUser -Name $ChildUsername -ErrorAction SilentlyContinue
if (-not $child) {
    $interactiveUser = (Get-CimInstance Win32_ComputerSystem).UserName
    if ($interactiveUser) {
        $detectedUsername = ($interactiveUser -split '\\')[-1]
        $detectedChild = Get-LocalUser -Name $detectedUsername -ErrorAction SilentlyContinue
        if ($detectedChild) {
            Write-Host "Configured child '$ChildUsername' was not found; using signed-in account '$detectedUsername'."
            $ChildUsername = $detectedUsername
            $child = $detectedChild
        }
    }
}
if (-not $child) {
    throw "Child account '$ChildUsername' was not found, and no signed-in local account could be detected."
}
if ($child.PrincipalSource -notin @('Local', 'MicrosoftAccount')) {
    throw "'$ChildUsername' is not a supported local or Microsoft-linked Windows account."
}
$administrators = Get-LocalGroup -SID 'S-1-5-32-544'
$childIsAdministrator = Get-LocalGroupMember -Group $administrators -ErrorAction Stop |
    Where-Object { $_.SID -eq $child.SID }
if ($childIsAdministrator) {
    throw "'$ChildUsername' is an administrator. Remove administrator rights before installing parental enforcement."
}

$PortalUrl = $PortalUrl.Trim()
if ($PortalUrl) {
    $parsedPortalUrl = $null
    if (-not [Uri]::TryCreate($PortalUrl, [UriKind]::Absolute, [ref] $parsedPortalUrl) -or
        $parsedPortalUrl.Scheme -notin @('http', 'https')) {
        throw 'PortalUrl must be an absolute HTTP or HTTPS URL.'
    }
}
$portalEnabled = [bool]$EnablePortal -or [bool]$PortalUrl

$installRoot = Join-Path $env:ProgramData 'RowePcBlocker'
$serviceRoot = Join-Path $installRoot 'Service'
$activityRoot = Join-Path $installRoot 'Activity'
$sourceRoot = Join-Path $PSScriptRoot 'payload'

New-Item -ItemType Directory -Force -Path $serviceRoot, $activityRoot | Out-Null

$service = Get-Service -Name 'RowePcBlocker' -ErrorAction SilentlyContinue
if ($service) {
    Stop-Service -Name 'RowePcBlocker' -Force -ErrorAction SilentlyContinue
    & sc.exe delete RowePcBlocker | Out-Null
    for ($attempt = 0; $attempt -lt 20 -and (Get-Service -Name 'RowePcBlocker' -ErrorAction SilentlyContinue); $attempt++) {
        Start-Sleep -Milliseconds 250
    }
    if (Get-Service -Name 'RowePcBlocker' -ErrorAction SilentlyContinue) {
        throw 'The previous parental device blocker service could not be removed cleanly.'
    }
}
# Prevent the SYSTEM watchdog from immediately respawning the child UI while
# its executable is being replaced. The installer recreates both tasks below.
foreach ($legacyTask in @('Rowe PC Activity Watchdog', 'Rowe PC Activity')) {
    Unregister-ScheduledTask -TaskName $legacyTask -Confirm:$false -ErrorAction SilentlyContinue
}
Unregister-ScheduledTask -TaskName 'Parental Device Activity Watchdog' -Confirm:$false -ErrorAction SilentlyContinue
Stop-ScheduledTask -TaskName 'Parental Device Activity' -ErrorAction SilentlyContinue
Get-Process -Name 'RowePcActivity' -ErrorAction SilentlyContinue | Stop-Process -Force
for ($attempt = 0; $attempt -lt 40 -and (Get-Process -Name 'RowePcActivity' -ErrorAction SilentlyContinue); $attempt++) {
    Start-Sleep -Milliseconds 250
}
if (Get-Process -Name 'RowePcActivity' -ErrorAction SilentlyContinue) {
        throw 'The previous device activity process did not exit cleanly.'
}

Copy-Item -Path (Join-Path $sourceRoot 'service\*') -Destination $serviceRoot -Recurse -Force
Copy-Item -Path (Join-Path $sourceRoot 'activity\*') -Destination $activityRoot -Recurse -Force
Copy-Item -Path (Join-Path $PSScriptRoot 'Enable-Enforcement.ps1') -Destination $installRoot -Force
Copy-Item -Path (Join-Path $PSScriptRoot 'Enable-UserInterface.ps1') -Destination $installRoot -Force
Copy-Item -Path (Join-Path $PSScriptRoot 'Set-PortalUrl.ps1') -Destination $installRoot -Force
Copy-Item -Path (Join-Path $PSScriptRoot 'Emergency-Unblock.ps1') -Destination $installRoot -Force
Copy-Item -Path (Join-Path $PSScriptRoot 'Activity-Watchdog.ps1') -Destination $installRoot -Force
Copy-Item -Path (Join-Path $PSScriptRoot 'Uninstall-RowePcBlocker.ps1') -Destination $installRoot -Force

$configuration = @{
    Blocker = @{
        HomeAssistantUrl = $HomeAssistantUrl.TrimEnd('/')
        DeviceId = $DeviceId
        DeviceApiKey = $DeviceApiKey
        PollIntervalSeconds = 15
        EnforcementEnabled = [bool]$EnableEnforcement
        UserInterfaceEnabled = [bool]$EnableUserInterface
        PortalEnabled = $portalEnabled
        PortalUrl = $PortalUrl
        # The full-screen UI needs an interactive child session. UI-enabled
        # installs keep sign-in/unlock available and enforce through the protected,
        # repeatedly restarted kiosk instead of trapping the child at Windows login.
        KeepSessionForPortal = [bool]$EnableUserInterface
        GraceSeconds = 30
        ChildLocalUsername = $ChildUsername
    }
}
$configuration | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $installRoot 'appsettings.Local.json')

# Remove inherited write permissions. Children may execute the companion, but only
# SYSTEM and administrators may change binaries, scripts, or configuration.
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
$acl.SetOwner((New-Object Security.Principal.NTAccount('BUILTIN\Administrators')))
$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
    'NT AUTHORITY\SYSTEM', 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
    'BUILTIN\Administrators', 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
    'BUILTIN\Users', 'ReadAndExecute', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
Set-Acl -Path $installRoot -AclObject $acl

$serviceExe = Join-Path $serviceRoot 'RowePcBlocker.exe'
New-Service -Name 'RowePcBlocker' -BinaryPathName ('"{0}"' -f $serviceExe) `
    -DisplayName 'Parental Device Blocker' -StartupType Automatic `
    -Description 'Reads the private Home Assistant PC policy and enforces child account access.' | Out-Null
& sc.exe failure RowePcBlocker reset= 86400 actions= restart/5000/restart/15000/restart/60000 | Out-Null
& sc.exe failureflag RowePcBlocker 1 | Out-Null

$taskName = 'Parental Device Activity'
Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
$activityExe = Join-Path $activityRoot 'RowePcActivity.exe'
$action = New-ScheduledTaskAction -Execute $activityExe
$trigger = New-ScheduledTaskTrigger -AtLogOn -User ("{0}\{1}" -f $env:COMPUTERNAME, $ChildUsername)
$taskPrincipal = New-ScheduledTaskPrincipal -UserId ("{0}\{1}" -f $env:COMPUTERNAME, $ChildUsername) -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $taskPrincipal -Settings $settings -Description 'Reports foreground application changes to Home Assistant.' | Out-Null

$watchdogTaskName = 'Parental Device Activity Watchdog'
Unregister-ScheduledTask -TaskName $watchdogTaskName -Confirm:$false -ErrorAction SilentlyContinue
$watchdogPath = Join-Path $installRoot 'Activity-Watchdog.ps1'
$watchdogAction = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $watchdogPath)
$watchdogRecurringTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
    -RepetitionInterval (New-TimeSpan -Minutes 1) -RepetitionDuration (New-TimeSpan -Days 3650)
$watchdogStartupTrigger = New-ScheduledTaskTrigger -AtStartup
$watchdogPrincipal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$watchdogSettings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $watchdogTaskName -Action $watchdogAction `
    -Trigger @($watchdogStartupTrigger, $watchdogRecurringTrigger) `
    -Principal $watchdogPrincipal -Settings $watchdogSettings `
    -Description 'Restarts the parental device blocker service and child-facing grace UI if either is stopped.' | Out-Null

Start-Service -Name 'RowePcBlocker'
$loggedOn = (Get-CimInstance Win32_ComputerSystem).UserName
if ($loggedOn -and $loggedOn.EndsWith("\$ChildUsername", [StringComparison]::OrdinalIgnoreCase)) {
    Start-ScheduledTask -TaskName $taskName
}

Write-Host "Installed Parental Device Blocker for $ChildUsername on $env:COMPUTERNAME."
Write-Host ("Enforcement: {0}" -f $(if ($EnableEnforcement) { 'ENABLED' } else { 'MONITOR ONLY' }))
Write-Host ("Child portal: {0}" -f $(if ($portalEnabled) { 'ENABLED' } else { 'DISABLED' }))
