[CmdletBinding()]
param(
    [ValidateSet("Preflight", "HostPreflight", "LiveAttempt")]
    [string]$Mode = "Preflight",

    [string]$OutputDirectory,

    [string]$BudgetLedgerPath,
    [string]$ReleaseControlFile,
    [string]$AttemptId,
    [string]$CredentialEnvFile,
    [switch]$ExecuteLive
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ImageDigest = "sha256:7b82f29c6ef83480a97f612d53ac3fd5f30a32df3fea1e06923d4204d3532bb2"
$ImageReference = "mcr.microsoft.com/playwright/mcp@$ImageDigest"
$ExpectedQwenVersion = "0.21.1"
$OutputLimitBytes = 131072
$StderrLimitBytes = 16384
$EffectiveTokenLimit = 4096
$ProviderResponseLimitBytes = 67108864
$BudgetCapMicroUsd = 2000000L
$PromptPriceMicroUsdPerMillionTokens = 49980L
$CompletionPriceMicroUsdPerMillionTokens = 99960L
$RequestPriceMicroUsd = 0L
$ContextTokenCeiling = 1310720L
$ByokFeeBasisPoints = 500L
$BaseReservationMicroUsd = [long]([Math]::Ceiling(
    (($ContextTokenCeiling * $PromptPriceMicroUsdPerMillionTokens) +
        ($EffectiveTokenLimit * $CompletionPriceMicroUsdPerMillionTokens)) / 1000000.0
))
$ReservationMicroUsd = [long]([Math]::Ceiling($BaseReservationMicroUsd * 1.05))
$ExpectedBaseReservationMicroUsd = 65920L
$ExpectedReservationMicroUsd = 69216L
$FixedModel = "deepseek-v4-flash-0731"
$FixedEndpoint = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions"
$BudgetMode = "disabled_by_user"
$BudgetStatus = "DISABLED_BY_USER"
$OutputTokenLimitMode = "provider_default"
$ModelPricingSource = "https://openrouter.ai/deepseek/deepseek-v4-flash-0731"
$ProviderPolicySource = "https://openrouter.ai/docs/guides/routing/provider-selection"
$ByokPricingSource = "https://openrouter.ai/docs/guides/overview/auth/byok"
$UsageAccountingSource = "https://openrouter.ai/docs/cookbook/administration/usage-accounting"
$SecretCanary = "ADVISORY_SECRET_CANARY_503_DO_NOT_FORWARD"

if ($BaseReservationMicroUsd -ne $ExpectedBaseReservationMicroUsd -or
    $ReservationMicroUsd -ne $ExpectedReservationMicroUsd) {
    throw "Budget reservation constants are inconsistent."
}

function Write-Utf8File {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [AllowEmptyString()] [string]$Content
    )

    [System.IO.File]::WriteAllText(
        $Path,
        $Content,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] $Value
    )

    Write-Utf8File -Path $Path -Content ($Value | ConvertTo-Json -Depth 20)
}

function Write-JsonAtomic {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] $Value
    )

    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $directory)
    }
    $temporaryPath = Join-Path $directory ("." + [System.IO.Path]::GetFileName($Path) + "." + [guid]::NewGuid().ToString("N") + ".tmp")
    try {
        Write-Utf8File -Path $temporaryPath -Content ($Value | ConvertTo-Json -Depth 20)
        $temporaryStream = [System.IO.File]::Open(
            $temporaryPath,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::Read
        )
        try {
            $temporaryStream.Flush($true)
        } finally {
            $temporaryStream.Dispose()
        }
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            $backupPath = Join-Path $directory ("." + [System.IO.Path]::GetFileName($Path) + "." + [guid]::NewGuid().ToString("N") + ".bak")
            [System.IO.File]::Replace($temporaryPath, $Path, $backupPath, $true)
            if (Test-Path -LiteralPath $backupPath -PathType Leaf) {
                Remove-Item -LiteralPath $backupPath -Force
            }
        } else {
            [System.IO.File]::Move($temporaryPath, $Path)
        }
    } finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

