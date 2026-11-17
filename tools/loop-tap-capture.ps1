<#
  维护提示:本文件必须保存为 **UTF-8 with BOM**。
  Windows PowerShell 5.1 对无 BOM 的 UTF-8 源文件按 ANSI(GBK)解码,中文字符串会被拆成
  非法 token 导致解析失败(报 "Unexpected token";用编辑器另存时请确认编码)。

.SYNOPSIS
  无缝循环输出流畅度取证:打开 PCM tap -> 进入环回并 seek 到区间内 -> 录 N 秒 -> 关 tap ->
  用 adb exec-out run-as cat 拉回 PCM 与 run 快照 -> 交给 tools/loop-smoothness.ts 判定。

.DESCRIPTION
  为什么要 exec-out + 重定向:run-as 把文件拷到 /data/local/tmp 会 Permission denied,
  而 PowerShell 的 ">" 重定向会把原生命令输出按文本解码(二进制必坏),所以这里用
  .NET Process + StandardOutput.BaseStream 直接把字节写进本地文件(等价于):
      adb exec-out run-as <pkg> cat <remote> > <local>

  产物(默认 e2e-artifacts/,已在 .gitignore):
      tap-<songId>-<ts>.wav          tap 落盘的 WAV(44 字节头 + 原始 PCM)
      tap-<songId>-<ts>.wav.jsonl    run 快照 / seam / configure / flush / degrade / end
      loopcat-<ts>.txt               本次窗口的 LoopPerf 日志(失败时先看这个)
      smoothness-<ts>.json           分析脚本的结构化结果
      capture-<songId>-<ts>.json     本次采集的 manifest(参数+产物+结论)

.EXAMPLE
  powershell -NoProfile -File tools/loop-tap-capture.ps1 -SongId 40904

.EXAMPLE
  powershell -NoProfile -File tools/loop-tap-capture.ps1 -SongId 40904 -AMs 20000 -BMs 50000 -Seconds 100

.EXAMPLE
  # 歌还没有确认过的环区间:先让脚本自动 analyze + confirm
  powershell -NoProfile -File tools/loop-tap-capture.ps1 -SongId 40904 -AutoConfirm -Tag mp3-A20-B50
#>
param(
    [long]$SongId = 0,
    [string]$Pkg = "com.zjr.hesimusic",
    [double]$Seconds = 0,
    [int]$AMs = -1,
    [int]$BMs = -1,
    [int]$SeekMs = -1,
    [string]$Base = "http://127.0.0.1:18765",
    [int]$ForwardLocal = 18765,
    [int]$ForwardRemote = 8765,
    [string]$TapPath = "/data/local/tmp/tap.pcm",
    [string]$OutDir = "e2e-artifacts",
    [string]$AdbPath = "",
    [string]$Tag = "",
    [int]$MaxSeconds = 180,
    [switch]$AutoConfirm,
    [switch]$NoAnalyze,
    [switch]$NoLogcat,
    [switch]$KeepDeviceFiles,
    [switch]$DryRun,
    [switch]$SelfTestPull,
    [switch]$ProbeOnly
)

$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }
try { $OutputEncoding = [Text.Encoding]::UTF8 } catch { }

# ============================================================================
# 小工具
# ============================================================================

