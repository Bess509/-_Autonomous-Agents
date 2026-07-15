[CmdletBinding()]
param(
    [ValidateSet('Interactive','Offline','Live')][string]$Mode = 'Interactive',
    [ValidateSet('Auto','Maven','Jar')][string]$Runtime = 'Auto',
    [switch]$NonInteractive,
    [switch]$AllowRedisFallback,
    [switch]$AllowNluFallback,
    [switch]$NoPause,
    [ValidateSet('None','JavaMissing','JavaWrong','MavenMissing','PostgresDown','RedisDown','OllamaDown','OllamaModelMissing','PortBusy','JarMissing','JarUnique','JarMultiple','LiveEmptyKey','LiveSentinel','Success','HealthFail','ChildExit')]
    [string]$TestScenario = 'None',
    [string]$TestRuntimeRoot,
    [int]$HealthTimeoutSeconds = 90,
    [int]$TestPort = 18080,
    [switch]$TestDetach,
    [switch]$InternalMockChild,
    [int]$InternalMockPort = 18080,
    [string]$InternalProbePath
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$script:IsTest = $TestScenario -ne 'None'
$script:ExitCode = 1
$script:Child = $null
$script:StatePath = $null
$script:Sentinel = ('MEDIX_SENTINEL_' + '7F3B2D91')
$script:CancelRequested = $false
$script:CancelHandler = $null
$script:ProjectRoot = $null
$script:ListenPort = 0

function Write-Info([string]$Message) { Write-Host "[INFO] $Message" }
function Write-Warn([string]$Message) { Write-Host "[WARN] $Message" -ForegroundColor Yellow }
function Stop-Launch([string]$Message, [int]$Code) {
    Write-Host "[ERROR] $Message" -ForegroundColor Red
    $script:ExitCode = $Code
    throw [System.InvalidOperationException]::new("MEDIX_EXIT:$Code")
}
function Clear-ApiKey {
    Remove-Item Env:MEDIX_OPENAI_API_KEY -ErrorAction SilentlyContinue
}
function Test-Scenario([string]$Name) { return $script:IsTest -and $TestScenario -eq $Name }
function Quote-ProcessArgument([string]$Value) { return '"' + $Value.Replace('"','\"') + '"' }
function Stop-OwnedChildTree([System.Diagnostics.Process]$Process) {
    if ($Process) {
        $Process.Refresh()
        if (-not $Process.HasExited) { & taskkill.exe /PID $Process.Id /T /F 2>&1 | Out-Null }
    }
    # A cmd wrapper can exit during an early launcher failure while its Java
    # child is still binding the application port. Only clean that residual
    # when its command line is provably rooted in this project.
    if ($script:ListenPort -gt 0 -and $script:ProjectRoot) {
        $owner = Get-PortOwner $script:ListenPort
        if ($null -ne $owner) {
            $ownedProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$owner" -ErrorAction SilentlyContinue
            $commandLine = [string]$ownedProcess.CommandLine
            if ($ownedProcess -and $commandLine.ToLowerInvariant().Contains($script:ProjectRoot.ToLowerInvariant())) {
                & taskkill.exe /PID $owner /T /F 2>&1 | Out-Null
            }
        }
    }
}

function Invoke-InternalMockChild {
    $probe = [ordered]@{
        LiveLlm = $env:MEDIX_LIVE_LLM
        AgentEngine = $env:MEDIX_AGENT_ENGINE
        RedisEnabled = $env:MEDIX_REDIS_ENABLED
        NluEnabled = $env:MEDIX_NLU_ENABLED
        RerankerEnabled = $env:MEDIX_RERANKER_ENABLED
        VectorStoreEnabled = $env:MEDIX_VECTOR_STORE_ENABLED
        MinioEnabled = $env:MEDIX_MINIO_ENABLED
        ApiKeyPresent = -not [string]::IsNullOrEmpty($env:MEDIX_OPENAI_API_KEY)
    }
    if ($InternalProbePath) {
        $probe | ConvertTo-Json | Set-Content -LiteralPath $InternalProbePath -Encoding UTF8
    }
    if (Test-Scenario 'ChildExit') { exit 37 }
    $listener = New-Object System.Net.HttpListener
    $listener.Prefixes.Add("http://127.0.0.1:$InternalMockPort/")
    $listener.Start()
    try {
        while ($listener.IsListening) {
            $context = $listener.GetContext()
            $body = if (Test-Scenario 'HealthFail') { '{"status":"DOWN"}' } else { '{"status":"UP"}' }
            $status = if (Test-Scenario 'HealthFail') { 503 } else { 200 }
            $bytes = [Text.Encoding]::UTF8.GetBytes($body)
            $context.Response.StatusCode = $status
            $context.Response.ContentType = 'application/json'
            $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
            $context.Response.Close()
        }
    } finally { try { $listener.Close() } catch { } }
    exit 0
}

if ($InternalMockChild) { Invoke-InternalMockChild }

function Get-JavaMajor {
    if (Test-Scenario 'JavaMissing') { return $null }
    if (Test-Scenario 'JavaWrong') { return 17 }
    if ($script:IsTest) { return 21 }
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) { return $null }
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $java.Source
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $versionProcess = New-Object Diagnostics.Process
    $versionProcess.StartInfo = $startInfo
    [void]$versionProcess.Start()
    $text = $versionProcess.StandardOutput.ReadToEnd() + $versionProcess.StandardError.ReadToEnd()
    $versionProcess.WaitForExit()
    if ($text -match 'version\s+"(?<major>\d+)(?:\.|\")') { return [int]$Matches.major }
    return -1
}

