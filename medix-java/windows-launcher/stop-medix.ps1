[CmdletBinding()]
param(
    [switch]$NoPause,
    [string]$RuntimeRoot,
    [switch]$TestOnly,
    [int]$ExpectedPid = 0
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root = if ($RuntimeRoot) { [IO.Path]::GetFullPath($RuntimeRoot) } else { Join-Path $PSScriptRoot 'state' }
$statePath = Join-Path $root 'medix-launcher-state.json'

function Remove-StaleState([string]$Reason) {
    Remove-Item -LiteralPath $statePath -Force -ErrorAction SilentlyContinue
    Write-Host "[WARN] $Reason Stale state was removed; no process was terminated." -ForegroundColor Yellow
}

if (-not (Test-Path -LiteralPath $statePath)) {
    Write-Host '[INFO] No MediX launcher state exists; nothing to stop.'
    exit 0
}

try { $state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json } catch {
    Remove-StaleState 'The state file is invalid.'
    exit 3
}

$pidValue = [int]$state.Pid
if ($pidValue -le 0 -or ($ExpectedPid -gt 0 -and $pidValue -ne $ExpectedPid)) {
    Remove-StaleState 'The saved PID is invalid or not the expected test PID.'
    exit 4
}
if ([IO.Path]::GetFullPath([string]$state.ProjectPath).TrimEnd('\') -ne $projectRoot.TrimEnd('\')) {
    Remove-StaleState 'The saved project path does not match this launcher.'
    exit 5
}

$process = Get-CimInstance Win32_Process -Filter "ProcessId=$pidValue" -ErrorAction SilentlyContinue
if (-not $process) {
    Remove-StaleState "PID $pidValue no longer exists."
    exit 0
}

$commandLine = [string]$process.CommandLine
$normalizedProject = $projectRoot.ToLowerInvariant()
$normalizedCommand = $commandLine.ToLowerInvariant()
$belongs = $normalizedCommand.Contains($normalizedProject) -and (
    $normalizedCommand.Contains('medix-java') -or
    $normalizedCommand.Contains('start-medix.ps1') -or
    $normalizedCommand.Contains('spring-boot:run')
)
if (-not $belongs) {
    Remove-StaleState "PID $pidValue does not belong to this MediX project."
    exit 6
}

$started = [DateTime]::Parse([string]$state.StartedAtUtc).ToUniversalTime()
$created = if ($process.CreationDate -is [DateTime]) {
    ([DateTime]$process.CreationDate).ToUniversalTime()
} else {
    [Management.ManagementDateTimeConverter]::ToDateTime([string]$process.CreationDate).ToUniversalTime()
}
if ([Math]::Abs(($created - $started).TotalMinutes) -gt 5) {
    Remove-StaleState "PID $pidValue creation time does not match the saved launch."
    exit 7
}

if ($TestOnly) {
    Write-Host "[INFO] Ownership validated for PID $pidValue; TestOnly made no changes."
    exit 0
}

& taskkill.exe /PID $pidValue /T /F | Out-Host
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] taskkill failed for validated PID $pidValue." -ForegroundColor Red; exit 8 }
Remove-Item -LiteralPath $statePath -Force -ErrorAction SilentlyContinue
Write-Host "[INFO] MediX process tree $pidValue stopped; state removed."
exit 0
