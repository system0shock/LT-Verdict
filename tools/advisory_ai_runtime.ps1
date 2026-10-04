[CmdletBinding()]
param(
    [ValidateSet("Live", "Preflight")]
    [string]$Mode = "Live",
    [Parameter(Mandatory)] [string]$EvidencePath,
    [Parameter(Mandatory)] [string]$OutputPath,
    [Parameter(Mandatory)] [string]$ResultPath,
    [Parameter(Mandatory)] [string]$CancelPath,
    [string]$CredentialEnvFile,
    [string]$QwenPackageRoot,
    [ValidateSet("", "wrapped-then-valid", "wrapped-twice", "deep-violation")]
    [string]$PreflightScenario = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ImageDigest = "sha256:7b82f29c6ef83480a97f612d53ac3fd5f30a32df3fea1e06923d4204d3532bb2"
$ImageReference = "mcr.microsoft.com/playwright/mcp@$ImageDigest"
$CliSha256 = "1db9709bf1753611ca2fec234cf5adf517376efeb1540fcf9e309da010f9ed38"
$EvidenceLimit = 262144L
$OutputLimit = 131072L
$HostDeadlineSeconds = 613
$DockerCommandTimeoutMilliseconds = 10000
$DockerCleanupTimeoutMilliseconds = 1500
$DockerOutputLimit = 65536L
$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$RelayPath = Join-Path $PSScriptRoot "advisory_ai_runtime_relay.mjs"
$QwenScriptPath = Join-Path $PSScriptRoot "advisory_ai_runtime_qwen.sh"
$PromptPath = Join-Path $RepoRoot "docs/contracts/advice/v1/system-prompt.md"
$SchemaPath = Join-Path $RepoRoot "docs/contracts/advice/v1/ai-advice-output.schema.json"
if ([string]::IsNullOrWhiteSpace($QwenPackageRoot)) {
    $QwenPackageRoot = Join-Path $RepoRoot "build/ai-runner/qwen-code-0.21.1/node_modules/@qwen-code/qwen-code"
}

$started = [DateTime]::UtcNow
$status = "FAILED"
$failureCode = "PROCESS_FAILED"
$unavailableReason = $null
$resultExitCode = 1
$providerRequestCount = $null
$promptSha256 = $null
$stage = "initialization"
$cleanupIncomplete = $false
$networkName = "ltv-ai-runtime-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$relayName = "$networkName-relay"
$qwenName = "$networkName-qwen"
$relayId = $null
$qwenId = $null
$networkMayExist = $false
$relayMayExist = $false
$qwenMayExist = $false
$attachProcess = $null
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ltv-ai-host-" + [guid]::NewGuid().ToString("N"))
$script:DockerPath = $null

function Write-Utf8File {
    param([Parameter(Mandatory)] [string]$Path, [Parameter(Mandatory)] [AllowEmptyString()] [string]$Content)
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Write-RuntimeResult {
    $value = [ordered]@{
        schema_version = "advisory-ai-runtime-result.v1"
        status = $script:status
        duration_ms = [long]([DateTime]::UtcNow - $script:started).TotalMilliseconds
        exit_code = $script:resultExitCode
        failure_code = if ($script:status -eq "FAILED") { $script:failureCode } else { $null }
        unavailable_reason = if ($script:status -eq "UNAVAILABLE") { $script:unavailableReason } else { $null }
        cleanup_incomplete = $script:cleanupIncomplete
        stage = $script:stage
        provider_request_count = $script:providerRequestCount
        prompt_sha256 = $script:promptSha256
    }
    Write-Utf8File -Path $ResultPath -Content ($value | ConvertTo-Json -Compress)
}

function Set-Unavailable {
    param([Parameter(Mandatory)] [string]$Reason)
    $script:status = "UNAVAILABLE"
    $script:unavailableReason = $Reason
    $script:resultExitCode = 1
    throw "runtime unavailable"
}

function Require-File {
    param([Parameter(Mandatory)] [string]$Path, [string]$Reason = "RUNNER_ARTIFACT_MISSING")
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Set-Unavailable $Reason }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Require-Directory {
    param([Parameter(Mandatory)] [string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { Set-Unavailable "RUNNER_ARTIFACT_MISSING" }
    return (Resolve-Path -LiteralPath $Path).Path
}

function ConvertTo-NativeArgument {
    param([Parameter(Mandatory)] [AllowEmptyString()] [string]$Value)
    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') { return $Value }
    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append([char]34)
    $backslashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq [char]92) {
            $backslashes += 1
        } elseif ($character -eq [char]34) {
            if ($backslashes -gt 0) { [void]$builder.Append(([string][char]92).PadLeft($backslashes * 2, [char]92)) }
            [void]$builder.Append([char]92)
            [void]$builder.Append([char]34)
            $backslashes = 0
        } else {
            if ($backslashes -gt 0) { [void]$builder.Append(([string][char]92).PadLeft($backslashes, [char]92)) }
            [void]$builder.Append($character)
            $backslashes = 0
        }
    }
    if ($backslashes -gt 0) { [void]$builder.Append(([string][char]92).PadLeft($backslashes * 2, [char]92)) }
    [void]$builder.Append([char]34)
    return $builder.ToString()
}

function Stop-ProcessTree {
    param([System.Diagnostics.Process]$Process)
    if ($null -eq $Process) { return }
    try { if ($Process.HasExited) { return } } catch { return }
    if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) {
        $taskkill = Join-Path $env:SystemRoot "System32\taskkill.exe"
        try {
            $killer = Start-Process -FilePath $taskkill -ArgumentList @("/PID", [string]$Process.Id, "/T", "/F") `
                -WindowStyle Hidden -PassThru
            if (-not $killer.WaitForExit(5000)) { $killer.Kill() }
        } catch {}
    }
    try {
        if (-not $Process.HasExited) { $Process.Kill() }
        [void]$Process.WaitForExit(500)
    } catch {}
}

function Invoke-Docker {
    param(
        [Parameter(Mandatory)] [string[]]$Arguments,
        [switch]$AllowFailure,
        [switch]$Cleanup
    )
    if ([string]::IsNullOrWhiteSpace($script:DockerPath)) {
        if ($Cleanup) { return [pscustomobject]@{ ExitCode = -1; Lines = @(); TimedOut = $false } }
        throw "docker executable is unavailable"
    }
    $commandId = [guid]::NewGuid().ToString("N")
    $stdoutPath = Join-Path $temporaryRoot "docker-$commandId.stdout"
    $stderrPath = Join-Path $temporaryRoot "docker-$commandId.stderr"
    $process = $null
    try {
        $commandLine = ($Arguments | ForEach-Object { ConvertTo-NativeArgument ([string]$_) }) -join " "
        $process = Start-Process -FilePath $script:DockerPath -ArgumentList $commandLine `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -WindowStyle Hidden -PassThru
        $null = $process.Handle
        $timeout = if ($Cleanup) { $DockerCleanupTimeoutMilliseconds } else { $DockerCommandTimeoutMilliseconds }
        $deadline = [DateTime]::UtcNow.AddMilliseconds($timeout)
        $hostDeadline = $started.AddSeconds($HostDeadlineSeconds)
        $timedOut = $false
        while (-not $process.HasExited) {
            if (-not $Cleanup -and (Test-Cancelled)) {
                $script:status = "CANCELLED"
                Stop-ProcessTree $process
                throw "cancelled"
            }
            if ([DateTime]::UtcNow -ge $deadline -or (-not $Cleanup -and [DateTime]::UtcNow -ge $hostDeadline)) {
                if (-not $Cleanup -and [DateTime]::UtcNow -ge $hostDeadline) { $script:failureCode = "TIMEOUT" }
                $timedOut = $true
                Stop-ProcessTree $process
                break
            }
            $capturedBytes = 0L
            if (Test-Path -LiteralPath $stdoutPath -PathType Leaf) { $capturedBytes += (Get-Item -LiteralPath $stdoutPath).Length }
            if (Test-Path -LiteralPath $stderrPath -PathType Leaf) { $capturedBytes += (Get-Item -LiteralPath $stderrPath).Length }
            if ($capturedBytes -gt $DockerOutputLimit) {
                Stop-ProcessTree $process
                throw "docker output exceeds limit"
            }
            Start-Sleep -Milliseconds 50
        }
        if ($timedOut) {
            if ($Cleanup) { return [pscustomobject]@{ ExitCode = -1; Lines = @(); TimedOut = $true } }
            throw "docker command timeout"
        }
        $process.WaitForExit()
        $process.Refresh()
        $lines = @()
        foreach ($path in @($stdoutPath, $stderrPath)) {
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                if ((Get-Item -LiteralPath $path).Length -gt $DockerOutputLimit) { throw "docker output exceeds limit" }
                $lines += @(Get-Content -LiteralPath $path -Encoding UTF8)
            }
        }
        $exitCode = $process.ExitCode
        if (-not $AllowFailure -and $exitCode -ne 0) { throw "docker command failed" }
        return [pscustomobject]@{ ExitCode = $exitCode; Lines = $lines; TimedOut = $false }
    } catch {
        Stop-ProcessTree $process
        if ($Cleanup -and $AllowFailure) {
            return [pscustomobject]@{ ExitCode = -1; Lines = @(); TimedOut = $false }
        }
        throw
    } finally {
        foreach ($path in @($stdoutPath, $stderrPath)) {
            if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue }
        }
    }
}

