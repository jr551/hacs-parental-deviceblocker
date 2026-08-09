<#
.SYNOPSIS
Replace only the activity agent (RowePcActivity.exe) on an installed PC.

.DESCRIPTION
A targeted alternative to a full upgrade for changes that live solely in the
activity/UI agent. It deliberately:

  * leaves the RowePcBlocker enforcement service running, so policy coverage
    never gaps;
  * refuses to run while the device is blocked, so a child's kiosk is never
    pulled off screen mid-block (while unblocked the activity app draws
    nothing, so the swap is invisible and does not disturb a running game);
  * disables the watchdog task during the swap, because it otherwise
    relaunches the exe mid-copy and locks the file;
  * reads the live configuration from %ProgramData%\RowePcBlocker\appsettings.Local.json.
    The Activity folder's own appsettings.json is only the shipped template
    (device "keithpc" with a placeholder key) — never copy that over an install;
  * keeps a timestamped rollback copy of the whole Activity folder and restores
    the old exe automatically if the copy fails.

Stage the new exe at %ProgramData%\rpa-new\RowePcActivity.exe first.
#>
$ErrorActionPreference = 'Stop'
$root = Join-Path $env:ProgramData 'RowePcBlocker'
$activity = Join-Path $root 'Activity'
$newExe = Join-Path $env:ProgramData 'rpa-new\RowePcActivity.exe'
$target = Join-Path $activity 'RowePcActivity.exe'

if (-not (Test-Path $newExe)) { Write-Output 'ABORT: new exe not staged'; exit 1 }
if (-not (Test-Path $target)) { Write-Output 'ABORT: no existing install'; exit 1 }

Write-Output ("service_before=" + (Get-Service -Name 'RowePcBlocker' -ErrorAction SilentlyContinue).Status)

$config = Get-Content (Join-Path $root 'appsettings.Local.json') -Raw | ConvertFrom-Json
$deviceId = $config.Blocker.DeviceId
$url = $config.Blocker.HomeAssistantUrl.TrimEnd('/')
try {
    $state = Invoke-RestMethod -Uri "$url/api/rowe_pc_blocker/$deviceId/state" `
        -Headers @{ 'X-Device-Blocker-Key' = $config.Blocker.DeviceApiKey } -TimeoutSec 10
    Write-Output ("blocked_now=" + $state.blocked + " requested=" + $state.block_requested)
    if ($state.blocked -or $state.block_requested) {
        Write-Output 'ABORT: device is blocked right now; not touching the kiosk'
        exit 2
    }
} catch {
    Write-Output 'ABORT: could not confirm unblocked state'
    exit 3
}

$backup = Join-Path $env:ProgramData ("RowePcBlocker-activity-rollback-" + (Get-Date -Format 'yyyyMMddTHHmmss'))
Copy-Item -Path $activity -Destination $backup -Recurse -Force
Write-Output ("backup=" + $backup)

Disable-ScheduledTask -TaskName 'Parental Device Activity Watchdog' -ErrorAction SilentlyContinue | Out-Null
Stop-ScheduledTask -TaskName 'Parental Device Activity' -ErrorAction SilentlyContinue
Get-Process -Name 'RowePcActivity' -ErrorAction SilentlyContinue | Stop-Process -Force
for ($i = 0; $i -lt 20 -and (Get-Process -Name 'RowePcActivity' -ErrorAction SilentlyContinue); $i++) {
    Start-Sleep -Milliseconds 500
}

try {
    Copy-Item -Path $newExe -Destination $target -Force
    Write-Output 'exe_replaced=true'
} catch {
    Copy-Item -Path (Join-Path $backup 'RowePcActivity.exe') -Destination $target -Force
    Write-Output ('ROLLED_BACK: ' + $_.Exception.Message)
    Enable-ScheduledTask -TaskName 'Parental Device Activity Watchdog' -ErrorAction SilentlyContinue | Out-Null
    Start-ScheduledTask -TaskName 'Parental Device Activity' -ErrorAction SilentlyContinue
    exit 4
}

Enable-ScheduledTask -TaskName 'Parental Device Activity Watchdog' -ErrorAction SilentlyContinue | Out-Null
Start-ScheduledTask -TaskName 'Parental Device Activity'
Start-Sleep -Seconds 6

Write-Output ("activity_running=" + [bool](Get-Process -Name 'RowePcActivity' -ErrorAction SilentlyContinue))
Write-Output ("activity_version=" + (Get-Item $target).VersionInfo.FileVersion)
Write-Output ("service_after=" + (Get-Service -Name 'RowePcBlocker').Status)
$windows = Get-Process -Name 'RowePcActivity' -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -ne '' } | Select-Object -ExpandProperty MainWindowTitle
Write-Output ("visible_windows=" + $(if ($windows) { $windows -join ',' } else { 'none' }))
Remove-Item (Split-Path $newExe) -Recurse -Force -ErrorAction SilentlyContinue
Write-Output 'DONE'