function Write-Step([string]$m) { Write-Host ("[step] " + $m) }
function Write-Info([string]$m) { Write-Host ("       " + $m) }
function Write-Note([string]$m) { Write-Host ("[note] " + $m) }
function Now-Ms { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
function Stamp { Get-Date -Format "yyyyMMdd-HHmmss" }

function Resolve-Adb {
    $cands = New-Object System.Collections.ArrayList
    if ($env:ANDROID_HOME) { [void]$cands.Add((Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")) }
    if ($env:ANDROID_SDK_ROOT) { [void]$cands.Add((Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")) }
    if ($env:LOCALAPPDATA) { [void]$cands.Add((Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")) }
    if (-not [string]::IsNullOrWhiteSpace($AdbPath)) {
        if (Test-Path -LiteralPath $AdbPath) { return (Resolve-Path -LiteralPath $AdbPath).Path }
        throw ("-AdbPath 不存在: " + $AdbPath)
    }
    foreach ($c in $cands) { if (Test-Path -LiteralPath $c) { return $c } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "adb 未找到:设置 ANDROID_HOME(platform-tools 所在 SDK 目录),或把 platform-tools 加进 PATH,或用 -AdbPath 指定"
}

# ---------------------------------------------------------------------------
# -SelfTestPull:不接设备,验证「exec-out 拉二进制」这条管线字节精确
#   (用 cmd /c type 代替 adb exec-out;同样的 Process + BaseStream 落盘路径)
# ---------------------------------------------------------------------------
function Invoke-PullSelfTest {
    $fixture = Join-Path $PSScriptRoot "fixtures/loop-smoothness/ok-loop.wav"
    if (-not (Test-Path -LiteralPath $fixture)) {
        $bunExe = (Get-Command bun -ErrorAction SilentlyContinue).Source
        if (-not $bunExe) { throw ("fixture 缺失且找不到 bun: " + $fixture) }
        Write-Step "生成 fixture(bun run tools/loop-smoothness.ts --selftest)"
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $bunExe run (Join-Path $PSScriptRoot "loop-smoothness.ts") --selftest 2>&1 | ForEach-Object { Write-Host ([string]$_) }
        } finally { $ErrorActionPreference = $prevEap }
    }
    if (-not (Test-Path -LiteralPath $fixture)) { throw ("fixture 仍缺失: " + $fixture) }

    $work = Join-Path $PSScriptRoot "..\e2e-artifacts\pull-selftest"
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    $dst = Join-Path $work "pulled-ok-loop.wav"
    $cmdExe = Join-Path $env:SystemRoot "System32\cmd.exe"
    Write-Step "pull 自测: cmd /c type -> Process.BaseStream -> 文件"
    $r = Invoke-AdbExecOutToFile -RemotePath $fixture -LocalPath $dst -Exe $cmdExe -ArgvOverride @("/c", "type", $fixture)
    $fail = @()
    if ($r.ExitCode -ne 0) { $fail += ("exit=" + $r.ExitCode + " " + $r.Stderr) }
    if (-not (Test-Path -LiteralPath $dst)) { $fail += "目标文件不存在" }
    else {
        $h1 = (Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash
        $h2 = (Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash
        if ($h1 -ne $h2) { $fail += ("SHA256 不一致: " + $h1 + " != " + $h2) }
        if (-not (Test-RiffHeader -Path $dst)) { $fail += "拉回文件不是 RIFF" }
        Write-Info ("bytes=" + (Get-Item -LiteralPath $dst).Length + " sha256=" + $h2.Substring(0, 16) + "...")
    }
    # 失败路径:文件不存在时必须是 size=0 / 非 0 exit,而不是写出半个文件冒充成功
    $dst2 = Join-Path $work "pulled-missing.wav"
    $r2 = Invoke-AdbExecOutToFile -RemotePath (Join-Path $work "no-such-file.wav") -LocalPath $dst2 -Exe $cmdExe -ArgvOverride @("/c", "type", (Join-Path $work "no-such-file.wav"))
    if ($r2.ExitCode -eq 0 -and $r2.Size -gt 0) { $fail += "缺失文件被判成功(size=" + $r2.Size + ")" }
    Write-Info ("missing-file: exit=" + $r2.ExitCode + " size=" + $r2.Size + "(应为非 0/0)")

    if ($fail.Count -gt 0) {
        Write-Host ("SELFTEST-PULL: FAIL -> " + ($fail -join "; "))
        return 1
    }
    Write-Host "SELFTEST-PULL: PASS(字节精确、缺失文件不冒充成功)"
    return 0
}

try { $adb = Resolve-Adb } catch { if ($DryRun) { $adb = "adb" } else { throw } }

function Ensure-Forward {
    Write-Step ("adb forward tcp:" + $ForwardLocal + " tcp:" + $ForwardRemote)
    if ($DryRun) { return }
    $list = (& $adb forward --list) 2>$null
    $needle = "tcp:" + $ForwardLocal
    if (-not ($list | Select-String -SimpleMatch $needle)) {
        & $adb forward $needle ("tcp:" + $ForwardRemote) | Out-Null
        Start-Sleep -Milliseconds 250
    }
}

function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body = $null, [int]$TimeoutSec = 30)
    $uri = $Base + $Path
    $attempt = 0
    while ($true) {
        try {
            if ($null -ne $Body) {
                $json = ($Body | ConvertTo-Json -Compress)
                return Invoke-RestMethod -Method $Method -Uri $uri -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec $TimeoutSec
            }
            return Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec $TimeoutSec
        } catch {
            if ($attempt -ge 1) { throw ("HTTP " + $Method + " " + $Path + " 失败: " + $_.Exception.Message) }
            $attempt++
            # forward 被 adb/系统重启清掉是已知现象:重建后重试一次
            & $adb forward --remove ("tcp:" + $ForwardLocal) 2>$null | Out-Null
            & $adb forward ("tcp:" + $ForwardLocal) ("tcp:" + $ForwardRemote) 2>$null | Out-Null
            Start-Sleep -Milliseconds 300
        }
    }
}

# adb exec-out 的 stdout 按二进制直接落盘(不经过 PowerShell 的文本解码)
# -Exe / -ArgvOverride 只给 -SelfTestPull 用(用 cmd /c type 代替 adb,证明字节不被文本解码破坏)
function Invoke-AdbExecOutToFile {
    param([string]$RemotePath, [string]$LocalPath, [string]$Exe = "", [string[]]$ArgvOverride = $null)
    if ([string]::IsNullOrEmpty($Exe)) { $Exe = $adb }
    if ($DryRun) {
        Write-Host ("       [dry-run] adb exec-out run-as " + $Pkg + " cat " + $RemotePath + " > " + $LocalPath)
        return @{ ExitCode = 0; Stderr = ""; Size = 0 }
    }
    $argv = if ($null -ne $ArgvOverride) { $ArgvOverride } else { @("exec-out", "run-as", $Pkg, "cat", $RemotePath) }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = (($argv | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join " ")
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $errTask = $proc.StandardError.ReadToEndAsync()
    $fs = [System.IO.File]::Create($LocalPath)
    try { $proc.StandardOutput.BaseStream.CopyTo($fs) } finally { $fs.Dispose() }
    $proc.WaitForExit()
    $stderr = ""
    try { if ($errTask -ne $null) { $stderr = $errTask.Result } } catch { }
    $size = 0
    if (Test-Path -LiteralPath $LocalPath) { $size = (Get-Item -LiteralPath $LocalPath).Length }
    return @{ ExitCode = $proc.ExitCode; Stderr = $stderr; Size = $size }
}

# 原生命令的 stderr 合并(2>&1)在 $ErrorActionPreference="Stop" 下会变成终止性
# NativeCommandError —— 真实 adb 报 "ls: ...: No such file" / "run-as: ..." 时就会踩到,
# 结果整个脚本在"探测 sidecar"这一步直接中止。这里局部把 EAP 降级,只取文本不抛错。
function Invoke-NativeCapture {
    param([string[]]$NativeArgs, [string]$Exe = "")
    if ([string]::IsNullOrEmpty($Exe)) { $Exe = $adb }
    $prev = $ErrorActionPreference
    # SilentlyContinue:2>&1 仍会把 stderr 文本并入管道,但不再把 NativeCommandError
    # 记录打到控制台(否则用户会看到一堆 "At line:xxx" 的假崩溃)。
    $ErrorActionPreference = "SilentlyContinue"
    try {
        $text = (& $Exe @NativeArgs 2>&1 | Out-String).Trim()
        return @{ Text = $text; ExitCode = $LASTEXITCODE }
    } finally { $ErrorActionPreference = $prev }
}

function Get-RemoteInfo {
    param([string]$RemotePath)
    if ($DryRun) { return @{ Exists = $true; Raw = "(dry-run)" } }
    $r = Invoke-NativeCapture -NativeArgs @("shell", "run-as", $Pkg, "ls", "-l", $RemotePath)
    $bad = ($r.Text -match "No such file") -or ($r.Text -match "run-as:") -or ($r.Text -match "Permission denied")
    $exists = ($r.ExitCode -eq 0) -and ($r.Text -ne "") -and (-not $bad)
    return @{ Exists = $exists; Raw = $r.Text }
}

function Test-RiffHeader {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $fs = [System.IO.File]::OpenRead($Path)
    try {
        $b = New-Object byte[] 4
        $n = $fs.Read($b, 0, 4)
        return ($n -eq 4 -and $b[0] -eq 0x52 -and $b[1] -eq 0x49 -and $b[2] -eq 0x46 -and $b[3] -eq 0x46)
    } finally { $fs.Dispose() }
}

function Pull-Artifact {
    param([string]$RemotePath, [string]$LocalPath, [bool]$Required = $false)
    Write-Step ("pull " + $RemotePath + " -> " + $LocalPath)
    $r = Invoke-AdbExecOutToFile -RemotePath $RemotePath -LocalPath $LocalPath
    if ($DryRun) { return $true }
    if ($r.ExitCode -ne 0 -or $r.Size -le 0) {
        $err = ([string]$r.Stderr -replace "\s+", " ").Trim()
        Write-Warning ("exec-out run-as cat 失败(exit=" + $r.ExitCode + ", size=" + $r.Size + "): " + $err)
        Write-Step "兜底 adb pull(仅当该文件对 shell 可读时有效)"
        $pr = Invoke-NativeCapture -NativeArgs @("pull", $RemotePath, $LocalPath)
        if ($pr.Text) { foreach ($ln in ($pr.Text -split "\r?\n")) { if ($ln) { Write-Info $ln } } }
        if (Test-Path -LiteralPath $LocalPath) { $r.Size = (Get-Item -LiteralPath $LocalPath).Length }
        if ($r.Size -le 0) {
            $info = Get-RemoteInfo -RemotePath $RemotePath
            $msg = "拉取失败: " + $RemotePath + " (size=" + $r.Size + ") ls -l: " + $info.Raw
            if ($Required) { throw $msg }
            Write-Warning $msg
            return $false
        }
    }
    Write-Info ("ok " + $r.Size + " bytes")
    return $true
}

function Find-Sidecar {
    param([string]$TapRemote)
    $cands = New-Object System.Collections.ArrayList
    [void]$cands.Add($TapRemote + ".jsonl")
    [void]$cands.Add([System.IO.Path]::ChangeExtension($TapRemote, ".jsonl"))
    [void]$cands.Add($TapRemote + ".events.jsonl")
    $seen = @{}
    foreach ($c in $cands) {
        if ([string]::IsNullOrWhiteSpace($c)) { continue }
        if ($seen.ContainsKey($c)) { continue }
        $seen[$c] = $true
        $info = Get-RemoteInfo -RemotePath $c
        if ($info.Exists) { return @{ Path = $c; Raw = $info.Raw } }
    }
    return $null
}

function Summarize-Events {
    param([string]$Path)
    $lines = @()
    if (Test-Path -LiteralPath $Path) { $lines = Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue }
    $seams = 0
    $run = $null
    $cfg = $null
    $end = $null
    $degrade = 0
    $flush = 0
    foreach ($l in $lines) {
        if ($l -match '"ev"\s*:\s*"seam"') { $seams++ }
        elseif ($l -match '"ev"\s*:\s*"degrade"') { $degrade++ }
        elseif ($l -match '"ev"\s*:\s*"flush"') { $flush++ }
        elseif (($null -eq $run) -and ($l -match '"ev"\s*:\s*"run"')) { $run = $l }
        elseif (($null -eq $cfg) -and ($l -match '"ev"\s*:\s*"configure"')) { $cfg = $l }
        elseif (($null -eq $end) -and ($l -match '"ev"\s*:\s*"end"')) { $end = $l }
    }
    return @{ Lines = $lines.Count; Seams = $seams; Run = $run; Configure = $cfg; End = $end; Degrade = $degrade; Flush = $flush }
}

function Wait-Analyze {
    param([int]$TimeoutSec = 180)
    $t0 = Now-Ms
    while (((Now-Ms) - $t0) -lt ($TimeoutSec * 1000)) {
        Start-Sleep -Milliseconds 500
        $a = Invoke-Api -Method GET -Path "/analysis"
        if ($a.phase -eq "done" -or $a.phase -eq "failed") { return $a }
    }
    throw ("分析超时(" + $TimeoutSec + "s)")
}

# 全部函数就绪后再跑自测(依赖 Invoke-AdbExecOutToFile / Test-RiffHeader)
if ($SelfTestPull) { exit (Invoke-PullSelfTest) }

# ============================================================================
# 0) 基本信息
# ============================================================================

$stamp = Stamp
$tag = if ([string]::IsNullOrWhiteSpace($Tag)) { $stamp } else { $Tag + "-" + $stamp }
$outAbs = if ([System.IO.Path]::IsPathRooted($OutDir)) { $OutDir } else { Join-Path (Get-Location).Path $OutDir }
if (-not $DryRun) { New-Item -ItemType Directory -Force -Path $outAbs | Out-Null }

Write-Host "== loop-tap-capture =="
Write-Info ("adb      : " + $adb)
Write-Info ("api      : " + $Base + "  (forward tcp:" + $ForwardLocal + " -> tcp:" + $ForwardRemote + ")")
Write-Info ("package  : " + $Pkg)
Write-Info ("songId   : " + $SongId)
Write-Info ("tap path : " + $TapPath)
Write-Info ("out dir  : " + $outAbs)

if (-not $DryRun) {
    $devs = @(& $adb devices | Select-String "\sdevice$")
    if ($devs.Count -eq 0) { throw "没有已连接的 adb 设备(adb devices)" }
    Write-Info ("device   : " + (($devs | ForEach-Object { ([string]$_).Trim() }) -join ", "))
}

# ============================================================================
# 1) forward + 服务自检
# ============================================================================

Ensure-Forward
if (-not $DryRun) {
    Write-Step "GET /state(服务自检)"
    $state = Invoke-Api -Method GET -Path "/state"
    $pb = $state.playback
    Write-Info ("engine=" + $pb.engine + " playing=" + $pb.playing + " repeat=" + $pb.repeatMode + " songId=" + $pb.songId)
    $loopNow = $state.loop
    if ($null -ne $loopNow) {
        Write-Info ("loop now: engine=" + $loopNow.engine + " seamCount=" + $loopNow.seamCount + " degrade=" + $loopNow.degradeCount + " ringComplete=" + $loopNow.isRingComplete)
    }
}

# ============================================================================
# 2) 选歌 + 确定 A/B
# ============================================================================

if ($SongId -le 0) {
    Write-Step "未指定 -SongId:从 /library/songs 里挑第一首(建议显式指定)"
    $songs = Invoke-Api -Method GET -Path "/library/songs?limit=20"
    $pick = $songs.songs | Select-Object -First 1
    if ($null -eq $pick) { throw "库里没有歌,无法采集" }
    $SongId = [long]$pick.id
    Write-Note ("picked songId=" + $SongId + " title=" + $pick.title)
}

$needAnalyze = $false
if ($AMs -gt 0 -and $BMs -gt $AMs) {
    Write-Step "使用显式 A/B"
    Write-Info ("A=" + $AMs + "ms B=" + $BMs + "ms L=" + ($BMs - $AMs) + "ms")
} else {
    Write-Step ("GET /song/" + $SongId + " 读已确认的环区间")
    $song = $null
    try { $song = Invoke-Api -Method GET -Path ("/song/" + $SongId) } catch { Write-Warning ("读取歌曲失败: " + $_.Exception.Message) }
    if ($null -ne $song -and $null -ne $song.loopStartMs -and $null -ne $song.loopEndMs -and [int]$song.loopEndMs -gt [int]$song.loopStartMs) {
        $AMs = [int]$song.loopStartMs
        $BMs = [int]$song.loopEndMs
        Write-Info ("A=" + $AMs + "ms B=" + $BMs + "ms (confirmed=" + $song.loopConfirmed + ")")
    } else {
        $needAnalyze = $true
    }
}

if ($needAnalyze) {
    if (-not $AutoConfirm) {
        throw "该歌没有可用的环区间(loopStartMs/loopEndMs 为空)。先跑 /analyze + /confirm,或加 -AutoConfirm 让本脚本代做,或用 -AMs/-BMs 指定。"
    }
    Write-Step "自动分析 + 确认候选 0(-AutoConfirm)"
    $ar = Invoke-Api -Method POST -Path "/analyze" -Body @{ songId = $SongId }
    Write-Info ("analyze started: " + ($ar | ConvertTo-Json -Compress))
    $an = Wait-Analyze
    Write-Info ("analysis phase=" + $an.phase + " candidates=" + $an.candidateCount)
    if ($an.phase -eq "failed") { throw ("分析失败: " + $an.message) }
    $cf = Invoke-Api -Method POST -Path "/confirm" -Body @{ songId = $SongId; useCandidate = 0 }
    Write-Info ("confirm: " + ($cf | ConvertTo-Json -Compress -Depth 4))
    $song = Invoke-Api -Method GET -Path ("/song/" + $SongId)
    if ($null -eq $song.loopStartMs -or $null -eq $song.loopEndMs) { throw "确认后仍拿不到 A/B,请手工用 -AMs/-BMs 指定" }
    $AMs = [int]$song.loopStartMs
    $BMs = [int]$song.loopEndMs
    Write-Info ("A=" + $AMs + "ms B=" + $BMs + "ms")
}

$loopMs = $BMs - $AMs
if ($loopMs -le 0) { throw ("非法环区间: A=" + $AMs + " B=" + $BMs) }
$loopSec = $loopMs / 1000.0

if ($SeekMs -lt 0) {
    # 起点放在区间内 20% 处(至少 500ms),让引擎有足够前奏跨过 B 进入重放
    $SeekMs = $AMs + [int][math]::Max(500, [math]::Round($loopMs * 0.2))
    if ($SeekMs -ge $BMs) { $SeekMs = $AMs + [int]([math]::Max(1, $loopMs / 2)) }
}

$autoSeconds = $false
if ($Seconds -le 0) {
    $autoSeconds = $true
    $auto = [int][math]::Min($MaxSeconds, [int][math]::Max(20, [int]([math]::Ceiling($loopSec * 3.5)) + 2))
    $Seconds = $auto
    Write-Note ("-Seconds 未指定:按 >= 3 个环自动定为 " + $Seconds + "s(环长 " + [math]::Round($loopSec, 2) + "s)")
}
# 首个环回点在 B(距 seek 起点 B-SeekMs),之后每 L 一个 => 跨过的接缝数 = floor((T - lead)/L) + 1
$leadSec = ($BMs - $SeekMs) / 1000.0
$seamsExpected = [math]::Floor(($Seconds - $leadSec) / $loopSec) + 1
if ($seamsExpected -lt 3 -and $autoSeconds -and $Seconds -lt $MaxSeconds) {
    # 默认/偏短的录制时长直接补到 3 个环回点(用户显式给小值时只告警,不改判)
    $bump = [int][math]::Ceiling($leadSec + 3 * $loopSec) + 1
    if ($bump -gt $Seconds -and $bump -le $MaxSeconds) {
        Write-Note ("录制时长 " + $Seconds + "s 只跨 " + [int]$seamsExpected + " 个环回点:自动加到 " + $bump + "s(-MaxSeconds=" + $MaxSeconds + ")")
        $Seconds = $bump
        $seamsExpected = [math]::Floor(($Seconds - $leadSec) / $loopSec) + 1
    }
}

Write-Host ""
Write-Info ("plan     : seek=" + $SeekMs + "ms  record=" + $Seconds + "s  expected seams = " + [int]$seamsExpected)
if ($seamsExpected -lt 3 -and -not $DryRun) {
    Write-Warning ("预计只跨 " + [int]$seamsExpected + " 个环回点(<3):环长 " + [math]::Round($loopSec, 2) + "s 太大时请加大 -MaxSeconds / -Seconds")
}

if ($ProbeOnly) {
    Write-Host ""
    Write-Note "-ProbeOnly:只探测(forward + /state + A/B + 计划),不打开 tap、不播放、不录制"
    exit 0
}

if ($DryRun) {
    $localPlan = Join-Path $outAbs ("tap-" + $SongId + "-" + $tag + ".wav")
    Write-Host ""
    Write-Host "[dry-run] 计划执行:"
    Write-Host ("  1. adb forward tcp:" + $ForwardLocal + " tcp:" + $ForwardRemote)
    Write-Host ('  2. POST /debug/tap {"path":"' + $TapPath + '"}')
    Write-Host ('  3. POST /loop/enter {"songId":' + $SongId + ',"play":true}')
    Write-Host ('  4. POST /player/seek {"positionMs":' + $SeekMs + '}; 等待 ' + $Seconds + 's')
    Write-Host '  5. POST /debug/tap {"path":null}'
    Write-Host ('  6. adb exec-out run-as ' + $Pkg + ' cat ' + $TapPath + ' > ' + $localPlan)
    Write-Host ('  7. bun run tools/loop-smoothness.ts ' + $localPlan + ' --events <jsonl> --json <json>')
    exit 0
}

# ============================================================================
# 3) 开 tap -> 进环回 -> seek -> 录 N 秒 -> 关 tap
# ============================================================================

$logcatFile = Join-Path $outAbs ("loopcat-" + $tag + ".txt")
if (-not $NoLogcat) {
    Write-Step "清 logcat(只保留本次窗口)"
    [void](Invoke-NativeCapture -NativeArgs @("logcat", "-c"))
}

Write-Step ("开 tap: POST /debug/tap {path:" + $TapPath + "}")
$tapOn = Invoke-Api -Method POST -Path "/debug/tap" -Body @{ path = $TapPath }
Write-Info ("tap: " + ($tapOn | ConvertTo-Json -Compress))
$tapRemote = $TapPath
if ($null -ne $tapOn -and $tapOn.path) { $tapRemote = [string]$tapOn.path }
Write-Info ("tap remote (server-resolved): " + $tapRemote)

Write-Step ("进环回: POST /loop/enter {songId:" + $SongId + "}")
$enter = Invoke-Api -Method POST -Path "/loop/enter" -Body @{ songId = $SongId; play = $true }
Write-Info ("enter: " + ($enter | ConvertTo-Json -Compress))
Start-Sleep -Milliseconds 1200

Write-Step "取实际窗口(window 可能刚从 run 快照写入)"
$st2 = Invoke-Api -Method GET -Path "/state"
$w2 = $st2.loop.window
if ($null -ne $w2) {
    $wa = [int][math]::Round($w2.loopStartUs / 1000)
    $wb = [int][math]::Round($w2.loopEndUs / 1000)
    Write-Info ("window: songId=" + $w2.songId + " A=" + $wa + "ms B=" + $wb + "ms")
    if ($w2.songId -ne $SongId) { Write-Warning ("窗口 songId=" + $w2.songId + " 与请求 " + $SongId + " 不一致") }
    if ([math]::Abs($wa - $AMs) -gt 5 -or [math]::Abs($wb - $BMs) -gt 5) {
        Write-Warning ("实际窗口与预期 A/B 不一致: 预期 A=" + $AMs + " B=" + $BMs)
    }
} else {
    Write-Warning "window 仍为 null:环回可能没起来(/loop/enter 返回 playing=false 时检查 repeat=one 与队列)"
}

Write-Step ("seek: POST /player/seek {positionMs:" + $SeekMs + "}")
$seek = Invoke-Api -Method POST -Path "/player/seek" -Body @{ positionMs = $SeekMs }
Write-Info ("seek: " + ($seek | ConvertTo-Json -Compress))

Write-Step ("录制 " + $Seconds + " 秒(期间保持前台播放;锁屏也能测,但会引入额外中断)")
Start-Sleep -Seconds $Seconds

Write-Step "关 tap: POST /debug/tap {path:null}"
$tapOff = Invoke-Api -Method POST -Path "/debug/tap" -Body @{ path = $null }
Write-Info ("tap off: " + ($tapOff | ConvertTo-Json -Compress))
Start-Sleep -Milliseconds 800   # 等 close 回填 WAV data 长度并 flush 事件文件

# 采集结束后的引擎计数(失败时第一个要看的就是这几项)
try {
    $st3 = Invoke-Api -Method GET -Path "/state"
    $l3 = $st3.loop
    if ($null -ne $l3) {
        Write-Info ("after: engine=" + $l3.engine + " seamCount=" + $l3.seamCount + " degrade=" + $l3.degradeCount +
            " ringComplete=" + $l3.isRingComplete + " replaying=" + $l3.isReplaying + " posUs=" + $l3.positionUs)
    }
} catch { Write-Warning ("读 /state 失败: " + $_.Exception.Message) }

# ============================================================================
# 4) 拉回 PCM + sidecar + logcat
# ============================================================================

$localPcm = Join-Path $outAbs ("tap-" + $SongId + "-" + $tag + ".wav")
[void](Pull-Artifact -RemotePath $tapRemote -LocalPath $localPcm -Required $true)

if (-not (Test-RiffHeader -Path $localPcm)) {
    Write-Warning ("拉回的 tap 不是 RIFF/WAV 头(可能 tap 未经过 configure,或文件被截断): " + $localPcm)
}
$pcmBytes = (Get-Item -LiteralPath $localPcm).Length
$srGuess = 0
$chGuess = 0
if ($pcmBytes -gt 44) {
    $fs = [System.IO.File]::OpenRead($localPcm)
    try {
        $h = New-Object byte[] 44
        [void]$fs.Read($h, 0, 44)
        $srGuess = [BitConverter]::ToInt32($h, 24)
        $chGuess = [BitConverter]::ToInt16($h, 22)
        Write-Info ("wav header: sr=" + $srGuess + " ch=" + $chGuess + " bytes=" + $pcmBytes)
    } finally { $fs.Dispose() }
}

Write-Step "定位 run 快照(sidecar jsonl)文件名"
$side = Find-Sidecar -TapRemote $tapRemote
$localJsonl = $null
if ($null -eq $side) {
    Write-Warning ("没找到 sidecar:试过 " + $tapRemote + ".jsonl / .jsonl / .events.jsonl。分析脚本将无接缝锚点。")
} else {
    Write-Info ("sidecar: " + $side.Path + "  (ls -l: " + ($side.Raw -replace "\s+", " ") + ")")
    $localJsonl = [System.IO.Path]::ChangeExtension($localPcm, ".jsonl")
    if (-not $localJsonl.EndsWith(".jsonl")) { $localJsonl = $localPcm + ".jsonl" }
    if (-not (Pull-Artifact -RemotePath $side.Path -LocalPath $localJsonl)) { $localJsonl = $null }
}

if (-not $NoLogcat) {
    Write-Step ("拉 LoopPerf 日志 -> " + $logcatFile)
    $filtered = & $adb logcat -d -v time 2>$null | Select-String -Pattern "LoopPerf|LoopAudio|LoopPcm|LoopSeam|seam" | ForEach-Object { [string]$_ }
    if ($filtered) {
        $filtered | Set-Content -LiteralPath $logcatFile -Encoding utf8
        Write-Info ("logcat lines: " + $filtered.Count)
        $filtered | Select-Object -Last 5 | ForEach-Object { Write-Info ("  " + $_) }
    } else {
        Write-Warning "logcat 没有 LoopPerf/seam 行(可能是 release 包或日志被清)"
    }
}

if ($null -ne $localJsonl) {
    $ev = Summarize-Events -Path $localJsonl
    Write-Info ("events: lines=" + $ev.Lines + " seam=" + $ev.Seams + " flush=" + $ev.Flush + " degrade=" + $ev.Degrade)
    if ($ev.Configure) { Write-Info ("  configure: " + $ev.Configure) }
    if ($ev.Run) { Write-Info ("  run      : " + $ev.Run) } else { Write-Warning "事件文件里没有 run 事件(writeTapRunLocked 未触发:window 为 null?)" }
    if ($ev.End) { Write-Info ("  end      : " + $ev.End) } else { Write-Warning "事件文件里没有 end 事件(tap 未正常关闭,或进程被杀)" }
    if ($ev.Seams -eq 0) { Write-Warning "0 个 seam 事件:录制期没跨过 B,或引擎没进重放(增大 -Seconds / 检查 seek 是否落在区间内)" }
}

if (-not $KeepDeviceFiles) {
    Write-Step "清理设备侧文件"
    foreach ($p in @($tapRemote, ($tapRemote + ".jsonl"))) {
        [void](Invoke-NativeCapture -NativeArgs @("shell", "run-as", $Pkg, "rm", "-f", $p))
    }
}

# ============================================================================
# 5) 分析
# ============================================================================

$analyzer = Join-Path $PSScriptRoot "loop-smoothness.ts"
$jsonOut = Join-Path $outAbs ("smoothness-" + $tag + ".json")
$analyzerLog = Join-Path $outAbs ("smoothness-" + $tag + ".log")
$analyzerExit = $null
$resultLine = $null
$manifest = [ordered]@{
    tool = "loop-tap-capture"
    timestamp = (Get-Date).ToString("o")
    pkg = $Pkg
    songId = $SongId
    window = @{ aMs = $AMs; bMs = $BMs; loopMs = $loopMs }
    seekMs = $SeekMs
    seconds = $Seconds
    tapPath = $tapRemote
    files = [ordered]@{
        pcm = $localPcm
        events = $localJsonl
        logcat = $(if (-not $NoLogcat) { $logcatFile } else { $null })
        smoothnessJson = $jsonOut
        smoothnessLog = $analyzerLog
    }
    analyzerExit = $null
    result = $null
}

if ($NoAnalyze) {
    Write-Note "-NoAnalyze:跳过分析"
} else {
    if (-not (Test-Path -LiteralPath $analyzer)) { throw ("分析脚本不存在: " + $analyzer) }
    $bun = (Get-Command bun -ErrorAction SilentlyContinue).Source
    if (-not $bun) { throw "bun 未找到(PATH 里需要 bun;分析脚本用 Bun/TS 写的)" }
    $aArgs = @("run", $analyzer, $localPcm, "--json", $jsonOut)
    if ($null -ne $localJsonl) { $aArgs += @("--events", $localJsonl) }
    Write-Step ("分析: bun " + ($aArgs -join " "))
    # 分析脚本把错误写到 stderr:同样要在 EAP=Continue 下合流,否则 FAIL 结果会变成脚本崩溃
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try {
        & $bun @aArgs 2>&1 | Tee-Object -FilePath $analyzerLog | ForEach-Object { Write-Host ([string]$_) }
        $analyzerExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $prevEap }
    $hit = Select-String -LiteralPath $analyzerLog -Pattern "^RESULT:" -ErrorAction SilentlyContinue | Select-Object -Last 1
    if ($hit) { $resultLine = ([string]$hit.Line).Trim() }
}

# ============================================================================
# 6) manifest + 人读结论
# ============================================================================

$manifest.analyzerExit = $analyzerExit
$manifest.result = $resultLine
$manifestFile = Join-Path $outAbs ("capture-" + $SongId + "-" + $tag + ".json")
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestFile -Encoding utf8

Write-Host ""
Write-Host "== artifacts =="
Write-Host ("  pcm      : " + $localPcm + "  (" + $pcmBytes + " bytes, sr=" + $srGuess + " ch=" + $chGuess + ")")
if ($null -ne $localJsonl) { Write-Host ("  events   : " + $localJsonl) }
if (-not $NoLogcat) { Write-Host ("  logcat   : " + $logcatFile) }
if (-not $NoAnalyze) {
    Write-Host ("  analysis : " + $jsonOut)
    Write-Host ("  log      : " + $analyzerLog)
}
Write-Host ("  manifest : " + $manifestFile)
if ($resultLine) { Write-Host ("  " + $resultLine) }

if ($null -ne $analyzerExit -and $analyzerExit -ne 0) {
    Write-Host ""
    Write-Host "判读(按顺序):"
    Write-Host "  1) zeroRuns FAIL      -> 输出里有成段数字静音 = 缓冲/预填没接上(先看 logcat 的 stallCount/covered/cursor)"
    Write-Host "  2) clicks SEAM FAIL   -> 接缝点样本跳变 = 接缝爆音(环路取点/拼接边界问题)"
    Write-Host "  3) clicks 非 SEAM     -> 与接缝无关的爆音 = 解码/写入损坏"
    Write-Host "  4) seamAlign FAIL     -> 帧距/相位/PTS 不符 = 环长度或取素材错位"
    Write-Host "  5) dupCheck FAIL      -> 相邻周期不一致 = ring 被改写/部分覆盖(不真的是同一段在循环)"
    Write-Host "  6) rmsWindows FAIL    -> 中途掉块后恢复 = delegate 欠载"
    exit $analyzerExit
}
exit 0