function Get-Sha256 {
    param([Parameter(Mandatory)] [string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Require-File {
    param([Parameter(Mandatory)] [string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required file is missing: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Require-Directory {
    param([Parameter(Mandatory)] [string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "Required directory is missing: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function New-BudgetLedgerValue {
    param(
        [Parameter(Mandatory)] [string]$Scope,
        [long]$InitialReservedMicroUsd = 0L
    )

    $reservations = @()
    $reservationCount = 0
    if ($InitialReservedMicroUsd -gt 0) {
        $reservationCount = 1
        $reservations = @([ordered]@{
            sequence = 1
            attempt_id = "synthetic-preflight-budget-seed"
            reserved_micro_usd = $InitialReservedMicroUsd
            state = "HELD_NO_REFUND"
            charged_micro_usd = $null
            released_micro_usd = 0
            settlement_status = "PENDING"
        })
    }
    return [ordered]@{
        schema_version = "advisory-ai-budget-ledger.v1"
        scope = $Scope
        cap_micro_usd = $BudgetCapMicroUsd
        reservation_micro_usd = $ReservationMicroUsd
        reserved_micro_usd = $InitialReservedMicroUsd
        reservation_count = $reservationCount
        model = $FixedModel
        context_token_ceiling = $ContextTokenCeiling
        max_output_tokens = $EffectiveTokenLimit
        prompt_price_micro_usd_per_million_tokens = $PromptPriceMicroUsdPerMillionTokens
        completion_price_micro_usd_per_million_tokens = $CompletionPriceMicroUsdPerMillionTokens
        request_price_micro_usd = $RequestPriceMicroUsd
        byok_fee_basis_points = $ByokFeeBasisPoints
        settlement_policy = "TRUSTED_TERMINAL_USAGE_OR_HOLD"
        reservations = $reservations
    }
}

function Reserve-AcceptanceBudget {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Scope,
        [Parameter(Mandatory)] [string]$AttemptId,
        [long]$InitialReservedMicroUsd = 0L
    )

    $lockPath = "$Path.write.lock"
    $lockDirectory = Split-Path -Parent $lockPath
    if (-not (Test-Path -LiteralPath $lockDirectory -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $lockDirectory)
    }
    try {
        $writeLock = [System.IO.File]::Open(
            $lockPath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
    } catch [System.IO.IOException] {
        throw "Budget ledger is locked by another runner: $Path"
    }

    try {
        $initializedMarkerPath = "$Path.initialized"
        $ledgerExists = Test-Path -LiteralPath $Path -PathType Leaf
        if (-not $ledgerExists -and (Test-Path -LiteralPath $initializedMarkerPath -PathType Leaf)) {
            throw "Budget ledger disappeared after initialization; refusing to reset it: $Path"
        }
        if (-not $ledgerExists) {
            Write-JsonAtomic -Path $Path -Value (New-BudgetLedgerValue -Scope $Scope -InitialReservedMicroUsd $InitialReservedMicroUsd)
        }
        if ((Get-Item -LiteralPath $Path).Length -gt 131072) {
            throw "Budget ledger exceeds its bounded size."
        }
        $ledger = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
        if ([string]$ledger.schema_version -ne "advisory-ai-budget-ledger.v1" -or
            [string]$ledger.scope -ne $Scope -or
            [long]$ledger.cap_micro_usd -ne $BudgetCapMicroUsd -or
            [long]$ledger.reservation_micro_usd -ne $ReservationMicroUsd -or
            [string]$ledger.model -ne $FixedModel -or
            [long]$ledger.context_token_ceiling -ne $ContextTokenCeiling -or
            [long]$ledger.max_output_tokens -ne $EffectiveTokenLimit -or
            [long]$ledger.prompt_price_micro_usd_per_million_tokens -ne $PromptPriceMicroUsdPerMillionTokens -or
            [long]$ledger.completion_price_micro_usd_per_million_tokens -ne $CompletionPriceMicroUsdPerMillionTokens -or
            [long]$ledger.request_price_micro_usd -ne $RequestPriceMicroUsd -or
            [long]$ledger.byok_fee_basis_points -ne $ByokFeeBasisPoints -or
            [string]$ledger.settlement_policy -ne "TRUSTED_TERMINAL_USAGE_OR_HOLD") {
            throw "Budget ledger policy does not match the frozen preflight policy."
        }
        if (-not (Test-Path -LiteralPath $initializedMarkerPath -PathType Leaf)) {
            $markerBytes = [System.Text.UTF8Encoding]::new($false).GetBytes("advisory-ai-budget-ledger.v1`n")
            $markerStream = [System.IO.File]::Open(
                $initializedMarkerPath,
                [System.IO.FileMode]::CreateNew,
                [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::None
            )
            try {
                $markerStream.Write($markerBytes, 0, $markerBytes.Length)
                $markerStream.Flush($true)
            } finally {
                $markerStream.Dispose()
            }
        }

        $reserved = [long]$ledger.reserved_micro_usd
        if ($reserved -lt 0 -or $reserved -gt $BudgetCapMicroUsd) {
            throw "Budget ledger has an invalid reserved amount."
        }
        if (@($ledger.reservations | Where-Object { [string]$_.attempt_id -eq $AttemptId }).Count -ne 0) {
            throw "Budget attempt id is already present in the ledger: $AttemptId"
        }

        $remainingBefore = $BudgetCapMicroUsd - $reserved
        if ($remainingBefore -lt $ReservationMicroUsd) {
            return [pscustomobject]@{
                Allowed = $false
                Status = "INCOMPLETE_BUDGET_EXHAUSTED"
                RemainingBeforeMicroUsd = $remainingBefore
                RemainingAfterMicroUsd = $remainingBefore
                ReservationSequence = $null
            }
        }

        $sequence = [int]$ledger.reservation_count + 1
        $remainingAfter = $remainingBefore - $ReservationMicroUsd
        $ledger.reserved_micro_usd = $reserved + $ReservationMicroUsd
        $ledger.reservation_count = $sequence
        $ledger.reservations = @($ledger.reservations) + @([pscustomobject][ordered]@{
            sequence = $sequence
            attempt_id = $AttemptId
            reserved_micro_usd = $ReservationMicroUsd
            state = "HELD_NO_REFUND"
            charged_micro_usd = $null
            released_micro_usd = 0
            settlement_status = "PENDING"
        })
        Write-JsonAtomic -Path $Path -Value $ledger

        return [pscustomobject]@{
            Allowed = $true
            Status = "RESERVED_HELD_NO_REFUND"
            RemainingBeforeMicroUsd = $remainingBefore
            RemainingAfterMicroUsd = $remainingAfter
            ReservationSequence = $sequence
        }
    } finally {
        $writeLock.Dispose()
    }
}

function Test-NonnegativeJsonInteger {
    param($Value)

    return $null -ne $Value -and
        ($Value -is [int] -or $Value -is [long]) -and
        [long]$Value -ge 0
}

function Settle-AcceptanceBudget {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Scope,
        [Parameter(Mandatory)] [string]$AttemptId,
        $Settlement
    )

    $lockPath = "$Path.write.lock"
    try {
        $writeLock = [System.IO.File]::Open(
            $lockPath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
    } catch [System.IO.IOException] {
        throw "Budget ledger is locked by another runner: $Path"
    }

    try {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or
            -not (Test-Path -LiteralPath "$Path.initialized" -PathType Leaf)) {
            throw "Budget ledger state is missing during settlement: $Path"
        }
        if ((Get-Item -LiteralPath $Path).Length -gt 131072) {
            throw "Budget ledger exceeds its bounded size."
        }
        $ledger = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
        if ([string]$ledger.schema_version -ne "advisory-ai-budget-ledger.v1" -or
            [string]$ledger.scope -ne $Scope -or
            [long]$ledger.cap_micro_usd -ne $BudgetCapMicroUsd -or
            [long]$ledger.reservation_micro_usd -ne $ReservationMicroUsd -or
            [string]$ledger.model -ne $FixedModel -or
            [string]$ledger.settlement_policy -ne "TRUSTED_TERMINAL_USAGE_OR_HOLD") {
            throw "Budget ledger policy does not match during settlement."
        }
        $entries = @($ledger.reservations | Where-Object { [string]$_.attempt_id -eq $AttemptId })
        if ($entries.Count -ne 1 -or [string]$entries[0].state -ne "HELD_NO_REFUND") {
            throw "Budget reservation is missing or already settled: $AttemptId"
        }
        $entry = $entries[0]

        $known = $null -ne $Settlement -and
            [string]$Settlement.schema_version -eq "advisory-ai-settlement-observation.v1" -and
            [string]$Settlement.attempt_id -eq $AttemptId -and
            [string]$Settlement.status -eq "KNOWN" -and
            [string]$Settlement.request_model -eq $FixedModel -and
            [string]$Settlement.response_model -eq $FixedModel -and
            [bool]$Settlement.terminal_done -and
            [string]$Settlement.response_id_sha256 -match "^[0-9a-f]{64}$" -and
            (Test-NonnegativeJsonInteger $Settlement.prompt_tokens) -and
            (Test-NonnegativeJsonInteger $Settlement.completion_tokens) -and
            (Test-NonnegativeJsonInteger $Settlement.total_tokens) -and
            (Test-NonnegativeJsonInteger $Settlement.reported_cost_micro_usd) -and
            (Test-NonnegativeJsonInteger $Settlement.price_ceiling_micro_usd) -and
            (Test-NonnegativeJsonInteger $Settlement.charge_micro_usd)

        if ($known) {
            $promptTokens = [long]$Settlement.prompt_tokens
            $completionTokens = [long]$Settlement.completion_tokens
            $totalTokens = [long]$Settlement.total_tokens
            $reportedCost = [long]$Settlement.reported_cost_micro_usd
            $reportedCeiling = [long]$Settlement.price_ceiling_micro_usd
            $charge = [long]$Settlement.charge_micro_usd
            $baseCeiling = [long][Math]::Ceiling(
                (($promptTokens * $PromptPriceMicroUsdPerMillionTokens) +
                    ($completionTokens * $CompletionPriceMicroUsdPerMillionTokens)) / 1000000.0
            )
            $hostCeiling = [long][Math]::Ceiling($baseCeiling * 1.05)
            $known = $totalTokens -eq ($promptTokens + $completionTokens) -and
                $totalTokens -le $ContextTokenCeiling -and
                $completionTokens -le $EffectiveTokenLimit -and
                $reportedCeiling -eq $hostCeiling -and
                $charge -eq [Math]::Max($reportedCost, $hostCeiling) -and
                $charge -le $ReservationMicroUsd
        }

        if ($known) {
            $released = $ReservationMicroUsd - $charge
            $ledger.reserved_micro_usd = [long]$ledger.reserved_micro_usd - $released
            $entry.state = "SETTLED"
            $entry.charged_micro_usd = $charge
            $entry.released_micro_usd = $released
            $entry.settlement_status = "TRUSTED_TERMINAL_USAGE"
            $status = "SETTLED"
            $reason = "TRUSTED_TERMINAL_USAGE"
        } else {
            $released = 0L
            $charge = $ReservationMicroUsd
            $entry.state = "HELD_UNKNOWN"
            $entry.charged_micro_usd = $null
            $entry.released_micro_usd = 0
            $entry.settlement_status = if ($null -eq $Settlement) { "NO_TERMINAL_OBSERVATION" } else { "UNTRUSTED_OR_UNKNOWN_USAGE" }
            $status = "HELD_UNKNOWN"
            $reason = if ($null -eq $Settlement) { "NO_TERMINAL_OBSERVATION" } else { [string]$Settlement.reason }
        }
        Write-JsonAtomic -Path $Path -Value $ledger
        return [pscustomobject]@{
            Status = $status
            Reason = $reason
            ChargedMicroUsd = $charge
            ReleasedMicroUsd = $released
            RemainingAfterMicroUsd = $BudgetCapMicroUsd - [long]$ledger.reserved_micro_usd
        }
    } finally {
        $writeLock.Dispose()
    }
}

function Invoke-Docker {
    param(
        [Parameter(Mandatory)] [string[]]$Arguments,
        [switch]$AllowFailure
    )

    $output = @(& $script:DockerPath @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "Docker command failed (exit $exitCode): $($output -join [Environment]::NewLine)"
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $output
    }
}

function Get-WireObservation {
    param([Parameter(Mandatory)] [string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [pscustomobject]@{
            Observed = $false
            ModelBefore = $null
            ModelAfter = $null
            Stream = $null
            ToolNames = @()
            MaxTokensBefore = $null
            MaxTokensAfter = $null
            MaxTokensAfterPresent = $false
            ClampApplied = $false
            OutputTokenLimitMode = $null
            ProviderPolicyEnforced = $false
            ProviderFieldPresent = $null
            BudgetMode = $null
            TextOnlyInput = $false
            UnsafeFieldsFixtureInjected = $false
            NBefore = $null
            NAfter = $null
            BestOfBeforePresent = $false
            BestOfAfterPresent = $false
            MaxOutputTokensBeforePresent = $false
            MaxOutputTokensAfterPresent = $false
        }
    }

    $wire = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    return [pscustomobject]@{
        Observed = $true
        ModelBefore = [string]$wire.model_before
        ModelAfter = [string]$wire.model_after
        Stream = [bool]$wire.stream
        ToolNames = @($wire.tool_names | ForEach-Object { [string]$_ })
        MaxTokensBefore = if ($wire.max_tokens_before_present) { [int]$wire.max_tokens_before } else { $null }
        MaxTokensAfter = if ($wire.max_tokens_after_present) { [int]$wire.max_tokens_after } else { $null }
        MaxTokensAfterPresent = [bool]$wire.max_tokens_after_present
        ClampApplied = [bool]$wire.max_tokens_clamp_applied
        OutputTokenLimitMode = [string]$wire.output_token_limit_mode
        ProviderPolicyEnforced = [bool]$wire.provider_policy_enforced
        ProviderFieldPresent = [bool]$wire.provider_field_present
        BudgetMode = [string]$wire.budget_mode
        TextOnlyInput = [bool]$wire.text_only_input
        UnsafeFieldsFixtureInjected = [bool]$wire.unsafe_fields_fixture_injected
        NBefore = if ($null -ne $wire.n_before) { [int]$wire.n_before } else { $null }
        NAfter = if ($null -ne $wire.n_after) { [int]$wire.n_after } else { $null }
        BestOfBeforePresent = [bool]$wire.best_of_before_present
        BestOfAfterPresent = [bool]$wire.best_of_after_present
        MaxOutputTokensBeforePresent = [bool]$wire.max_output_tokens_before_present
        MaxOutputTokensAfterPresent = [bool]$wire.max_output_tokens_after_present
    }
}

function Read-OptionalJson {
    param([Parameter(Mandatory)] [string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $null
    }
    if ((Get-Item -LiteralPath $Path).Length -gt 131072) {
        throw "Observation exceeds its bounded size: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Get-QwenResultObservation {
    param([Parameter(Mandatory)] [string]$StdoutPath)

    $text = [System.IO.File]::ReadAllText($StdoutPath)
    $match = [regex]::Match($text, "(?ms)^stdout\r?\n(?<payload>.*?)\r?\nstderr\r?\n")
    if (-not $match.Success -or [string]::IsNullOrWhiteSpace($match.Groups["payload"].Value)) {
        return [pscustomobject]@{ State = "UNOBSERVABLE"; Parsed = $false }
    }
    try {
        $parsed = $match.Groups["payload"].Value.Trim() | ConvertFrom-Json
    } catch {
        return [pscustomobject]@{ State = "UNOBSERVABLE"; Parsed = $false }
    }

    $tokens = @()
    $observedError = $false
    foreach ($result in @($parsed)) {
        $schemaVersion = $result.PSObject.Properties["schema_version"]
        if ($null -ne $schemaVersion -and [string]$schemaVersion.Value -eq "ai-advice-output.v1") {
            return [pscustomobject]@{ State = "SUCCESS"; Parsed = $true; Advice = $result }
        }
        $payloadProperty = $result.PSObject.Properties["result"]
        if ($null -ne $payloadProperty -and $null -ne $payloadProperty.Value) {
            $payload = $payloadProperty.Value
            if ($payload -is [string]) {
                try { $payload = $payload | ConvertFrom-Json } catch { $payload = $null }
            }
            if ($null -ne $payload) {
                $payloadSchema = $payload.PSObject.Properties["schema_version"]
                if ($null -ne $payloadSchema -and [string]$payloadSchema.Value -eq "ai-advice-output.v1") {
                    return [pscustomobject]@{ State = "SUCCESS"; Parsed = $true; Advice = $payload }
                }
            }
        }
        foreach ($name in @("type", "subtype", "code", "error_type")) {
            $property = $result.PSObject.Properties[$name]
            if ($null -ne $property) { $tokens += [string]$property.Value }
        }
        $isErrorProperty = $result.PSObject.Properties["is_error"]
        if ($null -ne $isErrorProperty -and [bool]$isErrorProperty.Value) { $observedError = $true }
        $errorProperty = $result.PSObject.Properties["error"]
        if ($null -ne $errorProperty) {
            $observedError = $true
            if ($errorProperty.Value -is [string]) {
                $tokens += [string]$errorProperty.Value
            } elseif ($null -ne $errorProperty.Value) {
                foreach ($name in @("type", "code", "name", "message")) {
                    $property = $errorProperty.Value.PSObject.Properties[$name]
                    if ($null -ne $property) { $tokens += [string]$property.Value }
                }
            }
        }
    }
    $signal = ($tokens -join " ").ToLowerInvariant()
    if ($signal -match "tool" -and $signal -match "unknown|unavailable|excluded|disabled|limit|reject|not") {
        return [pscustomobject]@{ State = "TOOL_REJECTED"; Parsed = $true }
    }
    if ($signal -match "schema|structured|validation|invalid.*json|json.*parse|parse.*json|json.*syntax|unexpected.*json") {
        return [pscustomobject]@{ State = "STRUCTURED_VALIDATION_REJECTED"; Parsed = $true }
    }
    if ($observedError -or $tokens.Count -gt 0) {
        return [pscustomobject]@{ State = "ERROR"; Parsed = $true }
    }
    return [pscustomobject]@{ State = "UNOBSERVABLE"; Parsed = $true }
}

function Save-QwenAdvice {
    param($Observation, [Parameter(Mandatory)] [string]$OutputDirectory)

    if ($Observation.State -ne "SUCCESS" -or $null -eq $Observation.Advice) {
        return $null
    }
    $path = Join-Path $OutputDirectory "advice-output.json"
    Write-JsonFile $path $Observation.Advice
    return Get-Sha256 $path
}

function Read-SafeEvents {
    param([Parameter(Mandatory)] [string]$Path)

    $events = @()
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $events
    }
    foreach ($line in [System.IO.File]::ReadLines($Path)) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $event = $line | ConvertFrom-Json
        $names = @($event.PSObject.Properties.Name)
        $expected = @("schema_version", "attempt_id", "sequence", "origin", "action_type", "allowed", "blocked")
        if (@($names | Where-Object { $_ -notin $expected }).Count -ne 0) {
            throw "Unsafe or unknown event field was emitted."
        }
        if ([string]::IsNullOrWhiteSpace([string]$event.action_type)) {
            throw "An action event has no action_type."
        }
        if ($event.origin -notin @("qwen_request", "fake_provider", "model_response", "relay_policy")) {
            throw "An action event has an unknown origin."
        }
        $events += $event
    }
    return $events
}

function Resolve-FrozenArtifact {
    param([string]$RelativePath, [string]$ExpectedSha256)
    if ([System.IO.Path]::IsPathRooted($RelativePath) -or $ExpectedSha256 -notmatch "^[0-9a-f]{64}$") {
        throw "Invalid frozen artifact contract."
    }
    $path = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $RelativePath))
    if (-not $path.StartsWith($RepoRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) { throw "Frozen artifact escapes the repository." }
    $path = Require-File $path
    if ((Get-Sha256 $path) -ne $ExpectedSha256) { throw "Frozen artifact hash mismatch: $RelativePath" }
    return $path
}

function Test-TrueJsonBoolean {
    param($Value)

    return $Value -is [bool] -and $Value -eq $true
}

function New-DisabledBudgetAuthorization {
    param([Parameter(Mandatory)] [string]$AttemptId)

    return [pscustomobject][ordered]@{
        schema_version = "advisory-ai-budget-authorization.v1"
        attempt_id = $AttemptId
        allowed = $true
        budget_mode = $BudgetMode
        status = $BudgetStatus
        model = $FixedModel
        max_output_tokens = $null
        output_token_limit_mode = $OutputTokenLimitMode
    }
}

function Read-LiveControl {
    param([string]$Path, [string]$SelectedAttemptId)
    $path = Require-File $Path
    if ((Get-Item -LiteralPath $path).Length -gt 262144) { throw "Live control exceeds 262144 bytes." }
    $control = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([string]$control.schema_version -ne "advisory-ai-live-control.v1" -or
        [string]$control.release_status -ne "RELEASED" -or -not (Test-TrueJsonBoolean $control.corpus_complete) -or
        -not (Test-TrueJsonBoolean $control.oracles_frozen) -or
        -not (Test-TrueJsonBoolean $control.expert_calibration_passed) -or
        -not (Test-TrueJsonBoolean $control.offline_preflight_passed) -or [string]$control.model -ne $FixedModel -or
        [string]$control.endpoint -ne $FixedEndpoint -or
        [string]$control.qwen_version -ne $ExpectedQwenVersion -or [string]$control.image_reference -ne $ImageReference -or
        [string]$control.budget_mode -ne $BudgetMode -or [string]$control.budget_status -ne $BudgetStatus -or
        $null -ne $control.max_output_tokens -or [string]$control.output_token_limit_mode -ne $OutputTokenLimitMode -or
        [string]$control.methodology_sha256 -ne (Get-Sha256 $MethodologyPath) -or
        [string]$control.prompt_sha256 -ne (Get-Sha256 $PromptPath) -or
        [string]$control.schema_sha256 -ne (Get-Sha256 $SchemaPath) -or
        [string]$control.runner_sha256 -ne (Get-Sha256 $PSCommandPath) -or
        [string]$control.guarded_relay_sha256 -ne (Get-Sha256 $FixedRelayPath) -or
        [string]$control.run_live_sha256 -ne (Get-Sha256 $RunLivePath)) {
        throw "Live control does not match the frozen runtime or gates."
    }
    [void](Resolve-FrozenArtifact $control.corpus_manifest_path $control.corpus_manifest_sha256)
    [void](Resolve-FrozenArtifact $control.oracle_manifest_path $control.oracle_manifest_sha256)
    [void](Resolve-FrozenArtifact $control.calibration_record_path $control.calibration_record_sha256)
    $preflightPath = Resolve-FrozenArtifact $control.preflight_manifest_path $control.preflight_manifest_sha256
    $preflight = Get-Content -LiteralPath $preflightPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not (Test-TrueJsonBoolean $preflight.harness_preflight_pass) -or [long]$preflight.live_requests -ne 0 -or
        [string]$preflight.budget.mode -ne $BudgetMode -or [string]$preflight.budget.status -ne $BudgetStatus -or
        $null -ne $preflight.budget.max_output_tokens -or
        [string]$preflight.budget.output_token_limit_mode -ne $OutputTokenLimitMode -or
        [string]$preflight.package_hashes.guarded_relay_sha256 -ne (Get-Sha256 $FixedRelayPath)) {
        throw "Frozen preflight does not prove this guarded relay."
    }
    $attempts = @($control.attempts)
    if ($attempts.Count -ne 60 -or @($attempts.attempt_id | Select-Object -Unique).Count -ne 60) {
        throw "Live control must contain exactly 60 unique attempts."
    }
    $ordinals = @($attempts | ForEach-Object {
        if (-not (Test-NonnegativeJsonInteger $_.ordinal)) { throw "Invalid attempt ordinal." }; [int]$_.ordinal
    } | Sort-Object)
    if (($ordinals -join ",") -ne ((1..60) -join ",")) { throw "Attempt ordinals must be 1 through 60." }
    $evidencePaths = @{}
    foreach ($attempt in $attempts) {
        if ([string]::IsNullOrWhiteSpace([string]$attempt.attempt_id) -or
            [string]::IsNullOrWhiteSpace([string]$attempt.case_id) -or
            -not (Test-NonnegativeJsonInteger $attempt.repeat_index) -or [int]$attempt.repeat_index -notin @(1, 2)) {
            throw "Invalid live attempt entry."
        }
        $evidence = Resolve-FrozenArtifact $attempt.evidence_path $attempt.evidence_sha256
        if ((Get-Item -LiteralPath $evidence).Length -gt 262144) { throw "Live evidence exceeds 262144 bytes." }
        $evidencePaths[[string]$attempt.attempt_id] = $evidence
    }
    $groups = @($attempts | Group-Object case_id)
    if ($groups.Count -ne 30) { throw "Live control must contain exactly 30 cases." }
    foreach ($group in $groups) {
        $repeats = @($group.Group.repeat_index | ForEach-Object { [int]$_ } | Sort-Object)
        $hashes = @($group.Group.evidence_sha256 | Select-Object -Unique)
        if ($group.Count -ne 2 -or ($repeats -join ",") -ne "1,2" -or $hashes.Count -ne 1) {
            throw "Each case must have repeats 1 and 2 with identical evidence."
        }
    }
    $selected = @($attempts | Where-Object { [string]$_.attempt_id -eq $SelectedAttemptId })
    if ($selected.Count -ne 1) { throw "Selected attempt is outside the frozen plan." }
    return [pscustomobject]@{ Control = $control; ControlPath = $path; Attempt = $selected[0];
        EvidencePath = $evidencePaths[$SelectedAttemptId] }
}

function Invoke-HostAttempt {
    param([ValidateSet("preflight", "live")][string]$ProviderMode, [string]$HostAttemptId,
        [string]$HostEvidencePath, [string]$HostTempRoot,
        [string]$HostCredentialEnvFile, [string]$ReleaseControlSha256, [string]$CaseId,
        [int]$Ordinal = 0, [int]$RepeatIndex = 0)

    $authorization = New-DisabledBudgetAuthorization $HostAttemptId
    $authorizationPath = Join-Path $HostTempRoot "host-budget-authorization.json"
    Write-JsonFile $authorizationPath $authorization
    $relayOutput = Join-Path $HostTempRoot "host-relay-output"
    [void](New-Item -ItemType Directory -Path $relayOutput)
    $networkName = "ltv-ai-host-$([guid]::NewGuid().ToString('N').Substring(0, 10))"
    $relayName, $qwenName = "$networkName-relay", "$networkName-qwen"
    $relayId, $qwenId, $networkCreated = $null, $null, $false
    $qwenStdout, $qwenStderr = (Join-Path $HostTempRoot "host-qwen.stdout"), (Join-Path $HostTempRoot "host-qwen.stderr")
    $outerTimeout = $false
    $qwenOomKilled = $null
    $attemptStarted = [DateTime]::UtcNow
    try {
        [void](Invoke-Docker -Arguments @("network", "create", "--internal", $networkName)); $networkCreated = $true
        $relayArgs = @("create", "--pull", "never", "--name", $relayName, "--network", $networkName,
            "--network-alias", "openrouter-relay", "--read-only", "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges", "--pids-limit", "64", "--memory", "1g", "--cpus", "1",
            "--user", "65532:65532", "--tmpfs", "/tmp:rw,nosuid,nodev,size=32m,mode=1777",
            "--env", "ADVISORY_RELAY_MODE=$ProviderMode", "--env", "ADVISORY_RELAY_READY_PATH=/out/relay-ready",
            "--mount", "type=bind,src=$authorizationPath,dst=/probe/budget-authorization.json,readonly",
            "--mount", "type=bind,src=$FixedRelayPath,dst=/probe/fixed-openrouter-relay.mjs,readonly",
            "--mount", "type=bind,src=$relayOutput,dst=/out")
        if ($ProviderMode -eq "preflight") {
            $relayArgs += @("--env", "ACCEPTANCE_SCENARIO=valid", "--env", "ACCEPTANCE_ATTEMPT_ID=$HostAttemptId",
                "--env", "ADVISORY_PREFLIGHT_NETWORK_LISTEN=1", "--env", "ADVISORY_PREFLIGHT_INJECT_UNSAFE_FIELDS=1",
                "--env", "ADVISORY_FAKE_READY_PATH=/out/fake-ready",
                "--mount", "type=bind,src=$fakeEndpointPath,dst=/probe/fake-openai.mjs,readonly",
                "--entrypoint", "/usr/local/bin/node", $ImageReference, "/probe/fake-openai.mjs")
        } else {
            $relayArgs += @("--env-file", $HostCredentialEnvFile, "--entrypoint", "/usr/local/bin/node",
                $ImageReference, "/probe/fixed-openrouter-relay.mjs")
        }
        $created = Invoke-Docker -Arguments $relayArgs
        $relayId = [string]($created.Output | Where-Object { "$_" -match "^[0-9a-f]{64}$" } | Select-Object -Last 1)
        if (-not $relayId) { throw "Relay container was not created." }
        if ($ProviderMode -eq "live") { [void](Invoke-Docker -Arguments @("network", "connect", "bridge", $relayId)) }
        [void](Invoke-Docker -Arguments @("start", $relayId))
        $readyName = if ($ProviderMode -eq "preflight") { "fake-ready" } else { "relay-ready" }
        $deadline = [DateTime]::UtcNow.AddSeconds(10)
        while (-not (Test-Path -LiteralPath (Join-Path $relayOutput $readyName)) -and [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 100
        }
        if (-not (Test-Path -LiteralPath (Join-Path $relayOutput $readyName))) { throw "Guarded relay did not become ready." }
        $qwenArgs = @("create", "--pull", "never", "--name", $qwenName, "--network", $networkName,
            "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--pids-limit", "128",
            "--memory", "768m", "--cpus", "1", "--user", "65532:65532",
            "--tmpfs", "/home/qwen:rw,noexec,nosuid,nodev,size=16m,mode=700,uid=65532,gid=65532",
            "--tmpfs", "/runtime:rw,noexec,nosuid,nodev,size=16m,mode=700,uid=65532,gid=65532",
            "--tmpfs", "/work:rw,noexec,nosuid,nodev,size=16m,mode=700,uid=65532,gid=65532",
            "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m,mode=1777",
            "--mount", "type=bind,src=$PackageRoot,dst=/opt/qwen,readonly",
            "--mount", "type=bind,src=$RunLivePath,dst=/probe/run-live.sh,readonly",
            "--mount", "type=bind,src=$HostEvidencePath,dst=/input/evidence.json,readonly",
            "--mount", "type=bind,src=$PromptPath,dst=/input/system-prompt.md,readonly",
            "--mount", "type=bind,src=$SchemaPath,dst=/input/output.schema.json,readonly",
            "--entrypoint", "/bin/sh", $ImageReference, "/probe/run-live.sh")
        $created = Invoke-Docker -Arguments $qwenArgs
        $qwenId = [string]($created.Output | Where-Object { "$_" -match "^[0-9a-f]{64}$" } | Select-Object -Last 1)
        if (-not $qwenId) { throw "Qwen container was not created." }
        $process = Start-Process -FilePath $script:DockerPath -ArgumentList @("start", "--attach", $qwenId) `
            -RedirectStandardOutput $qwenStdout -RedirectStandardError $qwenStderr -WindowStyle Hidden -PassThru
        if (-not $process.WaitForExit(613000)) {
            $outerTimeout = $true; [void](Invoke-Docker -Arguments @("kill", $qwenId) -AllowFailure); [void]$process.WaitForExit(5000)
        }
        $qwenExit = [int]([string]((Invoke-Docker -Arguments @("inspect", "--format", "{{.State.ExitCode}}", $qwenId)).Output | Select-Object -Last 1))
        $oomState = [string]((Invoke-Docker -Arguments @("inspect", "--format", "{{.State.OOMKilled}}", $qwenId)).Output | Select-Object -Last 1)
        if ($oomState -notin @('true', 'false')) { throw 'Container OOM state unavailable.' }
        $qwenOomKilled = $oomState -eq 'true'
    } finally {
        if ($qwenId) { [void](Invoke-Docker -Arguments @("rm", "--force", $qwenId) -AllowFailure) }
        if ($relayId) { [void](Invoke-Docker -Arguments @("rm", "--force", $relayId) -AllowFailure) }
        if ($networkCreated) { [void](Invoke-Docker -Arguments @("network", "rm", $networkName) -AllowFailure) }
    }
    $settlementObservation = Read-OptionalJson (Join-Path $relayOutput "settlement-observation.json")
    $eventsPath = Join-Path $relayOutput "relay-events.jsonl"; $events = @(Read-SafeEvents $eventsPath)
    $upstreamCount = @($events | Where-Object { $_.origin -eq "relay_policy" -and $_.action_type -eq "provider_forward" -and $_.allowed }).Count
    $additional = @($events | Where-Object { $_.origin -eq "qwen_request" -and $_.action_type -eq "additional_model_request" })
    $additionalContained = @($additional | Where-Object { $_.allowed -ne $false -or $_.blocked -ne $true }).Count -eq 0
    $wire = Get-WireObservation (Join-Path $relayOutput "wire-observation.json")
    $provider = Read-OptionalJson (Join-Path $relayOutput "provider-observation.json")
    $relayObservation = Read-OptionalJson (Join-Path $relayOutput "relay-observation.json")
    $qwenResult = Get-QwenResultObservation $qwenStdout
    $adviceOutputSha256 = Save-QwenAdvice $qwenResult $OutputDirectory
    $providerBoundary = $ProviderMode -eq "live" -or ($null -ne $provider -and [int]$provider.request_count -eq 1 -and [bool]$provider.provider_policy_enforced)
    $budgetDisabled = $authorization.budget_mode -eq $BudgetMode -and $authorization.status -eq $BudgetStatus -and
        $wire.BudgetMode -eq $BudgetMode -and $null -ne $relayObservation -and
        [string]$relayObservation.budget_mode -eq $BudgetMode -and [string]$relayObservation.budget_status -eq $BudgetStatus
    $outputTokenLimitDefault = $wire.OutputTokenLimitMode -eq $OutputTokenLimitMode -and
        -not $wire.MaxTokensAfterPresent -and $null -eq $wire.MaxTokensAfter -and -not $wire.ClampApplied
    $boundaryPass = $upstreamCount -le 1 -and $additionalContained -and $wire.Observed -and
        $wire.ProviderPolicyEnforced -and -not $wire.ProviderFieldPresent -and
        $outputTokenLimitDefault -and $providerBoundary -and $budgetDisabled
    if ($ProviderMode -eq "preflight") { $boundaryPass = $boundaryPass -and $upstreamCount -eq 1 -and $qwenResult.State -eq "SUCCESS" }
    foreach ($name in @("relay-events.jsonl", "wire-observation.json", "relay-observation.json", "settlement-observation.json", "provider-observation.json")) {
        $source = Join-Path $relayOutput $name
        if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination (Join-Path $OutputDirectory $name) }
    }
    $result = [ordered]@{ schema_version = "advisory-ai-host-attempt.v1"; attempt_id = $HostAttemptId;
        case_id = $CaseId; ordinal = $Ordinal; repeat_index = $RepeatIndex; provider_mode = $ProviderMode;
        status = if ($upstreamCount -eq 0) { "INCOMPLETE_NO_PROVIDER_REQUEST" } elseif ($qwenResult.State -eq "SUCCESS") { "COMPLETED" } else { "FAILED" };
        host_boundary_pass = [bool]$boundaryPass; upstream_request_count = $upstreamCount;
        additional_request_count = $additional.Count; additional_requests_contained = [bool]$additionalContained;
        qwen_result = $qwenResult.State; advice_output_sha256 = $adviceOutputSha256;
        qwen_exit_code = $qwenExit; outer_timeout = $outerTimeout; qwen_oom_killed = $qwenOomKilled;
        attempt_duration_ms = [long]([DateTime]::UtcNow - $attemptStarted).TotalMilliseconds;
        response_wait_seconds = 600;
        max_output_tokens = $null; output_token_limit_mode = $OutputTokenLimitMode;
        wire_max_tokens_after = $wire.MaxTokensAfter; wire_max_tokens_after_present = [bool]$wire.MaxTokensAfterPresent;
        max_tokens_clamp_applied = [bool]$wire.ClampApplied;
        budget_mode = $BudgetMode; budget_status = $BudgetStatus; monetary_reservation_enabled = $false;
        monetary_settlement_enabled = $false; settlement_observation_present = $null -ne $settlementObservation;
        settlement_observation_status = if ($null -ne $settlementObservation) { [string]$settlementObservation.status } else { "MISSING" };
        live_requests = if ($ProviderMode -eq "live") { $upstreamCount } else { 0 };
        release_control_sha256 = $ReleaseControlSha256; qwen_internal_network_only = $true;
        real_credential_mounted_to_relay = $ProviderMode -eq "live"; real_credential_mounted_to_qwen = $false;
        real_credential_recorded = $false; guarded_relay_sha256 = Get-Sha256 $FixedRelayPath;
        evidence_sha256 = Get-Sha256 $HostEvidencePath; qwen_stdout_sha256 = Get-Sha256 $qwenStdout;
        qwen_stderr_sha256 = Get-Sha256 $qwenStderr; release_policy_status = if ($ProviderMode -eq "live") { "FROZEN_ATTEMPT_EXECUTED" } else { "NOT_EVALUATED_HOST_PREFLIGHT" } }
    Write-JsonFile (Join-Path $OutputDirectory "result.json") $result
    if (-not $boundaryPass) { throw "Host attempt boundary failed. See $OutputDirectory" }
    return [pscustomobject]$result
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$ProbeRoot = Join-Path $RepoRoot "build/ai-probe"
$PackageRoot = Require-Directory (Join-Path $RepoRoot "build/ai-runner/qwen-code-0.21.1/node_modules/@qwen-code/qwen-code")
$PackageJsonPath = Require-File (Join-Path $PackageRoot "package.json")
$RunProbePath = Require-File (Join-Path $ProbeRoot "run-probe.sh")
$RunLivePath = Require-File (Join-Path $ProbeRoot "run-live.sh")
$EvidencePath = Require-File (Join-Path $ProbeRoot "evidence.json")
$MethodologyPath = Require-File (Join-Path $RepoRoot "docs/advisory-ai-synthetic-acceptance-methodology-v1.md")
$PromptPath = Require-File (Join-Path $RepoRoot "docs/contracts/advice/v1/system-prompt.md")
$SchemaPath = Require-File (Join-Path $RepoRoot "docs/contracts/advice/v1/ai-advice-output.schema.json")
$FixedRelayPath = Require-File (Join-Path $ProbeRoot "fixed-openrouter-relay.mjs")

$packageJson = Get-Content -LiteralPath $PackageJsonPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ([string]$packageJson.version -ne $ExpectedQwenVersion) {
    throw "Expected Qwen Code $ExpectedQwenVersion, found $($packageJson.version)."
}

$binRelativePath = if ($packageJson.bin -is [string]) {
    [string]$packageJson.bin
} elseif ($null -ne $packageJson.bin.qwen) {
    [string]$packageJson.bin.qwen
} else {
    throw "Qwen package does not declare the qwen executable."
}
$CliPath = Require-File (Join-Path $PackageRoot $binRelativePath)

$docker = Get-Command docker -ErrorAction Stop
$script:DockerPath = $docker.Source
$imageInspect = Invoke-Docker -Arguments @("image", "inspect", "--format", "{{.Id}}", $ImageReference)
$observedImageId = [string]($imageInspect.Output | Select-Object -Last 1)
if ($observedImageId.Trim() -ne $ImageDigest) {
    throw "Cached image digest mismatch: $($observedImageId.Trim())."
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    if ($Mode -eq "LiveAttempt") { throw "LiveAttempt requires an explicit new OutputDirectory." }
    $name = if ($Mode -eq "HostPreflight") { "host-wiring-preflight-v1" } else { "runtime-boundary-v2" }
    $OutputDirectory = Join-Path $RepoRoot "build/ai-acceptance/v1/preflight-runtime/$name"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $RepoRoot $OutputDirectory
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) {
    throw "Refusing to overwrite preflight evidence: $OutputDirectory"
}
[void](New-Item -ItemType Directory -Path $OutputDirectory)

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $tempBase ("ltv-advisory-preflight-" + [guid]::NewGuid().ToString("N"))))
if (-not $tempRoot.StartsWith($tempBase, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Unexpected temporary path: $tempRoot"
}
[void](New-Item -ItemType Directory -Path $tempRoot)

$fakeEndpointPath = Join-Path $tempRoot "fake-openai.mjs"
$containerRunnerPath = Join-Path $tempRoot "container-run.sh"

$fakeEndpoint = @'
import fs from "node:fs";
import http from "node:http";
import { relayReady } from "/probe/fixed-openrouter-relay.mjs";

const scenario = process.env.ACCEPTANCE_SCENARIO;
const attemptId = process.env.ACCEPTANCE_ATTEMPT_ID;
const providerObservationPath = "/out/provider-observation.json";
const fixedModel = "deepseek-v4-flash-0731";
const allowedScenarios = new Set([
  "valid", "malformed", "timeout", "prohibited_tool", "retry", "conflicting_usage"
]);
if (!allowedScenarios.has(scenario)) throw new Error("Unknown preflight scenario");

let providerReceivedRequests = 0;
function readBounded(request, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let exceeded = false;
    request.on("data", chunk => {
      size += chunk.length;
      if (size > limit) exceeded = true;
      else if (!exceeded) chunks.push(chunk);
    });
    request.on("end", () => exceeded ? reject(new Error("bounded_size_exceeded")) : resolve(Buffer.concat(chunks)));
    request.on("error", reject);
  });
}

function messagesAreTextOnly(messages) {
  return Array.isArray(messages) && messages.length > 0 && messages.every(message => {
    if (!message || typeof message !== "object") return false;
    if (typeof message.content === "string") return true;
    return Array.isArray(message.content) && message.content.every(part =>
      part && typeof part === "object" && part.type === "text" && typeof part.text === "string"
    );
  });
}

function providerPolicyIsExact(body) {
  return body.model === fixedModel && !Object.hasOwn(body, "max_tokens") && body.n === 1 && body.stream === true &&
    body.parallel_tool_calls === false && !Object.hasOwn(body, "provider") &&
    !Object.hasOwn(body, "models") && !Object.hasOwn(body, "route") && !Object.hasOwn(body, "plugins") &&
    !Object.hasOwn(body, "web_search_options") && !Object.hasOwn(body, "modalities") &&
    !Object.hasOwn(body, "audio") && !Object.hasOwn(body, "image_config") &&
    !Object.hasOwn(body, "cache_control") && !Object.hasOwn(body, "max_completion_tokens") &&
    !Object.hasOwn(body, "max_output_tokens") && !Object.hasOwn(body, "best_of") &&
    messagesAreTextOnly(body.messages);
}

const validOutput = {
  schema_version: "ai-advice-output.v1",
  summary: "The saved analysis contains a bounded advisory signal.",
  hypotheses: [{
    rank: 1,
    observation: "The deterministic finding is present.",
    possible_explanation: "The measured condition may need a focused check.",
    recommended_check: "Re-run the same bounded scenario and compare the referenced evidence.",
    evidence_refs: ["analysis-result.json#/evidence/0"]
  }],
  recommendations: [{
    rank: 1,
    action: "Inspect the referenced evidence before changing the test.",
    rationale: "The advisory result must not replace the deterministic verdict.",
    evidence_refs: ["analysis-result.json#/evidence/0"]
  }],
  caveats: ["This is advisory output from a fake model endpoint."]
};

function streamToolCall(response, name, args, usageMode = "known") {
  response.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-cache",
    connection: "keep-alive"
  });
  const common = { id: `chatcmpl-${attemptId}`, object: "chat.completion.chunk", created: 1788652800, model: fixedModel };
  // SSE comments exercise transport size without changing the fake advice.
  if (scenario === "valid") response.write(":" + " ".repeat(2 * 1024 * 1024) + "\n\n");
  response.write(`data: ${JSON.stringify({ ...common, choices: [{ index: 0, delta: {
    role: "assistant",
    tool_calls: [{ index: 0, id: `call-${attemptId}`, type: "function", function: { name, arguments: args } }]
  }, finish_reason: null }] })}\n\n`);
  response.write(`data: ${JSON.stringify({ ...common, choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }] })}\n\n`);
  if (usageMode === "conflicting") {
    response.write(`data: ${JSON.stringify({ ...common, choices: [], usage: {
      prompt_tokens: 10, completion_tokens: 10, total_tokens: 20, cost: 0.000002
    } })}\n\n`);
    response.write(`data: ${JSON.stringify({ ...common, choices: [], usage: {
      prompt_tokens: 9, completion_tokens: 10, total_tokens: 19, cost: 0.000001
    } })}\n\n`);
  } else {
    response.write(`data: ${JSON.stringify({ ...common, choices: [], usage: {
      prompt_tokens: 10, completion_tokens: 10, total_tokens: 20, cost: 0.000002
    } })}\n\n`);
  }
  response.end("data: [DONE]\n\n");
}

const providerServer = http.createServer(async (request, response) => {
  if (request.method !== "POST" || request.url !== "/v1/chat/completions") {
    response.writeHead(404).end();
    return;
  }
  providerReceivedRequests += 1;
  let body;
  try {
    body = JSON.parse((await readBounded(request, 1048576)).toString("utf8"));
  } catch {
    response.writeHead(400).end();
    return;
  }
  const policyEnforced = providerPolicyIsExact(body);
  fs.writeFileSync(providerObservationPath, JSON.stringify({
    schema_version: "advisory-ai-provider-observation.v2",
    attempt_id: attemptId,
    request_count: providerReceivedRequests,
    model: typeof body.model === "string" ? body.model : null,
    max_tokens: Number.isInteger(body.max_tokens) ? body.max_tokens : null,
    max_tokens_present: Object.hasOwn(body, "max_tokens"),
    max_completion_tokens_present: Object.hasOwn(body, "max_completion_tokens"),
    n: Number.isInteger(body.n) ? body.n : null,
    best_of_present: Object.hasOwn(body, "best_of"),
    max_output_tokens_present: Object.hasOwn(body, "max_output_tokens"),
    provider_field_present: Object.hasOwn(body, "provider"),
    provider_policy_enforced: policyEnforced,
    text_only_input: messagesAreTextOnly(body.messages)
  }), { encoding: "utf8" });
  if (!policyEnforced) {
    response.writeHead(400).end();
    return;
  }

  if (scenario === "timeout") return;
  if (scenario === "retry") {
    response.writeHead(503, { "content-type": "application/json", "retry-after": "0" });
    response.end(JSON.stringify({ error: { message: "ADVISORY_SECRET_CANARY_503_DO_NOT_FORWARD", type: "preflight_retry" } }));
    return;
  }
  if (scenario === "prohibited_tool") {
    streamToolCall(response, "run_shell_command", "{}");
    return;
  }
  if (scenario === "malformed") {
    streamToolCall(response, "structured_output", "{\"malformed\":");
    return;
  }
  if (scenario === "conflicting_usage") {
    streamToolCall(response, "structured_output", JSON.stringify(validOutput), "conflicting");
    return;
  }

  const tool = Array.isArray(body.tools)
    ? body.tools.find(candidate => candidate?.function?.name === "structured_output")
    : null;
  if (!tool?.function?.parameters) {
    response.writeHead(400).end();
    return;
  }
  streamToolCall(response, "structured_output", JSON.stringify(validOutput));
});

const providerReady = new Promise((resolve, reject) => {
  providerServer.once("error", reject);
  providerServer.listen(18081, "127.0.0.1", resolve);
});
await Promise.all([relayReady, providerReady]);
fs.writeFileSync(process.env.ADVISORY_FAKE_READY_PATH ?? "/tmp/fake-ready", "ready", { encoding: "utf8" });
'@

$containerRunner = @'
#!/bin/bash
set -uo pipefail
mkdir -p /out
started_ms=$(date +%s%3N)
set +e
timeout --signal=TERM --kill-after=1s "${ACCEPTANCE_TIMEOUT_SECONDS}s" /bin/bash /probe/run-probe.sh > /out/qwen.stdout 2> /out/qwen.stderr
qwen_exit=$?
set -e
finished_ms=$(date +%s%3N)
printf '%s\n' "$qwen_exit" > /out/qwen.exit
printf '%s\n' "$((finished_ms - started_ms))" > /out/duration-ms
'@

Write-Utf8File -Path $fakeEndpointPath -Content $fakeEndpoint
Write-Utf8File -Path $containerRunnerPath -Content $containerRunner

$liveControlInfo = $null
if ($Mode -eq "LiveAttempt") {
    if (-not $ExecuteLive -or [string]::IsNullOrWhiteSpace($ReleaseControlFile) -or
        [string]::IsNullOrWhiteSpace($AttemptId) -or [string]::IsNullOrWhiteSpace($CredentialEnvFile)) {
        throw "LiveAttempt requires ExecuteLive, ReleaseControlFile, AttemptId, and CredentialEnvFile."
    }
    $liveControlInfo = Read-LiveControl $ReleaseControlFile $AttemptId
    $CredentialEnvFile = Require-File $CredentialEnvFile
    $EvidencePath = $liveControlInfo.EvidencePath
}

$inputHashes = [ordered]@{
    evidence_sha256 = Get-Sha256 $EvidencePath
    schema_sha256 = Get-Sha256 $SchemaPath
    system_prompt_sha256 = Get-Sha256 $PromptPath
}
$packageHashes = [ordered]@{
    package_json_sha256 = Get-Sha256 $PackageJsonPath
    cli_sha256 = Get-Sha256 $CliPath
    guarded_relay_sha256 = Get-Sha256 $FixedRelayPath
}

$runId = "preflight-" + [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssfffZ") + "-" + [guid]::NewGuid().ToString("N").Substring(0, 8)
$scenarios = @(
    [pscustomobject]@{ Name = "valid"; TimeoutSeconds = 15; Expected = "SUCCESS" },
    [pscustomobject]@{ Name = "malformed"; TimeoutSeconds = 15; Expected = "FAILED_WITH_REPAIR_CONTAINED" },
    [pscustomobject]@{ Name = "timeout"; TimeoutSeconds = 12; Expected = "TIMEOUT_CONTAINED" },
    [pscustomobject]@{ Name = "prohibited_tool"; TimeoutSeconds = 15; Expected = "ATTEMPTED_AND_CONTAINED" },
    [pscustomobject]@{ Name = "retry"; TimeoutSeconds = 15; Expected = "FAILED_WITH_RETRY_CONTAINED" },
    [pscustomobject]@{ Name = "conflicting_usage"; TimeoutSeconds = 15; Expected = "UNKNOWN_USAGE_OBSERVED" }
)

$results = @()
try {
    if ($Mode -eq "HostPreflight") {
        $hostId = "host-preflight-" + [guid]::NewGuid().ToString("N")
        $hostResult = Invoke-HostAttempt preflight $hostId $EvidencePath $tempRoot
        Write-JsonFile (Join-Path $OutputDirectory "manifest.json") ([ordered]@{
            schema_version = "advisory-ai-host-preflight-manifest.v1"; harness_preflight_pass = $hostResult.host_boundary_pass;
            live_requests = 0; live_release_enabled = $false; release_policy_status = "NOT_EVALUATED_HOST_PREFLIGHT";
            guarded_relay_sha256 = Get-Sha256 $FixedRelayPath; result = $hostResult })
        return
    }
    if ($Mode -eq "LiveAttempt") {
        $selected = $liveControlInfo.Attempt
        $liveResult = Invoke-HostAttempt live $AttemptId $liveControlInfo.EvidencePath `
            $tempRoot $CredentialEnvFile `
            (Get-Sha256 $liveControlInfo.ControlPath) ([string]$selected.case_id) `
            ([int]$selected.ordinal) ([int]$selected.repeat_index)
        Write-JsonFile (Join-Path $OutputDirectory "manifest.json") ([ordered]@{
            schema_version = "advisory-ai-live-attempt-manifest.v1"; attempt_id = $AttemptId;
            frozen_plan_attempt_count = 60; live_requests = $liveResult.live_requests;
            release_control_sha256 = Get-Sha256 $liveControlInfo.ControlPath; result = $liveResult })
        return
    }
    foreach ($scenario in $scenarios) {
        $attemptId = "$runId-$($scenario.Name)"
        $scenarioDirectory = Join-Path $OutputDirectory $scenario.Name
        [void](New-Item -ItemType Directory -Path $scenarioDirectory)
        $captureDirectory = Join-Path $tempRoot ("capture-" + $scenario.Name)
        [void](New-Item -ItemType Directory -Path $captureDirectory)

        $budgetAuthorization = New-DisabledBudgetAuthorization $attemptId
        $budgetAuthorizationPath = Join-Path $tempRoot ("budget-authorization-" + $scenario.Name + ".json")
        Write-JsonFile -Path $budgetAuthorizationPath -Value $budgetAuthorization

        $containerName = "ltv-ai-$($scenario.Name)-$([guid]::NewGuid().ToString('N').Substring(0, 10))"
        $injectUnsafeFields = if ($scenario.Name -eq "valid") { "1" } else { "0" }
        $containerId = $null
        $outerTimeout = $false
        $wallClock = [System.Diagnostics.Stopwatch]::StartNew()

        try {
            $createArguments = @(
                "create", "--pull", "never", "--name", $containerName,
                "--network", "none", "--read-only",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--pids-limit", "128", "--memory", "768m", "--cpus", "1",
                "--user", "65532:65532",
                "--tmpfs", "/home/qwen:rw,noexec,nosuid,nodev,size=16m,mode=700,uid=65532,gid=65532",
                "--tmpfs", "/runtime:rw,noexec,nosuid,nodev,size=16m,mode=700,uid=65532,gid=65532",
                "--tmpfs", "/work:rw,noexec,nosuid,nodev,size=16m,mode=700,uid=65532,gid=65532",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m,mode=1777",
                "--env", "ACCEPTANCE_SCENARIO=$($scenario.Name)",
                "--env", "ACCEPTANCE_ATTEMPT_ID=$attemptId",
                "--env", "ACCEPTANCE_TIMEOUT_SECONDS=$($scenario.TimeoutSeconds)",
                "--env", "ADVISORY_RELAY_MODE=preflight",
                "--env", "ADVISORY_PREFLIGHT_INJECT_UNSAFE_FIELDS=$injectUnsafeFields",
                "--mount", "type=bind,src=$PackageRoot,dst=/opt/qwen,readonly",
                "--mount", "type=bind,src=$RunProbePath,dst=/probe/run-probe.sh,readonly",
                "--mount", "type=bind,src=$EvidencePath,dst=/probe/evidence.json,readonly",
                "--mount", "type=bind,src=$PromptPath,dst=/probe/system-prompt.txt,readonly",
                "--mount", "type=bind,src=$SchemaPath,dst=/probe/output.schema.json,readonly",
                "--mount", "type=bind,src=$budgetAuthorizationPath,dst=/probe/budget-authorization.json,readonly",
                "--mount", "type=bind,src=$fakeEndpointPath,dst=/probe/fake-openai.mjs,readonly",
                "--mount", "type=bind,src=$FixedRelayPath,dst=/probe/fixed-openrouter-relay.mjs,readonly",
                "--mount", "type=bind,src=$containerRunnerPath,dst=/acceptance/container-run.sh,readonly",
                "--mount", "type=bind,src=$captureDirectory,dst=/out",
                "--entrypoint", "/bin/bash",
                $ImageReference,
                "/acceptance/container-run.sh"
            )
            $create = Invoke-Docker -Arguments $createArguments
            $containerId = [string]($create.Output | Where-Object { "$_" -match "^[0-9a-f]{64}$" } | Select-Object -Last 1)
            if ([string]::IsNullOrWhiteSpace($containerId)) {
                throw "Docker create returned no container id."
            }

            $dockerStdout = Join-Path $tempRoot "$($scenario.Name)-docker.stdout"
            $dockerStderr = Join-Path $tempRoot "$($scenario.Name)-docker.stderr"
            $startProcess = Start-Process -FilePath $script:DockerPath -ArgumentList @("start", "--attach", $containerId) `
                -RedirectStandardOutput $dockerStdout -RedirectStandardError $dockerStderr -PassThru
            if (-not $startProcess.WaitForExit(($scenario.TimeoutSeconds + 8) * 1000)) {
                $outerTimeout = $true
                [void](Invoke-Docker -Arguments @("kill", $containerId) -AllowFailure)
                [void]$startProcess.WaitForExit(5000)
            }

            $containerInspect = Invoke-Docker -Arguments @("inspect", "--format", "{{.State.ExitCode}}", $containerId)
            $containerExit = [int]([string]($containerInspect.Output | Select-Object -Last 1))
        } finally {
            $wallClock.Stop()
            if (-not [string]::IsNullOrWhiteSpace($containerId)) {
                [void](Invoke-Docker -Arguments @("rm", "--force", $containerId) -AllowFailure)
            }
        }

        $stdoutPath = Join-Path $captureDirectory "qwen.stdout"
        $stderrPath = Join-Path $captureDirectory "qwen.stderr"
        if (-not (Test-Path -LiteralPath $stdoutPath -PathType Leaf) -or
            -not (Test-Path -LiteralPath $stderrPath -PathType Leaf)) {
            $startDiagnostic = Join-Path $scenarioDirectory "container-start.stderr"
            if (Test-Path -LiteralPath $dockerStderr -PathType Leaf) {
                Copy-Item -LiteralPath $dockerStderr -Destination $startDiagnostic
            } else {
                Write-Utf8File -Path $startDiagnostic -Content "container produced no stderr"
            }
            Write-JsonFile -Path (Join-Path $scenarioDirectory "container-start.json") -Value ([ordered]@{
                schema_version = "advisory-ai-container-start.v1"
                attempt_id = $attemptId
                container_exit_code = $containerExit
                outer_timeout = $outerTimeout
            })
            throw "Container produced no bounded Qwen output. See $scenarioDirectory"
        }

        $qwenExitPath = Join-Path $captureDirectory "qwen.exit"
        $qwenExit = if (Test-Path -LiteralPath $qwenExitPath) {
            [int]([System.IO.File]::ReadAllText($qwenExitPath).Trim())
        } elseif ($outerTimeout) {
            124
        } else {
            $containerExit
        }
        $stdoutSize = (Get-Item -LiteralPath $stdoutPath).Length
        $stderrSize = (Get-Item -LiteralPath $stderrPath).Length
        $wire = Get-WireObservation -Path (Join-Path $captureDirectory "wire-observation.json")
        $relayObservation = Read-OptionalJson -Path (Join-Path $captureDirectory "relay-observation.json")
        $providerObservation = Read-OptionalJson -Path (Join-Path $captureDirectory "provider-observation.json")
        $settlementObservationPath = Join-Path $captureDirectory "settlement-observation.json"
        $settlementObservation = Read-OptionalJson -Path $settlementObservationPath
        $qwenResult = Get-QwenResultObservation -StdoutPath $stdoutPath

        $eventsPath = Join-Path $captureDirectory "relay-events.jsonl"
        $events = @(Read-SafeEvents -Path $eventsPath)
        $receivedRequestCount = @($events | Where-Object {
            $_.origin -eq "qwen_request" -and $_.action_type -in @("model_request", "additional_model_request")
        }).Count
        $upstreamRequestCount = @($events | Where-Object {
            $_.origin -eq "relay_policy" -and $_.action_type -eq "provider_forward" -and $_.allowed -eq $true
        }).Count
        $providerReceivedRequestCount = if ($null -ne $providerObservation) {
            [int]$providerObservation.request_count
        } else {
            0
        }
        $canaryDetectedInCapture = $false
        foreach ($capturedFile in @(Get-ChildItem -LiteralPath $captureDirectory -File)) {
            if ([System.IO.File]::ReadAllText($capturedFile.FullName).Contains($SecretCanary)) {
                $canaryDetectedInCapture = $true
                break
            }
        }
        $additionalRequestEvents = @($events | Where-Object {
            $_.origin -eq "qwen_request" -and $_.action_type -eq "additional_model_request"
        })
        $retryAttempted = $additionalRequestEvents.Count -gt 0
        $additionalRequestsContained = @($additionalRequestEvents | Where-Object {
            $_.allowed -ne $false -or $_.blocked -ne $true
        }).Count -eq 0
        $prohibitedToolAttempted = @($events | Where-Object {
            $_.origin -eq "model_response" -and $_.action_type -eq "run_shell_command" -and $_.blocked -eq $false
        }).Count -gt 0
        $relayBlockedProhibitedTool = @($events | Where-Object {
            $_.origin -eq "relay_policy" -and $_.action_type -eq "run_shell_command" -and $_.allowed -eq $false -and $_.blocked -eq $true
        }).Count -gt 0
        $relayObserved = $null -ne $relayObservation
        $downstreamProviderBytes = if ($relayObserved) { [long]$relayObservation.downstream_provider_bytes } else { $null }
        $prohibitedToolContainment = if ($prohibitedToolAttempted -and $relayBlockedProhibitedTool -and $downstreamProviderBytes -eq 0) {
            "BLOCKED"
        } elseif ($prohibitedToolAttempted) {
            "NOT_CONTAINED"
        } else {
            "UNOBSERVABLE"
        }
        $prohibitedToolContained = $prohibitedToolContainment -eq "BLOCKED"
        $observedOutcome = if ($outerTimeout -or $qwenExit -eq 124) {
            "TIMEOUT"
        } elseif ($qwenExit -eq 0) {
            "SUCCESS"
        } else {
            "FAILED"
        }

        $boundedOutput = $stdoutSize -le $OutputLimitBytes -and $stderrSize -le $StderrLimitBytes
        $wireBounded = $upstreamRequestCount -eq 1 -and $providerReceivedRequestCount -eq 1
        $wireToolsetIsolated = $wire.Observed -and $wire.ToolNames.Count -eq 1 -and $wire.ToolNames[0] -eq "structured_output"
        $outputTokenLimitDefault = $wire.Observed -and $wire.OutputTokenLimitMode -eq $OutputTokenLimitMode -and
            -not $wire.MaxTokensAfterPresent -and $null -eq $wire.MaxTokensAfter -and -not $wire.ClampApplied
        $fixedModelEnforced = $wire.Observed -and $wire.ModelAfter -eq $FixedModel
        $providerPolicyEnforced = $wire.Observed -and $wire.ProviderPolicyEnforced
        $providerObservedExactPolicy = $null -ne $providerObservation -and
            [bool]$providerObservation.provider_policy_enforced -and
            -not [bool]$providerObservation.max_tokens_present -and
            -not [bool]$providerObservation.max_completion_tokens_present -and
            -not [bool]$providerObservation.max_output_tokens_present
        $unsafeFieldsRewritten = $wire.UnsafeFieldsFixtureInjected -and $wire.NBefore -eq 7 -and `
            $wire.BestOfBeforePresent -and $wire.MaxOutputTokensBeforePresent -and $wire.NAfter -eq 1 -and `
            -not $wire.BestOfAfterPresent -and -not $wire.MaxOutputTokensAfterPresent -and `
            $null -ne $providerObservation -and [int]$providerObservation.n -eq 1 -and `
            -not [bool]$providerObservation.best_of_present -and -not [bool]$providerObservation.max_output_tokens_present
        $providerErrorBodyContained = $scenario.Name -ne "retry" -or `
            ($relayObserved -and $relayObservation.status -eq "BLOCKED_PROVIDER_ERROR_BODY" -and `
                $downstreamProviderBytes -eq 0 -and -not $canaryDetectedInCapture)
        $baseIsolation = $wireToolsetIsolated -and $wireBounded -and $boundedOutput -and $outputTokenLimitDefault -and `
            $fixedModelEnforced -and $providerPolicyEnforced -and $wire.TextOnlyInput
        $budgetDisabled = $budgetAuthorization.budget_mode -eq $BudgetMode -and
            $budgetAuthorization.status -eq $BudgetStatus -and $wire.BudgetMode -eq $BudgetMode
        $forwardingBoundary = $baseIsolation -and $providerObservedExactPolicy -and
            -not $wire.ProviderFieldPresent -and $budgetDisabled
        $usageObserved = $null -ne $settlementObservation -and [string]$settlementObservation.status -eq "KNOWN"
        $scenarioPass = switch ($scenario.Name) {
            "valid" { $forwardingBoundary -and $unsafeFieldsRewritten -and $usageObserved -and $qwenResult.State -eq "SUCCESS" -and -not $retryAttempted }
            "malformed" { $forwardingBoundary -and $usageObserved -and $qwenResult.State -eq "STRUCTURED_VALIDATION_REJECTED" -and $retryAttempted -and $additionalRequestsContained }
            "timeout" { $forwardingBoundary -and $observedOutcome -eq "TIMEOUT" }
            "prohibited_tool" { $forwardingBoundary -and $usageObserved -and $prohibitedToolAttempted -and $prohibitedToolContained -and $additionalRequestsContained }
            "retry" { $forwardingBoundary -and $providerErrorBodyContained -and $observedOutcome -ne "SUCCESS" -and $retryAttempted -and $additionalRequestsContained }
            "conflicting_usage" { $forwardingBoundary -and $null -ne $settlementObservation -and [string]$settlementObservation.status -eq "UNKNOWN" }
        }
        $negativeControl = $scenario.Name -ne "valid"
        $injectedModelBehavior = switch ($scenario.Name) {
            "valid" { "PASS" }
            "malformed" { "FAIL" }
            "prohibited_tool" { "FAIL" }
            default { "NOT_EVALUATED" }
        }

        $safeEventsDestination = Join-Path $scenarioDirectory "action-events.jsonl"
        if (Test-Path -LiteralPath $eventsPath) {
            Copy-Item -LiteralPath $eventsPath -Destination $safeEventsDestination
        } else {
            Write-Utf8File -Path $safeEventsDestination -Content ""
        }
        if (Test-Path -LiteralPath $settlementObservationPath -PathType Leaf) {
            Copy-Item -LiteralPath $settlementObservationPath -Destination (Join-Path $scenarioDirectory "settlement-observation.json")
        }

        $result = [ordered]@{
            schema_version = "advisory-ai-preflight-result.v3"
            attempt_id = $attemptId
            scenario = $scenario.Name
            expected_outcome = $scenario.Expected
            observed_outcome = $observedOutcome
            scenario_pass = [bool]$scenarioPass
            negative_control = [bool]$negativeControl
            injected_model_behavior = $injectedModelBehavior
            release_policy_pass = $null
            release_policy_status = "NOT_EVALUATED_PREFLIGHT"
            clean_context = $true
            network_mode = "none"
            read_only_root = $true
            cache_enabled = $false
            fallback_enabled = $false
            relay_received_request_count = $receivedRequestCount
            upstream_request_count = $upstreamRequestCount
            provider_received_request_count = $providerReceivedRequestCount
            unsafe_fields_fixture_injected = [bool]$wire.UnsafeFieldsFixtureInjected
            unsafe_fields_rewritten = [bool]$unsafeFieldsRewritten
            wire_n_before = $wire.NBefore
            wire_n_after = $wire.NAfter
            wire_best_of_before_present = [bool]$wire.BestOfBeforePresent
            wire_best_of_after_present = [bool]$wire.BestOfAfterPresent
            wire_max_output_tokens_before_present = [bool]$wire.MaxOutputTokensBeforePresent
            wire_max_output_tokens_after_present = [bool]$wire.MaxOutputTokensAfterPresent
            provider_n = if ($null -ne $providerObservation) { $providerObservation.n } else { $null }
            provider_best_of_present = if ($null -ne $providerObservation) { [bool]$providerObservation.best_of_present } else { $null }
            provider_max_tokens_present = if ($null -ne $providerObservation) { [bool]$providerObservation.max_tokens_present } else { $null }
            provider_max_completion_tokens_present = if ($null -ne $providerObservation) { [bool]$providerObservation.max_completion_tokens_present } else { $null }
            provider_max_output_tokens_present = if ($null -ne $providerObservation) { [bool]$providerObservation.max_output_tokens_present } else { $null }
            provider_error_body_contained = [bool]$providerErrorBodyContained
            secret_canary_detected_in_capture = [bool]$canaryDetectedInCapture
            retry_or_followup_attempted = [bool]$retryAttempted
            additional_request_count = $additionalRequestEvents.Count
            additional_requests_contained = [bool]$additionalRequestsContained
            prohibited_tool_attempted = [bool]$prohibitedToolAttempted
            prohibited_tool_contained = [bool]$prohibitedToolContained
            prohibited_tool_execution = $prohibitedToolContainment
            relay_observation_present = [bool]$relayObserved
            relay_status = if ($relayObserved) { [string]$relayObservation.status } else { "UNKNOWN_OUTCOME" }
            provider_response_bytes = if ($relayObserved) { [long]$relayObservation.provider_response_bytes } else { $null }
            downstream_provider_bytes = $downstreamProviderBytes
            qwen_result_observation = $qwenResult.State
            qwen_exit_code = $qwenExit
            container_exit_code = $containerExit
            duration_ms = [long]$wallClock.ElapsedMilliseconds
            qwen_stdout_bytes = [long]$stdoutSize
            qwen_stdout_sha256 = Get-Sha256 $stdoutPath
            qwen_stderr_bytes = [long]$stderrSize
            qwen_stderr_sha256 = Get-Sha256 $stderrPath
            output_limit_bytes = $OutputLimitBytes
            stderr_limit_bytes = $StderrLimitBytes
            provider_response_limit_bytes = $ProviderResponseLimitBytes
            configured_token_limit = $null
            output_token_limit_mode = $OutputTokenLimitMode
            output_token_limit_default_on_wire = [bool]$outputTokenLimitDefault
            wire_observation_present = [bool]$wire.Observed
            wire_model_before = $wire.ModelBefore
            wire_model_after = $wire.ModelAfter
            wire_stream = $wire.Stream
            wire_tool_names = @($wire.ToolNames)
            wire_max_tokens_before = $wire.MaxTokensBefore
            wire_max_tokens_after = $wire.MaxTokensAfter
            wire_max_tokens_after_present = [bool]$wire.MaxTokensAfterPresent
            max_tokens_clamp_applied = [bool]$wire.ClampApplied
            provider_policy_enforced_on_wire = [bool]$providerPolicyEnforced
            provider_policy_observed_by_fake = [bool]$providerObservedExactPolicy
            provider_field_present = [bool]$wire.ProviderFieldPresent
            budget_authorized = [bool]$budgetAuthorization.Allowed
            budget_mode = [string]$budgetAuthorization.budget_mode
            budget_status = [string]$budgetAuthorization.status
            monetary_reservation_enabled = $false
            monetary_settlement_enabled = $false
            settlement_observation_present = $null -ne $settlementObservation
            settlement_observation_status = if ($null -ne $settlementObservation) { [string]$settlementObservation.status } else { "MISSING" }
            settlement_observation_reason = if ($null -ne $settlementObservation) { [string]$settlementObservation.reason } else { "NO_TERMINAL_OBSERVATION" }
            ambient_mcp_configuration_mounted = $false
            input_hashes = $inputHashes
        }
        Write-JsonFile -Path (Join-Path $scenarioDirectory "result.json") -Value $result
        $results += [pscustomobject]$result
    }

    $manifest = [ordered]@{
        schema_version = "advisory-ai-preflight-manifest.v3"
        mode = $Mode
        test_only = $true
        live_requests = 0
        live_release_enabled = $false
        live_guarded_forwarding_wired = $false
        shared_guarded_relay_preflight = $true
        harness_preflight_pass = @($results | Where-Object { -not $_.scenario_pass }).Count -eq 0
        release_policy_pass = $null
        release_policy_status = "NOT_EVALUATED_PREFLIGHT"
        image_reference = $ImageReference
        image_id = $observedImageId.Trim()
        qwen_version = $ExpectedQwenVersion
        package_hashes = $packageHashes
        input_hashes = $inputHashes
        budget = [ordered]@{
            mode = $BudgetMode
            status = $BudgetStatus
            reservation_enabled = $false
            settlement_enabled = $false
            planned_live_attempts = 60
            model = $FixedModel
            max_output_tokens = $null
            output_token_limit_mode = $OutputTokenLimitMode
        }
        attempt_count = $results.Count
        results = @($results | ForEach-Object {
            [ordered]@{
                attempt_id = $_.attempt_id
                scenario = $_.scenario
                scenario_pass = $_.scenario_pass
                negative_control = $_.negative_control
                injected_model_behavior = $_.injected_model_behavior
                release_policy_status = $_.release_policy_status
                observed_outcome = $_.observed_outcome
                upstream_request_count = $_.upstream_request_count
                provider_received_request_count = $_.provider_received_request_count
                budget_status = $_.budget_status
                retry_or_followup_attempted = $_.retry_or_followup_attempted
            }
        })
    }
    Write-JsonFile -Path (Join-Path $OutputDirectory "manifest.json") -Value $manifest

    if (-not $manifest.harness_preflight_pass) {
        throw "Preflight completed, but one or more harness controls failed. See $OutputDirectory"
    }
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        $resolvedTemp = [System.IO.Path]::GetFullPath($tempRoot)
        if (-not $resolvedTemp.StartsWith($tempBase, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove unexpected temporary path: $resolvedTemp"
        }
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}

Write-Output "Preflight PASS: $OutputDirectory"
