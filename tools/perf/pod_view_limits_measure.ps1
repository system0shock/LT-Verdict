<#
Measurement driver of platform slice P2e (ADR 0020 limits of pod-view.v1).
Needs: python -m tools.perf.pod_view_limits --out <Data> (see that file), and a build with
`gradlew installDist testClasses -x uiBuild -x npmCi` (build\install\ltv\lib and build\classes\kotlin\test).
The JVM is the one of the product: no flags except -Xmx where a suite sweeps it, and a GC log. The java process is bound to
all but two logical processors. Pass -SlotScript <ltv-slot.ps1> to run every JVM inside Invoke-LtvExclusive.

Usage (repository root, PowerShell):
  .\tools\perf\pod_view_limits_measure.ps1 -Data <dir> -Work <dir> -Suite stages|server|cli|sweep|files|shape|all [-Reps 3] [-SweepShapes ..] [-SweepHeaps ..] [-Files <pod-view files for -Suite files>] [-Shape <shape for -Suite shape>] [-SlotScript <path>]
Results go to <Work>\results.tsv (RESULT lines of the Kotlin driver) and <Work>\cli.csv (CLI runs); raw logs stay in <Work>.
#>
param(
    [Parameter(Mandatory)] [string] $Data,
    [Parameter(Mandatory)] [string] $Work,
    [ValidateSet('stages', 'server', 'cli', 'sweep', 'files', 'shape', 'all')] [string] $Suite = 'all',
    [int] $Reps = 3,
    [string[]] $SweepShapes = @('LIMIT-WORST', 'LIMIT', 'ADV-WIDE', 'ADV-ROWS', 'OVER-ROWS'),
    [string[]] $SweepHeaps = @('128m', '192m', '256m', '384m', '512m'),
    [string[]] $Files = @(),
    [string] $Shape = '',
    [string] $SlotScript = ''
)
$ErrorActionPreference = 'Stop'
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
$repo = Resolve-Path (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..')
$classpath = "$repo\build\install\ltv\lib\*;$repo\build\classes\kotlin\test"
New-Item -ItemType Directory -Force $Work | Out-Null
$results = Join-Path $Work 'results.tsv'
$cliCsv = Join-Path $Work 'cli.csv'
if (-not (Test-Path $cliCsv)) { 'suite,scenario,rep,xmx,exit,wall_s,peak_ws_mb,gc_count,gc_before_max_mb,gc_after_max_mb' | Set-Content $cliCsv }
if ($SlotScript) { . $SlotScript }
# The java.exe on PATH may be a launcher shim that starts the real JVM as a child; bind and poll the real one.
$java = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { "$env:JAVA_HOME\bin\java.exe" } else { (Get-Command java).Source }
$cores = [Environment]::ProcessorCount
$mask = [int64]([math]::Pow(2, [math]::Max($cores - 2, 1)) - 1)

function Invoke-Heavy([scriptblock] $Block) {
    if ($SlotScript) { Invoke-LtvExclusive $Block } else { & $Block }
}

# One JVM: returns exit code, wall seconds, peak working set (MB) and the GC log summary.
function Invoke-Java([string] $Tag, [string] $Xmx, [string[]] $JavaArgs) {
    $gcLog = Join-Path $Work "gc-$Tag.log"
    $out = Join-Path $Work "out-$Tag.txt"
    $err = Join-Path $Work "err-$Tag.txt"
    foreach ($f in $gcLog, $out, $err) { if (Test-Path $f) { Remove-Item $f } }
    $a = @("-Xlog:gc:file=$gcLog")
    if ($Xmx) { $a += "-Xmx$Xmx" }
    $a += @('-cp', "`"$classpath`"") + $JavaArgs
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $java
    $psi.Arguments = ($a -join ' ')
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $p = [Diagnostics.Process]::Start($psi)
    try { $p.ProcessorAffinity = [IntPtr]$mask } catch {}
    $stdout = $p.StandardOutput.ReadToEndAsync()
    $stderr = $p.StandardError.ReadToEndAsync()
    $peak = 0
    while (-not $p.HasExited) {
        try { $p.Refresh(); if ($p.PeakWorkingSet64 -gt $peak) { $peak = $p.PeakWorkingSet64 } } catch {}
        Start-Sleep -Milliseconds 100
    }
    $p.WaitForExit()
    $sw.Stop()
    Set-Content $out $stdout.Result
    Set-Content $err $stderr.Result
    $before = 0; $after = 0; $count = 0
    if (Test-Path $gcLog) {
        foreach ($l in Get-Content $gcLog) {
            if ($l -match '(\d+)M->(\d+)M\((\d+)M\)') {
                $count++
                if ([int] $Matches[1] -gt $before) { $before = [int] $Matches[1] }
                if ([int] $Matches[2] -gt $after) { $after = [int] $Matches[2] }
            }
        }
    }
    [pscustomobject]@{ Exit = $p.ExitCode; Wall = $sw.Elapsed.TotalSeconds; PeakWs = $peak / 1MB; GcCount = $count; Before = $before; After = $after; Out = $out; Err = $err }
}

function Add-Results([string] $Tag, $Run) {
    Add-Content $results ("# {0} exit={1} wall_s={2:F1} peak_ws_mb={3:F0} gc_count={4} gc_before_max_mb={5} gc_after_max_mb={6}" -f $Tag, $Run.Exit, $Run.Wall, $Run.PeakWs, $Run.GcCount, $Run.Before, $Run.After)
    Get-Content $Run.Out | Where-Object { $_ -like 'RESULT*' } | Add-Content $results
}

function Run-Stages([string] $File, [int] $Count, [string] $Xmx = '', [string] $Prefix = '') {
    $tag = "stages-$Prefix$([IO.Path]::GetFileNameWithoutExtension($File))-$Xmx"
    $run = Invoke-Heavy { Invoke-Java $tag $Xmx @('io.ltverdict.perf.PodViewLimitsMeasureKt', 'stages', "`"$File`"", "$Count") }
    Add-Results $tag $run
    Write-Host ("{0} exit={1} wall={2:F1}s" -f $tag, $run.Exit, $run.Wall)
}

function Run-Server([string] $Shape, [string] $Arms, [string] $Xmx, [string[]] $Rejects = @(), [string] $Tag = '') {
    if (-not $Tag) { $tag = "server-$Shape-$Xmx" } else { $tag = $Tag }
    $dataDir = Join-Path $Work "data-$tag"
    if (Test-Path $dataDir) { Remove-Item -Recurse -Force $dataDir }
    $a = @('io.ltverdict.perf.PodViewLimitsMeasureKt', 'server', "`"$dataDir`"", "`"$Data\load.jtl`"", "`"$Data`"", $Shape, $Arms) + ($Rejects | ForEach-Object { "`"$Data\$_`"" })
    $run = Invoke-Heavy { Invoke-Java $tag $Xmx $a }
    Add-Results $tag $run
    Write-Host ("{0} exit={1} wall={2:F1}s" -f $tag, $run.Exit, $run.Wall)
    if ($run.Exit -ne 0) { Write-Host (Get-Content $run.Err -TotalCount 5) }
}

function Run-Cli([string] $Suite, [string] $Scenario, [int] $Rep, [string] $Xmx, [string] $PodViewFile) {
    $tag = "cli-$Scenario-$Xmx-$Rep"
    $dataDir = Join-Path $Work "data-$tag"
    if (Test-Path $dataDir) { Remove-Item -Recurse -Force $dataDir }
    $a = @('io.ltverdict.MainKt', 'analyze', "`"$Data\load.jtl`"", '--resources', "`"$Data\snapshot-A.json`"", '--data-dir', "`"$dataDir`"")
    if ($PodViewFile) { $a += @('--pod-view', "`"$Data\$PodViewFile`"") }
    $run = Invoke-Heavy { Invoke-Java $tag $Xmx $a }
    '{0},{1},{2},{3},{4},{5:F1},{6:F0},{7},{8},{9}' -f $Suite, $Scenario, $Rep, $Xmx, $run.Exit, $run.Wall, $run.PeakWs, $run.GcCount, $run.Before, $run.After | Add-Content $cliCsv
    Write-Host ("{0} exit={1} wall={2:F1}s gc_before_max={3}M" -f $tag, $run.Exit, $run.Wall, $run.Before)
    if ($run.Exit -notin 0, 1, 2, 3) { Write-Host (Get-Content $run.Err -TotalCount 5) }
    if (Test-Path $dataDir) { Remove-Item -Recurse -Force $dataDir }
}

$shapes = 'F', 'C', 'LIMIT', 'LIMIT-WORST', 'ONE-SERVICE', 'OVER-ROWS', 'OVER-PODS', 'OVER-SERVICES', 'OVER-CONTAINERS', 'OVER-COLUMNS', 'OVER-BYTES', 'ADV-WIDE', 'ADV-ROWS'
if ($Suite -in 'stages', 'all') {
    foreach ($s in $shapes) { Run-Stages "$Data\pod-view-$s-A.json" 8 }
}
if ($Suite -eq 'files') {
    foreach ($f in $Files) { Run-Stages $f 8 '' ((Split-Path (Split-Path $f -Parent) -Leaf) + '-') }
}
if ($Suite -eq 'shape') {
    # Three analyses in a row through the HTTP job path (arms A,B,C), then the read API, on a shape that the data directory holds for those arms.
    foreach ($x in '', '512m', '384m') { Run-Server $Shape 'A,B,C' $x -Tag "server-$Shape-$(if ($x) { $x } else { 'default' })" }
}
if ($Suite -in 'sweep', 'all') {
    foreach ($s in $SweepShapes) {
        foreach ($x in $SweepHeaps) { Run-Stages "$Data\pod-view-$s-A.json" 3 $x }
    }
}
if ($Suite -in 'server', 'all') {
    $rejects = $shapes | Where-Object { $_ -like 'OVER-*' -or $_ -like 'ADV-*' } | ForEach-Object { "pod-view-$_-A.json" }
    Run-Server 'none' 'A,B,C' '512m' -Tag 'server-none-512m'
    Run-Server 'LIMIT-WORST' 'A,B,C' '512m' -Rejects $rejects -Tag 'server-LIMIT-WORST-512m'
    Run-Server 'C' 'A,B,C' '512m' -Tag 'server-C-512m'
    Run-Server 'LIMIT-WORST' 'A,B,C' '' -Tag 'server-LIMIT-WORST-default'
    Run-Server 'LIMIT-WORST' 'A,B,C' '384m' -Tag 'server-LIMIT-WORST-384m'
    Run-Server 'none' 'A,B,C' '384m' -Tag 'server-none-384m'
    Run-Server 'ONE-SERVICE' 'A' '512m' -Tag 'server-ONE-SERVICE-512m'
}
if ($Suite -in 'cli', 'all') {
    $matrix = [ordered]@{ none = ''; F = 'pod-view-F-A.json'; C = 'pod-view-C-A.json'; LIMIT = 'pod-view-LIMIT-A.json'; 'LIMIT-WORST' = 'pod-view-LIMIT-WORST-A.json' }
    Run-Cli 'matrix' 'warmup' 0 '' ''
    for ($rep = 1; $rep -le $Reps; $rep++) {
        foreach ($k in $matrix.Keys) { Run-Cli 'matrix' $k $rep '' $matrix[$k] }
    }
    foreach ($x in '512m', '384m', '320m', '256m') {
        foreach ($k in 'none', 'LIMIT-WORST') { Run-Cli 'xmx' $k 1 $x $matrix[$k] }
    }
}
