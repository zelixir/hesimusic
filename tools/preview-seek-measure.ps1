<#
.SYNOPSIS
    分析页(无缝循环结果页)seek 响应与"循环区外 seek"语义的真机量测脚本(issue 00023)。

.DESCRIPTION
    测什么(全部经 debug 服务 /debug/preview 与 /debug/preview-seek,不依赖人肉点击):

      A. 区外 seek(循环区外自由试听)
         - 判定"落点正确":displayAfterMs 与请求值差 <= -PositionToleranceMs,且 outsideLoop=true,
           且 sourceWindow == [0, duration](整曲源);
         - 判定"有声音":framesDelta > 0(TapAudioSink 真正交给 AudioTrack 的帧数增量);
         - 判定"不回弹":+800ms 再读一次,display 必须继续向前(不小于落点,且 <= 目标+2s)。

      B. 拖回区间(恢复循环)
         - outsideLoop=false、sourceWindow 回到 [A,B)、display 落在区间内。

      C. 区内 5 次 seek(快速路径)
         - advanceMs(请求 -> ExoPlayer 播放头推进到落点+30ms 且 READY)<= -AdvanceBudgetMs 判 PASS;
         - "UI 没有重建播放器/source":用 logcat 对账 —— 这一批里
           source installed 必须为 0 次,source seek display= 必须为 5 次;
         - 记录 applyMs(主线程应用延迟),它才是 UI 层该负责的部分。

      D. A/B 不变:页面 A/B 文本在整轮前后必须完全一致。

    判定口径说明:
      * advanceMs 用 ExoPlayer 的播放头(渲染位置)推进作用"出声"的代理 —— 它比 frames 更严格
        (frames 可能只是把音频塞进 AudioTrack 缓冲);framesDelta 作为旁证一起打印。
      * 暂停态只测 applyMs(不会推进),脚本会提示。

.PARAMETER Device
    adb 序列号(默认 b1e84dad)。

.PARAMETER Port
    本机 adb forward 的端口(默认 18765 -> 设备 8765)。

.PARAMETER SongId
    目标歌曲 id(默认 40169,对应 /navigate route loop/40169)。

.PARAMETER InsideSeeks
    区内 seek 的展示位置(ms)。缺省按 A/B 六等分取 5 个点。

.PARAMETER OutsideSeeks
    区外 seek 的展示位置(ms),默认 30000 与 200000(该曲 A=92752 / B=173546 / 时长 225045)。

.PARAMETER AdvanceBudgetMs
    区内 seek 的"出声音"预算(默认 100ms,issue 00023 验收线)。

.PARAMETER UiTap
    额外跑一遍"UI 波形点击"路径(用 uiautomator 抓波形区域几何后 input tap),验证端到端
    reducer -> effect -> PreviewPlayer 的链路(默认只跑 debug 端点)。

.PARAMETER WaitBandFill
    换 source 前等当前环带填完(避免撞上已知的 loop/audio/source 释放 UAF,
    见与 seam-check-fix 的交接)。默认开启;UAF 修好后可用 -WaitBandFill:$false 跳过。
    估算:环带解码约 7x 实时 -> 等 (窗口秒数/7 + 4s)。

.PARAMETER OutFile
    可选:把 markdown 对照表写到该文件。

.EXAMPLE
    powershell -NoProfile -File tools\preview-seek-measure.ps1 -Device b1e84dad

.EXAMPLE
    # UAF 修好后跳过等待,并额外验证 UI 点击路径
    powershell -NoProfile -File tools\preview-seek-measure.ps1 -WaitBandFill:$false -UiTap
#>
param(
    [string]$Device = "b1e84dad",
    [int]$Port = 18765,
    [long]$SongId = 40169,
    [long[]]$OutsideSeeks = @(30000, 200000),
    [long[]]$InsideSeeks = @(),
    [int]$AdvanceBudgetMs = 100,
    [int]$PositionToleranceMs = 400,
    [switch]$UiTap,
    [bool]$WaitBandFill = $true,
    [double]$BandFillRate = 7.0,
    [string]$OutFile = ""
)

$ErrorActionPreference = "Stop"
$Adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$Base = "http://127.0.0.1:" + $Port
$Log = New-Object System.Collections.Generic.List[string]
$Rows = New-Object System.Collections.Generic.List[object]
$Newline = [Environment]::NewLine

function Write-Log([string]$text) {
    Write-Host $text
    [void]$Log.Add($text)
}

function Invoke-Json([string]$method, [string]$path, $body) {
    $curlArgs = @("-s", "-m", "15", "-X", $method, ($Base + $path))
    if ($null -ne $body) {
        $curlArgs += @("-H", "Content-Type: application/json", "-d", ($body | ConvertTo-Json -Compress))
    }
    $raw = & curl.exe @curlArgs
    if (-not $raw) { return $null }
    try { return ($raw | ConvertFrom-Json) } catch { return $null }
}

