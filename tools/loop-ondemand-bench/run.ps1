# Offline benchmark for on-demand decoding (issue 00027 follow-up).
#
# Uses the PRODUCTION classes (LoopRing / PcmBlockCache / LoopingPcmSourceCore /
# RingBandReader) on a plain JVM: no app code is touched, nothing is copied.
#
#   memory   : real heap footprint of the full ring vs. the block cache (measured)
#   alloc    : allocation rate (churn) of the read / block-install paths, pooled vs current
#   prefetch : two-stage supply simulation; audible underruns (outputFrames gaps) for
#              prefetch window 1/2/3/5/6 blocks
#   readcost : ring read CPU cost (ms of CPU per second of audio)
#
# Usage:
#   powershell -NoProfile -File tools\loop-ondemand-bench\run.ps1
#   powershell -NoProfile -File tools\loop-ondemand-bench\run.ps1 -Task memory
#   powershell -NoProfile -File tools\loop-ondemand-bench\run.ps1 -Task prefetch -Seed 7
param(
    [ValidateSet("all", "memory", "alloc", "prefetch", "readcost", "heapslope", "lazyring", "seamcheck", "prefetchgate")] [string]$Task = "all",
    [int]$Seed = 20261121,
    [string]$Out = ""
)

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$repo = Split-Path -Parent (Split-Path -Parent $here)

function Find-Kotlinc {
    $candidates = @(
        (Join-Path $env:ProgramFiles "Android\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat"),
        (Join-Path $env:LOCALAPPDATA "Programs\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat")
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command kotlinc.bat -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "kotlinc not found (install Android Studio or put kotlinc on PATH)"
}

$kotlinc = Find-Kotlinc
$kotlinLib = Join-Path (Split-Path -Parent (Split-Path -Parent $kotlinc)) "lib"
$stdlib = Join-Path $kotlinLib "kotlin-stdlib.jar"
if (-not (Test-Path $stdlib)) { throw "missing kotlin-stdlib.jar under $kotlinLib" }
$build = if ($Out) { $Out } else { Join-Path $repo "build\loop-ondemand-bench" }
$classes = Join-Path $build "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

# Only pure-JVM production files (no Android / media3 imports).
$prod = @(
    "app\src\main\java\com\zjr\hesimusic\loop\analysis\Pcm16Segment.kt",
    "app\src\main\java\com\zjr\hesimusic\loop\audio\source\PcmRangeProvider.kt",
    "app\src\main\java\com\zjr\hesimusic\loop\audio\source\LoopingPcmTimeline.kt",
    "app\src\main\java\com\zjr\hesimusic\loop\audio\source\PcmBlockCache.kt",
    "app\src\main\java\com\zjr\hesimusic\loop\audio\source\LoopingPcmSourceCore.kt",
    "app\src\main\java\com\zjr\hesimusic\loop\audio\source\LoopRing.kt",
    "app\src\main\java\com\zjr\hesimusic\loop\audio\source\RingBandReader.kt"
) | ForEach-Object { Join-Path $repo $_ }

$bench = Get-ChildItem (Join-Path $here "src") -Filter *.kt -File | ForEach-Object { $_.FullName }

$all = @($prod) + @($bench)
foreach ($f in $all) { if (-not (Test-Path $f)) { throw "source missing: $f" } }

Write-Host "[bench] kotlinc=$kotlinc"
Write-Host "[bench] compile $($prod.Count) prod + $($bench.Count) bench files -> $classes"
& $kotlinc -nowarn -jvm-target 17 -d $classes @all
if ($LASTEXITCODE -ne 0) { throw "kotlinc failed: $LASTEXITCODE" }

Write-Host "[bench] run task=$Task seed=$Seed"
# -Xmx384m = 402,653,184B heap, exactly the 00027 device heap so heap/6 and the
# block-cache budget (maxMemory/10 clamped to 32MB) match the app.
& java -Xmx384m -cp "$classes;$stdlib" bench.BenchMainKt --task $Task --seed $Seed
if ($LASTEXITCODE -ne 0) { throw "bench failed: $LASTEXITCODE" }
