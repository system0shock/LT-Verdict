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
