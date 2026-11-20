<#
.SYNOPSIS
    分析页(无缝循环结果页)seek 响应与"循环区外 seek"语义的真机量测脚本(issue 00023)。

.DESCRIPTION
    测什么(全部经 debug 服务 /debug/preview 与 /debug/preview-seek,不依赖人肉点击):

      A. 区外 seek(循环区外自由试听)
         - 判定"落点正确":displayAfterMs 与请求值差 <= -PositionToleranceMs,且 outsideLoop=true,
           且 sourceKind == "native"(原生 media3 源:没有环带 PTS 投影,playerPosition 也必须对得上,
           见 issue 00023 回归:整曲窗口的环带源 seek 回退时 PTS 会被 max() 留在读前位置);
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
    # 缺省按 A/B 自动取两个区外点:A 之前(A/2)、B 之后((B+duration)/2);显式传入则覆盖。
    [long[]]$OutsideSeeks = @(),
    [switch]$InsideBackwardCheck,
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

# debug 服务/App 可能刚重启:/navigate 失败不能静默,轮询到路由真的落在结果页再继续。
$serverUp = $false
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    if ($null -ne (Invoke-Json "GET" "/state" $null)) { $serverUp = $true; break }
    Start-Sleep -Milliseconds 300
}
if (-not $serverUp) { throw "/debug 服务未就绪(adb forward / 进程重启?)" }
$route = "loop/" + $SongId
# 已经在结果页就不要重复 navigate:同一路由再推一次会创建第二个 LoopViewModel/PreviewPlayer,
# 探针会挂到新实例上(旧实例还在播),量测就串台了。
$already = Invoke-Json "GET" "/state" $null
if ($null -ne $already -and $already.screen -eq $route) {
    Write-Log "  [setup] 已在结果页,跳过 navigate(避免重复实例)"
    $state = $already
} else {
# 冷启动(尤其刚 install)时 MainActivity 的导航钩子注册得晚:/navigate 会 no-op,
# 这里给足 ~110s 窗口并逐次重试(实测 install 后首次可导航要 60~90s:进程冷启动+媒体库加载)。
for ($attempt = 0; $attempt -lt 120; $attempt++) {
    Invoke-Json "POST" "/navigate" @{ route = $route } | Out-Null
    Start-Sleep -Milliseconds 900
    $state = Invoke-Json "GET" "/state" $null
    if ($null -ne $state -and $state.screen -eq $route) { break }
    if ($attempt -eq 0 -or $attempt % 10 -eq 9) {
        Write-Log ("  [setup] 等导航生效… attempt=" + ($attempt + 1) + " screen=" + $(if ($null -ne $state) { $state.screen } else { "?" }))
    }
}
$state = Invoke-Json "GET" "/state" $null
}
if ($null -eq $state -or $state.screen -ne $route) {
    throw ("导航到 " + $route + " 失败(当前 screen=" + $state.screen + ")")
}
Start-Sleep -Seconds 2
$probe = Get-Preview
if ($null -eq $probe -or -not $probe.snapshot.isPlaying) {
    Write-Log "  [setup] 结果页未在播放,点『仅循环段』起播"
    # 结果页可能还在骨架/解码波形的阶段:文本出现需要一会儿,重试点击。
    $tapped = $false
    for ($attempt = 0; $attempt -lt 20 -and -not $tapped; $attempt++) {
        foreach ($label in @("仅循环段", "接缝检查", "完整循环")) {
            $xml = Get-UiDump
            if ($xml -match [regex]::Escape($label)) {
                Tap-Text $label | Out-Null
                $tapped = $true
                break
            }
        }
        if (-not $tapped) { Start-Sleep -Milliseconds 500 }
    }
    if (-not $tapped) { throw "结果页没有出现试听模式按钮(页面未就绪?)" }
    # 等试听真正起来(SOURCE 首块/播放器创建)。
    $playing = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $probe = Get-Preview
        if ($null -ne $probe -and $probe.snapshot.isPlaying) { $playing = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $playing) { throw "预览没有在播:先手动起播(点『仅循环段』)再跑本脚本" }
}
if ($probe.snapshot.engine -ne "SOURCE") {
    Write-Log ("  [warn] engine=" + $probe.snapshot.engine + "(本脚本的区外语义按 SOURCE 口径判定)")
}

$durationMs = [long]$probe.snapshot.durationMs
$aMs = [long]$probe.snapshot.aMs
$bMs = [long]$probe.snapshot.bMs
$loopWindowMs = $bMs - $aMs
Write-Log ("  [setup] duration=" + $durationMs + " A=" + $aMs + " B=" + $bMs + " mode=" + $probe.snapshot.mode)

