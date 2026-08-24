[CmdletBinding(SupportsShouldProcess)]
param([switch] $RemoveConfiguration, [string] $ChildUsername)

$ErrorActionPreference = 'Stop'
if ($PSCmdlet.ShouldProcess('Parental Device Blocker', 'Stop service and remove scheduled task')) {
    # Stop kiosk and ensure child account is not left disabled.
    Get-Process -Name 'RowePcActivity' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Stop-Service -Name 'RowePcBlocker' -Force -ErrorAction SilentlyContinue
    & sc.exe delete RowePcBlocker | Out-Null
    Unregister-ScheduledTask -TaskName 'Parental Device Activity' -Confirm:$false -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName 'Parental Device Activity Watchdog' -Confirm:$false -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName 'Rowe PC Activity' -Confirm:$false -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName 'Rowe PC Activity Watchdog' -Confirm:$false -ErrorAction SilentlyContinue
    if (-not $ChildUsername) {
        $configPath = Join-Path $env:ProgramData 'RowePcBlocker\appsettings.Local.json'
        if (Test-Path $configPath) {
            try { $ChildUsername = (Get-Content -Raw $configPath | ConvertFrom-Json).Blocker.ChildLocalUsername } catch {}
        }
    }
    if ($ChildUsername) { & net.exe user $ChildUsername /active:yes 2>$null | Out-Null }
    if ($RemoveConfiguration) {
        Remove-Item -Path (Join-Path $env:ProgramData 'RowePcBlocker') -Recurse -Force
    }
    Write-Host 'Parental Device Blocker was uninstalled.'
}
