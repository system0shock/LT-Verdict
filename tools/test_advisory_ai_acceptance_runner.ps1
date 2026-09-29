[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$runnerPath = Join-Path $PSScriptRoot "advisory_ai_acceptance_runner.ps1"
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $runnerPath,
    [ref]$tokens,
    [ref]$parseErrors
)
if ($parseErrors.Count -ne 0) {
    throw "Runner has PowerShell parse errors."
}

$functionDefinitions = @(
    "Write-Utf8File",
    "Write-JsonFile",
    "Get-Sha256",
    "Require-File",
    "Test-NonnegativeJsonInteger",
    "Get-WireObservation",
    "Resolve-FrozenArtifact",
    "Test-TrueJsonBoolean",
    "New-DisabledBudgetAuthorization",
    "Read-LiveControl",
    "Get-QwenResultObservation",
    "Save-QwenAdvice"
) | ForEach-Object {
    $name = $_
    $definition = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true)
    if ($null -ne $definition) {
        $definition.Extent.Text
    }
}

function Write-TestFile {
    param([string]$Path, [string]$Content)
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Message)
    try {
        & $Action | Out-Null
    } catch {
        return
    }
    throw $Message
}

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$tempRoot = [System.IO.Path]::GetFullPath((
    Join-Path $tempBase ("ltv-runner-test-" + [guid]::NewGuid().ToString("N"))
))
[void](New-Item -ItemType Directory -Path $tempRoot)
try {
    $functionModulePath = Join-Path $tempRoot "runner-functions.ps1"
    Write-TestFile $functionModulePath ($functionDefinitions -join "`r`n`r`n")
    . $functionModulePath

    $RepoRoot = $tempRoot
    $FixedModel = "deepseek-v4-flash-0731"
    $FixedEndpoint = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions"
    $BudgetMode = "disabled_by_user"
    $BudgetStatus = "DISABLED_BY_USER"
    $OutputTokenLimitMode = "provider_default"
    $ExpectedQwenVersion = "0.21.1"
    $ImageReference = "image@sha256:test"
    $EffectiveTokenLimit = 4096

    $authorization = New-DisabledBudgetAuthorization "attempt-01"
    $authorizationKeys = @($authorization.PSObject.Properties.Name | Sort-Object)
    $expectedAuthorizationKeys = @(
        "allowed", "attempt_id", "budget_mode", "max_output_tokens", "model", "output_token_limit_mode",
        "schema_version", "status"
    ) | Sort-Object
    if (($authorizationKeys -join ",") -ne ($expectedAuthorizationKeys -join ",") -or
        $authorization.allowed -ne $true -or $authorization.budget_mode -ne "disabled_by_user" -or
        $authorization.status -ne "DISABLED_BY_USER" -or $authorization.model -ne $FixedModel -or
        $null -ne $authorization.max_output_tokens -or
        $authorization.output_token_limit_mode -ne "provider_default") {
        throw "Disabled budget authorization contract is not exact."
    }

    $wirePath = Join-Path $tempRoot "wire-observation.json"
    Write-TestFile $wirePath (@{
        model_before = $FixedModel; model_after = $FixedModel; stream = $true; tool_names = @("structured_output")
        max_tokens_before_present = $true; max_tokens_before = 4096; max_tokens_after_present = $false
        max_tokens_after = $null; max_tokens_clamp_applied = $false; output_token_limit_mode = "provider_default"
        provider_policy_enforced = $true; provider_field_present = $false; budget_mode = $BudgetMode
        text_only_input = $true; unsafe_fields_fixture_injected = $false; n_before = 1; n_after = 1
        best_of_before_present = $false; best_of_after_present = $false
        max_output_tokens_before_present = $false; max_output_tokens_after_present = $false
    } | ConvertTo-Json -Depth 10)
    $wire = Get-WireObservation $wirePath
    if ($wire.MaxTokensAfterPresent -or $null -ne $wire.MaxTokensAfter -or $wire.ClampApplied -or
        $wire.OutputTokenLimitMode -ne "provider_default") {
        throw "Provider-default token limit was not preserved."
    }

    $MethodologyPath = Join-Path $tempRoot "methodology.md"
    $PromptPath = Join-Path $tempRoot "prompt.md"
    $SchemaPath = Join-Path $tempRoot "schema.json"
    $FixedRelayPath = Join-Path $tempRoot "relay.mjs"
    $RunLivePath = Join-Path $tempRoot "run-live.sh"
    $evidencePath = Join-Path $tempRoot "evidence.json"
    foreach ($path in @($MethodologyPath, $PromptPath, $SchemaPath, $FixedRelayPath, $RunLivePath, $evidencePath)) {
        Write-TestFile $path ([System.IO.Path]::GetFileName($path))
    }

    $preflightPath = Join-Path $tempRoot "preflight.json"
    $preflight = [ordered]@{
        harness_preflight_pass = $true
        live_requests = 0
        package_hashes = [ordered]@{ guarded_relay_sha256 = Get-Sha256 $FixedRelayPath }
        budget = [ordered]@{
            mode = "disabled_by_user"
            status = "DISABLED_BY_USER"
            max_output_tokens = $null
            output_token_limit_mode = "provider_default"
        }
    }
    Write-TestFile $preflightPath ($preflight | ConvertTo-Json -Depth 10)

    $artifacts = @{}
    foreach ($name in @("corpus.json", "oracle.json", "calibration.json")) {
        $path = Join-Path $tempRoot $name
        Write-TestFile $path $name
        $artifacts[$name] = Get-Sha256 $path
    }
    $attempts = for ($ordinal = 1; $ordinal -le 60; $ordinal++) {
        $case = [int][Math]::Ceiling($ordinal / 2)
        [ordered]@{
            attempt_id = "attempt-{0:d2}" -f $ordinal
            case_id = "case-{0:d2}" -f $case
            ordinal = $ordinal
            repeat_index = if ($ordinal % 2 -eq 1) { 1 } else { 2 }
            evidence_path = "evidence.json"
            evidence_sha256 = Get-Sha256 $evidencePath
        }
    }
    $control = [ordered]@{
        schema_version = "advisory-ai-live-control.v1"
        release_status = "RELEASED"
        corpus_complete = $true
        oracles_frozen = $true
        expert_calibration_passed = $true
        offline_preflight_passed = $true
        model = $FixedModel
        endpoint = $FixedEndpoint
        qwen_version = $ExpectedQwenVersion
        image_reference = $ImageReference
        budget_mode = "disabled_by_user"
        budget_status = "DISABLED_BY_USER"
        max_output_tokens = $null
        output_token_limit_mode = "provider_default"
        methodology_sha256 = Get-Sha256 $MethodologyPath
        prompt_sha256 = Get-Sha256 $PromptPath
        schema_sha256 = Get-Sha256 $SchemaPath
        runner_sha256 = Get-Sha256 $functionModulePath
        guarded_relay_sha256 = Get-Sha256 $FixedRelayPath
        run_live_sha256 = Get-Sha256 $RunLivePath
        corpus_manifest_path = "corpus.json"
        corpus_manifest_sha256 = $artifacts["corpus.json"]
        oracle_manifest_path = "oracle.json"
        oracle_manifest_sha256 = $artifacts["oracle.json"]
        calibration_record_path = "calibration.json"
        calibration_record_sha256 = $artifacts["calibration.json"]
        preflight_manifest_path = "preflight.json"
        preflight_manifest_sha256 = Get-Sha256 $preflightPath
        attempts = $attempts
    }
    $controlPath = Join-Path $tempRoot "control.json"
    Write-TestFile $controlPath ($control | ConvertTo-Json -Depth 20)
    [void](Read-LiveControl $controlPath "attempt-01")

    foreach ($field in @("budget_mode", "budget_status", "output_token_limit_mode")) {
        $expected = $control[$field]
        $control[$field] = "legacy"
        Write-TestFile $controlPath ($control | ConvertTo-Json -Depth 20)
        Assert-Throws { Read-LiveControl $controlPath "attempt-01" } "Legacy budget control was accepted: $field"
        $control[$field] = $expected
    }
    $control.max_output_tokens = 4096
    Write-TestFile $controlPath ($control | ConvertTo-Json -Depth 20)
    Assert-Throws { Read-LiveControl $controlPath "attempt-01" } "Hard output token limit was accepted."
    $control.max_output_tokens = $null

    foreach ($field in @("corpus_complete", "oracles_frozen", "expert_calibration_passed", "offline_preflight_passed")) {
        foreach ($invalid in @("false", "true", 0, 1, $null)) {
            $control[$field] = $invalid
            Write-TestFile $controlPath ($control | ConvertTo-Json -Depth 20)
            Assert-Throws { Read-LiveControl $controlPath "attempt-01" } "Non-boolean opened release gate: $field"
            $control[$field] = $true
        }
    }

    Write-TestFile $controlPath ($control | ConvertTo-Json -Depth 20)
    foreach ($invalid in @("false", "true", 0, 1, $null)) {
        $preflight.harness_preflight_pass = $invalid
        Write-TestFile $preflightPath ($preflight | ConvertTo-Json -Depth 10)
        $control.preflight_manifest_sha256 = Get-Sha256 $preflightPath
        Write-TestFile $controlPath ($control | ConvertTo-Json -Depth 20)
        Assert-Throws { Read-LiveControl $controlPath "attempt-01" } "Non-boolean opened preflight release gate."
    }

    $advice = [ordered]@{ schema_version = "ai-advice-output.v1"; summary = "retained" }
    $envelope = [ordered]@{ result = ($advice | ConvertTo-Json -Compress) }
    $stdoutPath = Join-Path $tempRoot "qwen.stdout"
    Write-TestFile $stdoutPath ("qwen_exit=0`nstdout`n" + ($envelope | ConvertTo-Json -Compress) + "`nstderr`n")
    $observation = Get-QwenResultObservation $stdoutPath
    Remove-Item -LiteralPath $stdoutPath
    if ($null -eq $observation.PSObject.Properties["Advice"]) {
        throw "Parsed structured advice was discarded."
    }
    $adviceSha256 = Save-QwenAdvice $observation $tempRoot
    $persistedAdvicePath = Join-Path $tempRoot "advice-output.json"
    $persistedAdvice = Get-Content -LiteralPath $persistedAdvicePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($observation.State -ne "SUCCESS" -or $persistedAdvice.schema_version -ne "ai-advice-output.v1" -or
        $persistedAdvice.summary -ne "retained" -or $adviceSha256 -ne (Get-Sha256 $persistedAdvicePath)) {
        throw "Parsed structured advice was not retained after stdout removal."
    }
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath($tempRoot)
        if (-not $resolvedTempRoot.StartsWith($tempBase, [System.StringComparison]::OrdinalIgnoreCase) -or
            [System.IO.Path]::GetFileName($resolvedTempRoot) -notlike "ltv-runner-test-*") {
            throw "Refusing to remove unexpected test path: $resolvedTempRoot"
        }
        Remove-Item -LiteralPath $resolvedTempRoot -Recurse -Force
    }
}

