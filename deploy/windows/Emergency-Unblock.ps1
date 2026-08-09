[CmdletBinding()]
param([Parameter(Mandatory)] [ValidatePattern('^[A-Za-z0-9_. -]+$')] [string] $ChildUsername)

$ErrorActionPreference = 'Stop'
Stop-Service -Name 'RowePcBlocker' -Force -ErrorAction SilentlyContinue
& net.exe user $ChildUsername /active:yes
if ($LASTEXITCODE -ne 0) { throw 'Windows could not re-enable the child account.' }
Write-Host "$ChildUsername is enabled. The blocker service remains stopped until a parent starts it."