function Container-Id {
    param($DockerResult)
    return [string]($DockerResult.Lines | Where-Object { "$_" -match "^[0-9a-f]{64}$" } | Select-Object -Last 1)
}

function Read-BoundedJson {
    param([Parameter(Mandatory)] [string]$Path, [long]$Limit = 16384L)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or (Get-Item -LiteralPath $Path).Length -gt $Limit) {
        throw "bounded json is missing or oversized"
    }
    return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Read-RelayResult {
    param([Parameter(Mandatory)] [string]$Path)
    try {
        $value = Read-BoundedJson $Path
        if ($null -eq $value.PSObject.Properties["status"] -or
            $null -eq $value.PSObject.Properties["forwarded_request_count"]) { return $null }
        $null = [int]$value.PSObject.Properties["forwarded_request_count"].Value
        return $value
    } catch { return $null }
}

function Save-QwenAdvice {
    param([Parameter(Mandatory)] [string]$StdoutPath)
    if (-not (Test-Path -LiteralPath $StdoutPath -PathType Leaf) -or (Get-Item -LiteralPath $StdoutPath).Length -gt 150000) {
        return $false
    }
    $text = [System.IO.File]::ReadAllText($StdoutPath)
    $match = [regex]::Match($text, "(?ms)^stdout\r?\n(?<payload>.*?)\r?\nstderr\r?\n")
    if (-not $match.Success -or [string]::IsNullOrWhiteSpace($match.Groups["payload"].Value)) { return $false }
    try { $parsed = $match.Groups["payload"].Value.Trim() | ConvertFrom-Json } catch { return $false }
    foreach ($item in @($parsed)) {
        $candidate = $item
        $schemaProperty = $candidate.PSObject.Properties["schema_version"]
        if ($null -eq $schemaProperty -or [string]$schemaProperty.Value -ne "ai-advice-output.v1") {
            $property = $candidate.PSObject.Properties["result"]
            if ($null -eq $property -or $null -eq $property.Value) { continue }
            $candidate = $property.Value
            if ($candidate -is [string]) {
                try { $candidate = $candidate | ConvertFrom-Json } catch { continue }
            }
        }
        $candidateSchema = $candidate.PSObject.Properties["schema_version"]
        if ($null -ne $candidateSchema -and [string]$candidateSchema.Value -eq "ai-advice-output.v1") {
            Write-Utf8File -Path $OutputPath -Content ($candidate | ConvertTo-Json -Compress -Depth 30)
            return (Get-Item -LiteralPath $OutputPath).Length -le $OutputLimit
        }
    }
    return $false
}

