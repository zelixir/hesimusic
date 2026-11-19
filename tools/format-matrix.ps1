<#
.SYNOPSIS
  格式矩阵(真机):对 5 个短样本逐一验证 [A,B) 解码确定性、帧数契约与尾部对齐。

.DESCRIPTION
  唯一能给出"真实 PCM 逐字节/对齐偏差"的通道。流程:
    1. 把 app/src/test/resources/loop-format/*. 推到 demo 包的**应用专属外部目录**
       /sdcard/Android/data/com.zjr.hesimusic.demo/files/formats/
       (demo 变体没有存储权限,不能读 /sdcard/Music;应用专属目录不需要运行时权限);
    2. 同一区间 [A,B) 调两次 POST /debug/decode-range,比较 SHA-256 => 解码确定性;
    3. 解 [A-1s, B],裁剪掉前导帧后与直接 [A,B) 比较帧数 => 宽窗口裁剪偏差;
    4. 短窗 [0,1s)、贴尾窗 [duration-50ms, duration) => 不抛异常且帧数正确;
    5. 请求 tailFrames,端点返回 tailPcmBase64(尾部 PCM16);对直接/宽窗口的尾部逐样本
       做 +/-32 帧 lag 搜索 => 量出真实"样本对齐偏差(帧)";端点没返回该字段时明确标 UNMEASURED。

  前置:demo debug 构建已启动 LoopDebugServer(127.0.0.1:8765),并 adb forward tcp:18765 tcp:8765。
  脚本自己会建 forward;不会安装 APK、不会清数据。

.EXAMPLE
  powershell -NoProfile -File tools/format-matrix.ps1
  powershell -NoProfile -File tools/format-matrix.ps1 -Only "*flac*","*m4a*" -SkipPush
#>
param(
    [string]$Base = "http://127.0.0.1:18765",
    # demo 变体(applicationIdSuffix=.demo)没有存储权限:必须放应用专属外部目录,
    # 该目录属于 com.zjr.hesimusic.demo,app 自己可读,不需要 MANAGE_EXTERNAL_STORAGE。
    [string]$DeviceDir = "/sdcard/Android/data/com.zjr.hesimusic.demo/files/formats",
    # tailFrames>0 时请求 tailPcmBase64,用于真实 PCM 尾部偏差(帧);0 = 关闭该比对。
    [int]$TailFrames = 8192,
    [int]$AStartMs = 500,
    [int]$WindowMs = 1500,
    [int]$WideLeadMs = 1000,
    [string[]]$Only = @(),
    [string]$AdbPath = "",
    [switch]$SkipPush,
    [switch]$NoForward
)

$ErrorActionPreference = "Stop"
# 兼容 powershell -File 的两种写法:-Only "*flac*","*m4a*"(数组)或 -Only "*flac*,*m4a*"(单串)。
$Only = @($Only | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim().Trim('"').Trim("'") } | Where-Object { $_ -ne "" })
$SampleDir = Join-Path $PSScriptRoot "..\app\src\test\resources\loop-format"
$LocalPort = 18765
$RemotePort = 8765
$script:TailFrames = $TailFrames

# 期望值来自容器头(与 app/src/test/.../AudioContainerProbe.kt 一致)。
# DurationMs 是"用户时长"(已扣除编码器前导),用于选择窗口与贴尾位置。
$Samples = @(
    [pscustomobject]@{ Name = "css-44100-flac.flac";      Format = "flac";   Sr = 44100; Ch = 2; DurationMs = 3200; Priming = 0 },
    [pscustomobject]@{ Name = "css-44100-vorbis.ogg";     Format = "vorbis"; Sr = 44100; Ch = 2; DurationMs = 4000; Priming = 0 },
    [pscustomobject]@{ Name = "css-48000-opus.opus";      Format = "opus";   Sr = 48000; Ch = 2; DurationMs = 4000; Priming = 312 },
    [pscustomobject]@{ Name = "css-48000-m4a-aac-lc.m4a"; Format = "aac-lc"; Sr = 48000; Ch = 2; DurationMs = 4000; Priming = 1024 },
    [pscustomobject]@{ Name = "css-44100-mp3-lame.mp3";   Format = "mp3";    Sr = 44100; Ch = 2; DurationMs = 4000; Priming = 2160 }
)

$script:Adb = $null

function Resolve-Adb {
    if ($AdbPath -ne "" -and (Test-Path $AdbPath)) { return (Resolve-Path $AdbPath).Path }
    if ($env:ANDROID_HOME) {
        $p = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
        if (Test-Path $p) { return $p }
    }
    if ($env:ANDROID_SDK_ROOT) {
        $p = Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"
        if (Test-Path $p) { return $p }
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd -ne $null) { return $cmd.Source }
    return $null
}

function Ensure-Forward {
    if ($NoForward) { return }
    $list = (& $script:Adb forward --list) 2>$null
    if (-not ($list -match ("tcp:" + $LocalPort))) {
        & $script:Adb forward ("tcp:" + $LocalPort) ("tcp:" + $RemotePort) | Out-Null
        Start-Sleep -Milliseconds 200
    }
}

function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body = $null)
    $uri = "$Base$Path"
    $attempt = 0
    while ($true) {
        $attempt++
        try {
            if ($null -ne $Body) {
                $json = $Body | ConvertTo-Json -Compress
                return Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec 30 `
                    -ContentType "application/json; charset=utf-8" `
                    -Body ([System.Text.Encoding]::UTF8.GetBytes($json))
            }
            return Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec 30
        } catch {
            if ($attempt -ge 2 -or $NoForward -or $null -eq $script:Adb) { throw }
            # adb/系统重启会清掉 forward:重建后重试一次
            & $script:Adb forward --remove ("tcp:" + $LocalPort) 2>$null | Out-Null
            & $script:Adb forward ("tcp:" + $LocalPort) ("tcp:" + $RemotePort) | Out-Null
            Start-Sleep -Milliseconds 300
        }
    }
}

function Decode-Range {
    param([string]$Path, [long]$AMs, [long]$BMs)
    $r = Invoke-Api POST "/debug/decode-range" @{ path = $Path; aMs = $AMs; bMs = $BMs; tailFrames = $script:TailFrames }
    foreach ($field in @("frames", "bytes", "sha256", "sampleRate", "channels")) {
        if ($null -eq $r.$field) { throw ("decode-range response missing '" + $field + "'") }
    }
    return $r
}

$script:Bpf = 0

<#
  PCM 尾部 lag:比较 direct 与 wide 各自最后 n 帧,在 +/-MaxLagFrames 内找最佳对齐。
  lag = 0 且匹配率 >= 0.999 才算逐样本对齐。
#>
function Measure-TailLag {
    param([byte[]]$Direct, [byte[]]$Wide, [int]$BytesPerFrame, [int]$MaxLagFrames)
    $df = [int][Math]::Floor($Direct.Length / $BytesPerFrame)
    $wf = [int][Math]::Floor($Wide.Length / $BytesPerFrame)
    $n = [Math]::Min($df, $wf)
    $n = [Math]::Min($n, 8192)
    if ($n -le 0) { return $null }
    # 两段长度不同(wide 多了 1s 前导):只比较各自最后 n 帧,再在 +/-MaxLagFrames 内对齐。
    $dBase = ($df - $n) * $BytesPerFrame
    $wBase = ($wf - $n) * $BytesPerFrame
    $bestLag = 0
    $bestScore = -1.0
    for ($lag = -$MaxLagFrames; $lag -le $MaxLagFrames; $lag++) {
        $dOff = [Math]::Max(0, $lag)
        $wOff = [Math]::Max(0, -$lag)
        $count = [Math]::Min($n - $dOff, $n - $wOff)
        if ($count -le 0) { continue }
        $equal = 0
        for ($i = 0; $i -lt $count; $i++) {
            $db = $dBase + ($dOff + $i) * $BytesPerFrame
            $wb = $wBase + ($wOff + $i) * $BytesPerFrame
            $same = $true
            for ($b = 0; $b -lt $BytesPerFrame; $b++) {
                if ($Direct[$db + $b] -ne $Wide[$wb + $b]) { $same = $false; break }
            }
            if ($same) { $equal++ }
        }
        $score = [double]$equal / [double]$count
        if ($score -gt $bestScore -or ($score -eq $bestScore -and [Math]::Abs($lag) -lt [Math]::Abs($bestLag))) {
            $bestScore = $score
            $bestLag = $lag
        }
    }
    return [pscustomobject]@{ Lag = $bestLag; Score = $bestScore; Frames = $n }
}

function Get-PcmBytes {
    param($Response)
    $props = @($Response.PSObject.Properties.Name)
    foreach ($field in @("tailPcmBase64", "pcmBase64")) {
        if ($props -contains $field) {
            $v = [string]$Response.$field
            if ($v.Length -gt 0) { return [Convert]::FromBase64String($v) }
        }
    }
    return $null
}

function Add-Check {
    param($Condition, [string]$Message)
    if (-not $Condition) { [void]$script:Errors.Add($Message) }
}

# ---------------------------------------------------------------- prerequisites

if (-not (Test-Path $SampleDir)) {
    Write-Host ("[FATAL] sample dir missing: " + $SampleDir)
    exit 2
}

if (-not ($SkipPush -and $NoForward)) {
    $script:Adb = Resolve-Adb
    if ($null -eq $script:Adb) {
        Write-Host "[FATAL] adb not found (set ANDROID_HOME or pass -AdbPath); use -SkipPush -NoForward for a dry run"
        exit 2
    }
    if (-not $SkipPush) {
        & $script:Adb shell ("mkdir -p " + $DeviceDir) 2>$null | Out-Null
    }
    try { Ensure-Forward } catch {
        Write-Host ("[FATAL] adb forward failed: " + $_.Exception.Message)
        exit 2
    }
}

try {
    Invoke-Api GET "/state" | Out-Null
} catch {
    Write-Host ("[FATAL] debug endpoint unreachable at " + $Base + "/state: " + $_.Exception.Message)
    Write-Host "        start the debug build (LoopDebugServer) and adb forward tcp:18765 tcp:8765"
    exit 2
}

# ---------------------------------------------------------------- main

$selected = @()
foreach ($s in $Samples) {
    if ($Only.Count -eq 0) { $selected += $s; continue }
    foreach ($pattern in $Only) {
        if ($s.Name -like $pattern) { $selected += $s; break }
    }
}

$passCount = 0
$failCount = 0
$skipCount = 0
$warnCount = 0
$rows = New-Object System.Collections.ArrayList

foreach ($s in $selected) {
    $local = Join-Path $SampleDir $s.Name
    $remote = $DeviceDir + "/" + $s.Name
    if (-not (Test-Path $local)) {
        Write-Host ("[SKIP] " + $s.Name + " (local fixture missing)")
        $skipCount++
        continue
    }
    if (-not $SkipPush) {
        $pushOut = (& $script:Adb push $local $remote) 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host ("[FAIL] " + $s.Name + " push failed: " + ($pushOut -join " "))
            $failCount++
            continue
        }
    }

    $script:Errors = New-Object System.Collections.ArrayList
    $a = [long]$AStartMs
    $b = [long][Math]::Min([long]($AStartMs + $WindowMs), [long]($s.DurationMs - 100))
    if ($b -le $a) { $b = [long]$s.DurationMs - 50 }
    $pcmText = if ($TailFrames -le 0) {
        "DISABLED(tailFrames=0)"
    } else {
        "UNMEASURED(endpoint returned no tailPcmBase64)"
    }
    $detText = "n/a"
    $frameText = "n/a"
    $trimText = "n/a"
    $shortText = "n/a"
    $tailText = "n/a"

    try {
        $d1 = Decode-Range $remote $a $b
        $d2 = Decode-Range $remote $a $b
        $shaEqual = ($d1.sha256 -eq $d2.sha256)
        $detText = if ($shaEqual) { "sha-equal" } else { "sha-DIFF" }
        Add-Check ($shaEqual) ("two decodes of [" + $a + "," + $b + ") differ: " + $d1.sha256 + " vs " + $d2.sha256 + " (non-deterministic)")
        Add-Check ([long]$d1.frames -eq [long]$d2.frames) ("two decodes frame count differ: " + $d1.frames + " vs " + $d2.frames)

        Add-Check ([int]$d1.sampleRate -eq $s.Sr) ("sampleRate " + $d1.sampleRate + " != expected " + $s.Sr)
        Add-Check ([int]$d1.channels -eq $s.Ch) ("channels " + $d1.channels + " != expected " + $s.Ch)
        $expectedFrames = [long][Math]::Ceiling(([double]($b - $a) * [double]$s.Sr) / 1000.0)
        $frameText = ([string]$d1.frames + "/" + [string]$expectedFrames)
        Add-Check ([long]$d1.frames -eq $expectedFrames) ("frames " + $d1.frames + " != ceil(" + ($b - $a) + "ms * " + $s.Sr + ") = " + $expectedFrames)
        Add-Check ([long]$d1.bytes -eq ([long]$d1.frames * $s.Ch * 2)) ("bytes " + $d1.bytes + " != frames*ch*2 = " + ([long]$d1.frames * $s.Ch * 2))
        Add-Check (([string]$d1.sha256).Length -eq 64) ("sha256 length " + ([string]$d1.sha256).Length + " != 64")

        # 短窗 [0,1s)
        $short = Decode-Range $remote 0 1000
        $shortExpected = [long]$s.Sr
        $shortText = ([string]$short.frames + "/" + [string]$shortExpected)
        Add-Check ([long]$short.frames -eq $shortExpected) ("short [0,1s) frames " + $short.frames + " != " + $shortExpected)

        # 贴尾窗 [duration-50ms, duration)
        $tailA = [long]$s.DurationMs - 50
        $tail = Decode-Range $remote $tailA ([long]$s.DurationMs)
        $tailExpected = [long][Math]::Ceiling((50.0 * [double]$s.Sr) / 1000.0)
        $tailText = ([string]$tail.frames + "/" + [string]$tailExpected)
        Add-Check ([long]$tail.frames -eq $tailExpected) ("tail [" + $tailA + "," + $s.DurationMs + ") frames " + $tail.frames + " != " + $tailExpected)

        # 宽窗口 [A-1s, B):裁剪前导后帧数必须与直接 [A,B) 完全一致
        $wideA = [long][Math]::Max(0, $a - $WideLeadMs)
        $wide = Decode-Range $remote $wideA $b
        $skip = [long][Math]::Ceiling(([double]$a * [double]$s.Sr) / 1000.0) - [long][Math]::Ceiling(([double]$wideA * [double]$s.Sr) / 1000.0)
        $trimDelta = [long]$wide.frames - $skip - [long]$d1.frames
        $trimText = [string]$trimDelta
        Add-Check ($trimDelta -eq 0) ("wide [" + $wideA + "," + $b + ") trim delta " + $trimDelta + " frames != 0")
        $wideExpected = [long][Math]::Ceiling(([double]($b - $wideA) * [double]$s.Sr) / 1000.0)
        Add-Check ([long]$wide.frames -eq $wideExpected) ("wide frames " + $wide.frames + " != " + $wideExpected)

        # 可选:PCM 尾部逐样本比对(需要端点扩展返回 PCM;当前端点没有 => 明确标 UNMEASURED)
        $directPcm = Get-PcmBytes $d1
        $widePcm = Get-PcmBytes $wide
        if ($null -ne $directPcm -and $null -ne $widePcm) {
            $m = Measure-TailLag $directPcm $widePcm ($s.Ch * 2) 32
            if ($null -eq $m) {
                $pcmText = "EMPTY"
                Add-Check $false "PCM payload present but too short to compare"
            } else {
                $pcmText = ([string]$m.Lag + " frames match=" + [string][Math]::Round($m.Score, 4))
                Add-Check ($m.Lag -eq 0 -and $m.Score -ge 0.999) ("PCM tail deviation " + $pcmText + " (expected 0 frames)")
            }
        } elseif ($TailFrames -gt 0) {
            $warnCount++
        }
    } catch {
        [void]$script:Errors.Add("decode failed: " + $_.Exception.Message)
    }

    if ($script:Errors.Count -eq 0) {
        Write-Host ("[PASS] " + $s.Name + " format=" + $s.Format + " det=" + $detText + " frames=" + $frameText + " wideTrim=" + $trimText + " pcmTail=" + $pcmText + " short=" + $shortText + " tail=" + $tailText)
        $passCount++
        [void]$rows.Add([pscustomobject]@{ File = $s.Name; Format = $s.Format; Status = "PASS"; Determinism = $detText; Frames = $frameText; WideTrim = $trimText; PcmTail = $pcmText })
    } else {
        Write-Host ("[FAIL] " + $s.Name + " format=" + $s.Format + " det=" + $detText + " frames=" + $frameText + " wideTrim=" + $trimText + " pcmTail=" + $pcmText + " short=" + $shortText + " tail=" + $tailText)
        foreach ($err in $script:Errors) { Write-Host ("        - " + $err) }
        $failCount++
        [void]$rows.Add([pscustomobject]@{ File = $s.Name; Format = $s.Format; Status = "FAIL"; Determinism = $detText; Frames = $frameText; WideTrim = $trimText; PcmTail = $pcmText })
    }
}

Write-Host ""
Write-Host "=== format matrix summary ==="
$table = $rows | Format-Table -AutoSize | Out-String
Write-Host $table
Write-Host ("pass=" + $passCount + " fail=" + $failCount + " skip=" + $skipCount + " pcm-unmeasured=" + $warnCount)
Write-Host ""
Write-Host "说明:"
Write-Host "  - det=sha-equal 证明同一区间两次解码逐字节相同(解码确定性);"
Write-Host "  - frames/det 与 wideTrim 是帧号口径的精确性(trimDelta 必须为 0);"
Write-Host "  - pcmTail=N frames 是直接窗口与宽窗口尾部的逐样本 lag(0 = 完全对齐);"
Write-Host "    端点需支持 tailFrames -> tailPcmBase64;若显示 UNMEASURED,说明该构建/端点没返回 PCM。"
Write-Host ("  - 样本目录(设备): " + $DeviceDir + "  tailFrames=" + $TailFrames)
if ($skipCount -gt 0) { Write-Host ("  注意: " + $skipCount + " 个样本缺失,已跳过(不判 PASS/FAIL)") }

if ($failCount -gt 0) { exit 1 }
exit 0