function Test-Maven {
    if (Test-Scenario 'MavenMissing') { return $false }
    if ($script:IsTest) { return $true }
    return $null -ne (Get-Command mvn.cmd -ErrorAction SilentlyContinue)
}

function Test-Tcp([string]$HostName, [int]$Port, [string]$Dependency) {
    if ((Test-Scenario 'PostgresDown') -and $Dependency -eq 'PostgreSQL') { return $false }
    if ((Test-Scenario 'RedisDown') -and $Dependency -eq 'Redis') { return $false }
    if ($script:IsTest) { return $true }
    $client = New-Object Net.Sockets.TcpClient
    try {
        $result = $client.BeginConnect($HostName, $Port, $null, $null)
        return $result.AsyncWaitHandle.WaitOne(1500) -and $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

function Get-PortOwner([int]$Port) {
    if (Test-Scenario 'PortBusy') { return 424242 }
    if ($script:IsTest) { return $null }
    try {
        $row = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop | Select-Object -First 1
        if ($row) { return [int]$row.OwningProcess }
    } catch {
        $match = netstat -ano -p tcp | Select-String -Pattern (":$Port\s+.*LISTENING\s+(\d+)\s*$") | Select-Object -First 1
        if ($match -and $match.Matches[0].Groups.Count -gt 1) { return [int]$match.Matches[0].Groups[1].Value }
    }
    return $null
}

function Get-DbEndpoint([string]$Url) {
    if ($Url -match '^jdbc:postgresql://(?<host>\[[^\]]+\]|[^:/]+)(:(?<port>\d+))?/') {
        return @{ Host = $Matches.host.Trim('[',']'); Port = $(if ($Matches.port) { [int]$Matches.port } else { 5432 }) }
    }
    Stop-Launch 'MEDIX_DB_URL is not a supported PostgreSQL JDBC URL.' 23
}

function Get-UriEndpoint([string]$Url, [int]$DefaultPort) {
    try { $uri = [Uri]$Url } catch { Stop-Launch "Invalid URL: $Url" 24 }
    return @{ Host = $uri.Host; Port = $(if ($uri.IsDefaultPort) { $DefaultPort } else { $uri.Port }) }
}

function Confirm-Fallback([string]$Dependency, [switch]$Allowed) {
    if ($Allowed) { return $true }
    if ($NonInteractive -or $script:IsTest) { return $false }
    $answer = Read-Host "$Dependency is unavailable. Continue with local fallback? [y/N]"
    return $answer -match '^(y|yes)$'
}

function Test-Ollama([string]$BaseUrl, [string]$Model) {
    if (Test-Scenario 'OllamaDown') { return @{ Reachable=$false; Model=$false } }
    if (Test-Scenario 'OllamaModelMissing') { return @{ Reachable=$true; Model=$false } }
    if ($script:IsTest) { return @{ Reachable=$true; Model=$true } }
    try {
        $response = Invoke-RestMethod -Uri ($BaseUrl.TrimEnd('/') + '/api/tags') -Method Get -TimeoutSec 3
        $found = @($response.models | ForEach-Object { $_.name }) -contains $Model
        return @{ Reachable=$true; Model=$found }
    } catch { return @{ Reachable=$false; Model=$false } }
}

function Select-Mode {
    if ($Mode -ne 'Interactive') { return $Mode }
    if ($NonInteractive) { return 'Offline' }
    Write-Host '1. Offline demo (no API key)'
    Write-Host '2. DeepSeek live (hidden API key input)'
    Write-Host '3. Cancel'
    switch (Read-Host 'Select mode [1]') {
        '2' { return 'Live' }
        '3' { Stop-Launch 'Launch cancelled.' 2 }
        default { return 'Offline' }
    }
}

function Get-JarCandidates([string]$ProjectRoot) {
    if (Test-Scenario 'JarUnique') { return @((Join-Path $ProjectRoot 'target\medix-java-test.jar')) }
    if (Test-Scenario 'JarMultiple') { return @((Join-Path $ProjectRoot 'target\medix-java-a.jar'), (Join-Path $ProjectRoot 'target\medix-java-b.jar')) }
    if (Test-Scenario 'JarMissing') { return @() }
    return @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'target') -Filter 'medix-java-*.jar' -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike 'original-*' } | ForEach-Object FullName)
}