if ($OutsideSeeks.Count -eq 0) {
    $before = [long]($aMs / 2)
    $after = [long](($bMs + $durationMs) / 2)
    $OutsideSeeks = @([math]::Max(0L, $before), [math]::Min($durationMs - 1L, $after))
}
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
# 第一次进区外会释放环带源(换成原生媒体源):先等环带填完,避开 release UAF。
Wait-BandFillFor $loopWindowMs "首次区外 seek 前"
foreach ($target in $OutsideSeeks) {
    $m = Measure-Seek $target
    if ($null -eq $m) { Write-Log "  [FAIL] /debug/preview-seek 无响应"; $allOk = $false; continue }
    Start-Sleep -Milliseconds 800
    $after = Get-Preview
    $display = [long]$after.snapshot.displayMs
    $outside = [bool]$after.snapshot.outsideLoop
    $srcA = $after.snapshot.sourceWindowA_ms
    $srcB = $after.snapshot.sourceWindowB_ms
    $kind = $after.snapshot.sourceKind

    $landingD = if ($null -ne $m.landingDisplayAfterMs) { [long]$m.landingDisplayAfterMs } else { [long]$m.displayAfterMs }
    $positionOk = ([math]::Abs($landingD - $target) -le $PositionToleranceMs) -and
        ($m.outsideLoopAfter -eq $true) -and $outside
    $soundOk = ([long]$m.framesDelta -gt 0) -and ($null -ne $m.advanceMs)
    $noBounce = ($display -ge $target -and $display -le ($target + 2000))
    # 区外必须落点正确且 playerPosition 也对得上(只 display 对不算修好:环带 PTS 投影 bug 会两边一起错)。
    $playerPosOk = ([math]::Abs([long]$m.playerPositionAfterMs - $target) -le $PositionToleranceMs)
    $allOk = (Assert $positionOk ("区外 " + $target + "ms 落点正确(landing=" + $landingD + ")")) -and $allOk
    $allOk = (Assert $playerPosOk ("区外 " + $target + "ms 播放器位置正确(playerPos=" + $m.playerPositionAfterMs + ")")) -and $allOk
    $allOk = (Assert $soundOk ("区外 " + $target + "ms 有音频输出(framesDelta=" + $m.framesDelta + " advanceMs=" + $m.advanceMs + ")")) -and $allOk
    $allOk = (Assert $noBounce ("区外 " + $target + "ms 进度不回弹(+800ms display=" + $display + ")")) -and $allOk
    $allOk = (Assert ($m.sourceKindAfter -eq "native") ("区外数据源=原生 media3 源(sourceKind=" + $m.sourceKindAfter + ", srcWindow=" + $srcA + ".." + $srcB + ")")) -and $allOk
    $allOk = (Assert ($kind -eq "native") ("区外快照 sourceKind 仍为 native(实际=" + $kind + ")")) -and $allOk
    Add-Row "outside" $target $m ($positionOk -and $noBounce -and $playerPosOk) $soundOk ("sourceKind=" + $m.sourceKindAfter + " display800ms=" + $display)
}

# ---------------------------------------------------------------- B. 拖回区间

Write-Log ""
Write-Log "== B. 拖回 [A,B)(恢复循环) =="
$restoreTarget = [long](($aMs + $bMs) / 2)
# 自由试听是原生源、没有环带要等;恢复循环时才新建环带源(新建不会撞释放 UAF)。
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
        ($m.sourceWindowAfterA_ms -eq $aMs) -and ($m.sourceWindowAfterB_ms -eq $bMs) -and
        ($m.sourceKindAfter -eq "band")
    if ($m.isPlayingAfter -ne $true) {
        Write-Log ("  [warn] 区内 seek 后未在播放: isPlaying=" + $m.isPlayingAfter + " state=" + $m.playbackStateAfter + "(advance 无法测量)")
    }
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

# ---------------------------------------------------------------- C2. 区内 forward/backward

if ($InsideBackwardCheck) {
    Write-Log ""
    Write-Log "== C2. 区内 forward / backward 各 3 点(落点必须 = 请求值) =="
    $loopLen = $bMs - $aMs
    $forwardTargets = @(
        [long]($aMs + $loopLen * 0.50),
        [long]($aMs + $loopLen * 0.80),
        [long]($aMs + $loopLen * 0.95)
    )
    $backwardTargets = @(
        [long]($aMs + $loopLen * 0.60),
        [long]($aMs + $loopLen * 0.30),
        [long]($aMs + $loopLen * 0.05)
    )
    $rows2 = @()
    foreach ($pair in @(@("forward", $forwardTargets), @("backward", $backwardTargets))) {
        $phase = $pair[0]
        foreach ($target in $pair[1]) {
            $m = Measure-Seek $target
            Start-Sleep -Milliseconds 300
            $d = if ($null -ne $m.landingDisplayAfterMs) { [long]$m.landingDisplayAfterMs } else { [long]$m.displayAfterMs }
            # 环内 seek 的**精确落点** = A + floorMod(T, L)；T 是播放器真正 seek 到的时间轴值，
            # 用它可以判断"投影是否正确"，不受探针采样延迟影响(display 会带 target+30ms 左右的前进)。
            $landing = -1
            if ($null -ne $m.appliedPlayerTargetMs) {
                $landing = $aMs + ((([long]$m.appliedPlayerTargetMs % $loopLen) + $loopLen) % $loopLen)
            }
            $ok = ([math]::Abs($d - $target) -le 100) -and
                ($landing -ge 0) -and ([math]::Abs($landing - $target) -le 2) -and
                ([long]$m.framesDelta -gt 0) -and
                ($m.outsideLoopAfter -eq $false) -and
                ($m.isPlayingAfter -eq $true)
            $round = if ($null -ne $m.appliedPlayerTargetMs) {
                [math]::Floor([long]$m.appliedPlayerTargetMs / ($loopLen)) 
            } else { -1 }
            $rows2 += [pscustomobject]@{
                dir = $phase; target = $target; landing = $landing; display = $d
                playerT = $m.appliedPlayerTargetMs; round = $round
                apply = $m.applyMs; advance = $m.advanceMs; frames = $m.framesDelta; ok = $ok
            }
            $allOk = (Assert $ok ("区内 " + $phase + " target=" + $target + " landing=" + $landing +
                " display=" + $d + " playerT=" + $m.appliedPlayerTargetMs + " advance=" + $m.advanceMs)) -and $allOk
            Add-Row ("inside-" + $phase) $target $m $ok ([long]$m.framesDelta -gt 0) ("round=" + $round)
            Start-Sleep -Milliseconds 300
        }
    }
    Write-Log "  (forward/backward 明细)"
    $rows2 | Format-Table -AutoSize | Out-String -Width 200 | ForEach-Object { Write-Log $_ }
}

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
