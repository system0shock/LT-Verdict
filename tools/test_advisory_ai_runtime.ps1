[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$runner = Join-Path $PSScriptRoot "advisory_ai_runtime.ps1"
$temporary = Join-Path ([System.IO.Path]::GetTempPath()) ("ltv-ai-runtime-test-" + [guid]::NewGuid().ToString("N"))
[void](New-Item -ItemType Directory -Path $temporary)
try {
    $evidence = Join-Path $temporary "evidence.json"
    $output = Join-Path $temporary "advice-output.json"
    $result = Join-Path $temporary "runtime-result.json"
    $cancel = Join-Path $temporary "cancel"
    [System.IO.File]::WriteAllText(
        $evidence,
        '{"schema_version":"ai-evidence.v1","analysis":{"run_id":"test","analysis_id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","analysis_manifest_sha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},"facts":[{"ref":"analysis-result.json#/run_validity","value":"VALID"}],"findings":[],"evidence":[]}',
        [System.Text.UTF8Encoding]::new($false)
    )

    & $runner -Mode Preflight -EvidencePath $evidence -OutputPath $output -ResultPath $result -CancelPath $cancel
    if ($LASTEXITCODE -ne 0) { throw "Runtime preflight exited $LASTEXITCODE" }
    $runtimeResult = Get-Content -LiteralPath $result -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([string]$runtimeResult.status -ne "SUCCESS") {
        throw "Runtime preflight did not succeed: status=$($runtimeResult.status) failure=$($runtimeResult.failure_code) unavailable=$($runtimeResult.unavailable_reason) stage=$($runtimeResult.stage)"
    }
    if (-not (Test-Path -LiteralPath $output -PathType Leaf)) { throw "Runtime output is missing." }
    if ((Get-Item -LiteralPath $output).Length -gt 131072) { throw "Runtime output exceeds its bound." }
    $advice = Get-Content -LiteralPath $output -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([string]$advice.schema_version -ne "ai-advice-output.v1") { throw "Runtime output contract differs." }

    if ([int]$runtimeResult.provider_request_count -ne 1) { throw "Normal preflight provider count differs." }
    $prompt = Join-Path $repoRoot "docs/contracts/advice/v1/system-prompt.md"
    $expectedPromptHash = (Get-FileHash -LiteralPath $prompt -Algorithm SHA256).Hash.ToLowerInvariant()
    if ([string]$runtimeResult.prompt_sha256 -cne $expectedPromptHash) { throw "Normal preflight prompt hash differs." }

    function Invoke-Scenario {
        param([string]$Name, [string]$Scenario, [string]$RunMode = "Preflight")
        $scenarioOutput = Join-Path $temporary "$Name-advice-output.json"
        $scenarioResult = Join-Path $temporary "$Name-runtime-result.json"
        $scenarioCancel = Join-Path $temporary "$Name-cancel"
        & $runner -Mode $RunMode -PreflightScenario $Scenario -EvidencePath $evidence -OutputPath $scenarioOutput -ResultPath $scenarioResult -CancelPath $scenarioCancel
        if ($LASTEXITCODE -ne 0) { throw "Runtime scenario $Name exited $LASTEXITCODE" }
        return [pscustomobject]@{
            Result = (Get-Content -LiteralPath $scenarioResult -Raw -Encoding UTF8 | ConvertFrom-Json)
            Output = $scenarioOutput
        }
    }

    $wrappedValid = Invoke-Scenario -Name "wrapped-valid" -Scenario "wrapped-then-valid"
    if ([string]$wrappedValid.Result.status -ne "SUCCESS" -or [int]$wrappedValid.Result.provider_request_count -ne 2) {
        throw "Wrapped then valid preflight result differs."
    }
    if (-not (Test-Path -LiteralPath $wrappedValid.Output -PathType Leaf) -or
        [string](Get-Content -LiteralPath $wrappedValid.Output -Raw -Encoding UTF8 | ConvertFrom-Json).schema_version -ne "ai-advice-output.v1") {
        throw "Wrapped then valid advice output differs."
    }

    $wrappedTwice = Invoke-Scenario -Name "wrapped-twice" -Scenario "wrapped-twice"
    if ([string]$wrappedTwice.Result.status -ne "FAILED" -or
        [string]$wrappedTwice.Result.failure_code -ne "INVALID_OUTPUT" -or
        [int]$wrappedTwice.Result.provider_request_count -ne 2) {
        throw "Wrapped twice preflight result differs."
    }

    $wrappedError = Invoke-Scenario -Name "wrapped-error" -Scenario "wrapped-then-error"
    if ([string]$wrappedError.Result.status -ne "FAILED" -or
        [string]$wrappedError.Result.failure_code -ne "PROCESS_FAILED" -or
        [int]$wrappedError.Result.provider_request_count -ne 2) {
        throw "Wrapped then error preflight result differs."
    }

    $deepViolation = Invoke-Scenario -Name "deep-violation" -Scenario "deep-violation"
    if ([string]$deepViolation.Result.status -ne "FAILED" -or
        [string]$deepViolation.Result.failure_code -ne "PROCESS_FAILED" -or
        [int]$deepViolation.Result.provider_request_count -ne 1) {
        throw "Deep violation preflight result differs."
    }

    $liveScenario = Invoke-Scenario -Name "live-scenario" -Scenario "wrapped-twice" -RunMode "Live"
    if ([string]$liveScenario.Result.status -ne "FAILED" -or
        [string]$liveScenario.Result.failure_code -ne "PROCESS_FAILED" -or
        [string]$liveScenario.Result.stage -ne "validate_inputs") {
        throw "Live preflight scenario was not rejected at input validation."
    }

    $fakeBin = Join-Path $temporary "fake-bin"
    [void](New-Item -ItemType Directory -Path $fakeBin)
    $dockerCalls = Join-Path $temporary "docker-calls.txt"
    $dockerChildPids = Join-Path $temporary "docker-child-pids.txt"
    $leakMarker = Join-Path $temporary "docker-child-leaked.txt"
    $escapedPidPath = $dockerChildPids.Replace("'", "''")
    $escapedLeakPath = $leakMarker.Replace("'", "''")
    [System.IO.File]::WriteAllText(
        (Join-Path $fakeBin "docker.cmd"),
        "@echo off`r`necho %*>>`"$dockerCalls`"`r`nif `"%1 %2`"==`"image inspect`" (echo sha256:7b82f29c6ef83480a97f612d53ac3fd5f30a32df3fea1e06923d4204d3532bb2& exit /b 0)`r`nif `"%1 %2`"==`"network create`" exit /b 0`r`nif `"%1`"==`"create`" (echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa& exit /b 0)`r`npowershell.exe -NoLogo -NoProfile -NonInteractive -Command `"`$PID | Add-Content -LiteralPath '$escapedPidPath'; Start-Sleep -Seconds 120; 'leaked' | Set-Content -LiteralPath '$escapedLeakPath'`"`r`n",
        [System.Text.Encoding]::ASCII
    )

    $hungResult = Join-Path $temporary "hung-runtime-result.json"
    $hungOutput = Join-Path $temporary "hung-advice-output.json"
    $hungCancel = Join-Path $temporary "hung-cancel"
    $hungStdout = Join-Path $temporary "hung.stdout"
    $hungStderr = Join-Path $temporary "hung.stderr"
    $hungCredential = Join-Path $temporary "hung-modelstudio.env"
    [System.IO.File]::WriteAllText($hungCredential, "OPENAI_API_KEY=fake-timeout-secret", [System.Text.Encoding]::ASCII)
    $hungLauncher = Join-Path $temporary "hung-launcher.ps1"
    $escapedFakeBin = $fakeBin.Replace("'", "''")
    $escapedRunner = $runner.Replace("'", "''")
    $escapedEvidence = $evidence.Replace("'", "''")
    $escapedHungOutput = $hungOutput.Replace("'", "''")
    $escapedHungResult = $hungResult.Replace("'", "''")
    $escapedHungCancel = $hungCancel.Replace("'", "''")
    $escapedHungCredential = $hungCredential.Replace("'", "''")
    [System.IO.File]::WriteAllText(
        $hungLauncher,
        "`$env:PATH = '$escapedFakeBin;' + `$env:PATH`r`n`$env:OS = `$null`r`n& '$escapedRunner' -Mode Live -EvidencePath '$escapedEvidence' -OutputPath '$escapedHungOutput' -ResultPath '$escapedHungResult' -CancelPath '$escapedHungCancel' -CredentialEnvFile '$escapedHungCredential'`r`nexit `$LASTEXITCODE`r`n",
        [System.Text.UTF8Encoding]::new($false)
    )
    $currentPowerShell = (Get-Process -Id $PID).Path
    $hungRunner = Start-Process -FilePath $currentPowerShell -ArgumentList @(
        "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", $hungLauncher
    ) -RedirectStandardOutput $hungStdout -RedirectStandardError $hungStderr -WindowStyle Hidden -PassThru
    try {
        if (-not $hungRunner.WaitForExit(35000)) { throw "Hung Docker command was not bounded." }
        $boundedResult = Get-Content -LiteralPath $hungResult -Raw -Encoding UTF8 | ConvertFrom-Json
        $cleanupProperty = $boundedResult.PSObject.Properties["cleanup_incomplete"]
        $cleanupIncomplete = $null -ne $cleanupProperty -and $cleanupProperty.Value -eq $true
        if ([string]$boundedResult.status -ne "FAILED" -or [string]$boundedResult.stage -ne "create_relay" -or
            -not $cleanupIncomplete) {
            $observedCalls = Get-Content -LiteralPath $dockerCalls -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            $observedStdout = Get-Content -LiteralPath $hungStdout -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            $observedStderr = Get-Content -LiteralPath $hungStderr -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            throw "Hung Docker command returned an unexpected result: status=$($boundedResult.status) stage=$($boundedResult.stage) cleanup=$cleanupIncomplete calls=$observedCalls stdout=$observedStdout stderr=$observedStderr"
        }
        $calls = Get-Content -LiteralPath $dockerCalls -Raw -Encoding UTF8
        if ($calls -notmatch 'rm --force ltv-ai-runtime-[0-9a-f]{12}-relay' -or
            $calls -notmatch 'network rm ltv-ai-runtime-[0-9a-f]{12}') {
            throw "Named best-effort Docker cleanup was not attempted."
        }
        if ($calls.Contains("fake-timeout-secret")) { throw "Credential value reached Docker argv." }
        $childExitDeadline = [DateTime]::UtcNow.AddSeconds(2)
        foreach ($childPid in @(Get-Content -LiteralPath $dockerChildPids -ErrorAction SilentlyContinue)) {
            while ((Get-Process -Id ([int]$childPid) -ErrorAction SilentlyContinue) -and
                [DateTime]::UtcNow -lt $childExitDeadline) {
                Start-Sleep -Milliseconds 50
            }
            if (Get-Process -Id ([int]$childPid) -ErrorAction SilentlyContinue) {
                throw "Docker subprocess tree survived runtime timeout: pid=$childPid"
            }
        }
        if (Test-Path -LiteralPath $leakMarker -PathType Leaf) { throw "Docker child survived long enough to leak." }
    } finally {
        if ($null -ne $hungRunner -and -not $hungRunner.HasExited) {
            & "$env:SystemRoot\System32\taskkill.exe" /PID $hungRunner.Id /T /F 2>&1 | Out-Null
        }
        foreach ($childPid in @(Get-Content -LiteralPath $dockerChildPids -ErrorAction SilentlyContinue)) {
            if (Get-Process -Id ([int]$childPid) -ErrorAction SilentlyContinue) {
                & "$env:SystemRoot\System32\taskkill.exe" /PID ([int]$childPid) /T /F 2>&1 | Out-Null
            }
        }
    }
    Write-Output "advisory runtime preflight and bounded cleanup checks passed"
} finally {
    if ($temporary.StartsWith([System.IO.Path]::GetTempPath(), [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $temporary -PathType Container)) {
        Remove-Item -LiteralPath $temporary -Recurse -Force
    }
}
