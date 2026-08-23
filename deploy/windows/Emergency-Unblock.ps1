[CmdletBinding()]
param([Parameter(Mandatory)] [ValidatePattern('^[A-Za-z0-9_. -]+$')] [string] $ChildUsername)

$ErrorActionPreference = 'Stop'

# The SYSTEM 'Parental Device Activity Watchdog' task restarts the blocker
# service within a minute whenever it finds it stopped, and the service itself
# is set to Automatic startup. Both must be disabled here, otherwise the PC
# silently re-locks while a parent believes the child is unblocked.
Stop-Service -Name 'RowePcBlocker' -Force -ErrorAction SilentlyContinue
Disable-ScheduledTask -TaskName 'Parental Device Activity Watchdog' -ErrorAction SilentlyContinue | Out-Null
Stop-ScheduledTask -TaskName 'Parental Device Activity Watchdog' -ErrorAction SilentlyContinue
Set-Service -Name 'RowePcBlocker' -StartupType Disabled

& net.exe user $ChildUsername /active:yes
if ($LASTEXITCODE -ne 0) { throw 'Windows could not re-enable the child account.' }
Write-Host "$ChildUsername is enabled. The blocker service and its watchdog are disabled until a parent re-enables them:"
Write-Host "  Set-Service RowePcBlocker -StartupType Automatic; Start-Service RowePcBlocker"
Write-Host "  Enable-ScheduledTask 'Parental Device Activity Watchdog'"