$repoRoot = Split-Path $PSScriptRoot -Parent
$launcherSource = Get-Content (Join-Path $repoRoot 'build/ai-probe/run-live.sh') -Raw
$relaySource = Get-Content (Join-Path $repoRoot 'build/ai-probe/fixed-openrouter-relay.mjs') -Raw
$hostSource = Get-Content (Join-Path $PSScriptRoot 'advisory_ai_acceptance_runner.ps1') -Raw
$wallSeconds = [int]([regex]::Match($launcherSource, '--max-wall-time=(\d+)s').Groups[1].Value)
$killSeconds = [int]([regex]::Match($launcherSource, '--signal=KILL (\d+)s').Groups[1].Value)
$hostMilliseconds = [int]([regex]::Match($hostSource, 'process\.WaitForExit\((\d+)\)').Groups[1].Value)
$socketMilliseconds = [int]([regex]::Match($relaySource, 'timeout: (\d+)').Groups[1].Value)
$clientMilliseconds = [int]([regex]::Match($launcherSource, 'QWEN_CODE_API_TIMEOUT_MS=(\d+)').Groups[1].Value)
if ($wallSeconds -ne 600 -or $socketMilliseconds -ne 600000 -or $clientMilliseconds -ne 600000 -or
    $killSeconds -le $wallSeconds -or $hostMilliseconds -le ($killSeconds * 1000)) {
    throw 'Ten-minute response window is shortened by an outer timeout.'
}

Write-Output "advisory_ai_acceptance_runner regression PASS"
