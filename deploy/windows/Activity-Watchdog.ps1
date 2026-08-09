$ErrorActionPreference = 'SilentlyContinue'
$configurationPath = Join-Path $env:ProgramData 'RowePcBlocker\appsettings.Local.json'
if (-not (Test-Path $configurationPath)) { exit 0 }

# Service Control Manager recovery handles crashes, but not every clean stop or
# missed automatic start. This SYSTEM task is the independent recovery path.
$service = Get-Service -Name 'RowePcBlocker' -ErrorAction SilentlyContinue
if ($service -and $service.Status -ne 'Running') {
    Start-Service -Name 'RowePcBlocker' -ErrorAction SilentlyContinue
}

$configuration = Get-Content -Raw $configurationPath | ConvertFrom-Json
$child = [string] $configuration.Blocker.ChildLocalUsername
$loggedOn = (Get-CimInstance Win32_ComputerSystem).UserName
if (-not $loggedOn -or -not $loggedOn.EndsWith("\$child", [StringComparison]::OrdinalIgnoreCase)) {
    exit 0
}

$task = Get-ScheduledTask -TaskName 'Parental Device Activity'
if ($task.State -ne 'Running') {
    Start-ScheduledTask -TaskName 'Parental Device Activity'
}
