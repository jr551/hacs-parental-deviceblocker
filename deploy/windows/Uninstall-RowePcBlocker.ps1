[CmdletBinding(SupportsShouldProcess)]
param([switch] $RemoveConfiguration)

$ErrorActionPreference = 'Stop'
if ($PSCmdlet.ShouldProcess('Parental Device Blocker', 'Stop service and remove scheduled task')) {
    Stop-Service -Name 'RowePcBlocker' -Force -ErrorAction SilentlyContinue
    & sc.exe delete RowePcBlocker | Out-Null
    Unregister-ScheduledTask -TaskName 'Parental Device Activity' -Confirm:$false -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName 'Parental Device Activity Watchdog' -Confirm:$false -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName 'Rowe PC Activity' -Confirm:$false -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName 'Rowe PC Activity Watchdog' -Confirm:$false -ErrorAction SilentlyContinue
    if ($RemoveConfiguration) {
        Remove-Item -Path (Join-Path $env:ProgramData 'RowePcBlocker') -Recurse -Force
    }
    Write-Host 'Parental Device Blocker was uninstalled.'
}