function Get-Preview() { return (Invoke-Json "GET" "/debug/preview" $null) }

function Measure-Seek([long]$ms) {
    return (Invoke-Json "POST" "/debug/preview-seek" @{ positionMs = $ms })
}

function Get-UiDump() {
    & $Adb -s $Device shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
    return ((& $Adb -s $Device shell cat /sdcard/ui.xml) -join "")
}

function Get-UiTexts() {
    $xml = Get-UiDump
    return ([regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
}

function Tap-Text([string]$text) {
    $xml = Get-UiDump
    $pattern = 'text="' + [regex]::Escape($text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($xml, $pattern)
    if (-not $m.Success) { Write-Log ("  [tap] 找不到文本: " + $text); return $false }
    $x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
    $y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    & $Adb -s $Device shell input tap $x $y | Out-Null
    Write-Log ("  [tap] 文本 '" + $text + "' @ (" + $x + "," + $y + ")")
    return $true
}

function Get-AbText() {
    $texts = Get-UiTexts
    $a = $texts | Where-Object { $_ -match '^A \d{2}:\d{2}\.' } | Select-Object -First 1
    $b = $texts | Where-Object { $_ -match '^B \d{2}:\d{2}\.' } | Select-Object -First 1
    return @($a, $b)
}

function Wait-BandFillFor([long]$windowMs, [string]$why) {
    if (-not $WaitBandFill) { return }
    $seconds = [math]::Ceiling(($windowMs / 1000.0) / $BandFillRate + 4.0)
    Write-Log ("  [wait] " + $why + ": 等环带填完约 " + $seconds + "s(解码约 " + $BandFillRate + "x 实时)")
    Start-Sleep -Seconds $seconds
}

function Assert([bool]$ok, [string]$what) {
    if ($ok) {
        Write-Log ("  [PASS] " + $what)
    } else {
        Write-Log ("  [FAIL] " + $what)
    }
    return $ok
}

function Add-Row([string]$phase, [long]$requested, $measure, [bool]$positionOk, [bool]$soundOk, [string]$note) {
    $applyMs = $null
    $advanceMs = $null
    $framesDelta = $null
    $displayMs = $null
    $outside = $null
    $srcWin = ""
    if ($null -ne $measure) {
        $applyMs = $measure.applyMs
        $advanceMs = $measure.advanceMs
        $framesDelta = $measure.framesDelta
        $displayMs = $measure.displayAfterMs
        $outside = $measure.outsideLoopAfter
        $srcWin = "" + $measure.sourceWindowAfterA_ms + ".." + $measure.sourceWindowAfterB_ms
    }
    $Rows.Add([pscustomobject]@{
        phase       = $phase
        requested   = $requested
        applyMs     = $applyMs
        advanceMs   = $advanceMs
        framesDelta = $framesDelta
        displayMs   = $displayMs
        outside     = $outside
        sourceWin   = $srcWin
        positionOk  = $positionOk
        soundOk     = $soundOk
        note        = $note
    })
}

# ---------------------------------------------------------------- 前置检查

Write-Log ("== issue 00023 预览 seek 量测 ==")
Write-Log ("device=" + $Device + " port=" + $Port + " songId=" + $SongId + " budget=" + $AdvanceBudgetMs + "ms")

$devices = (& $Adb devices) -join $Newline
if ($devices -notmatch [regex]::Escape($Device)) { throw ("设备不在线: " + $Device) }

$probe = Get-Preview
if ($null -eq $probe) {
    throw ("/debug/preview 无响应:先确认 adb forward tcp:" + $Port + " tcp:8765 与 debug 包")
}

Invoke-Json "POST" "/navigate" @{ route = ("loop/" + $SongId) } | Out-Null
Start-Sleep -Seconds 4
$probe = Get-Preview
if ($null -eq $probe -or -not $probe.snapshot.prepared) {
    Write-Log "  [setup] 结果页未 prepare,点『仅循环段』起播"
    Tap-Text "仅循环段" | Out-Null
    Start-Sleep -Seconds 4
    $probe = Get-Preview
}
if ($null -eq $probe -or -not $probe.snapshot.isPlaying) {
    throw "预览没有在播:先手动起播(点『仅循环段』)再跑本脚本"
}
if ($probe.snapshot.engine -ne "SOURCE") {
    Write-Log ("  [warn] engine=" + $probe.snapshot.engine + "(本脚本的区外语义按 SOURCE 口径判定)")
}

$durationMs = [long]$probe.snapshot.durationMs
$aMs = [long]$probe.snapshot.aMs
$bMs = [long]$probe.snapshot.bMs
$loopWindowMs = $bMs - $aMs
Write-Log ("  [setup] duration=" + $durationMs + " A=" + $aMs + " B=" + $bMs + " mode=" + $probe.snapshot.mode)

if ($InsideSeeks.Count -eq 0) {
    $step = [long](($bMs - $aMs) / 6)
    $InsideSeeks = @(
        [long]($aMs + $step),
        [long]($aMs + 2 * $step),
        [long]($aMs + 3 * $step),
        [long]($aMs + 4 * $step),
        [long]($aMs + 5 * $step)
    )
}
Write-Log ("  [setup] inside target=" + ($InsideSeeks -join ",") + " outside target=" + ($OutsideSeeks -join ","))

$abBefore = Get-AbText
Write-Log ("  [setup] A/B 文本(前): " + ($abBefore -join " | "))

$allOk = $true

# ---------------------------------------------------------------- A. 区外 seek

Write-Log ""
Write-Log "== A. 循环区外 seek(自由试听:按文件时间,不折返) =="
foreach ($target in $OutsideSeeks) {
    Wait-BandFillFor $loopWindowMs ("区外 seek " + $target + "ms 前")
    $m = Measure-Seek $target
    if ($null -eq $m) { Write-Log "  [FAIL] /debug/preview-seek 无响应"; $allOk = $false; continue }
    Start-Sleep -Milliseconds 800
    $after = Get-Preview
    $display = [long]$after.snapshot.displayMs
    $outside = [bool]$after.snapshot.outsideLoop
    $srcA = $after.snapshot.sourceWindowA_ms
    $srcB = $after.snapshot.sourceWindowB_ms

    $positionOk = ([math]::Abs([long]$m.displayAfterMs - $target) -le $PositionToleranceMs) -and $outside
    $soundOk = ([long]$m.framesDelta -gt 0) -and ($null -ne $m.advanceMs)
    $noBounce = ($display -ge $target -and $display -le ($target + 2000))
    $allOk = (Assert $positionOk ("区外 " + $target + "ms 落点正确(display=" + $m.displayAfterMs + ")")) -and $allOk
    $allOk = (Assert $soundOk ("区外 " + $target + "ms 有音频输出(framesDelta=" + $m.framesDelta + " advanceMs=" + $m.advanceMs + ")")) -and $allOk
    $allOk = (Assert $noBounce ("区外 " + $target + "ms 进度不回弹(+800ms display=" + $display + ")")) -and $allOk
    $allOk = (Assert ($srcA -eq 0 -and $srcB -eq $durationMs) ("区外数据源=整曲 [" + $srcA + "," + $srcB + ")")) -and $allOk
    Add-Row "outside" $target $m ($positionOk -and $noBounce) $soundOk ("src=" + $srcA + ".." + $srcB + " display800ms=" + $display)
}

# ---------------------------------------------------------------- B. 拖回区间

Write-Log ""
Write-Log "== B. 拖回 [A,B)(恢复循环) =="
$restoreTarget = [long](($aMs + $bMs) / 2)
Wait-BandFillFor $durationMs ("拖回区间前(整曲环带)")
$m = Measure-Seek $restoreTarget
Start-Sleep -Milliseconds 800
$after = Get-Preview
$restored = (-not [bool]$after.snapshot.outsideLoop) -and
    ($after.snapshot.sourceWindowA_ms -eq $aMs) -and
    ($after.snapshot.sourceWindowB_ms -eq $bMs)
$inLoop = ([long]$after.snapshot.displayMs -ge $aMs) -and ([long]$after.snapshot.displayMs -lt $bMs)
$allOk = (Assert $restored ("恢复循环: outside=" + $after.snapshot.outsideLoop + " src=" + $after.snapshot.sourceWindowA_ms + ".." + $after.snapshot.sourceWindowB_ms)) -and $allOk
$allOk = (Assert $inLoop ("恢复后位置落在区间内 display=" + $after.snapshot.displayMs)) -and $allOk
Add-Row "restore" $restoreTarget $m $restored $true ("display800ms=" + $after.snapshot.displayMs)

# ---------------------------------------------------------------- C. 区内 5 次

Write-Log ""
Write-Log "== C. 区内连续 seek(快速路径,UI 不得重建 source) =="
& $Adb -s $Device logcat -c | Out-Null
Start-Sleep -Milliseconds 300

foreach ($target in $InsideSeeks) {
    $m = Measure-Seek $target
    if ($null -eq $m) { Write-Log "  [FAIL] /debug/preview-seek 无响应"; $allOk = $false; continue }
    $fast = ($null -ne $m.advanceMs) -and ([long]$m.advanceMs -le $AdvanceBudgetMs)
    $noSwap = (-not [bool]$m.outsideLoopAfter) -and
        ($m.sourceWindowAfterA_ms -eq $aMs) -and ($m.sourceWindowAfterB_ms -eq $bMs)
    $allOk = (Assert $fast ("区内 " + $target + "ms 出声音 <= " + $AdvanceBudgetMs + "ms(advance=" + $m.advanceMs + " apply=" + $m.applyMs + ")")) -and $allOk
    $allOk = (Assert $noSwap ("区内 " + $target + "ms 未换源(src=" + $m.sourceWindowAfterA_ms + ".." + $m.sourceWindowAfterB_ms + ")")) -and $allOk
    Add-Row "inside" $target $m $noSwap $fast ""
    Start-Sleep -Milliseconds 500
}

$logLines = (& $Adb -s $Device logcat -d -s LoopPreview) -join $Newline
$installed = ([regex]::Matches($logLines, 'source installed')).Count
$seekApplied = ([regex]::Matches($logLines, 'source seek display=')).Count
$allOk = (Assert ($installed -eq 0) ("区内 5 次期间 source installed 次数=" + $installed + "(必须 0)")) -and $allOk
$allOk = (Assert ($seekApplied -ge $InsideSeeks.Count) ("区内 5 次期间 source seek 次数=" + $seekApplied + "(应 >= " + $InsideSeeks.Count + ")")) -and $allOk

# ---------------------------------------------------------------- D. A/B 不变

Write-Log ""
Write-Log "== D. A/B 与循环行为不变 =="
$abAfter = Get-AbText
$abSame = ($abBefore[0] -eq $abAfter[0]) -and ($abBefore[1] -eq $abAfter[1])
$allOk = (Assert $abSame ("A/B 文本前后一致: " + ($abAfter -join " | "))) -and $allOk

# ---------------------------------------------------------------- E. 可选:UI 波形点击路径

if ($UiTap) {
    Write-Log ""
    Write-Log "== E. UI 波形点击路径(端到端) =="
    $xml = Get-UiDump
    $pattern = 'class="android.widget.ScrollView"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $scroll = [regex]::Match($xml, $pattern)
    if (-not $scroll.Success) {
        Write-Log "  [skip] 抓不到 ScrollView 几何"
    } else {
        $left = [int]$scroll.Groups[1].Value
        $right = [int]$scroll.Groups[3].Value
        $tapY = 700
        $uiTarget = [long]$OutsideSeeks[0]
        $x = [int]($left + ($uiTarget / $durationMs) * ($right - $left))
        Wait-BandFillFor $loopWindowMs ("UI 点击区外前")
        & $Adb -s $Device shell input tap $x $tapY | Out-Null
        Start-Sleep -Seconds 2
        $after = Get-Preview
        $uiOutside = [bool]$after.snapshot.outsideLoop
        $uiDisplay = [long]$after.snapshot.displayMs
        $hint = (Get-UiTexts) | Where-Object { $_ -match '循环已暂停 · 按文件时间试听' } | Select-Object -First 1
        $allOk = (Assert $uiOutside ("UI 点击区外进入自由试听(display=" + $uiDisplay + ")")) -and $allOk
        $allOk = (Assert ($null -ne $hint) "UI 显示『循环已暂停 · 按文件时间试听』提示") -and $allOk
        Add-Row "ui-tap-outside" $uiTarget $null $uiOutside $true ("display=" + $uiDisplay)
    }
}

# ---------------------------------------------------------------- 输出

Write-Log ""
Write-Log "== 对照表 =="
$table = @()
$table += "| phase | requested(ms) | applyMs | advanceMs | framesDelta | display(ms) | outside | sourceWindow | 判定 |"
$table += "|---|---|---|---|---|---|---|---|---|"
foreach ($row in $Rows) {
    $ok = if ($row.positionOk -and $row.soundOk) { "PASS" } else { "FAIL" }
    $table += ("| " + $row.phase + " | " + $row.requested + " | " + $row.applyMs + " | " + $row.advanceMs +
        " | " + $row.framesDelta + " | " + $row.displayMs + " | " + $row.outside + " | " + $row.sourceWin + " | " + $ok + " |")
}
$table | ForEach-Object { Write-Log $_ }

Write-Log ""
if ($allOk) {
    Write-Log "结果: 全部 PASS"
} else {
    Write-Log "结果: 有 FAIL(见上)"
}

if ($OutFile) {
    $md = @()
    $md += "# issue 00023 预览 seek 真机对照表"
    $md += ""
    $md += ("- device: " + $Device + " / port " + $Port + " / song " + $SongId)
    $md += ("- A=" + $aMs + " B=" + $bMs + " duration=" + $durationMs + " budget=" + $AdvanceBudgetMs + "ms")
    $md += ""
    $md += $table
    $md | Set-Content -Encoding utf8 $OutFile
    Write-Log ("已写入 " + $OutFile)
}

if (-not $allOk) { exit 1 }