function Test-Cancelled {
    return Test-Path -LiteralPath $CancelPath -PathType Leaf
}

function Remove-TemporaryRoot {
    if (-not (Test-Path -LiteralPath $temporaryRoot -PathType Container)) { return }
    $base = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $target = [System.IO.Path]::GetFullPath($temporaryRoot)
    if (-not $target.StartsWith($base, [System.StringComparison]::OrdinalIgnoreCase) -or
        -not [System.IO.Path]::GetFileName($target).StartsWith("ltv-ai-host-", [System.StringComparison]::Ordinal)) {
        throw "refusing unsafe temporary cleanup"
    }
    Remove-Item -LiteralPath $target -Recurse -Force
}

try {
    $stage = "validate_inputs"
    if ($Mode -ne "Preflight" -and $PreflightScenario -ne "") { throw "preflight scenario requires Preflight mode" }
    if ((Test-Path -LiteralPath $ResultPath -PathType Leaf) -or
        (Test-Path -LiteralPath $OutputPath -PathType Leaf)) {
        throw "runtime outputs already exist"
    }
    [void](New-Item -ItemType Directory -Path $temporaryRoot)
    $stage = "validate_artifacts"
    $EvidencePath = Require-File $EvidencePath
    if ((Get-Item -LiteralPath $EvidencePath).Length -gt $EvidenceLimit) {
        $failureCode = "INPUT_LIMIT"
        throw "evidence exceeds limit"
    }
    $RelayPath = Require-File $RelayPath
    $QwenScriptPath = Require-File $QwenScriptPath
    $PromptPath = Require-File $PromptPath
    $SchemaPath = Require-File $SchemaPath
    $QwenPackageRoot = Require-Directory $QwenPackageRoot
    $packageJsonPath = Require-File (Join-Path $QwenPackageRoot "package.json")
    $cliPath = Require-File (Join-Path $QwenPackageRoot "cli-entry.js")
    $package = Get-Content -LiteralPath $packageJsonPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([string]$package.version -ne "0.21.1" -or
        (Get-FileHash -LiteralPath $cliPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $CliSha256) {
        Set-Unavailable "RUNNER_ARTIFACT_MISMATCH"
    }
    $promptSnapshot = Join-Path $temporaryRoot "system-prompt.md"
    Copy-Item -LiteralPath $PromptPath -Destination $promptSnapshot
    $promptSha256 = (Get-FileHash -LiteralPath $promptSnapshot -Algorithm SHA256).Hash.ToLowerInvariant()

    if ($Mode -eq "Live") {
        $stage = "validate_credential"
        if ([string]::IsNullOrWhiteSpace($CredentialEnvFile)) { Set-Unavailable "CREDENTIAL_NOT_CONFIGURED" }
        $CredentialEnvFile = Require-File $CredentialEnvFile "CREDENTIAL_NOT_CONFIGURED"
        if ((Get-Item -LiteralPath $CredentialEnvFile).Length -gt 8192) { Set-Unavailable "CREDENTIAL_NOT_CONFIGURED" }
        $credentialLines = [System.IO.File]::ReadAllLines($CredentialEnvFile)
        if ($credentialLines.Count -ne 1 -or $credentialLines[0] -notmatch '^OPENAI_API_KEY=.+$') {
            Set-Unavailable "CREDENTIAL_NOT_CONFIGURED"
        }
    }

    $stage = "validate_runtime"
    try { $script:DockerPath = (Get-Command docker -ErrorAction Stop).Source } catch { Set-Unavailable "DOCKER_UNAVAILABLE" }
    $image = Invoke-Docker -Arguments @("image", "inspect", "--format", "{{.Id}}", $ImageReference) -AllowFailure
    if ($image.ExitCode -ne 0) { Set-Unavailable "RUNTIME_IMAGE_MISSING" }
    if ([string]($image.Lines | Select-Object -Last 1).Trim() -ne $ImageDigest) { Set-Unavailable "RUNTIME_IMAGE_MISSING" }

    $relayOutput = Join-Path $temporaryRoot "relay"
    [void](New-Item -ItemType Directory -Path $relayOutput)
    $qwenStdout = Join-Path $temporaryRoot "qwen.stdout"
    $qwenStderr = Join-Path $temporaryRoot "qwen.stderr"

    $stage = "create_network"
    $networkMayExist = $true
    [void](Invoke-Docker -Arguments @(
        "network", "create", "--internal", "--label", "io.ltverdict.advisory-runtime=$networkName", $networkName
    ))
    $stage = "create_relay"
    $relayMayExist = $true
    $relayArguments = @(
        "create", "--pull", "never", "--name", $relayName,
        "--label", "io.ltverdict.advisory-runtime=$networkName",
        "--network", $networkName, "--network-alias", "modelstudio-relay",
        "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
        "--pids-limit", "64", "--memory", "1g", "--cpus", "1", "--user", "65532:65532",
        "--tmpfs", "/tmp:rw,nosuid,nodev,size=32m,mode=1777",
        "--env", "ADVISORY_RELAY_MODE=$($Mode.ToLowerInvariant())",
        "--env", "ADVISORY_RELAY_READY_PATH=/out/relay-ready",
        "--mount", "type=bind,src=$RelayPath,dst=/runtime/relay.mjs,readonly",
        "--mount", "type=bind,src=$relayOutput,dst=/out"
    )
    if ($PreflightScenario -ne "") { $relayArguments += @("--env", "ADVISORY_RELAY_PREFLIGHT_SCENARIO=$PreflightScenario") }
    if ($Mode -eq "Live") { $relayArguments += @("--env-file", $CredentialEnvFile) }
    $relayArguments += @("--entrypoint", "/usr/local/bin/node", $ImageReference, "/runtime/relay.mjs")
    $relayId = Container-Id (Invoke-Docker -Arguments $relayArguments)
    if ([string]::IsNullOrWhiteSpace($relayId)) { throw "relay container was not created" }
    if ($Mode -eq "Live") { [void](Invoke-Docker -Arguments @("network", "connect", "bridge", $relayId)) }
    [void](Invoke-Docker -Arguments @("start", $relayId))

    $stage = "wait_relay"
    $readyPath = Join-Path $relayOutput "relay-ready"
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(10)
    while (-not (Test-Path -LiteralPath $readyPath -PathType Leaf) -and [DateTime]::UtcNow -lt $readyDeadline) {
        if (Test-Cancelled) { $status = "CANCELLED"; throw "cancelled" }
        Start-Sleep -Milliseconds 100
    }
    if (-not (Test-Path -LiteralPath $readyPath -PathType Leaf)) { throw "relay did not become ready" }

    $stage = "create_qwen"
    $qwenMayExist = $true
    $qwenArguments = @(
        "create", "--pull", "never", "--name", $qwenName,
        "--label", "io.ltverdict.advisory-runtime=$networkName",
        "--network", $networkName,
        "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
        "--pids-limit", "128", "--memory", "768m", "--cpus", "1", "--user", "65532:65532",
        "--tmpfs", "/home/qwen:rw,noexec,nosuid,nodev,size=16m,mode=700,uid=65532,gid=65532",
        "--tmpfs", "/runtime:rw,noexec,nosuid,nodev,size=16m,mode=700,uid=65532,gid=65532",
        "--tmpfs", "/work:rw,noexec,nosuid,nodev,size=16m,mode=700,uid=65532,gid=65532",
        "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m,mode=1777",
        "--mount", "type=bind,src=$QwenPackageRoot,dst=/opt/qwen,readonly",
        "--mount", "type=bind,src=$QwenScriptPath,dst=/runtime/run-qwen.sh,readonly",
        "--mount", "type=bind,src=$EvidencePath,dst=/input/evidence.json,readonly",
        "--mount", "type=bind,src=$promptSnapshot,dst=/input/system-prompt.md,readonly",
        "--mount", "type=bind,src=$SchemaPath,dst=/input/output.schema.json,readonly",
        "--entrypoint", "/bin/sh", $ImageReference, "/runtime/run-qwen.sh"
    )
    $qwenId = Container-Id (Invoke-Docker -Arguments $qwenArguments)
    if ([string]::IsNullOrWhiteSpace($qwenId)) { throw "Qwen container was not created" }
    $stage = "run_qwen"
    $attachProcess = Start-Process -FilePath $script:DockerPath -ArgumentList @("start", "--attach", $qwenId) `
        -RedirectStandardOutput $qwenStdout -RedirectStandardError $qwenStderr -WindowStyle Hidden -PassThru

    $deadline = $started.AddSeconds($HostDeadlineSeconds)
    $outerTimeout = $false
    while (-not $attachProcess.HasExited) {
        if (Test-Cancelled) {
            $status = "CANCELLED"
            [void](Invoke-Docker -Arguments @("kill", $qwenName) -AllowFailure -Cleanup)
            break
        }
        if ([DateTime]::UtcNow -ge $deadline) {
            $outerTimeout = $true
            [void](Invoke-Docker -Arguments @("kill", $qwenName) -AllowFailure -Cleanup)
            break
        }
        Start-Sleep -Milliseconds 100
    }
    if (-not $attachProcess.WaitForExit(500)) { Stop-ProcessTree $attachProcess }
    if ($status -eq "CANCELLED") { throw "cancelled" }

    $qwenExit = [int]([string]((Invoke-Docker -Arguments @("inspect", "--format", "{{.State.ExitCode}}", $qwenId)).Lines | Select-Object -Last 1))
    if ($outerTimeout -or $qwenExit -eq 124) {
        $failureCode = "TIMEOUT"
        throw "runtime timeout"
    }
    if ($qwenExit -eq 91) {
        $failureCode = "OUTPUT_LIMIT"
        throw "runtime output limit"
    }
    if ($qwenExit -ne 0) {
        $relayResult = Read-RelayResult (Join-Path $relayOutput "relay-result.json")
        if ($null -ne $relayResult) {
            $providerRequestCount = [int]$relayResult.forwarded_request_count
            if ($providerRequestCount -eq 2) { $failureCode = "INVALID_OUTPUT" }
        }
        throw "Qwen failed"
    }
    $stage = "validate_relay"
    $relayResult = Read-RelayResult (Join-Path $relayOutput "relay-result.json")
    if ($null -ne $relayResult) { $providerRequestCount = [int]$relayResult.forwarded_request_count }
    if ($null -eq $relayResult -or [string]$relayResult.status -ne "FORWARDED_STRUCTURED_OUTPUT" -or
        $providerRequestCount -notin @(1, 2)) {
        throw "relay boundary failed"
    }
    $stage = "parse_qwen_output"
    if (-not (Save-QwenAdvice $qwenStdout)) {
        $failureCode = "INVALID_OUTPUT"
        throw "Qwen output was invalid"
    }
    $status = "SUCCESS"
    $stage = "complete"
    $failureCode = $null
    $resultExitCode = 0
} catch {
    if ($status -notin @("UNAVAILABLE", "CANCELLED")) { $status = "FAILED" }
} finally {
    Stop-ProcessTree $attachProcess
    $containerNames = @()
    if ($qwenMayExist) { $containerNames += $qwenName }
    if ($relayMayExist) { $containerNames += $relayName }
    if ($containerNames.Count -gt 0) {
        $containerCleanup = Invoke-Docker -Arguments (@("rm", "--force") + $containerNames) -AllowFailure -Cleanup
        if ($containerCleanup.ExitCode -ne 0) { $cleanupIncomplete = $true }
    }
    if ($networkMayExist) {
        $networkCleanup = Invoke-Docker -Arguments @("network", "rm", $networkName) -AllowFailure -Cleanup
        if ($networkCleanup.ExitCode -ne 0) { $cleanupIncomplete = $true }
    }
    try { Remove-TemporaryRoot } catch { $cleanupIncomplete = $true }
    if ($cleanupIncomplete -and $status -eq "SUCCESS") {
        $status = "FAILED"
        $failureCode = "PROCESS_FAILED"
        $resultExitCode = 1
    }
}

Write-RuntimeResult
exit 0