function Select-Runtime([string]$ProjectRoot) {
    $jars = @(Get-JarCandidates $ProjectRoot)
    if ($Runtime -eq 'Jar') {
        if ($jars.Count -eq 1) { return @{ Kind='Jar'; Jar=$jars[0] } }
        Write-Warn "JAR selection requires exactly one candidate; found $($jars.Count). Falling back to Maven."
        return @{ Kind='Maven'; Jar=$null }
    }
    if ($Runtime -eq 'Maven') { return @{ Kind='Maven'; Jar=$null } }
    if ($jars.Count -eq 1 -and -not $NonInteractive -and -not $script:IsTest) {
        $choice = Read-Host 'Use packaged JAR? Maven/current source is recommended. [y/N]'
        if ($choice -match '^(y|yes)$') { return @{ Kind='Jar'; Jar=$jars[0] } }
    }
    Write-Info 'Using Maven/current source (recommended).'
    return @{ Kind='Maven'; Jar=$null }
}

function Set-LaunchEnvironment([string]$SelectedMode) {
    $defaults = [ordered]@{
        MEDIX_DB_URL = 'jdbc:postgresql://localhost:5432/postgres'
        MEDIX_DB_USERNAME = 'postgres'
        MEDIX_REDIS_ENABLED = 'true'
        MEDIX_REDIS_HOST = 'localhost'
        MEDIX_REDIS_PORT = '6379'
        MEDIX_NLU_ENABLED = 'true'
        MEDIX_NLU_BASE_URL = 'http://localhost:11434'
        MEDIX_NLU_MODEL = 'qwen2.5:1.5b'
        MEDIX_AGENT_ENGINE = 'agentscope'
        MEDIX_LIVE_LLM = $(if ($SelectedMode -eq 'Live') { 'true' } else { 'false' })
        MEDIX_OPENAI_BASE_URL = 'https://api.deepseek.com'
        MEDIX_OPENAI_MODEL = 'deepseek-v4-flash'
        MEDIX_RERANKER_ENABLED = 'false'
        MEDIX_VECTOR_STORE_ENABLED = 'false'
        MEDIX_MINIO_ENABLED = 'false'
    }
    foreach ($entry in $defaults.GetEnumerator()) {
        $current = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        if ($script:IsTest -or [string]::IsNullOrEmpty($current)) { [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process') }
    }
    $env:MEDIX_LIVE_LLM = $(if ($SelectedMode -eq 'Live') { 'true' } else { 'false' })
    $env:MEDIX_RERANKER_ENABLED = 'false'
    $env:MEDIX_VECTOR_STORE_ENABLED = 'false'
    $env:MEDIX_MINIO_ENABLED = 'false'
}

function Start-ChildProcess([hashtable]$SelectedRuntime, [string]$ProjectRoot, [string]$LogPath, [string]$ProbePath, [int]$Port) {
    $errorLog = $LogPath + '.err'
    if ($script:IsTest) {
        $args = '-NoProfile -ExecutionPolicy Bypass -File {0} -InternalMockChild -InternalMockPort {1} -InternalProbePath {2} -TestScenario {3}' -f (Quote-ProcessArgument $PSCommandPath), $Port, (Quote-ProcessArgument $ProbePath), (Quote-ProcessArgument $TestScenario)
        $executable = (Get-Command powershell.exe -ErrorAction Stop).Source
    } elseif ($SelectedRuntime.Kind -eq 'Jar') {
        $executable = (Get-Command java.exe -ErrorAction Stop).Source
        $args = '-jar {0}' -f (Quote-ProcessArgument $SelectedRuntime.Jar)
    } else {
        $executable = (Get-Command mvn.cmd -ErrorAction Stop).Source
        $args = '-f {0} spring-boot:run' -f (Quote-ProcessArgument (Join-Path $ProjectRoot 'pom.xml'))
    }
    # Redirection is performed inside an owned cmd wrapper. This preserves a
    # reliable child exit code on Windows PowerShell 5.1 while keeping secrets
    # out of the command line (the API key is inherited only via environment).
    $command = '""{0}" {1} 1>>"{2}" 2>>"{3}""' -f $executable, $args, $LogPath, $errorLog
    return Start-Process -FilePath 'cmd.exe' -ArgumentList ('/d /s /c ' + $command) -WorkingDirectory $ProjectRoot -PassThru
}

function Wait-Health([int]$Port, [int]$TimeoutSeconds, [System.Diagnostics.Process]$Process, [string]$LogPath) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $Process.Refresh()
        if ($Process.HasExited) {
            $Process.WaitForExit()
            $childExitCode = $Process.ExitCode
            $tail = if (Test-Path -LiteralPath $LogPath) { (Get-Content -LiteralPath $LogPath -Tail 12 -ErrorAction SilentlyContinue) -join [Environment]::NewLine } else { '' }
            if ($tail) { Write-Warn "Child log tail (credentials are never emitted by launcher):`n$tail" }
            Stop-Launch "MediX process exited before becoming healthy (exit $childExitCode). Log: $LogPath" 41
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2
            $content = if ($response.Content -is [byte[]]) {
                [Text.Encoding]::UTF8.GetString($response.Content)
            } else {
                [string]$response.Content
            }
            if ($response.StatusCode -eq 200 -and $content -match '"status"\s*:\s*"UP"') { return $true }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    Stop-Launch "Health check timed out or remained unhealthy. Log: $LogPath" 42
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:ProjectRoot = $projectRoot
$runtimeRoot = if ($TestRuntimeRoot) { [IO.Path]::GetFullPath($TestRuntimeRoot) } else { Join-Path $PSScriptRoot 'state' }
$logsRoot = if ($TestRuntimeRoot) { Join-Path $runtimeRoot 'logs' } else { Join-Path $PSScriptRoot 'logs' }
$script:StatePath = Join-Path $runtimeRoot 'medix-launcher-state.json'
$probePath = Join-Path $runtimeRoot 'test-env-probe.json'
$selectedMode = $null
$secureKey = $null
$plainKey = $null
$keyBuffer = $null
$keepChild = $false

try {
    New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $logsRoot -Force | Out-Null
    $selectedMode = Select-Mode
    Set-LaunchEnvironment $selectedMode

    $javaMajor = Get-JavaMajor
    if ($null -eq $javaMajor) { Stop-Launch 'Java was not found. Install Java 21 and ensure java.exe is on PATH.' 10 }
    if ($javaMajor -ne 21) { Stop-Launch "Java 21 is required; detected major version $javaMajor." 11 }
    Write-Info 'Java 21 check passed.'

    $selectedRuntime = Select-Runtime $projectRoot
    if ($selectedRuntime.Kind -eq 'Maven' -and -not (Test-Maven)) { Stop-Launch 'Maven mode requires mvn.cmd on PATH.' 12 }
    Write-Info "Runtime selected: $($selectedRuntime.Kind)."

    $listenPort = if ($script:IsTest) { $TestPort } else { 8080 }
    $script:ListenPort = $listenPort
    $owner = Get-PortOwner $listenPort
    if ($null -ne $owner) { Stop-Launch "Port $listenPort is already listening (PID $owner). No process was terminated." 20 }

    $db = Get-DbEndpoint $env:MEDIX_DB_URL
    if (-not (Test-Tcp $db.Host $db.Port 'PostgreSQL')) { Stop-Launch "PostgreSQL is unreachable at $($db.Host):$($db.Port)." 21 }
    Write-Info "PostgreSQL reachable at $($db.Host):$($db.Port)."

    if (-not (Test-Tcp $env:MEDIX_REDIS_HOST ([int]$env:MEDIX_REDIS_PORT) 'Redis')) {
        Write-Warn "Redis is unreachable at $($env:MEDIX_REDIS_HOST):$($env:MEDIX_REDIS_PORT); conversation persistence will use local fallback."
        if (Confirm-Fallback 'Redis' -Allowed:$AllowRedisFallback) { $env:MEDIX_REDIS_ENABLED = 'false' } else { Stop-Launch 'Redis fallback was not approved.' 22 }
    }

    $nlu = Test-Ollama $env:MEDIX_NLU_BASE_URL $env:MEDIX_NLU_MODEL
    if (-not $nlu.Reachable -or -not $nlu.Model) {
        $endpoint = Get-UriEndpoint $env:MEDIX_NLU_BASE_URL 11434
        $reason = if (-not $nlu.Reachable) { "Ollama is unreachable at $($endpoint.Host):$($endpoint.Port)" } else { "Ollama model '$($env:MEDIX_NLU_MODEL)' is missing at $($env:MEDIX_NLU_BASE_URL)" }
        Write-Warn "$reason; intent routing will fall back to LeadAgent."
        if (Confirm-Fallback 'Ollama' -Allowed:$AllowNluFallback) { $env:MEDIX_NLU_ENABLED = 'false' } else { Stop-Launch 'Ollama fallback was not approved.' 25 }
    }

    if ($selectedMode -eq 'Live') {
        if (Test-Scenario 'LiveEmptyKey') { Stop-Launch 'No API key was provided; MediX was not started.' 30 }
        if (Test-Scenario 'LiveSentinel') {
            $secureKey = ConvertTo-SecureString $script:Sentinel -AsPlainText -Force
        } elseif ($script:IsTest) {
            $secureKey = ConvertTo-SecureString 'test-only-nonsecret' -AsPlainText -Force
        } else {
            $secureKey = Read-Host 'DeepSeek API Key (hidden)' -AsSecureString
        }
        $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
        try { $plainKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
        if ([string]::IsNullOrWhiteSpace($plainKey)) { Stop-Launch 'No API key was provided; MediX was not started.' 30 }
        $keyBuffer = $plainKey.ToCharArray()
        $env:MEDIX_OPENAI_API_KEY = $plainKey
    } else { Clear-ApiKey }

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $logPath = Join-Path $logsRoot "medix-$stamp.log"
    Write-Info "Starting MediX. Log: $logPath"
    $script:Child = Start-ChildProcess $selectedRuntime $projectRoot $logPath $probePath $listenPort
    $script:CancelHandler = [ConsoleCancelEventHandler]{
        param($sender, $eventArgs)
        $eventArgs.Cancel = $true
        $script:CancelRequested = $true
        Stop-OwnedChildTree $script:Child
    }
    [Console]::add_CancelKeyPress($script:CancelHandler)
    Clear-ApiKey
    $plainKey = $null
    if ($keyBuffer) { [Array]::Clear($keyBuffer, 0, $keyBuffer.Length); $keyBuffer = $null }

    $state = [ordered]@{
        Pid = $script:Child.Id
        StartedAtUtc = [DateTime]::UtcNow.ToString('o')
        ProjectPath = $projectRoot
        Runtime = $selectedRuntime.Kind
        Mode = $selectedMode
        TestMode = [bool]$script:IsTest
    }
    $state | ConvertTo-Json | Set-Content -LiteralPath $script:StatePath -Encoding UTF8

    Wait-Health $listenPort $HealthTimeoutSeconds $script:Child $logPath | Out-Null
    Write-Info "MediX is healthy: http://127.0.0.1:$listenPort/actuator/health"
    $script:ExitCode = 0

    if ($script:IsTest) {
        if ($TestDetach) { $keepChild = $true; Write-Info "TEST_DETACHED_PID=$($script:Child.Id)" }
        else { Stop-OwnedChildTree $script:Child; Remove-Item -LiteralPath $script:StatePath -Force -ErrorAction SilentlyContinue }
    } else {
        Write-Info 'Press Ctrl+C to stop MediX.'
        Wait-Process -Id $script:Child.Id
        $script:Child.Refresh()
        $script:ExitCode = $(if ($script:CancelRequested) { 130 } else { $script:Child.ExitCode })
    }
} catch {
    if ($_.Exception.Message -notlike 'MEDIX_EXIT:*') { Write-Host "[ERROR] $($_.Exception.Message)" -ForegroundColor Red; $script:ExitCode = 99 }
} finally {
    if ($script:CancelHandler) {
        [Console]::remove_CancelKeyPress($script:CancelHandler)
        $script:CancelHandler = $null
    }
    Clear-ApiKey
    $plainKey = $null
    if ($keyBuffer) { [Array]::Clear($keyBuffer, 0, $keyBuffer.Length) }
    $secureKey = $null
    if (-not $keepChild -and $script:Child -and -not $script:Child.HasExited -and $script:ExitCode -ne 0) {
        Stop-OwnedChildTree $script:Child
    }
    if (-not $keepChild -and $script:StatePath -and (Test-Path -LiteralPath $script:StatePath)) {
        if (-not $script:Child -or $script:Child.HasExited -or $script:ExitCode -ne 0) { Remove-Item -LiteralPath $script:StatePath -Force -ErrorAction SilentlyContinue }
    }
}
exit $script:ExitCode
