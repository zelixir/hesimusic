<#
.SYNOPSIS
    无缝循环 P4 E2E 执行器(L2 A/C/D 组 + L3 PCM 取证矩阵)。

.DESCRIPTION
    按 docs/loop-v3-e2e-plan.md 的用例全集与判据,通过 debug HTTP 协议驱动设备,
    每个用例产出:判据判定 + 可复查证据(e2e-shots/<case>/) + 汇总(e2e-results.json)。

    设备坑:这台 OnePlus 8T 的 adb forward 会被系统/USB 抖动清掉 —— 每次 HTTP 请求
    失败都会重建 forward 并重试(最多 4 次),重试次数记进 evidence。

.PARAMETER Case
    只跑指定用例,逗号分隔或多次传参,如 -Case A1,C1,C2。
    默认跑全部可自动化用例(A1-A6, C1-C10, D1, D3);加 -IncludeL3 追加 L3-1/L3-2/L3-4。

.PARAMETER IncludeL3
    默认用例集里追加 L3 取证矩阵(L3-1 MP3 / L3-2 FLAC / L3-4 长跑 300s)。
    L3 每个用例都会:POST /debug/tap -> 播放采样 -> tap off -> 拉 wav+jsonl ->
    bun run tools/spike-analyze.ts <wav> <jsonl> --mode sink;判据 = analyzer 退出码 0 且 failedMust 为空。

.PARAMETER SongId
    主测试歌(默认 15579,-Star Divine-,248.2s,已 confirmed A=20s/B=50s)。

.PARAMETER AltSongId
    第二首已确认歌(C8/C6 用)。默认 14942(275s,已有临时行,脚本会 /confirm 它)。

.PARAMETER UnconfirmedSongId
    未确认歌(C3/C4 用),默认 14942(在 C8 之前它是未确认的)。

.PARAMETER AnalysisSongId
    A1/A2/A4/A5 的分析对象,默认 14934(173.5s,~165s 级曲,A2 判据 <=15s)。

.PARAMETER A3SongId
    A3(分析即暂停)分析的另一首歌,默认 14973。

.PARAMETER FlacSongId
    L3-2 指定 FLAC 歌 id(默认 0 = 自动挑第一首非 CUE、>=120s 的 FLAC)。

.PARAMETER AppendResults
    与已有 e2e-results.json 合并(默认先清空,保证一次运行的判据自洽)。

.EXAMPLE
    powershell -NoProfile -File tools/loop-e2e.ps1
    powershell -NoProfile -File tools/loop-e2e.ps1 -Case A1,C1,C2
    powershell -NoProfile -File tools/loop-e2e.ps1 -Case L3-1,L3-2,L3-4 -AppendResults
    powershell -NoProfile -File tools/loop-e2e.ps1 -IncludeL3 -LongRunSeconds 300

.PARAMETER Pkg
    被测包名。**默认 com.zjr.hesimusic.dev(Dev 包)**。
    日常包 com.zjr.hesimusic 是用户主力包,本脚本默认拒绝在其上安装/清数据/重启。

.PARAMETER Install
    先构建 Dev 变体(cmd /c "gradlew.bat :app:assembleDebug -PdevPackage=.dev")再
    adb install -r -t app/build/outputs/apk/debug/app-debug.apk。目标包必须是 .dev,否则 abort。

.PARAMETER DryRun
    只打印运行计划(包名/adbdb URI/用例/是否安装),不执行任何 adb 命令。

.PARAMETER AllowMainPkg
    逃生开关:显式允许对非 .dev 包执行操作。默认关闭,日常包禁止触碰。

.EXAMPLE
    powershell -NoProfile -File tools/loop-e2e.ps1
    powershell -NoProfile -File tools/loop-e2e.ps1 -Case A1,C1,C2
    powershell -NoProfile -File tools/loop-e2e.ps1 -Case L3-1,L3-2,L3-4 -AppendResults
    powershell -NoProfile -File tools/loop-e2e.ps1 -IncludeL3 -LongRunSeconds 300
    powershell -NoProfile -File tools/loop-e2e.ps1 -Install -Case C1
    powershell -NoProfile -File tools/loop-e2e.ps1 -DryRun

.NOTES
    安全(2026-11-15 事故后强约束):
    - **默认只在 Dev 包 com.zjr.hesimusic.dev 上跑**;日常包 com.zjr.hesimusic 是用户主力包,
      脚本不会对其安装/清数据/重启(启动即校验 -Pkg 以 .dev 结尾,否则 exit 2);
    - 安装步骤只构建 -PdevPackage=.dev 变体并装 .dev 包(安装前 Assert-SafePkg);
    - adbdb authority 随 applicationId 走:content://<Pkg>.adbdb(Dev 包即 com.zjr.hesimusic.dev.adbdb);
    - 脚本不含 pm clear / uninstall;唯一的数据改写用例是 L4-backup(export + import,会清表重导)。
    前置:Dev debug 包已装、设备已连接、debug 服务监听 127.0.0.1:8765。
    证据目录:e2e-shots/<case>/{result.json,samples.json,state.json,logcat.txt}。
    L3 大文件落 e2e-artifacts/l3-*/。
#>
[CmdletBinding()]
param(
    [string[]]$Case = @(),
    [string]$Base = "http://127.0.0.1:18765",
    [int]$LocalPort = 18765,
    [int]$RemotePort = 8765,
    [string]$Serial = "",
    # 安全:默认只对 Dev 包(com.zjr.hesimusic.dev)跑。日常包 com.zjr.hesimusic 是用户主力包,
    # 任何情况下都不得由本脚本安装/清数据/重启;确需对主包做只读排查时显式加 -AllowMainPkg。
    [string]$Pkg = "com.zjr.hesimusic.dev",
    [switch]$AllowMainPkg,
    [switch]$Install,
    [switch]$DryRun,
    [string]$ApkPath = "app/build/outputs/apk/debug/app-debug.apk",
    [long]$SongId = 15579,
    [long]$AltSongId = 14942,
    [long]$UnconfirmedSongId = 14942,
    [long]$AnalysisSongId = 14934,
    [long]$A3SongId = 14973,
    [long]$FlacSongId = 0,
    [int]$L3Seconds = 125,
    [int]$LongRunSeconds = 300,
    [string]$ResultsPath = "e2e-results.json",
    [string]$ShotsDir = "e2e-shots",
    [string]$ArtifactsDir = "e2e-artifacts",
    [switch]$IncludeL3,
    [switch]$AppendResults,
    [switch]$NoScreenshots
)

$ErrorActionPreference = "Stop"
# Windows PowerShell 5.1 默认不加载 System.Net.Http(pwsh 7 已内置)
try { Add-Type -AssemblyName System.Net.Http -ErrorAction Stop } catch { }
$NL = [Environment]::NewLine
$script:Root = (Get-Location).Path
$script:Adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
if (-not (Test-Path $script:Adb)) { throw "adb not found: $script:Adb (set ANDROID_HOME)" }

<# 跨包误操作防护:任何安装/清数据/重启前必须过这一关。 #>
function Assert-SafePkg([string]$Action = "操作") {
    if ($Pkg -match "\.dev$") { return }
    if ($AllowMainPkg) {
        Write-Host ("WARN: " + $Action + " 目标是主包 " + $Pkg + "(-AllowMainPkg 已显式放行)") -ForegroundColor Yellow
        return
    }
    Write-Host ("ABORT: 拒绝对 " + $Pkg + " 执行「" + $Action + "」——本脚本只允许 Dev 包(以 .dev 结尾)。") -ForegroundColor Red
    Write-Host "       日常包 com.zjr.hesimusic 是用户主力包;确需操作请显式加 -AllowMainPkg。" -ForegroundColor Red
    exit 2
}

if ($Pkg -notmatch "\.dev$" -and -not $AllowMainPkg) {
    Write-Host ("ABORT: -Pkg " + $Pkg + " 不是 Dev 包(应以 .dev 结尾)。默认/推荐:com.zjr.hesimusic.dev") -ForegroundColor Red
    Write-Host "       只做只读排查请显式加 -AllowMainPkg;本脚本不会对主包安装/清数据。" -ForegroundColor Red
    exit 2
}

if ($DryRun) {
    Write-Host "== loop-e2e DRY-RUN(不触碰设备)==" -ForegroundColor Cyan
    Write-Host ("   pkg=" + $Pkg + "  serial=" + $(if ($Serial) { $Serial } else { "(auto)" }) + "  base=" + $Base)
    Write-Host ("   adb=" + $script:Adb)
    Write-Host ("   cases=" + $(if ($Case.Count -gt 0) { ($Case -join ",") } else { "(default)" }))
    Write-Host ("   install=" + $Install + "  apk=" + (Join-Path $script:Root $ApkPath))
    Write-Host ("   adbdb=content://" + $Pkg + ".adbdb")
    Write-Host ("   results=" + (Join-Path $script:Root $ResultsPath))
    Write-Host "   dry-run ok(未执行任何 adb 命令)" -ForegroundColor Green
    exit 0
}

<# 安装 Dev 变体:必须 -PdevPackage=.dev(PowerShell 里直接写会被当 task,需 cmd /c 包一层)。 #>
function Install-DevApp {
    Assert-SafePkg "安装"
    $apkFull = Join-Path $script:Root $ApkPath
    $gradlew = Join-Path $script:Root "gradlew.bat"
    if (-not (Test-Path $gradlew)) { throw "gradlew.bat not found: $gradlew" }
    Write-Host "== build dev variant (-PdevPackage=.dev) ==" -ForegroundColor Cyan
    # 注意:PowerShell 里 -PdevPackage=.dev 会被拆成 task,必须经 cmd /c 传
    $buildArgs = '""{0}" :app:assembleDebug -PdevPackage=.dev --console=plain"' -f $gradlew
    & cmd.exe /c $buildArgs
    if ($LASTEXITCODE -ne 0) { throw ("dev build failed (exit " + $LASTEXITCODE + ")") }
    if (-not (Test-Path $apkFull)) { throw "apk not found: $apkFull" }
    Write-Host ("== install -r -t " + $apkFull + " -> " + $Pkg) -ForegroundColor Cyan
    $installArgs = @("-s", $Serial, "install", "-r", "-t", $apkFull)
    & $script:Adb @installArgs
    if ($LASTEXITCODE -ne 0) { throw ("adb install failed (exit " + $LASTEXITCODE + ")") }
}

if (-not $Serial) {
    # 设备序列号会因重启/USB 重插而变(实测 b1e84dad -> e95221d0):默认自动挑唯一在线设备
    $devLines = @((& $script:Adb devices 2>$null) | Where-Object { $_ -match "\s+device\s*$" })
    if ($devLines.Count -lt 1) { throw "no adb device online" }
    $Serial = ($devLines[0] -split "\s+")[0].Trim()
}
if ($Install) { Install-DevApp }
$script:ResultsPath = Join-Path $script:Root $ResultsPath
$script:ShotsDir = Join-Path $script:Root $ShotsDir
$script:ArtifactsDir = Join-Path $script:Root $ArtifactsDir
$script:Results = [System.Collections.ArrayList]::new()
$script:Cur = $null
$script:LastAnalysis = $null
$script:ForwardRepairs = 0
$script:Http = $null

# ------------------------------------------------------------------ 基础设施

function Now-Ms { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }

function Write-Log([string]$Text, [string]$Color = "Gray") { Write-Host $Text -ForegroundColor $Color }

function Get-Http {
    if ($null -eq $script:Http) {
        $script:Http = [System.Net.Http.HttpClient]::new()
        $script:Http.Timeout = [TimeSpan]::FromSeconds(30)
    }
    return $script:Http
}

function Repair-Forward {
    $script:ForwardRepairs++
    & $script:Adb forward --remove "tcp:$LocalPort" 2>$null | Out-Null
    $state = (& $script:Adb get-state 2>$null) -join ""
    if ($state -notmatch "device") {
        for ($i = 0; $i -lt 20; $i++) {
            Start-Sleep -Milliseconds 500
            $state = (& $script:Adb get-state 2>$null) -join ""
            if ($state -match "device") { break }
        }
    }
    & $script:Adb forward "tcp:$LocalPort" "tcp:$RemotePort" 2>$null | Out-Null
    Start-Sleep -Milliseconds 250
}

function Ensure-Forward {
    $list = (& $script:Adb forward --list 2>$null) -join $NL
    if ($list -notmatch "tcp:$LocalPort") { Repair-Forward }
}

function Format-Repro([string]$Method, [string]$Path, $Body) {
    $uri = "$Base$Path"
    if ($null -eq $Body) { return ('curl.exe -s -X {0} "{1}"' -f $Method, $uri) }
    $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Compress -Depth 6 }
    $escaped = $json.Replace('"', '\"')
    return ('curl.exe -s -X {0} "{1}" -H "Content-Type: application/json" -d "{2}"' -f $Method, $uri, $escaped)
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [int]$TimeoutMs = 20000,
        [int]$MaxAttempts = 4
    )
    $uri = "$Base$Path"
    $repro = Format-Repro $Method $Path $Body
    $json = $null
    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Compress -Depth 6 }
    }
    $lastErr = ""
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $uri)
            if ($null -ne $json) {
                $req.Content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
            }
            $cts = [System.Threading.CancellationTokenSource]::new($TimeoutMs)
            try {
                $resp = (Get-Http).SendAsync($req, $cts.Token).GetAwaiter().GetResult()
                $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            } finally { $cts.Dispose() }
            $parsed = $null
            if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $null } }
            return [pscustomobject]@{
                Ok       = [bool]$resp.IsSuccessStatusCode
                Status   = [int]$resp.StatusCode
                Text     = $text
                Json     = $parsed
                Attempts = $attempt
                Repro    = $repro
            }
        } catch {
            $lastErr = $_.Exception.Message
            if ($attempt -lt $MaxAttempts) { Repair-Forward }
        }
    }
    return [pscustomobject]@{
        Ok       = $false
        Status   = 0
        Text     = "transport error after $MaxAttempts attempts: $lastErr"
        Json     = $null
        Attempts = $MaxAttempts
        Repro    = $repro
    }
}

function Get-State {
    $r = Invoke-Api -Method GET -Path "/state"
    if (-not $r.Ok) { return $null }
    return $r.Json
}

function Get-Sample($s) {
    if ($null -eq $s) { return $null }
    $w = $s.loop.window
    return [pscustomobject][ordered]@{
        t        = if ($null -ne $script:Cur) { (Now-Ms) - $script:Cur.StartedMs } else { 0 }
        pos      = $s.playback.positionMs
        dur      = $s.playback.durationMs
        playing  = $s.playback.playing
        engine   = $s.playback.engine
        songId   = $s.playback.songId
        repeat   = $s.playback.repeatMode
        winSong  = if ($null -ne $w) { $w.songId } else { $null }
        aMs      = if ($null -ne $w) { [long][math]::Round($w.loopStartUs / 1000.0) } else { $null }
        bMs      = if ($null -ne $w) { [long][math]::Round($w.loopEndUs / 1000.0) } else { $null }
        replay   = $s.loop.isReplaying
        seams    = $s.loop.seamCount
        degrade  = $s.loop.degradeCount
        ring       = $s.loop.isRingComplete
        rawMs      = [long][math]::Round($s.loop.positionUs / 1000.0)
        claimed    = $s.loop.claimed
        claimActive = $s.loop.claimActive
        sourceActive = $s.loop.sourceActive
        claimReason = $s.loop.claimReason
        analysis   = $s.analysis.phase
    }
}

function Wait-State {
    param(
        [scriptblock]$Predicate,
        [int]$TimeoutMs = 5000,
        [int]$IntervalMs = 200,
        [switch]$NoCollect
    )
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $samples = [System.Collections.ArrayList]::new()
    $last = $null
    while ($sw.ElapsedMilliseconds -lt $TimeoutMs) {
        $s = Get-State
        if ($null -ne $s) {
            $last = $s
            if (-not $NoCollect) { [void]$samples.Add((Get-Sample $s)) }
            $ok = $false
            try { $ok = [bool](& $Predicate $s) } catch { $ok = $false }
            if ($ok) {
                return [pscustomobject]@{ Ok = $true; ElapsedMs = $sw.ElapsedMilliseconds; State = $s; Samples = $samples }
            }
        }
        Start-Sleep -Milliseconds $IntervalMs
    }
    return [pscustomobject]@{ Ok = $false; ElapsedMs = $sw.ElapsedMilliseconds; State = $last; Samples = $samples }
}

# ------------------------------------------------------------------ 用例上下文/证据

function Get-CaseTitle([string]$Id) {
    $titles = @{
        'A1' = '分析进度双阶段'; 'A2' = '分析耗时'; 'A3' = '分析即暂停'; 'A4' = '分析单飞(409)';
        'A5' = '临时结果落库'; 'A6' = '已确认不被覆盖';
        'C1' = '已确认歌+单曲循环=无缝'; 'C2' = '环回正确(200ms 采样跨 B)'; 'C3' = '未确认歌不无缝';
        'C4' = '切歌取消窗口'; 'C5' = 'repeat 改 all/off -> window=null'; 'C6' = 'demote -> window=null';
        'C7' = '重启恢复'; 'C8' = '切到另一首已确认歌自动无缝'; 'C9' = '区内 seek 预卷/degrade';
        'C10' = '暂停/恢复'; 'D1' = '智能歌单「无缝循环」'; 'D3' = '应用名区分';
        'L3-1' = 'PCM 取证 MP3(A=20s/B=50s)'; 'L3-2' = 'PCM 取证 FLAC'; 'L3-4' = '长跑 300s';
        'C11' = '重放期 seek 落点'; 'N1' = '切走恢复 repeat'; 'N2' = '切回无窗口'; 'N3' = '重新进入有窗口'; 'N4' = '用户改 repeat 退出';
        'L4-queue' = '队列/索引/位置/repeat/shuffle 恢复'; 'L4-wrap' = '首尾绕回';
        'L4-repeat' = 'repeat 切换与持久化'; 'L4-backup' = '备份/恢复(song_loop + 智能歌单)';
        'L4-notify' = '通知/session 位置一致 + 锁屏'; 'L4-smoke' = '库/歌单/收藏基础操作'; 'L5' = '竞态风暴 200 次';
    }
    if ($titles.ContainsKey($Id)) { return $titles[$Id] }
    return $Id
}

function Start-Case([string]$Id) {
    $dir = Join-Path $script:ShotsDir $Id
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $script:Cur = [pscustomobject]@{
        Id        = $Id
        Dir       = $dir
        StartedMs = Now-Ms
        StartedAt = (Get-Date).ToString("o")
        Metrics   = [ordered]@{}
        Evidence  = [System.Collections.ArrayList]::new()
        Samples   = [System.Collections.ArrayList]::new()
        Repro     = [System.Collections.ArrayList]::new()
        Failures  = [System.Collections.ArrayList]::new()
    }
    Write-Log ""
    Write-Log ("-- [" + $Id + "] " + (Get-CaseTitle $Id)) "Cyan"
}

function Add-Metric([string]$Name, $Value) {
    if ($null -ne $script:Cur) { $script:Cur.Metrics[$Name] = $Value }
}

function Add-Failure([string]$Text) {
    if ($null -ne $script:Cur) { [void]$script:Cur.Failures.Add($Text) }
    Write-Log ("   FAIL: " + $Text) "Red"
}

function Save-CaseJson([string]$Name, $Obj) {
    if ($null -eq $script:Cur) { return $null }
    $path = Join-Path $script:Cur.Dir $Name
    ($Obj | ConvertTo-Json -Depth 10) | Set-Content -Path $path -Encoding utf8
    $rel = ($script:Cur.Id + "/" + $Name)
    if (-not $script:Cur.Evidence.Contains($rel)) { [void]$script:Cur.Evidence.Add($rel) }
    return $path
}

function Add-SampleSeries($Samples) {
    if ($null -eq $script:Cur -or $null -eq $Samples) { return }
    foreach ($s in $Samples) { if ($null -ne $s) { [void]$script:Cur.Samples.Add($s) } }
}

function Save-Samples {
    if ($null -eq $script:Cur -or $script:Cur.Samples.Count -eq 0) { return }
    Save-CaseJson "samples.json" $script:Cur.Samples | Out-Null
}

function Save-Logcat([string]$Filter = "LoopPerf|LoopDebug|LoopAudio|LoopPcm|LoopSession|LoopPosition|AndroidRuntime|FATAL EXCEPTION") {
    try {
        $lines = @((& $script:Adb logcat -d -t 1500 2>$null) | Select-String -Pattern $Filter | ForEach-Object { $_.Line })
        $path = Join-Path $script:Cur.Dir "logcat.txt"
        ($lines -join $NL) | Set-Content -Path $path -Encoding utf8
        $rel = ($script:Cur.Id + "/logcat.txt")
        if (-not $script:Cur.Evidence.Contains($rel)) { [void]$script:Cur.Evidence.Add($rel) }
        return $lines
    } catch { return @() }
}

function Get-LogcatTag([string]$Tag, [int]$Lines = 200) {
    try { return @((& $script:Adb logcat -d -s $Tag -t $Lines 2>$null)) } catch { return @() }
}

function Save-Screenshot([string]$Name = "screen.png") {
    if ($NoScreenshots) { return }
    try {
        $path = Join-Path $script:Cur.Dir $Name
        $cmdLine = '"{0}" -s {1} exec-out screencap -p > "{2}"' -f $script:Adb, $Serial, $path
        & cmd.exe /c $cmdLine | Out-Null
        if ((Test-Path $path) -and ((Get-Item $path).Length -gt 1000)) {
            $rel = ($script:Cur.Id + "/" + $Name)
            if (-not $script:Cur.Evidence.Contains($rel)) { [void]$script:Cur.Evidence.Add($rel) }
        }
    } catch { }
}

function Get-CaseStatus { if ($null -eq $script:Cur) { return "FAIL" }; if ($script:Cur.Failures.Count -eq 0) { return "PASS" } else { return "FAIL" } }

function End-Case([string]$Status = "", [string]$Detail = "") {
    if ($null -eq $script:Cur) { return }
    if (-not $Status) { $Status = Get-CaseStatus }
    $dur = (Now-Ms) - $script:Cur.StartedMs
    Save-Samples
    $finalState = Get-State
    if ($null -ne $finalState) { Save-CaseJson "state.json" $finalState | Out-Null }
    $failDetail = if ($script:Cur.Failures.Count -gt 0) { ($script:Cur.Failures -join "; ") } else { $Detail }
    $result = [ordered]@{
        id         = $script:Cur.Id
        status     = $Status
        metrics    = $script:Cur.Metrics
        evidence   = @($script:Cur.Evidence)
        startedAt  = $script:Cur.StartedAt
        durationMs = $dur
        detail     = $failDetail
        repro      = @($script:Cur.Repro)
    }
    Save-CaseJson "result.json" $result | Out-Null
    [void]$script:Results.Add($result)
    Save-Results
    $color = "Red"
    if ($Status -eq "PASS") { $color = "Green" } elseif ($Status -eq "SKIP") { $color = "Yellow" }
    $tail = ""
    if ($failDetail) { $tail = "  " + $failDetail }
    Write-Log ("   => " + $Status + " (" + $dur + "ms)" + $tail) $color
    $script:Cur = $null
}

function Get-JsonItems([string]$Path) {
    # PS 5.1 的 ConvertFrom-Json 把顶层数组当单个对象返回,必须显式 foreach 展开
    $out = @()
    if (-not (Test-Path $Path)) { return ,$out }
    try {
        $parsed = Get-Content $Path -Raw -Encoding utf8 | ConvertFrom-Json
        foreach ($item in $parsed) { if ($null -ne $item) { $out += $item } }
    } catch { }
    return ,$out
}

function Save-Results {
    # 按 id 去重合并(本次结果覆盖同 id 的旧条目),顺序稳定
    $sources = @()
    $sources += Get-JsonItems $script:ResultsPath
    $sources += @($script:Results)
    $merged = @()
    foreach ($r in $sources) {
        if ($null -eq $r -or $null -eq $r.id) { continue }
        $key = [string]$r.id
        $idx = -1
        for ($i = 0; $i -lt $merged.Count; $i++) { if ([string]$merged[$i].id -eq $key) { $idx = $i; break } }
        if ($idx -ge 0) { $merged[$idx] = $r } else { $merged += $r }
    }
    ($merged | ConvertTo-Json -Depth 10) | Set-Content -Path $script:ResultsPath -Encoding utf8
}

# ------------------------------------------------------------------ 设备/播放辅助

function Set-Repeat([string]$Mode) {
    $r = Invoke-Api -Method POST -Path "/player/repeat" -Body @{ mode = $Mode }
    if (-not $r.Ok -and $null -ne $script:Cur) { [void]$script:Cur.Repro.Add($r.Repro) }
    return $r
}

function Start-Play([long]$Id, [bool]$RepeatOne = $true) {
    $r = Invoke-Api -Method POST -Path "/player/play" -Body @{ songId = $Id; repeatOne = $RepeatOne }
    if (-not $r.Ok -and $null -ne $script:Cur) { [void]$script:Cur.Repro.Add($r.Repro) }
    return $r
}

function Invoke-Seek([int]$PositionMs) {
    $r = Invoke-Api -Method POST -Path "/player/seek" -Body @{ positionMs = $PositionMs }
    if (-not $r.Ok -and $null -ne $script:Cur) { [void]$script:Cur.Repro.Add($r.Repro) }
    return $r
}

function Toggle-PlayPause {
    $r = Invoke-Api -Method POST -Path "/player/play-pause" -Body @{}
    if (-not $r.Ok -and $null -ne $script:Cur) { [void]$script:Cur.Repro.Add($r.Repro) }
    return $r
}

function Enter-Loop {
    # 显式意图:arm(songId) + repeat=ONE + 保队列播放(不切歌)。无缝生效的唯一入口。
    param([long]$Id, [switch]$Play)
    $body = @{ songId = $Id }
    if ($Play) { $body["play"] = $true }
    $r = Invoke-Api -Method POST -Path "/loop/enter" -Body $body
    if (-not $r.Ok -and $null -ne $script:Cur) {
        [void]$script:Cur.Repro.Add($r.Repro)
        if ($r.Status -eq 404) { Add-Failure "/loop/enter 端点未落地(HTTP 404):debug 服务需先补端点" }
    }
    return $r
}

function Exit-Loop {
    $r = Invoke-Api -Method POST -Path "/loop/exit" -Body @{}
    if (-not $r.Ok -and $null -ne $script:Cur) { [void]$script:Cur.Repro.Add($r.Repro) }
    return $r
}

function Start-PlayKeepRepeat {
    # 不传 repeatOne:切歌时保持当前 repeat(用于验证"切走恢复 prevRepeat")
    param([long]$Id)
    $r = Invoke-Api -Method POST -Path "/player/play" -Body @{ songId = $Id }
    if (-not $r.Ok -and $null -ne $script:Cur) { [void]$script:Cur.Repro.Add($r.Repro) }
    return $r
}

function Get-LoopIntent($s) {
    if ($null -eq $s -or $null -eq $s.loop) { return $null }
    return $s.loop.intent
}

function Initialize-Seamless {
    param([long]$Id, [int]$SeekMs = -1, [int]$TimeoutMs = 8000)
    # 新语义(issue 00004):必须先 /loop/enter 显式进入,否则即使"已确认 + repeat=ONE"也不无缝。
    # /loop/enter(play=true) 自己会:arm(记录 previousRepeat) + repeat=ONE + 用完整库队列播放该歌。
    $enter = Enter-Loop $Id
    if (-not $enter.Ok) {
        # 端点不可用/进入失败时退回旧路径,保证其余断言仍有数据可看
        Set-Repeat "one" | Out-Null
        Start-Play $Id $true | Out-Null
        Start-Sleep -Milliseconds 600
    }
    # /loop/enter(play=true) 会用完整库队列 setMediaItems:大库时 prepare 可能要十几秒,
    # 期间 seek 会被忽略、位置不动。先等"真的在前进",再做填环。
    $ready = Wait-State -Predicate { param($s) ($s.playback.playing -eq $true) -and ($null -ne $s.playback.positionMs) -and ([long]$s.playback.positionMs -gt 200) } -TimeoutMs 30000 -IntervalMs 300 -NoCollect
    if ($null -ne $script:Cur) { $script:Cur.Metrics["playReadyMs"] = $ready.ElapsedMs; $script:Cur.Metrics["playReadyOk"] = $ready.Ok }
    # 环缓冲必须先完整:ring 不完整时 B 处按设计降级直通(不产生接缝),会让"无缝"用例假失败。
    # 填环方式:从 A 前 1s 起播,完整经过 [A,B)(约 31s;若 seek 未生效则要 ~50s)。
    $st = Get-State
    if ($null -ne $st -and $st.loop.isRingComplete -ne $true) {
        Invoke-Seek 19000 | Out-Null
        Start-Sleep -Milliseconds 800
        $afterSeek = Get-State
        if ($null -ne $afterSeek -and $null -ne $afterSeek.playback.positionMs -and [long]$afterSeek.playback.positionMs -gt 22000) {
            # seek 没落到位(还在更后面),再试一次
            Invoke-Seek 19000 | Out-Null
            Start-Sleep -Milliseconds 600
        }
        $ring = Wait-State -Predicate { param($s) $s.loop.isRingComplete -eq $true } -TimeoutMs 75000 -IntervalMs 500 -NoCollect
        if ($null -ne $script:Cur) { $script:Cur.Metrics["ringWaitMs"] = $ring.ElapsedMs; $script:Cur.Metrics["ringWaitOk"] = $ring.Ok }
    }
    if ($SeekMs -ge 0) {
        Invoke-Seek $SeekMs | Out-Null
        Start-Sleep -Milliseconds 500
    }
    return Wait-State -Predicate { param($s) ($null -ne $s.loop.window) -and ($s.loop.window.songId -eq $Id) -and ($s.loop.claimed -eq $true) -and ($s.loop.sourceActive -eq $true) -and ($s.playback.engine -eq 'loop') } -TimeoutMs $TimeoutMs -IntervalMs 200
}

function Wait-PlayerReady {
    # 冷启后 debug 连接可能停在 restore 之前的快照(/player 报 idle/queue=0),等它真正就绪
    param([int]$TimeoutMs = 30000, [long]$ExpectSongId = 0)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt $TimeoutMs) {
        $p = Invoke-Api -Method GET -Path "/player"
        if ($p.Ok -and $null -ne $p.Json.queueSize -and [int]$p.Json.queueSize -ge 1) {
            if ($ExpectSongId -le 0 -or $p.Json.songId -eq $ExpectSongId) { return $true }
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Invoke-AdbDbCall {
    # debug 专属 content provider:adb shell content call --uri content://<pkg>.adbdb --method <m> [--arg '<arg>']
    param([string]$Method, [string]$Arg = "")
    $inner = if ($Arg) { "content call --uri content://$Pkg.adbdb --method $Method --arg '$Arg'" } else { "content call --uri content://$Pkg.adbdb --method $Method" }
    $out = ((& $script:Adb shell $inner 2>&1) -join $NL)
    $json = $null
    $m = [regex]::Match($out, 'result=(\{.*\}),\s*ok=')
    if ($m.Success) { try { $json = $m.Groups[1].Value | ConvertFrom-Json } catch { $json = $null } }
    return [pscustomobject]@{ Ok = ($out -match 'ok=true'); Raw = $out; Json = $json }
}

function ConvertTo-SqlText([string]$Text) {
    # 用 char() 拼 SQL 字符串:adb shell 会吞掉单/双引号(连 x'..' 里的引号也保不住),char() 不含引号最稳
    $codes = @()
    foreach ($ch in $Text.ToCharArray()) { $codes += [int]$ch }
    return "char(" + ($codes -join ",") + ")"
}

function Invoke-Adb {
    # adb shell 的 stderr 在 PS 5.1 + ErrorActionPreference=Stop 下会变成终止错误,统一兜住
    param([string]$Command)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { return ((& $script:Adb shell $Command 2>&1) -join $NL) } finally { $ErrorActionPreference = $prev }
}

function Restart-App {
    # force-stop + 冷启。注意:冷启后必须先静默等 restore 完成再发第一次请求,
    # 否则 debug MediaController 会绑到空 player(reads idle/queue=0,writes 全无效)。
    param([int]$SilentMs = 8000, [long]$ExpectSongId = 0, [int]$ReadyTimeoutMs = 30000)
    Assert-SafePkg "冷启重启"
    Invoke-Adb "am force-stop $Pkg" | Out-Null
    Start-Sleep -Milliseconds 1200
    Invoke-Adb "am start -n $Pkg/.MainActivity" | Out-Null
    Start-Sleep -Milliseconds $SilentMs
    return Wait-PlayerReady -TimeoutMs $ReadyTimeoutMs -ExpectSongId $ExpectSongId
}

function Get-Prefs {
    # 直接读 SharedPreferences 落盘文件,验证持久化(不依赖重启)
    $out = ((& $script:Adb shell "run-as $Pkg cat shared_prefs/playback_prefs.xml" 2>&1) -join $NL)
    $prefs = [ordered]@{}
    foreach ($k in @("repeat_mode", "shuffle_mode_enabled", "current_song_id", "current_position", "current_song_index")) {
        $m = [regex]::Match($out, 'name="' + $k + '"\s+value="([^"]*)"')
        if ($m.Success) { $prefs[$k] = $m.Groups[1].Value }
    }
    return $prefs
}

function Get-SessionPosition {
    # dumpsys media_session 里的 PlaybackState position(不对外推)
    $out = ((& $script:Adb shell "dumpsys media_session | grep -o 'position=[0-9]*'" 2>&1) -join " ")
    $m = [regex]::Match($out, 'position=(\d+)')
    if ($m.Success) { return [long]$m.Groups[1].Value }
    return $null
}

function Get-SessionState {
    # session 的 PlaybackState:position/speed/updated(elapsedRealtime ms);并按 speed 外推到现在
    # 注意:adb shell 里带空格/逗号的 grep 模式会被引号吞噬,这里整段 dump 回 PS 再正则
    $out = Invoke-Adb "dumpsys media_session"
    $m = [regex]::Match($out, 'state=PlaybackState \{[^}]*?position=(\d+)[^}]*?speed=([0-9.-]+)[^}]*?updated=(\d+)')
    if (-not $m.Success) { return $null }
    $pos = [long]$m.Groups[1].Value
    $speed = [double]$m.Groups[2].Value
    $updated = [long]$m.Groups[3].Value
    $up = (Invoke-Adb "cat /proc/uptime").Trim()
    $upMs = 0
    $um = [regex]::Match($up, '([0-9.]+)')
    if ($um.Success) { $upMs = [long]([double]$um.Groups[1].Value * 1000) }
    $extra = $null
    if ($upMs -gt 0 -and $updated -gt 0) { $extra = $pos + [long](($upMs - $updated) * $speed) }
    return [pscustomobject]@{ Position = $pos; Speed = $speed; Updated = $updated; UptimeMs = $upMs; Extrapolated = $extra }
}

function Test-ProcessAlive {
    $procId = ((& $script:Adb shell "pidof $Pkg" 2>$null) -join "").Trim()
    return [pscustomobject]@{ Alive = [bool]$procId; Pid = $procId }
}

function Get-FatalLines([int]$Lines = 3000) {
    $out = @((& $script:Adb logcat -d -t $Lines 2>$null) | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime: Process: $Pkg" | ForEach-Object { $_.Line })
    return $out
}

function Get-LoopRow([long]$Id) {
    $r = Invoke-Api -Method GET -Path "/song/$Id"
    if (-not $r.Ok) { return $null }
    return $r.Json
}

# ------------------------------------------------------------------ A 组:分析链

function Case-A1 {
    Start-Case 'A1'
    try {
        $st = Get-State
        if ($null -ne $st -and $st.analysis.analyzing) {
            Invoke-Api -Method POST -Path "/cancelAnalysis" -Body @{} | Out-Null
            Wait-State -Predicate { param($s) -not $s.analysis.analyzing } -TimeoutMs 10000 -NoCollect | Out-Null
        }
        $t0 = Now-Ms
        $start = Invoke-Api -Method POST -Path "/analyze" -Body @{ songId = $AnalysisSongId }
        if (-not $start.Ok) {
            [void]$script:Cur.Repro.Add($start.Repro)
            Add-Failure ("POST /analyze 失败: HTTP " + $start.Status + " " + $start.Text)
            End-Case; return
        }
        $phases = [System.Collections.ArrayList]::new()
        $phaseSeq = [System.Collections.ArrayList]::new()
        $failed = $false
        $done = $false
        $deadline = $t0 + 120000
        $lastPhase = ""
        $an = $null
        while ((Now-Ms) -lt $deadline) {
            Start-Sleep -Milliseconds 500
            $resp = Invoke-Api -Method GET -Path "/analysis"
            if (-not $resp.Ok) { continue }
            $an = $resp.Json
            $row = [pscustomobject][ordered]@{ t = (Now-Ms) - $t0; phase = $an.phase; progress = $an.progress; songId = $an.songId; candidates = $an.candidateCount }
            [void]$phases.Add($row)
            if ($an.phase -ne $lastPhase) { [void]$phaseSeq.Add($an.phase); $lastPhase = $an.phase }
            if ($an.phase -eq "done") { $done = $true; break }
            if ($an.phase -eq "failed") { $failed = $true; Add-Failure ("分析 failed: " + $an.message); break }
        }
        $elapsed = (Now-Ms) - $t0
        Save-CaseJson "analysis-progress.json" $phases | Out-Null
        $decoding = @($phases | Where-Object { $_.phase -eq "decoding" }).Count
        $analyzing = @($phases | Where-Object { $_.phase -eq "analyzing" }).Count
        $mono = $true
        $prev = -1.0
        $prevPhase = ""
        foreach ($p in $phases) {
            if ($p.phase -ne $prevPhase) { $prev = -1.0; $prevPhase = $p.phase }
            if ([double]$p.progress -lt ($prev - 0.0001)) { $mono = $false }
            $prev = [double]$p.progress
        }
        Add-Metric "songId" $AnalysisSongId
        Add-Metric "elapsedMs" $elapsed
        Add-Metric "phaseSequence" ($phaseSeq -join ">")
        Add-Metric "decodingSamples" $decoding
        Add-Metric "analyzingSamples" $analyzing
        Add-Metric "progressMonotonicInPhase" $mono
        Add-Metric "done" $done
        Add-Metric "failed" $failed
        if ($null -ne $an) {
            Add-Metric "candidateCount" $an.candidateCount
            Add-Metric "sampleRate" $an.sampleRate
        }
        if (-not $done) { Add-Failure ("120s 内未到 done(phase=" + $(if ($null -ne $an) { $an.phase } else { "?" }) + ")") }
        if ($decoding -lt 1) { Add-Failure "Decoding 阶段无样本" }
        if ($analyzing -lt 1) { Add-Failure "Analyzing 阶段无样本" }
        if (-not $mono) { Add-Failure "阶段内进度出现回退" }
        $script:LastAnalysis = [pscustomobject]@{
            songId     = $AnalysisSongId
            elapsedMs  = $elapsed
            sampleRate = $(if ($null -ne $an) { $an.sampleRate } else { $null })
            candidates = $(if ($null -ne $an) { $an.candidates } else { $null })
            doneAt     = Now-Ms
        }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        Save-Logcat | Out-Null
        End-Case
    }
}

function Case-A2 {
    Start-Case 'A2'
    try {
        $row = Get-LoopRow $AnalysisSongId
        $durMs = if ($null -ne $row) { $row.durationMs } else { $null }
        if ($null -eq $script:LastAnalysis -or $script:LastAnalysis.songId -ne $AnalysisSongId) {
            Write-Log "   (A1 未跑,自行执行一次 /analyze 计时)"
            $t0 = Now-Ms
            $start = Invoke-Api -Method POST -Path "/analyze" -Body @{ songId = $AnalysisSongId }
            if (-not $start.Ok) {
                [void]$script:Cur.Repro.Add($start.Repro)
                Add-Failure ("POST /analyze 失败: HTTP " + $start.Status + " " + $start.Text)
                End-Case; return
            }
            Wait-State -Predicate { param($s) $s.analysis.phase -eq 'done' -or $s.analysis.phase -eq 'failed' } -TimeoutMs 120000 -IntervalMs 500 -NoCollect | Out-Null
            $script:LastAnalysis = [pscustomobject]@{ songId = $AnalysisSongId; elapsedMs = (Now-Ms) - $t0; sampleRate = $null; candidates = $null; doneAt = Now-Ms }
        }
        $elapsed = $script:LastAnalysis.elapsedMs
        $logLines = Get-LogcatTag "LoopPerf" 120
        $path = Join-Path $script:Cur.Dir "logcat-loopPerf.txt"
        ($logLines -join $NL) | Set-Content -Path $path -Encoding utf8
        $rel = ($script:Cur.Id + "/logcat-loopPerf.txt")
        if (-not $script:Cur.Evidence.Contains($rel)) { [void]$script:Cur.Evidence.Add($rel) }
        $decodeLine = @($logLines | Where-Object { $_ -match "decode=" }) | Select-Object -First 1
        $analyzeLine = @($logLines | Where-Object { $_ -match "analyze=" }) | Select-Object -First 1
        Add-Metric "songId" $AnalysisSongId
        Add-Metric "durationMs" $durMs
        Add-Metric "elapsedMs" $elapsed
        Add-Metric "logcatDecodeLine" $decodeLine
        Add-Metric "logcatAnalyzeLine" $analyzeLine
        $bound = 15000
        Add-Metric "boundMs" $bound
        if ($null -ne $durMs -and $durMs -gt 0) { Add-Metric "elapsedMsPerSongMinute" ([math]::Round($elapsed / ($durMs / 60000.0))) }
        if ($elapsed -gt $bound) { Add-Failure ("分析耗时 " + $elapsed + "ms > " + $bound + "ms") }
        if (-not $decodeLine) { Add-Failure "logcat 无 LoopPerf decode= 行" }
        if (-not $analyzeLine) { Add-Failure "logcat 无 LoopPerf analyze= 行" }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-A3 {
    Start-Case 'A3'
    try {
        Set-Repeat "one" | Out-Null
        Start-Play $SongId $true | Out-Null
        $ready = Wait-State -Predicate { param($s) $s.playback.playing -eq $true } -TimeoutMs 8000 -IntervalMs 200
        if (-not $ready.Ok) { Add-Failure "前置:播放未起来"; End-Case; return }
        Start-Sleep -Milliseconds 800
        $t0 = Now-Ms
        $an = Invoke-Api -Method POST -Path "/analyze" -Body @{ songId = $A3SongId }
        if (-not $an.Ok) {
            [void]$script:Cur.Repro.Add($an.Repro)
            Add-Failure ("POST /analyze 失败: HTTP " + $an.Status + " " + $an.Text)
            End-Case; return
        }
        $pausedAt = -1
        $samples = [System.Collections.ArrayList]::new()
        for ($i = 0; $i -lt 150; $i++) {
            $s = Get-State
            if ($null -ne $s) {
                [void]$samples.Add((Get-Sample $s))
                if ($s.playback.playing -eq $false) { $pausedAt = (Now-Ms) - $t0; break }
            }
        }
        Add-SampleSeries $samples
        Add-Metric "songId" $A3SongId
        Add-Metric "pausedAfterMs" $pausedAt
        Add-Metric "strictBoundMs" 100
        Add-Metric "relaxedBoundMs" 800
        Add-Metric "probeCount" $samples.Count
        if ($pausedAt -lt 0) {
            Add-Failure "分析开始后播放未暂停(2s 内 playing 恒 true)"
        } elseif ($pausedAt -gt 800) {
            Add-Failure ("暂停延迟 " + $pausedAt + "ms > 800ms(plan §0 放宽口径;严格 100ms)")
        }
        Invoke-Api -Method POST -Path "/cancelAnalysis" -Body @{} | Out-Null
        Wait-State -Predicate { param($s) -not $s.analysis.analyzing } -TimeoutMs 10000 -NoCollect | Out-Null
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-A4 {
    Start-Case 'A4'
    try {
        $st = Get-State
        if ($null -ne $st -and $st.analysis.analyzing) {
            Invoke-Api -Method POST -Path "/cancelAnalysis" -Body @{} | Out-Null
            Wait-State -Predicate { param($s) -not $s.analysis.analyzing } -TimeoutMs 10000 -NoCollect | Out-Null
        }
        $first = Invoke-Api -Method POST -Path "/analyze" -Body @{ songId = $AnalysisSongId }
        if (-not $first.Ok) {
            [void]$script:Cur.Repro.Add($first.Repro)
            Add-Failure ("第一次 /analyze 失败: HTTP " + $first.Status + " " + $first.Text)
            End-Case; return
        }
        $second = Invoke-Api -Method POST -Path "/analyze" -Body @{ songId = $AnalysisSongId }
        if (-not $second.Ok) { [void]$script:Cur.Repro.Add($second.Repro) }
        $stAfter = Get-State
        Add-Metric "firstStatus" $first.Status
        Add-Metric "secondStatus" $second.Status
        Add-Metric "secondBody" $second.Text
        Add-Metric "analyzingAfter" $(if ($null -ne $stAfter) { $stAfter.analysis.analyzing } else { $null })
        Add-Metric "phaseAfter" $(if ($null -ne $stAfter) { $stAfter.analysis.phase } else { $null })
        if ($second.Status -ne 409) { Add-Failure ("第二次 /analyze 应 409,实际 " + $second.Status) }
        if ($null -ne $stAfter -and $stAfter.analysis.phase -eq "failed") { Add-Failure "第二次请求把状态打成 failed(状态跳变)" }
        $done = Wait-State -Predicate { param($s) $s.analysis.phase -eq 'done' -or $s.analysis.phase -eq 'failed' } -TimeoutMs 120000 -IntervalMs 500 -NoCollect
        $finalPhase = if ($null -ne $done.State) { $done.State.analysis.phase } else { $null }
        Add-Metric "finalPhase" $finalPhase
        if ($finalPhase -eq "failed") { Add-Failure "分析最终 failed" }
        $script:LastAnalysis = [pscustomobject]@{ songId = $AnalysisSongId; elapsedMs = 0; sampleRate = $null; candidates = $null; doneAt = Now-Ms }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-A5 {
    Start-Case 'A5'
    try {
        $an = Invoke-Api -Method GET -Path "/analysis"
        $needRun = $true
        if ($an.Ok -and $an.Json.phase -eq "done" -and $an.Json.songId -eq $AnalysisSongId -and @($an.Json.candidates).Count -gt 0) { $needRun = $false }
        if ($needRun) {
            $start = Invoke-Api -Method POST -Path "/analyze" -Body @{ songId = $AnalysisSongId }
            if (-not $start.Ok) {
                [void]$script:Cur.Repro.Add($start.Repro)
                Add-Failure ("POST /analyze 失败: HTTP " + $start.Status + " " + $start.Text)
                End-Case; return
            }
            Wait-State -Predicate { param($s) $s.analysis.phase -eq 'done' -or $s.analysis.phase -eq 'failed' } -TimeoutMs 120000 -IntervalMs 500 -NoCollect | Out-Null
            $an = Invoke-Api -Method GET -Path "/analysis"
        }
        if (-not $an.Ok) { Add-Failure "/analysis 读取失败"; End-Case; return }
        $a = $an.Json
        $cand = @($a.candidates) | Select-Object -First 1
        $sr = [int]$a.sampleRate
        $expectA = $null
        $expectB = $null
        if ($null -ne $cand -and $sr -gt 0) {
            $expectA = [long][math]::Round($cand.startSamples * 1000.0 / $sr)
            $expectB = [long][math]::Round($cand.endSamples * 1000.0 / $sr)
        }
        $row = Get-LoopRow $AnalysisSongId
        Add-Metric "songId" $AnalysisSongId
        Add-Metric "phase" $a.phase
        Add-Metric "candidateA_ms" $expectA
        Add-Metric "candidateB_ms" $expectB
        Add-Metric "storedA_ms" $(if ($null -ne $row) { $row.loopStartMs } else { $null })
        Add-Metric "storedB_ms" $(if ($null -ne $row) { $row.loopEndMs } else { $null })
        Add-Metric "storedConfirmed" $(if ($null -ne $row) { $row.loopConfirmed } else { $null })
        Add-Metric "storedSampleRate" $(if ($null -ne $row) { $row.sampleRate } else { $null })
        if ($null -eq $cand) { Add-Failure "Done 结果无候选" }
        if ($null -eq $row -or -not $row.hasLoop) {
            Add-Failure ("/song/" + $AnalysisSongId + " 无 song_loop 行(临时结果未落库)")
        } else {
            if ($row.loopConfirmed -ne $false -and $row.loopConfirmed -ne 0) { Add-Failure ("落库行 confirmed 应为 0,实际 " + $row.loopConfirmed) }
            if ($null -ne $expectA -and [math]::Abs([long]$row.loopStartMs - $expectA) -gt 2) { Add-Failure ("落库 A=" + $row.loopStartMs + "ms 与候选 " + $expectA + "ms 不一致") }
            if ($null -ne $expectB -and [math]::Abs([long]$row.loopEndMs - $expectB) -gt 2) { Add-Failure ("落库 B=" + $row.loopEndMs + "ms 与候选 " + $expectB + "ms 不一致") }
        }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-A6 {
    Start-Case 'A6'
    try {
        $before = Get-LoopRow $SongId
        if ($null -eq $before -or -not $before.hasLoop) { Add-Failure "前置:主歌无已确认循环行"; End-Case; return }
        $st = Get-State
        if ($null -ne $st -and $st.analysis.analyzing) {
            Invoke-Api -Method POST -Path "/cancelAnalysis" -Body @{} | Out-Null
            Wait-State -Predicate { param($s) -not $s.analysis.analyzing } -TimeoutMs 10000 -NoCollect | Out-Null
        }
        $start = Invoke-Api -Method POST -Path "/analyze" -Body @{ songId = $SongId }
        if (-not $start.Ok) {
            [void]$script:Cur.Repro.Add($start.Repro)
            Add-Failure ("POST /analyze 失败: HTTP " + $start.Status + " " + $start.Text)
            End-Case; return
        }
        Wait-State -Predicate { param($s) $s.analysis.phase -eq 'done' -or $s.analysis.phase -eq 'failed' } -TimeoutMs 120000 -IntervalMs 500 -NoCollect | Out-Null
        $after = Get-LoopRow $SongId
        Add-Metric "songId" $SongId
        Add-Metric "beforeA_samples" $before.loopStartSamples
        Add-Metric "beforeB_samples" $before.loopEndSamples
        Add-Metric "beforeConfirmed" $before.loopConfirmed
        Add-Metric "beforeScore" $before.score
        Add-Metric "afterA_samples" $(if ($null -ne $after) { $after.loopStartSamples } else { $null })
        Add-Metric "afterB_samples" $(if ($null -ne $after) { $after.loopEndSamples } else { $null })
        Add-Metric "afterConfirmed" $(if ($null -ne $after) { $after.loopConfirmed } else { $null })
        Add-Metric "afterScore" $(if ($null -ne $after) { $after.score } else { $null })
        if ($null -eq $after) { Add-Failure ("分析后 /song/" + $SongId + " 读取失败"); End-Case; return }
        if ($after.loopConfirmed -ne $true -and $after.loopConfirmed -ne 1) { Add-Failure ("分析后 confirmed 不再是 1(实际 " + $after.loopConfirmed + ")") }
        if ($after.loopStartSamples -ne $before.loopStartSamples -or $after.loopEndSamples -ne $before.loopEndSamples) {
            Add-Failure ("已确认 A/B 被覆盖: " + $before.loopStartSamples + "/" + $before.loopEndSamples + " -> " + $after.loopStartSamples + "/" + $after.loopEndSamples)
        }
        if ($after.sampleRate -ne $before.sampleRate) { Add-Failure "已确认行 sampleRate 被改写" }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

# ------------------------------------------------------------------ C 组:播放链

function Case-C1 {
    Start-Case 'C1'
    try {
        $setup = Initialize-Seamless -Id $SongId -SeekMs 23000 -TimeoutMs 8000
        Add-SampleSeries $setup.Samples
        if (-not $setup.Ok) { Add-Failure "前置:8s 内 window 未命中(engine=loop)"; End-Case; return }
        # 先挪到 B 前 2s,保证 5s 观察窗内一定跨过一次接缝(否则 30s 环长下 3s 内不可能有接缝)
        $w1 = $setup.State.loop.window
        $bMs = [long][math]::Round($w1.loopEndUs / 1000.0)
        Invoke-Seek ($bMs - 2000) | Out-Null
        Start-Sleep -Milliseconds 300
        $s0 = Get-Sample (Get-State)
        $seam0 = $s0.seams
        $obs = Wait-State -Predicate { param($s) ($s.loop.seamCount -gt $seam0) -or $s.loop.isReplaying } -TimeoutMs 5000 -IntervalMs 300
        Add-SampleSeries $obs.Samples
        $s1 = Get-Sample $obs.State
        Add-Metric "seamWaitOk" $obs.Ok
        Add-Metric "seamWaitMs" $obs.ElapsedMs
        if ($null -eq $s1) { $s1 = $s0 }
        Save-Screenshot "screen.png"
        Add-Metric "songId" $SongId
        Add-Metric "windowSongId" $s1.winSong
        Add-Metric "aMs" $s1.aMs
        Add-Metric "bMs" $s1.bMs
        Add-Metric "engine" $s1.engine
        Add-Metric "seamCountStart" $seam0
        Add-Metric "seamCountEnd" $s1.seams
        Add-Metric "isReplaying" $s1.replay
        Add-Metric "degradeCount" $s1.degrade
        if ($null -eq $s1.winSong) { Add-Failure "3s 后 window 为 null" }
        elseif ($s1.winSong -ne $SongId) { Add-Failure ("window.songId=" + $s1.winSong + " != " + $SongId) }
        if ($null -ne $s1.seams -and $null -ne $seam0 -and $s1.seams -le $seam0 -and -not $s1.replay) { Add-Failure "seamCount 未增长且 isReplaying=false" }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-C2 {
    Start-Case 'C2'
    try {
        $setup = Initialize-Seamless -Id $SongId -SeekMs 23000 -TimeoutMs 8000
        if (-not $setup.Ok) { Add-Failure "前置:window 未命中"; End-Case; return }
        $w = $setup.State.loop.window
        $aMs = [long][math]::Round($w.loopStartUs / 1000.0)
        $bMs = [long][math]::Round($w.loopEndUs / 1000.0)
        # 重放期的 seek 目前会落回 A(实测:seek 30000 -> disp 20331),直通期 seek 才可靠。
        # 先 seek 到区外(前奏)让引擎退出重放,再 seek 到 B 前 1.5s,60 次采样才能真实跨过 B。
        Invoke-Seek 10000 | Out-Null
        $exitReplay = Wait-State -Predicate { param($s) $s.loop.isReplaying -ne $true } -TimeoutMs 3000 -IntervalMs 100
        Add-Metric "exitReplayOk" $exitReplay.Ok
        Invoke-Seek ($bMs - 1500) | Out-Null
        Start-Sleep -Milliseconds 300
        $samples = [System.Collections.ArrayList]::new()
        for ($i = 0; $i -lt 60; $i++) {
            $s = Get-State
            if ($null -ne $s) { [void]$samples.Add((Get-Sample $s)) }
            Start-Sleep -Milliseconds 200
        }
        Add-SampleSeries $samples
        Save-Screenshot "screen.png"
        $posList = @($samples | Where-Object { $null -ne $_.pos } | ForEach-Object { [long]$_.pos })
        if ($posList.Count -eq 0) { Add-Failure "60 次采样全失败(/state 不可用)"; End-Case; return }
        $maxPos = ($posList | Measure-Object -Maximum).Maximum
        $minPos = ($posList | Measure-Object -Minimum).Minimum
        $dropIdx = -1
        for ($i = 1; $i -lt $posList.Count; $i++) {
            if ($posList[$i] -lt ($posList[$i - 1] - 1000)) { $dropIdx = $i; break }
        }
        $afterDrop = $null
        if ($dropIdx -ge 0) { $afterDrop = $posList[$dropIdx] }
        $notPlaying = @($samples | Where-Object { $_.playing -ne $true }).Count
        $outOfBounds = @($posList | Where-Object { $_ -lt ($aMs - 1000) -or $_ -gt ($bMs + 500) }).Count
        Add-Metric "aMs" $aMs
        Add-Metric "bMs" $bMs
        Add-Metric "sampleCount" $samples.Count
        Add-Metric "posMin" $minPos
        Add-Metric "posMax" $maxPos
        Add-Metric "maxOvershootPastB_ms" ($maxPos - $bMs)
        Add-Metric "outOfBoundsSamples" $outOfBounds
        Add-Metric "dropIndex" $dropIdx
        Add-Metric "posAfterDrop" $afterDrop
        Add-Metric "playingFalseSamples" $notPlaying
        Add-Metric "bound" "[A-1000, B+500]"
        if ($dropIdx -lt 0) { Add-Failure "60 次采样内未观察到跨 B 回折" }
        elseif ($afterDrop -lt $aMs -or $afterDrop -gt ($aMs + 3000)) { Add-Failure ("回折后位置 " + $afterDrop + "ms 不在 [A, A+3000]") }
        if ($outOfBounds -gt 0) { Add-Failure ("有 " + $outOfBounds + " 个样本越界([A-1s,B+0.5s]),displayPos 最大越过 B " + ($maxPos - $bMs) + "ms") }
        if ($notPlaying -gt 0) { Add-Failure ("采样期间出现 " + $notPlaying + " 个 playing=false 样本") }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-C3 {
    Start-Case 'C3'
    try {
        # 即使显式 /loop/enter,未确认行也不该出窗口(资格判定第 3 条)
        $enter = Enter-Loop $UnconfirmedSongId
        Start-Sleep -Milliseconds 300
        Set-Repeat "one" | Out-Null
        Start-Play $UnconfirmedSongId $true | Out-Null
        Add-Metric "enterStatus" $enter.Status
        Add-Metric "intentArmed" ((Get-LoopIntent (Get-State)) | ConvertTo-Json -Compress)
        $obs = Wait-State -Predicate { param($s) $false } -TimeoutMs 3200 -IntervalMs 400
        Add-SampleSeries $obs.Samples
        $s = Get-Sample $obs.State
        $row = Get-LoopRow $UnconfirmedSongId
        Add-Metric "songId" $UnconfirmedSongId
        Add-Metric "loopConfirmedInDb" $(if ($null -ne $row) { $row.loopConfirmed } else { $null })
        Add-Metric "hasLoopRow" $(if ($null -ne $row) { $row.hasLoop } else { $null })
        Add-Metric "windowSongId" $(if ($null -ne $s) { $s.winSong } else { $null })
        Add-Metric "engine" $(if ($null -ne $s) { $s.engine } else { $null })
        Add-Metric "playing" $(if ($null -ne $s) { $s.playing } else { $null })
        if ($null -eq $s) { Add-Failure "/state 不可用"; End-Case; return }
        if ($null -ne $s.winSong) { Add-Failure ("未确认歌 window 非 null(songId=" + $s.winSong + ")") }
        if ($s.engine -ne "exo") { Add-Failure ("未确认歌 engine 应为 exo,实际 " + $s.engine) }
        if ($s.playing -ne $true) { Add-Failure "未确认歌未正常播放" }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-C4 {
    Start-Case 'C4'
    try {
        # 进入前 repeat=ALL;进入后引擎设为 ONE;切走应恢复进入前的 ALL
        Set-Repeat "all" | Out-Null
        Start-Sleep -Milliseconds 300
        $setup = Initialize-Seamless -Id $SongId -SeekMs 25000 -TimeoutMs 10000
        if (-not $setup.Ok) { Add-Failure "前置:主歌 window 未命中"; End-Case; return }
        $repeatBefore = $setup.State.playback.repeatMode
        Add-Metric "repeatBeforeEnter" "all"
        Add-Metric "repeatWhileSeamless" $repeatBefore
        Add-Metric "intentWhileSeamless" ((Get-LoopIntent $setup.State) | ConvertTo-Json -Compress)
        Start-PlayKeepRepeat $UnconfirmedSongId | Out-Null
        $sw = Wait-State -Predicate { param($s) $s.playback.songId -eq $UnconfirmedSongId } -TimeoutMs 5000 -IntervalMs 200
        Add-SampleSeries $sw.Samples
        $after = Wait-State -Predicate { param($s) $null -eq $s.loop.window } -TimeoutMs 3000 -IntervalMs 200
        Add-SampleSeries $after.Samples
        $s = Get-Sample $after.State
        if ($null -eq $s) { Add-Failure "/state 不可用"; End-Case; return }
        $p0 = $s.pos
        Start-Sleep -Milliseconds 1500
        $s2 = Get-Sample (Get-State)
        Add-Metric "repeatBefore" $repeatBefore
        Add-Metric "repeatAfter" $s.repeat
        Add-Metric "windowSongId" $s.winSong
        Add-Metric "engine" $s.engine
        Add-Metric "newSongId" $s.songId
        Add-Metric "playing" $s.playing
        Add-Metric "posBefore" $p0
        Add-Metric "posAfter" $(if ($null -ne $s2) { $s2.pos } else { $null })
        if ($null -ne $s.winSong) { Add-Failure ("切歌后 window 非 null(songId=" + $s.winSong + ")") }
        # 新语义:切走要恢复进入前的 repeat(ALL),而不是保持 ONE
        if ($s.repeat -ne "all") { Add-Failure ("切走后 repeat 未恢复为进入前的 all,实际 " + $s.repeat) }
        Add-Metric "intentAfterSwitch" ((Get-LoopIntent $after.State) | ConvertTo-Json -Compress)
        if ($s.songId -ne $UnconfirmedSongId) { Add-Failure ("切歌后当前歌=" + $s.songId + " != " + $UnconfirmedSongId) }
        if ($s.playing -ne $true) { Add-Failure "新歌未在播放" }
        elseif ($null -ne $p0 -and $null -ne $s2.pos -and ($s2.pos - $p0) -lt 300) { Add-Failure ("新歌位置未前进(" + $p0 + " -> " + $s2.pos + ")") }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-C5 {
    Start-Case 'C5'
    try {
        $setup = Initialize-Seamless -Id $SongId -SeekMs 26000 -TimeoutMs 8000
        if (-not $setup.Ok) { Add-Failure "前置:window 未命中"; End-Case; return }
        $t0 = Now-Ms
        Set-Repeat "all" | Out-Null
        $all = Wait-State -Predicate { param($s) $null -eq $s.loop.window } -TimeoutMs 1500 -IntervalMs 100
        $allMs = (Now-Ms) - $t0
        Add-SampleSeries $all.Samples
        $sAll = Get-Sample $all.State
        # 新语义:repeat 改非 ONE 会清空意图 → 再切回 ONE 也不自动恢复(必须重新 /loop/enter)
        Add-Metric "intentAfterAll" ((Get-LoopIntent $all.State) | ConvertTo-Json -Compress)
        Set-Repeat "one" | Out-Null
        $back = Wait-State -Predicate { param($s) $null -ne $s.loop.window } -TimeoutMs 2500 -IntervalMs 200
        $t1 = Now-Ms
        Set-Repeat "off" | Out-Null
        $off = Wait-State -Predicate { param($s) $null -eq $s.loop.window } -TimeoutMs 1500 -IntervalMs 100
        $offMs = (Now-Ms) - $t1
        Add-SampleSeries $off.Samples
        $sOff = Get-Sample $off.State
        if ($null -eq $sOff) { Add-Failure "/state 不可用"; End-Case; return }
        $p0 = $sOff.pos
        Start-Sleep -Milliseconds 1500
        $sOff2 = Get-Sample (Get-State)
        Add-Metric "repeatAllWindowNull" $all.Ok
        Add-Metric "repeatAllLatencyMs" $allMs
        Add-Metric "repeatOffWindowNull" $off.Ok
        Add-Metric "repeatOffLatencyMs" $offMs
        Add-Metric "windowBackOnOne" $back.Ok
        Add-Metric "engineAfterAll" $(if ($null -ne $sAll) { $sAll.engine } else { $null })
        Add-Metric "playingAfterOff" $sOff.playing
        Add-Metric "posBefore" $p0
        Add-Metric "posAfter" $(if ($null -ne $sOff2) { $sOff2.pos } else { $null })
        Add-Metric "boundMs" 1000
        if ($back.Ok) { Add-Failure "repeat 改回 one 后 window 自动恢复(新语义应保持 null,需重新 /loop/enter)" }
        if (-not $all.Ok) { Add-Failure ("repeat=all 后 1s 内 window 未变 null(耗时 " + $allMs + "ms)") }
        if (-not $off.Ok) { Add-Failure ("repeat=off 后 1s 内 window 未变 null(耗时 " + $offMs + "ms)") }
        if ($sOff.playing -ne $true) { Add-Failure "repeat=off 后播放中断" }
        elseif ($null -ne $p0 -and $null -ne $sOff2.pos -and ($sOff2.pos - $p0) -lt 300) { Add-Failure ("repeat=off 后位置未前进(" + $p0 + " -> " + $sOff2.pos + ")") }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-C8 {
    Start-Case 'C8'
    try {
        # 新语义(issue 00004):从武装歌 A 切到另一首已确认歌 B → B 不无缝(window=null)
        # 且 repeat 恢复为进入前的模式(而不是保持 ONE)
        $row = Get-LoopRow $AltSongId
        if ($null -eq $row) { Add-Failure ("前置:/song/" + $AltSongId + " 读取失败"); End-Case; return }
        if ($row.loopConfirmed -ne $true -and $row.loopConfirmed -ne 1) {
            $conf = Invoke-Api -Method POST -Path "/confirm" -Body @{ songId = $AltSongId }
            if (-not $conf.Ok) { Add-Failure ("前置 /confirm 失败: HTTP " + $conf.Status + " " + $conf.Text); End-Case; return }
        }
        Set-Repeat "all" | Out-Null
        Start-Sleep -Milliseconds 300
        $setup = Initialize-Seamless -Id $SongId -SeekMs 23000 -TimeoutMs 10000
        if (-not $setup.Ok) { Add-Failure "前置:主歌 window 未命中"; End-Case; return }
        Add-Metric "repeatWhileSeamless" $setup.State.playback.repeatMode
        Add-Metric "intentWhileSeamless" ((Get-LoopIntent $setup.State) | ConvertTo-Json -Compress)
        Start-PlayKeepRepeat $AltSongId | Out-Null
        $w = Wait-State -Predicate { param($s) ($null -eq $s.loop.window) -and ($s.playback.songId -eq $AltSongId) } -TimeoutMs 5000 -IntervalMs 200
        Add-SampleSeries $w.Samples
        $s = Get-Sample $w.State
        Add-Metric "songId" $AltSongId
        Add-Metric "windowSongId" $(if ($null -ne $s) { $s.winSong } else { $null })
        Add-Metric "engine" $(if ($null -ne $s) { $s.engine } else { $null })
        Add-Metric "repeatAfterSwitch" $(if ($null -ne $s) { $s.repeat } else { $null })
        Add-Metric "intentAfterSwitch" ((Get-LoopIntent $w.State) | ConvertTo-Json -Compress)
        Add-Metric "playing" $(if ($null -ne $s) { $s.playing } else { $null })
        if (-not $w.Ok) { Add-Failure ("切到已确认歌 B 后 window 未变 null 或当前歌不是 B(window=" + $(if ($null -ne $s) { $s.winSong } else { "?" }) + ", song=" + $(if ($null -ne $s) { $s.songId } else { "?" }) + ")") }
        if ($null -ne $s -and $s.engine -ne "exo") { Add-Failure ("切走后 engine 应为 exo,实际 " + $s.engine) }
        if ($null -ne $s -and $s.repeat -ne "all") { Add-Failure ("切走后 repeat 未恢复为进入前的 all,实际 " + $s.repeat) }
        if ($null -ne $s -and $s.playing -ne $true) { Add-Failure "切走后 B 未正常播放" }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-N1 {
    Start-Case 'N1'
    try {
        # 进入前 repeat=ALL → 进入 A → repeat 变 ONE、intent 武装 A → 切到 B → repeat 恢复 ALL、window=null
        Set-Repeat "all" | Out-Null
        Start-Sleep -Milliseconds 300
        $setup = Initialize-Seamless -Id $SongId -SeekMs 23000 -TimeoutMs 10000
        if (-not $setup.Ok) { Add-Failure "前置:进入后 window 未命中"; End-Case; return }
        Add-Metric "repeatWhileSeamless" $setup.State.playback.repeatMode
        Add-Metric "intentWhileSeamless" ((Get-LoopIntent $setup.State) | ConvertTo-Json -Compress)
        if ($setup.State.playback.repeatMode -ne "one") { Add-Failure ("/loop/enter 后 repeat 应为 one,实际 " + $setup.State.playback.repeatMode) }
        Start-PlayKeepRepeat $AltSongId | Out-Null
        $w = Wait-State -Predicate { param($s) ($null -eq $s.loop.window) -and ($s.playback.songId -eq $AltSongId) } -TimeoutMs 5000 -IntervalMs 200
        Add-SampleSeries $w.Samples
        $s = Get-Sample $w.State
        Add-Metric "repeatAfterSwitch" $(if ($null -ne $s) { $s.repeat } else { $null })
        Add-Metric "windowAfterSwitch" $(if ($null -ne $s) { $s.winSong } else { $null })
        Add-Metric "intentAfterSwitch" ((Get-LoopIntent $w.State) | ConvertTo-Json -Compress)
        if (-not $w.Ok) { Add-Failure "切到 B 后 5s 内 window 未变 null" }
        if ($null -ne $s -and $s.repeat -ne "all") { Add-Failure ("切到 B 后 repeat 未恢复为 all,实际 " + $s.repeat) }
        if ($null -ne $s -and $null -ne $s.winSong) { Add-Failure ("切到 B 后 window 非 null(songId=" + $s.winSong + ")") }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-N2 {
    Start-Case 'N2'
    try {
        # 先制造 N1 的终态(在 B、无意图),再切回 A:A 不应自动无缝
        Set-Repeat "all" | Out-Null
        Start-Sleep -Milliseconds 300
        $setup = Initialize-Seamless -Id $SongId -SeekMs 23000 -TimeoutMs 10000
        if (-not $setup.Ok) { Add-Failure "前置:进入后 window 未命中"; End-Case; return }
        Start-PlayKeepRepeat $AltSongId | Out-Null
        $w1 = Wait-State -Predicate { param($s) ($null -eq $s.loop.window) -and ($s.playback.songId -eq $AltSongId) } -TimeoutMs 5000 -IntervalMs 200
        Add-SampleSeries $w1.Samples
        if (-not $w1.Ok) { Add-Failure "前置:切到 B 后 window 未清空"; End-Case; return }
        Start-PlayKeepRepeat $SongId | Out-Null
        $w2 = Wait-State -Predicate { param($s) $s.playback.songId -eq $SongId } -TimeoutMs 5000 -IntervalMs 200
        Add-SampleSeries $w2.Samples
        Start-Sleep -Milliseconds 2500
        $s = Get-Sample (Get-State)
        Add-Metric "windowBackOnA" $(if ($null -ne $s) { $s.winSong } else { $null })
        Add-Metric "engineOnA" $(if ($null -ne $s) { $s.engine } else { $null })
        Add-Metric "repeatOnA" $(if ($null -ne $s) { $s.repeat } else { $null })
        Add-Metric "intentOnA" ((Get-LoopIntent (Get-State)) | ConvertTo-Json -Compress)
        Add-Metric "playingOnA" $(if ($null -ne $s) { $s.playing } else { $null })
        if ($null -ne $s -and $null -ne $s.winSong) { Add-Failure ("切回 A 后 window 非 null(songId=" + $s.winSong + "):不应自动恢复无缝") }
        if ($null -ne $s -and $s.engine -ne "exo") { Add-Failure ("切回 A 后 engine 应为 exo,实际 " + $s.engine) }
        if ($null -ne $s -and $s.playing -ne $true) { Add-Failure "切回 A 后未正常播放" }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-N3 {
    Start-Case 'N3'
    try {
        # 前置:在 A、无意图 → 再次 /loop/enter {A} → window 命中
        Set-Repeat "all" | Out-Null
        Start-Sleep -Milliseconds 300
        Start-PlayKeepRepeat $SongId | Out-Null
        $base = Wait-State -Predicate { param($s) ($null -eq $s.loop.window) -and ($s.playback.songId -eq $SongId) } -TimeoutMs 6000 -IntervalMs 200
        Add-SampleSeries $base.Samples
        Add-Metric "windowBeforeEnter" $(if ($null -ne $base.State) { (Get-Sample $base.State).winSong } else { $null })
        $enter = Enter-Loop $SongId
        Add-Metric "enterStatus" $enter.Status
        $w = Wait-State -Predicate { param($s) ($null -ne $s.loop.window) -and ($s.loop.window.songId -eq $SongId) -and ($s.loop.claimed -eq $true) -and ($s.loop.sourceActive -eq $true) -and ($s.playback.engine -eq 'loop') } -TimeoutMs 8000 -IntervalMs 200
        Add-SampleSeries $w.Samples
        $s = Get-Sample $w.State
        Add-Metric "windowSongId" $(if ($null -ne $s) { $s.winSong } else { $null })
        Add-Metric "engine" $(if ($null -ne $s) { $s.engine } else { $null })
        Add-Metric "repeatAfterEnter" $(if ($null -ne $s) { $s.repeat } else { $null })
        Add-Metric "intentAfterEnter" ((Get-LoopIntent $w.State) | ConvertTo-Json -Compress)
        if (-not $w.Ok) { Add-Failure ("再次 /loop/enter 后 8s 内 window 未命中(engine=" + $(if ($null -ne $s) { $s.engine } else { "?" }) + ")") }
        if ($null -ne $s -and $s.repeat -ne "one") { Add-Failure ("/loop/enter 后 repeat 应为 one,实际 " + $s.repeat) }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-N4 {
    Start-Case 'N4'
    try {
        # 无缝中用户把 repeat 改成 off/all → ≤1s window=null;再改回 one 不自动恢复
        $setup = Initialize-Seamless -Id $SongId -SeekMs 23000 -TimeoutMs 10000
        if (-not $setup.Ok) { Add-Failure "前置:window 未命中"; End-Case; return }
        $t0 = Now-Ms
        Set-Repeat "off" | Out-Null
        $off = Wait-State -Predicate { param($s) $null -eq $s.loop.window } -TimeoutMs 1500 -IntervalMs 100
        $offMs = (Now-Ms) - $t0
        Add-SampleSeries $off.Samples
        Add-Metric "repeatOffWindowNull" $off.Ok
        Add-Metric "repeatOffLatencyMs" $offMs
        Add-Metric "intentAfterOff" ((Get-LoopIntent $off.State) | ConvertTo-Json -Compress)
        Set-Repeat "one" | Out-Null
        $back = Wait-State -Predicate { param($s) $null -ne $s.loop.window } -TimeoutMs 2500 -IntervalMs 200
        Add-SampleSeries $back.Samples
        $s = Get-Sample $back.State
        Add-Metric "windowBackOnOne" $back.Ok
        Add-Metric "playingAfterOne" $(if ($null -ne $s) { $s.playing } else { $null })
        if (-not $off.Ok) { Add-Failure ("repeat=off 后 1s 内 window 未变 null(耗时 " + $offMs + "ms)") }
        if ($back.Ok) { Add-Failure "repeat 改回 one 后 window 自动恢复(新语义应保持 null)" }
        if ($null -ne $s -and $s.playing -ne $true) { Add-Failure "repeat 改回 one 后播放中断" }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-C6 {
    Start-Case 'C6'
    try {
        $row = Get-LoopRow $AltSongId
        if ($null -eq $row -or -not $row.hasLoop) { Add-Failure ("前置:/song/" + $AltSongId + " 无循环行"); End-Case; return }
        if ($row.loopConfirmed -ne $true -and $row.loopConfirmed -ne 1) {
            $c = Invoke-Api -Method POST -Path "/confirm" -Body @{ songId = $AltSongId }
            if (-not $c.Ok) { Add-Failure ("前置 /confirm 失败: HTTP " + $c.Status + " " + $c.Text); End-Case; return }
        }
        $setup = Initialize-Seamless -Id $AltSongId -SeekMs -1 -TimeoutMs 8000
        if (-not $setup.Ok) { Add-Failure "前置:alt 歌 window 未命中"; End-Case; return }
        $t0 = Now-Ms
        $dem = Invoke-Api -Method POST -Path "/demote" -Body @{ songId = $AltSongId }
        if (-not $dem.Ok) {
            [void]$script:Cur.Repro.Add($dem.Repro)
            Add-Failure ("/demote 失败: HTTP " + $dem.Status + " " + $dem.Text)
            End-Case; return
        }
        $w = Wait-State -Predicate { param($s) $null -eq $s.loop.window } -TimeoutMs 1500 -IntervalMs 100
        $latency = (Now-Ms) - $t0
        Add-SampleSeries $w.Samples
        $s = Get-Sample $w.State
        if ($null -eq $s) { Add-Failure "/state 不可用"; End-Case; return }
        $p0 = $s.pos
        Start-Sleep -Milliseconds 1500
        $s2 = Get-Sample (Get-State)
        Add-Metric "songId" $AltSongId
        Add-Metric "windowNull" $w.Ok
        Add-Metric "latencyMs" $latency
        Add-Metric "playingAfterDemote" $s.playing
        Add-Metric "engineAfterDemote" $s.engine
        Add-Metric "posBefore" $p0
        Add-Metric "posAfter" $(if ($null -ne $s2) { $s2.pos } else { $null })
        Add-Metric "demoteBody" $(if ($null -ne $dem.Json) { $dem.Json | ConvertTo-Json -Compress -Depth 4 } else { $null })
        if (-not $w.Ok) { Add-Failure ("demote 后 1s 内 window 未变 null(耗时 " + $latency + "ms)") }
        if ($s.playing -ne $true) { Add-Failure "demote 后播放中断(playing=false)" }
        elseif ($null -ne $p0 -and $null -ne $s2.pos -and ($s2.pos - $p0) -lt 300) { Add-Failure ("demote 后位置未前进(" + $p0 + " -> " + $s2.pos + ")") }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-C9 {
    Start-Case 'C9'
    try {
        $setup = Initialize-Seamless -Id $SongId -SeekMs 23000 -TimeoutMs 8000
        if (-not $setup.Ok) { Add-Failure "前置:window 未命中"; End-Case; return }
        $w = $setup.State.loop.window
        $aMs = [long][math]::Round($w.loopStartUs / 1000.0)
        $bMs = [long][math]::Round($w.loopEndUs / 1000.0)
        $s0 = Get-Sample $setup.State
        $deg0 = $s0.degrade
        $seam0 = $s0.seams
        $t0 = Now-Ms
        Invoke-Seek ($aMs + 2000) | Out-Null
        $back = Wait-State -Predicate { param($s) ($null -ne $s.loop.window) -and ($s.loop.claimed -eq $true) -and ($s.loop.sourceActive -eq $true) -and ($s.playback.engine -eq 'loop') -and $s.playback.playing } -TimeoutMs 3000 -IntervalMs 100
        $seekLatency = (Now-Ms) - $t0
        Add-SampleSeries $back.Samples
        $sBack = Get-Sample $back.State
        # 阶段 2:seek 到 B 前 2s -> 一轮内环回(displayPos 折返 或 seamCount 增长)
        $seamBase = (Get-Sample (Get-State)).seams
        Invoke-Seek ($bMs - 2000) | Out-Null
        $prevPos = $null
        $wrapped = $false
        $wrapSamples = [System.Collections.ArrayList]::new()
        $sw2 = [System.Diagnostics.Stopwatch]::StartNew()
        while ($sw2.ElapsedMilliseconds -lt 10000) {
            Start-Sleep -Milliseconds 200
            $s = Get-State
            if ($null -eq $s) { continue }
            $smp = Get-Sample $s
            [void]$wrapSamples.Add($smp)
            if ($null -ne $prevPos -and $null -ne $smp.pos -and $smp.pos -lt ($prevPos - 1000)) { $wrapped = $true; break }
            if ($null -ne $smp.pos) { $prevPos = $smp.pos }
            if ($null -ne $smp.seams -and $null -ne $seamBase -and $smp.seams -gt $seamBase) { $wrapped = $true; break }
        }
        $seamLatency = $sw2.ElapsedMilliseconds
        Add-SampleSeries $wrapSamples
        $seam = [pscustomobject]@{ Ok = $wrapped; State = $(if ($wrapSamples.Count -gt 0) { Get-State } else { $null }) }
        $sSeam = Get-Sample $seam.State
        if ($null -eq $sSeam) { Add-Failure "/state 不可用"; End-Case; return }
        $degDelta = if ($null -ne $sSeam.degrade) { $sSeam.degrade - $deg0 } else { $null }
        Add-Metric "aMs" $aMs
        Add-Metric "bMs" $bMs
        Add-Metric "degradeBefore" $deg0
        Add-Metric "degradeAfter" $sSeam.degrade
        Add-Metric "degradeDelta" $degDelta
        Add-Metric "windowBackLatencyMs" $seekLatency
        Add-Metric "seamLatencyMs" $seamLatency
        Add-Metric "seamCountBefore" $seam0
        Add-Metric "seamCountAfter" $sSeam.seams
        Add-Metric "engineAfterSeek" $(if ($null -ne $sBack) { $sBack.engine } else { $null })
        Add-Metric "ringCompleteAfterSeek" $(if ($null -ne $sBack) { $sBack.ring } else { $null })
        if (-not $back.Ok) { Add-Failure "区内 seek 后 3s 内未恢复 seamless(engine=loop)" }
        if (-not $seam.Ok) { Add-Failure "seek 到 B 前 2s 后 10s 内未观察到环回(displayPos 折返或 seamCount 增长)" }
        if ($null -ne $degDelta -and $degDelta -gt 1) { Add-Failure ("degradeCount 增加 " + $degDelta + " 次(>1)") }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-C10 {
    Start-Case 'C10'
    try {
        $setup = Initialize-Seamless -Id $SongId -SeekMs 23000 -TimeoutMs 8000
        if (-not $setup.Ok) { Add-Failure "前置:window 未命中"; End-Case; return }
        $w = $setup.State.loop.window
        $bMs = [long][math]::Round($w.loopEndUs / 1000.0)
        Invoke-Seek ($bMs - 4000) | Out-Null
        Start-Sleep -Milliseconds 400
        $s0 = Get-Sample (Get-State)
        $seam0 = $s0.seams
        Toggle-PlayPause | Out-Null
        $paused = Wait-State -Predicate { param($s) $s.playback.playing -eq $false } -TimeoutMs 2000 -IntervalMs 100
        Add-SampleSeries $paused.Samples
        Start-Sleep -Milliseconds 1000
        $sHold = Get-Sample (Get-State)
        Toggle-PlayPause | Out-Null
        $resumed = Wait-State -Predicate { param($s) $s.playback.playing -eq $true } -TimeoutMs 2000 -IntervalMs 100
        Add-SampleSeries $resumed.Samples
        $sResumed = Get-Sample $resumed.State
        $posDelta = $null
        if ($null -ne $sHold -and $null -ne $sResumed -and $null -ne $sHold.pos -and $null -ne $sResumed.pos) {
            $posDelta = [math]::Abs([long]$sResumed.pos - [long]$sHold.pos)
        }
        $seam = Wait-State -Predicate { param($s) $s.loop.seamCount -gt $seam0 } -TimeoutMs 36000 -IntervalMs 300
        Add-SampleSeries $seam.Samples
        $sSeam = Get-Sample $seam.State
        Add-Metric "pausedOk" $paused.Ok
        Add-Metric "resumedOk" $resumed.Ok
        Add-Metric "posWhilePaused" $(if ($null -ne $sHold) { $sHold.pos } else { $null })
        Add-Metric "posAfterResume" $(if ($null -ne $sResumed) { $sResumed.pos } else { $null })
        Add-Metric "posDeltaMs" $posDelta
        Add-Metric "seamCountBefore" $seam0
        Add-Metric "seamCountAfter" $(if ($null -ne $sSeam) { $sSeam.seams } else { $null })
        Add-Metric "windowSongId" $(if ($null -ne $sSeam) { $sSeam.winSong } else { $null })
        if (-not $paused.Ok) { Add-Failure "暂停未在 2s 内生效" }
        if (-not $resumed.Ok) { Add-Failure "恢复未在 2s 内生效" }
        if ($null -ne $posDelta -and $posDelta -gt 1000) { Add-Failure ("暂停/恢复位置跳变 " + $posDelta + "ms(>1000ms)") }
        if (-not $seam.Ok) { Add-Failure ("恢复后 36s 内 seamCount 未增长(" + $seam0 + " -> " + $(if ($null -ne $sSeam) { $sSeam.seams } else { "?" }) + ")") }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-C7 {
    Start-Case 'C7'
    try {
        $setup = Initialize-Seamless -Id $SongId -SeekMs 35000 -TimeoutMs 8000
        if (-not $setup.Ok) { Add-Failure "前置:window 未命中"; End-Case; return }
        $obs = Wait-State -Predicate { param($s) $false } -TimeoutMs 2000 -IntervalMs 400
        Add-SampleSeries $obs.Samples
        $before = Get-Sample $obs.State
        if ($null -eq $before) { Add-Failure "/state 不可用"; End-Case; return }
        Assert-SafePkg "C7 冷启重启"
        $pidBefore = ((& $script:Adb shell "pidof $Pkg" 2>$null) -join "").Trim()
        Write-Log "   (force-stop + 冷启)" "DarkYellow"
        & $script:Adb shell "am force-stop $Pkg" 2>$null | Out-Null
        Start-Sleep -Milliseconds 1500
        $pidDead = ((& $script:Adb shell "pidof $Pkg" 2>$null) -join "").Trim()
        & $script:Adb shell "am start -n $Pkg/.MainActivity" 2>$null | Out-Null
        # 冷启后必须先静默等 restore 完成再发第一次请求:debug MediaController 若在 service
        # 恢复出 session 之前建连,会绑到一个空 player 上(reads 报 idle/queue=0,writes 全部无效),
        # 且没有重连通路。实测静默 8s 后建连即正常。
        Start-Sleep -Seconds 8
        $ready = Wait-PlayerReady -TimeoutMs 30000 -ExpectSongId $SongId
        $coldRetries = 0
        if (-not $ready) {
            # 实测:第一次冷启后 debug 连接可能停在 restore 之前的快照(/player 报 idle/queue=0),
            # 应用侧 restore 本身是成功的(见 dumpsys media_session),再冷启一次即可让连接对上
            $coldRetries = 1
            Write-Log "   (debug 协议未就绪,二次冷启)" "DarkYellow"
            & $script:Adb shell "am force-stop $Pkg" 2>$null | Out-Null
            Start-Sleep -Milliseconds 1500
            & $script:Adb shell "am start -n $Pkg/.MainActivity" 2>$null | Out-Null
            Start-Sleep -Seconds 8
            $ready = Wait-PlayerReady -TimeoutMs 30000 -ExpectSongId $SongId
        }
        Add-Metric "coldStartRetries" $coldRetries
        if (-not $ready) { Add-Failure "冷启后 30s 内 /player 未就绪(queue=0/idle,debug 连接停在 restore 前快照)"; Save-Logcat | Out-Null; End-Case; return }
        $up = Wait-State -Predicate { param($s) $null -ne $s.playback.positionMs } -TimeoutMs 5000 -IntervalMs 500
        Add-SampleSeries $up.Samples
        $settle = Wait-State -Predicate { param($s) $false } -TimeoutMs 4000 -IntervalMs 500
        Add-SampleSeries $settle.Samples
        $after = Get-Sample $settle.State
        if ($null -eq $after) { Add-Failure "/state 不可用(冷启后)"; End-Case; return }
        $delta = $null
        if ($null -ne $before.pos -and $null -ne $after.pos) { $delta = [long]$after.pos - [long]$before.pos }
        if ($after.playing -ne $true) { Toggle-PlayPause | Out-Null }
        $resume = Wait-State -Predicate { param($s) $s.playback.playing -eq $true } -TimeoutMs 5000 -IntervalMs 200
        Add-SampleSeries $resume.Samples
        # 新语义(issue 00004):意图是会话级、不持久化 → 冷启后必须没有窗口(即使 repeat 仍是 ONE)
        $seam = Wait-State -Predicate { param($s) $null -eq $s.loop.window } -TimeoutMs 4000 -IntervalMs 200
        Add-SampleSeries $seam.Samples
        $sFinal = Get-Sample $seam.State
        Start-Sleep -Milliseconds 1500
        $sFinal2 = Get-Sample (Get-State)
        $pidAfter = ((& $script:Adb shell "pidof $Pkg" 2>$null) -join "").Trim()
        Add-Metric "posBeforeKill" $before.pos
        Add-Metric "posAfterColdStart" $after.pos
        Add-Metric "posDeltaMs" $delta
        Add-Metric "boundMs" 15000
        Add-Metric "pidBefore" $pidBefore
        Add-Metric "pidAfterForceStop" $pidDead
        Add-Metric "pidAfterRestart" $pidAfter
        Add-Metric "playingAfterRestart" $after.playing
        Add-Metric "windowSongId" $(if ($null -ne $sFinal) { $sFinal.winSong } else { $null })
        Add-Metric "engine" $(if ($null -ne $sFinal) { $sFinal.engine } else { $null })
        Add-Metric "seamsAfterResume" $(if ($null -ne $sFinal) { $sFinal.seams } else { $null })
        Add-Metric "posAfterResume" $(if ($null -ne $sFinal) { $sFinal.pos } else { $null })
        Add-Metric "posAfterResume2" $(if ($null -ne $sFinal2) { $sFinal2.pos } else { $null })
        if (-not $pidAfter) { Add-Failure "冷启后进程未起来" }
        if ($null -ne $delta -and [math]::Abs($delta) -gt 15000) { Add-Failure ("冷启后位置偏差 " + $delta + "ms(>±15s):展示 " + $before.pos + " vs 恢复 " + $after.pos) }
        Add-Metric "intentAfterRestart" ((Get-LoopIntent (Get-State)) | ConvertTo-Json -Compress)
        if ($null -ne $sFinal -and $null -ne $sFinal.winSong) { Add-Failure ("冷启后 window 非 null(songId=" + $sFinal.winSong + "):意图不应持久化") }
        if ($null -ne $sFinal -and $sFinal.engine -ne "exo") { Add-Failure ("冷启后 engine 应为 exo,实际 " + $sFinal.engine) }
        if ($null -ne $sFinal -and $null -ne $after.pos -and [math]::Abs([long]$sFinal.pos - [long]$after.pos) -gt 15000) { Add-Failure ("恢复播放位置未从恢复位继续(" + $after.pos + " -> " + $sFinal.pos + ")") }
        Save-Logcat | Out-Null
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        Save-Logcat | Out-Null
        End-Case
    }
}

# ------------------------------------------------------------------ D 组

function Case-D1 {
    Start-Case 'D1'
    try {
        $pl = Invoke-Api -Method GET -Path "/library/playlists"
        if (-not $pl.Ok) { Add-Failure ("/library/playlists 失败: HTTP " + $pl.Status); End-Case; return }
        $playlists = @($pl.Json)
        Save-CaseJson "playlists.json" $playlists | Out-Null
        $smart = @($playlists | Where-Object { $_.name -match "无缝" })
        $source = "protocol"
        # /library/playlists 只暴露普通歌单;智能歌单(smart_playlists)走 debug 的 adbdb content provider
        if ($smart.Count -eq 0) {
            $lv = Invoke-AdbDbCall "list_views"
            if ($lv.Ok -and $null -ne $lv.Json -and $null -ne $lv.Json.views) {
                $views = @($lv.Json.views)
                Save-CaseJson "smart-views.json" $views | Out-Null
                $smart = @($views | Where-Object { $_.name -match "无缝" })
                if ($smart.Count -gt 0) { $source = "adbdb" }
            }
        }
        $confirmed = [System.Collections.ArrayList]::new()
        $off = 0
        while ($off -lt 3000) {
            $page = Invoke-Api -Method GET -Path "/library/songs?limit=500&offset=$off"
            if (-not $page.Ok) { break }
            $pageSongs = @($page.Json.songs)
            foreach ($s in $pageSongs) {
                if ($s.loopConfirmed -eq $true -or $s.loopConfirmed -eq 1) { [void]$confirmed.Add([long]$s.id) }
            }
            if ($pageSongs.Count -lt 500) { break }
            $off += 500
        }
        Add-Metric "playlistCount" $playlists.Count
        Add-Metric "smartPlaylistFound" ($smart.Count -gt 0)
        Add-Metric "smartPlaylistSource" $source
        Add-Metric "playlistNames" (($playlists | ForEach-Object { $_.name }) -join "|")
        Add-Metric "confirmedCount" $confirmed.Count
        if ($smart.Count -eq 0) {
            Add-Failure ("未找到「无缝循环」智能歌单(/library/playlists=" + $playlists.Count + " 个普通歌单, smart_playlists 里也没有; confirmed=" + $confirmed.Count + ")")
        } else {
            $name = $smart[0].name
            $entry = $smart[0]
            Add-Metric "protocolSmartFlag" $(if ($null -ne $entry.smart) { $entry.smart } else { "(缺省)" })
            Add-Metric "protocolSongCount" $(if ($null -ne $entry.songCount) { $entry.songCount } else { $null })
            $ids = @()
            $isSmart = ($source -eq "adbdb") -or ($null -ne $entry.smart -and $entry.smart -eq $true)
            if ($isSmart) {
                # 智能歌单的内容走 adbdb 的视图 SQL(/library/songs?playlist= 只解析普通歌单)
                $sql = $entry.sql
                if (-not $sql) {
                    $lv = Invoke-AdbDbCall "list_views"
                    if ($lv.Ok -and $null -ne $lv.Json) {
                        $view = @($lv.Json.views | Where-Object { $_.name -eq $name }) | Select-Object -First 1
                        if ($null -ne $view) { $sql = $view.sql }
                    }
                }
                Add-Metric "smartPlaylistSql" $sql
                Add-Metric "contentSource" "adbdb-view"
                if ($sql) {
                    $q = Invoke-AdbDbCall "query" $sql
                    if ($q.Ok -and $null -ne $q.Json -and $null -ne $q.Json.rows) {
                        $ids = @($q.Json.rows | ForEach-Object { [long]$_.id })
                    }
                }
            } else {
                Add-Metric "contentSource" "protocol"
                $items = Invoke-Api -Method GET -Path ("/library/songs?limit=500&playlist=" + [uri]::EscapeDataString($name))
                if ($items.Ok) { $ids = @($items.Json.songs | ForEach-Object { [long]$_.id }) }
            }
            Save-CaseJson "smart-playlist.json" @{ name = $name; source = $source; ids = $ids; confirmed = @($confirmed) } | Out-Null
            Add-Metric "smartPlaylistName" $name
            Add-Metric "smartPlaylistCount" $ids.Count
            Add-Metric "protocolExposesSmart" ($source -eq "protocol")
            $missing = @($confirmed | Where-Object { $ids -notcontains $_ })
            $extra = @($ids | Where-Object { $confirmed -notcontains $_ })
            Add-Metric "missingFromSmart" $missing.Count
            Add-Metric "extraInSmart" $extra.Count
            if ($missing.Count -gt 0) { Add-Failure ("智能歌单缺 " + $missing.Count + " 首已确认歌(" + ($missing -join ",") + ")") }
            if ($extra.Count -gt 0) { Add-Failure ("智能歌单多 " + $extra.Count + " 首非 confirmed 歌(" + ($extra -join ",") + ")") }
            if ($source -eq "protocol" -and $null -ne $entry.smart -and $entry.smart -ne $true) { Add-Failure "协议返回的「无缝循环」smart 字段不是 true" }
            if ($null -ne $entry.songCount -and [int]$entry.songCount -ne $confirmed.Count) { Add-Failure ("协议 songCount=" + $entry.songCount + " != confirmed " + $confirmed.Count) }
        }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-D3 {
    Start-Case 'D3'
    try {
        $apk = Join-Path $script:Root "app/build/outputs/apk/debug/app-debug.apk"
        $aapt2 = Get-ChildItem (Join-Path $env:ANDROID_HOME "build-tools/*/aapt2.exe") -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
        $label = $null
        $pkgName = $null
        if ($null -ne $aapt2 -and (Test-Path $apk)) {
            $badging = (& $aapt2.FullName dump badging $apk 2>$null) -join $NL
            $m = [regex]::Match($badging, "application-label:'([^']*)'")
            if ($m.Success) { $label = $m.Groups[1].Value }
            $mp = [regex]::Match($badging, "package: name='([^']*)'")
            if ($mp.Success) { $pkgName = $mp.Groups[1].Value }
            (($badging -split '\n') | Select-String -Pattern "^(package:|application-label|application:)") | ForEach-Object { $_.Line } | Set-Content -Path (Join-Path $script:Cur.Dir "badging.txt") -Encoding utf8
            $rel = ($script:Cur.Id + "/badging.txt")
            if (-not $script:Cur.Evidence.Contains($rel)) { [void]$script:Cur.Evidence.Add($rel) }
        }
        $installed = ((& $script:Adb shell "pm path $Pkg" 2>$null) -join "").Trim()
        Add-Metric "apkPath" $apk
        Add-Metric "aapt2" $(if ($null -ne $aapt2) { $aapt2.FullName } else { $null })
        Add-Metric "packageName" $pkgName
        Add-Metric "applicationLabel" $label
        Add-Metric "installedPackage" $installed
        Add-Metric "expected" "海瑟音乐 Dev(debug)"
        # dev 变体是 opt-in:gradlew :app:assembleDebug -PdevPackage=.dev
        # → applicationIdSuffix=.dev + manifestPlaceholders["appLabel"]="海瑟音乐 Dev"
        $isDevVariant = ($pkgName -match "\.dev$")
        Add-Metric "variant" $(if ($isDevVariant) { "dev" } else { "default" })
        Add-Metric "devVariantHint" "dev 变体需 -PdevPackage=.dev 构建(applicationIdSuffix + appLabel)"
        if (-not $label) { Add-Failure "无法读取 application-label(aapt2/APK 缺失)" }
        elseif ($label -notmatch "海瑟音乐") { Add-Failure ("应用名不含「海瑟音乐」: " + $label) }
        elseif ($isDevVariant -and $label -notmatch "Dev") { Add-Failure ("dev 变体应用名未带 Dev 后缀: " + $label) }
        elseif (-not $isDevVariant -and $label -match "Dev") { Add-Failure ("默认变体不应带 Dev 后缀: " + $label) }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

# ------------------------------------------------------------------ C11:重放期 seek 落点

function Case-C11 {
    Start-Case 'C11'
    try {
        $setup = Initialize-Seamless -Id $SongId -SeekMs 23000 -TimeoutMs 12000
        Add-SampleSeries $setup.Samples
        if (-not $setup.Ok) { Add-Failure "前置:window 未命中"; End-Case; return }
        $rep = Wait-State -Predicate { param($s) $s.loop.isReplaying -eq $true } -TimeoutMs 45000 -IntervalMs 300 -NoCollect
        Add-Metric "replayWaitMs" $rep.ElapsedMs
        if (-not $rep.Ok) { Add-Failure "45s 内未进入重放态"; End-Case; return }
        $before = Get-Sample $rep.State
        Add-Metric "posBeforeSeek" $(if ($null -ne $before) { $before.pos } else { $null })
        $w0 = $setup.State.loop.window
        $aMs = [long][math]::Round($w0.loopStartUs / 1000.0)
        $bMs = [long][math]::Round($w0.loopEndUs / 1000.0)
        foreach ($target in @(30000, 48500)) {
            # 落点必须在 seek 后立刻读:等 3s 再读会把正常播放推进算成偏差
            $prePos = $null
            $pre = Get-State
            if ($null -ne $pre) { $prePos = $pre.playback.positionMs }
            $tSeek = Now-Ms
            Invoke-Seek $target | Out-Null
            $s = $null
            $firstAt = 0
            # seek 是异步的:等到位置真正跳变(>500ms)再取落点
            for ($j = 0; $j -lt 25; $j++) {
                Start-Sleep -Milliseconds 100
                $st = Get-State
                if ($null -eq $st -or $null -eq $st.playback.positionMs) { continue }
                $p = [long]$st.playback.positionMs
                if ($null -ne $prePos -and [math]::Abs($p - [long]$prePos) -le 500) { continue }
                $s = Get-Sample $st
                $firstAt = (Now-Ms) - $tSeek
                break
            }
            $expect = $target + $firstAt
            if ($expect -ge $bMs) { $expect = $aMs + (($expect - $aMs) % ($bMs - $aMs)) }
            $delta = $null
            if ($null -ne $s -and $null -ne $s.pos) { $delta = [long]$s.pos - [long]$expect }
            Add-Metric ("seek" + $target + "_pos") $(if ($null -ne $s) { $s.pos } else { $null })
            Add-Metric ("seek" + $target + "_expected") $expect
            Add-Metric ("seek" + $target + "_probeAtMs") $firstAt
            Add-Metric ("seek" + $target + "_deltaMs") $delta
            Add-Metric ("seek" + $target + "_replay") $(if ($null -ne $s) { $s.replay } else { $null })
            if ($null -eq $delta) { Add-Failure ("重放期 seek " + $target + " 后 /state 不可用") }
            elseif ([math]::Abs($delta) -gt 500) { Add-Failure ("重放期 seek " + $target + "ms 落点 " + $s.pos + "ms,期望约 " + $expect + "ms(偏差 " + $delta + "ms > ±500ms)") }
        }
        $s2 = Get-Sample (Get-State)
        Add-Metric "windowSongId" $(if ($null -ne $s2) { $s2.winSong } else { $null })
        Add-Metric "engine" $(if ($null -ne $s2) { $s2.engine } else { $null })
        Add-Metric "playing" $(if ($null -ne $s2) { $s2.playing } else { $null })
        if ($null -ne $s2 -and $null -eq $s2.winSong) { Add-Failure "重放期 seek 后 window 丢失" }
        if ($null -ne $s2 -and $s2.playing -ne $true) { Add-Failure "重放期 seek 后播放中断" }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

# ------------------------------------------------------------------ L4 定向回归

function Case-L4-queue {
    Start-Case 'L4-queue'
    try {
        # 协议里唯一的"整队列"入口:/confirm 的 autoplay 会用完整库列表 setMediaItems
        $conf = Invoke-Api -Method POST -Path "/confirm" -Body @{ songId = $SongId }
        if (-not $conf.Ok) {
            [void]$script:Cur.Repro.Add($conf.Repro)
            Add-Failure ("/confirm 失败: HTTP " + $conf.Status + " " + $conf.Text)
            End-Case; return
        }
        Start-Sleep -Milliseconds 900
        Set-Repeat "all" | Out-Null
        Invoke-Api -Method POST -Path "/player/shuffle" -Body @{ on = $false } | Out-Null
        for ($i = 0; $i -lt 2; $i++) {
            Invoke-Api -Method POST -Path "/player/next" -Body @{} | Out-Null
            Start-Sleep -Milliseconds 700
        }
        Invoke-Seek 30000 | Out-Null
        Start-Sleep -Milliseconds 1000
        $stBefore = Get-State
        if ($null -eq $stBefore) { Add-Failure "/state 不可用"; End-Case; return }
        $posBefore = $stBefore.playback.positionMs
        Add-Metric "queueSize" $stBefore.playback.queueSize
        Add-Metric "currentIndexBefore" $stBefore.playback.currentIndex
        Add-Metric "songIdBefore" $stBefore.playback.songId
        Add-Metric "posBefore" $posBefore
        Add-Metric "repeatBefore" $stBefore.playback.repeatMode
        Add-Metric "shuffleBefore" $stBefore.playback.shuffle
        $ready = Restart-App -SilentMs 8000 -ExpectSongId 0 -ReadyTimeoutMs 30000
        Add-Metric "restoreReady" $ready
        if (-not $ready) { Add-Failure "冷启后 /player 未就绪"; Save-Logcat | Out-Null; End-Case; return }
        Start-Sleep -Milliseconds 4000
        $stAfter = Get-State
        if ($null -eq $stAfter) { Add-Failure "冷启后 /state 不可用"; End-Case; return }
        $posDelta = $null
        if ($null -ne $posBefore -and $null -ne $stAfter.playback.positionMs) { $posDelta = [long]$stAfter.playback.positionMs - [long]$posBefore }
        Add-Metric "queueSizeAfter" $stAfter.playback.queueSize
        Add-Metric "currentIndexAfter" $stAfter.playback.currentIndex
        Add-Metric "songIdAfter" $stAfter.playback.songId
        Add-Metric "posAfter" $stAfter.playback.positionMs
        Add-Metric "posDeltaMs" $posDelta
        Add-Metric "repeatAfter" $stAfter.playback.repeatMode
        Add-Metric "shuffleAfter" $stAfter.playback.shuffle
        Add-Metric "boundMs" 15000
        if ($stAfter.playback.queueSize -ne $stBefore.playback.queueSize) { Add-Failure ("队列长度未还原: " + $stBefore.playback.queueSize + " -> " + $stAfter.playback.queueSize) }
        if ($stAfter.playback.currentIndex -ne $stBefore.playback.currentIndex) { Add-Failure ("当前索引未还原: " + $stBefore.playback.currentIndex + " -> " + $stAfter.playback.currentIndex) }
        if ($stAfter.playback.songId -ne $stBefore.playback.songId) { Add-Failure ("当前歌未还原: " + $stBefore.playback.songId + " -> " + $stAfter.playback.songId) }
        if ($null -eq $posDelta -or [math]::Abs($posDelta) -gt 15000) { Add-Failure ("位置未还原: " + $posBefore + " -> " + $stAfter.playback.positionMs + "(Δ" + $posDelta + "ms)") }
        if ($stAfter.playback.repeatMode -ne $stBefore.playback.repeatMode) { Add-Failure ("repeat 未还原: " + $stBefore.playback.repeatMode + " -> " + $stAfter.playback.repeatMode) }
        if ($stAfter.playback.shuffle -ne $stBefore.playback.shuffle) { Add-Failure ("shuffle 未还原: " + $stBefore.playback.shuffle + " -> " + $stAfter.playback.shuffle) }
        # shuffle 开关在会话内生效(不重启)
        Invoke-Api -Method POST -Path "/player/shuffle" -Body @{ on = $true } | Out-Null
        Start-Sleep -Milliseconds 600
        $sh = Get-State
        Add-Metric "shuffleToggleOn" $(if ($null -ne $sh) { $sh.playback.shuffle } else { $null })
        if ($null -ne $sh -and $sh.playback.shuffle -ne $true) { Add-Failure "shuffle 打开未生效" }
        Invoke-Api -Method POST -Path "/player/shuffle" -Body @{ on = $false } | Out-Null
        Set-Repeat "one" | Out-Null
        Save-Logcat | Out-Null
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        Save-Logcat | Out-Null
        End-Case
    }
}

function Case-L4-wrap {
    Start-Case 'L4-wrap'
    try {
        $conf = Invoke-Api -Method POST -Path "/confirm" -Body @{ songId = $SongId }
        if (-not $conf.Ok) { Add-Failure ("/confirm 失败: HTTP " + $conf.Status + " " + $conf.Text); End-Case; return }
        Start-Sleep -Milliseconds 900
        Set-Repeat "all" | Out-Null
        Invoke-Api -Method POST -Path "/player/shuffle" -Body @{ on = $false } | Out-Null
        Start-Sleep -Milliseconds 500
        $st = Get-State
        if ($null -eq $st) { Add-Failure "/state 不可用"; End-Case; return }
        $idx0 = [int]$st.playback.currentIndex
        $qsize = [int]$st.playback.queueSize
        Add-Metric "queueSize" $qsize
        Add-Metric "startIndex" $idx0
        if ($idx0 -gt 12) { Add-Failure ("当前索引 " + $idx0 + " 过大,previous 绕回成本高;需要队列前几首"); End-Case; return }
        for ($i = 0; $i -lt ($idx0 + 1); $i++) {
            Invoke-Api -Method POST -Path "/player/previous" -Body @{} | Out-Null
            Start-Sleep -Milliseconds 500
        }
        Start-Sleep -Milliseconds 900
        $stLast = Get-State
        Add-Metric "indexAfterPrevWrap" $(if ($null -ne $stLast) { $stLast.playback.currentIndex } else { $null })
        Add-Metric "songIdAtLast" $(if ($null -ne $stLast) { $stLast.playback.songId } else { $null })
        Invoke-Api -Method POST -Path "/player/next" -Body @{} | Out-Null
        Start-Sleep -Milliseconds 1100
        $stFirst = Get-State
        $firstPage = Invoke-Api -Method GET -Path "/library/songs?limit=1"
        $firstSongId = $null
        if ($firstPage.Ok -and @($firstPage.Json.songs).Count -gt 0) { $firstSongId = [long]$firstPage.Json.songs[0].id }
        Add-Metric "indexAfterNextWrap" $(if ($null -ne $stFirst) { $stFirst.playback.currentIndex } else { $null })
        Add-Metric "songIdAtFirst" $(if ($null -ne $stFirst) { $stFirst.playback.songId } else { $null })
        Add-Metric "expectedFirstSongId" $firstSongId
        Add-Metric "playingAfterWrap" $(if ($null -ne $stFirst) { $stFirst.playback.playing } else { $null })
        if ($null -eq $stLast -or [int]$stLast.playback.currentIndex -ne ($qsize - 1)) { Add-Failure ("previous 到首后未绕回最后一首(index=" + $(if ($null -ne $stLast) { $stLast.playback.currentIndex } else { "?" }) + ",期望 " + ($qsize - 1) + ")") }
        if ($null -eq $stFirst -or [int]$stFirst.playback.currentIndex -ne 0) { Add-Failure ("最后一首 next 未回到第 1 首(index=" + $(if ($null -ne $stFirst) { $stFirst.playback.currentIndex } else { "?" }) + ")") }
        elseif ($null -ne $firstSongId -and $stFirst.playback.songId -ne $firstSongId) { Add-Failure ("第 1 首 songId=" + $stFirst.playback.songId + " != " + $firstSongId) }
        if ($null -ne $stFirst -and $stFirst.playback.playing -ne $true) { Add-Failure "绕回后未继续播放" }
        Set-Repeat "one" | Out-Null
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-L4-repeat {
    Start-Case 'L4-repeat'
    try {
        $setup = Initialize-Seamless -Id $SongId -SeekMs 23000 -TimeoutMs 12000
        if (-not $setup.Ok) { Add-Failure "前置:无缝未起"; End-Case; return }
        $map = @{ "off" = "0"; "one" = "1"; "all" = "2" }
        foreach ($mode in @("one", "all", "off", "one")) {
            Set-Repeat $mode | Out-Null
            Start-Sleep -Milliseconds 900
            $s = Get-State
            $p = Get-Prefs
            Add-Metric ("state_" + $mode) $(if ($null -ne $s) { $s.playback.repeatMode } else { $null })
            Add-Metric ("prefs_" + $mode) $p["repeat_mode"]
            if ($null -eq $s) { Add-Failure ("repeat=" + $mode + " 后 /state 不可用"); continue }
            if ($s.playback.repeatMode -ne $mode) { Add-Failure ("repeat=" + $mode + " 后 /state 为 " + $s.playback.repeatMode + "(引擎改写了 repeat)") }
            if ($p["repeat_mode"] -ne $map[$mode]) { Add-Failure ("repeat=" + $mode + " 后 prefs.repeat_mode=" + $p["repeat_mode"] + ",期望 " + $map[$mode]) }
        }
        # 持久化:all -> 冷启
        Set-Repeat "all" | Out-Null
        Start-Sleep -Milliseconds 600
        $ready = Restart-App -SilentMs 8000 -ExpectSongId 0 -ReadyTimeoutMs 30000
        Add-Metric "restoreReady" $ready
        Start-Sleep -Milliseconds 1500
        $after = Get-State
        $pAfter = Get-Prefs
        Add-Metric "restoredRepeat" $(if ($null -ne $after) { $after.playback.repeatMode } else { $null })
        Add-Metric "restoredPrefsRepeat" $pAfter["repeat_mode"]
        if (-not $ready) { Add-Failure "冷启后 /player 未就绪" }
        elseif ($null -ne $after -and $after.playback.repeatMode -ne "all") { Add-Failure ("冷启后 repeatMode=" + $after.playback.repeatMode + " != all") }
        if ($pAfter["repeat_mode"] -ne "2") { Add-Failure ("冷启后 prefs.repeat_mode=" + $pAfter["repeat_mode"] + " != 2") }
        Set-Repeat "one" | Out-Null
        Save-Logcat | Out-Null
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        Save-Logcat | Out-Null
        End-Case
    }
}

function Case-L4-backup {
    Start-Case 'L4-backup'
    try {
        $exp = Invoke-AdbDbCall "export"
        Add-Metric "exportOk" $exp.Ok
        Add-Metric "exportRaw" $exp.Raw
        if (-not $exp.Ok) { Add-Failure ("export 失败: " + $exp.Raw); End-Case; return }
        $dir = Join-Path $script:ArtifactsDir "l4-backup"
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $localBackup = Join-Path $dir "adb_export.json"
        $okPull = Invoke-DeviceCat "adb_export.json" $localBackup
        Add-Metric "backupBytes" $(if (Test-Path $localBackup) { (Get-Item $localBackup).Length } else { 0 })
        if (-not $okPull) { Add-Failure "备份 JSON 拉取失败"; End-Case; return }
        $bk = Get-Content $localBackup -Raw -Encoding utf8 | ConvertFrom-Json
        $loops = @($bk.songLoops)
        $smart = @($bk.smartPlaylists)
        $seamlessSmart = @($smart | Where-Object { $_.name -match "无缝" })
        Add-Metric "backupVersion" $bk.version
        Add-Metric "backupSongs" @($bk.songs).Count
        Add-Metric "backupSongLoops" $loops.Count
        Add-Metric "backupSmartPlaylists" $smart.Count
        Add-Metric "backupHasSeamlessSmart" ($seamlessSmart.Count -gt 0)
        if ($loops.Count -lt 1) { Add-Failure "备份 JSON 不含 songLoops" }
        if ($seamlessSmart.Count -eq 0) { Add-Failure "备份 JSON 不含「无缝循环」智能歌单" }
        # 改动循环点:AltSongId demote + 用显式 A/B 重新 confirm
        $target = $AltSongId
        $row0 = Get-LoopRow $target
        if ($null -eq $row0 -or -not $row0.hasLoop) { Add-Failure ("前置:/song/" + $target + " 无循环行"); End-Case; return }
        $sr = [int]$row0.sampleRate
        $backupA = [long]$row0.loopStartMs
        $backupB = [long]$row0.loopEndMs
        Add-Metric "targetSongId" $target
        Add-Metric "backupA_ms" $backupA
        Add-Metric "backupB_ms" $backupB
        Invoke-Api -Method POST -Path "/demote" -Body @{ songId = $target } | Out-Null
        Start-Sleep -Milliseconds 600
        $conf = Invoke-Api -Method POST -Path "/confirm" -Body @{ songId = $target; aSamples = ([long]40 * $sr); bSamples = ([long]80 * $sr) }
        if (-not $conf.Ok) { Add-Failure ("改动循环点失败(/confirm): HTTP " + $conf.Status + " " + $conf.Text); End-Case; return }
        Start-Sleep -Milliseconds 600
        $row1 = Get-LoopRow $target
        Add-Metric "modifiedA_ms" $(if ($null -ne $row1) { $row1.loopStartMs } else { $null })
        Add-Metric "modifiedB_ms" $(if ($null -ne $row1) { $row1.loopEndMs } else { $null })
        if ($null -eq $row1 -or [long]$row1.loopStartMs -ne 40000) { Add-Failure ("改动未生效(期望 A=40000,实际 " + $(if ($null -ne $row1) { $row1.loopStartMs } else { "?" }) + ")") }
        # 导入(直接读回刚导出的文件,无需传输)
        $imp = Invoke-AdbDbCall "import" "adb_export.json"
        Add-Metric "importOk" $imp.Ok
        Add-Metric "importRaw" $imp.Raw
        if (-not $imp.Ok) { Add-Failure ("import 失败: " + $imp.Raw); End-Case; return }
        Start-Sleep -Milliseconds 1200
        $row2 = Get-LoopRow $target
        Add-Metric "restoredA_ms" $(if ($null -ne $row2) { $row2.loopStartMs } else { $null })
        Add-Metric "restoredB_ms" $(if ($null -ne $row2) { $row2.loopEndMs } else { $null })
        Add-Metric "restoredConfirmed" $(if ($null -ne $row2) { $row2.loopConfirmed } else { $null })
        if ($null -eq $row2) { Add-Failure "导入后 /song 读取失败" }
        else {
            if ([long]$row2.loopStartMs -ne $backupA -or [long]$row2.loopEndMs -ne $backupB) { Add-Failure ("song_loop 未恢复为备份值: " + $row2.loopStartMs + "/" + $row2.loopEndMs + ",期望 " + $backupA + "/" + $backupB) }
            if ($row2.loopConfirmed -ne $true -and $row2.loopConfirmed -ne 1) { Add-Failure "导入后 confirmed 丢失" }
        }
        # 智能歌单仍在
        $lv = Invoke-AdbDbCall "list_views"
        $views = @()
        if ($lv.Ok -and $null -ne $lv.Json) { $views = @($lv.Json.views) }
        $smartAfter = @($views | Where-Object { $_.name -match "无缝" })
        Add-Metric "smartAfterImport" ($smartAfter.Count -gt 0)
        if ($smartAfter.Count -eq 0) { Add-Failure "导入后「无缝循环」智能歌单丢失" }
        $pl = Invoke-Api -Method GET -Path "/library/playlists"
        $protocolSmart = @()
        if ($pl.Ok) { $protocolSmart = @($pl.Json | Where-Object { $_.name -match "无缝" }) }
        Add-Metric "smartInProtocol" ($protocolSmart.Count -gt 0)
        if ($protocolSmart.Count -eq 0) { Add-Failure "导入后 /library/playlists 不再含「无缝循环」" }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-L4-notify {
    Start-Case 'L4-notify'
    try {
        $setup = Initialize-Seamless -Id $SongId -SeekMs 23000 -TimeoutMs 12000
        Add-SampleSeries $setup.Samples
        if (-not $setup.Ok) { Add-Failure "前置:无缝未起"; End-Case; return }
        $w = $setup.State.loop.window
        $aMs = [long][math]::Round($w.loopStartUs / 1000.0)
        $bMs = [long][math]::Round($w.loopEndUs / 1000.0)
        Add-Metric "aMs" $aMs
        Add-Metric "bMs" $bMs
        $deltas = [System.Collections.ArrayList]::new()
        $extrapDeltas = [System.Collections.ArrayList]::new()
        $outOfRange = 0
        $updates = 0
        $prevRaw = $null
        $samples = [System.Collections.ArrayList]::new()
        for ($i = 0; $i -lt 20; $i++) {
            $s = Get-State
            if ($null -eq $s) { continue }
            $smp = Get-Sample $s
            [void]$samples.Add($smp)
            $sess = Get-SessionState
            if ($null -ne $sess -and $null -ne $smp.pos) {
                $raw = [long]$sess.Position - [long]$smp.pos
                [void]$deltas.Add($raw)
                if ($null -ne $prevRaw -and $raw -gt ($prevRaw + 200)) { $updates++ }
                $prevRaw = $raw
                if ($null -ne $sess.Extrapolated) {
                    $d = [long]$sess.Extrapolated - [long]$smp.pos
                    [void]$extrapDeltas.Add($d)
                    if ([math]::Abs($d) -gt 1500) { $outOfRange++ }
                } elseif ([math]::Abs($raw) -gt 1500) { $outOfRange++ }
            }
            if ($null -ne $smp.pos -and ($smp.pos -lt $aMs -or $smp.pos -ge $bMs)) { Add-Metric ("posOutOfRange_" + $i) $smp.pos }
            Start-Sleep -Milliseconds 400
        }
        Add-SampleSeries $samples
        $maxAbs = 0
        foreach ($d in $deltas) { if ([math]::Abs($d) -gt $maxAbs) { $maxAbs = [math]::Abs($d) } }
        $maxExtrap = 0
        foreach ($d in $extrapDeltas) { if ([math]::Abs($d) -gt $maxExtrap) { $maxExtrap = [math]::Abs($d) } }
        Add-Metric "sessionRawVsStateMaxDeltaMs" $maxAbs
        Add-Metric "sessionExtrapolatedVsStateMaxDeltaMs" $maxExtrap
        Add-Metric "sessionDeltaOutOfRange" $outOfRange
        Add-Metric "sessionUpdateResets" $updates
        Add-Metric "sessionSamples" $deltas.Count
        if ($deltas.Count -eq 0) { Add-Failure "无法读取 dumpsys media_session 的 PlaybackState(session 对比为空,不能空过)" }
        Add-Metric "deltaSamples" ($deltas -join ",")
        Add-Metric "extrapolatedDeltaSamples" ($extrapDeltas -join ",")
        if ($outOfRange -gt 0) { Add-Failure ("session 外推位置与 /state 偏差 >1.5s 的样本 " + $outOfRange + " 个") }
        # session PlaybackState 刷新间隔:窗口内 delta 没有回落 = 一直没刷新
        if ($updates -eq 0 -and $maxAbs -gt 3000) { Add-Metric "sessionStalenessWarning" ("10s 窗口内 session PlaybackState 未刷新,原始 position 落后 /state " + $maxAbs + "ms(通知/锁屏进度靠系统外推)") }
        # 只在 claimActive 时要求 position ∈ [A,B)；源未生效时位置本来就该是线性媒体时间。
        $posBad = @($samples | Where-Object { $_.claimActive -eq $true -and $null -ne $_.pos -and ($_.pos -lt $aMs -or $_.pos -ge $bMs) }).Count
        $posSkipped = @($samples | Where-Object { $_.claimActive -ne $true }).Count
        Add-Metric "statePosOutOfWindow" $posBad
        Add-Metric "statePosUncheckedBecauseClaimInactive" $posSkipped
        if ($posBad -gt 0) { Add-Failure ("/state positionMs 在 claimActive 时不在 [A,B) 内的样本 " + $posBad + " 个") }
        # 锁屏 -> 亮屏 -> 解锁
        Write-Log "   (锁屏/亮屏)" "DarkYellow"
        Invoke-Adb "input keyevent 26" | Out-Null
        Start-Sleep -Seconds 3
        Invoke-Adb "input keyevent 224" | Out-Null
        Start-Sleep -Milliseconds 800
        Invoke-Adb "input swipe 540 1900 540 700" | Out-Null
        Start-Sleep -Seconds 2
        $after = Get-Sample (Get-State)
        $alive = Test-ProcessAlive
        $fatal = Get-FatalLines 2000
        Add-Metric "playingAfterScreenCycle" $(if ($null -ne $after) { $after.playing } else { $null })
        Add-Metric "posAfterScreenCycle" $(if ($null -ne $after) { $after.pos } else { $null })
        Add-Metric "processAlive" $alive.Alive
        Add-Metric "fatalLines" $fatal.Count
        if (-not $alive.Alive) { Add-Failure "锁屏/亮屏后进程消失" }
        if ($fatal.Count -gt 0) { Add-Failure ("logcat 出现 FATAL: " + ($fatal -join " | ")) }
        if ($null -eq $after -or $after.playing -ne $true) { Add-Failure "锁屏/亮屏后播放未继续" }
        Save-Logcat | Out-Null
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-L4-smoke {
    Start-Case 'L4-smoke'
    try {
        # 1) 打开列表
        $list = Invoke-Api -Method GET -Path "/library/songs?limit=20"
        $listCount = 0
        if ($list.Ok) { $listCount = @($list.Json.songs).Count }
        Add-Metric "listOk" $list.Ok
        Add-Metric "listCount" $listCount
        Add-Metric "libraryTotal" $(if ($list.Ok) { $list.Json.total } else { $null })
        if (-not $list.Ok -or $listCount -lt 1) { Add-Failure "库列表读取失败" }
        $detail = Invoke-Api -Method GET -Path "/song/$SongId"
        Add-Metric "songDetailOk" $detail.Ok
        if (-not $detail.Ok) { Add-Failure "/song/{id} 读取失败" }
        # 2) 排序切换:协议无排序入口(UI-only),记录为 SKIP
        Add-Metric "sortSwitch" "SKIP: 协议无排序入口(UI-only)"
        # 3) 建歌单 + 加歌(adbdb exec)-> 用 /library/songs?playlist= 复核
        $plName = "e2e-tmp-" + (Get-Date -Format "HHmmss")
        $plHex = ConvertTo-SqlText $plName
        $nowMs = Now-Ms
        $r1 = Invoke-AdbDbCall "exec" ("INSERT INTO playlists(name, createdAt) VALUES(" + $plHex + ", " + $nowMs + ")")
        $r2 = Invoke-AdbDbCall "exec" ("INSERT INTO playlist_entries(playlistId, songId, [order]) SELECT p.id, " + $SongId + ", 0 FROM playlists p WHERE p.name=" + $plHex)
        Add-Metric "createPlaylistOk" ($r1.Ok -and $r2.Ok)
        Add-Metric "createPlaylistRaw" ($r1.Raw + " || " + $r2.Raw)
        if (-not ($r1.Ok -and $r2.Ok)) { Add-Failure ("建歌单/加歌失败: " + $r1.Raw + " || " + $r2.Raw) }
        $qCount = Invoke-AdbDbCall "query" ("SELECT COUNT(*) AS c FROM playlist_entries e JOIN playlists p ON p.id=e.playlistId WHERE p.name=" + $plHex)
        $entryCount = 0
        if ($null -ne $qCount.Json -and @($qCount.Json.rows).Count -gt 0) { $entryCount = [int]$qCount.Json.rows[0].c }
        Add-Metric "playlistEntryCountInDb" $entryCount
        $items = Invoke-Api -Method GET -Path ("/library/songs?limit=50&playlist=" + [uri]::EscapeDataString($plName))
        $itemIds = @()
        if ($items.Ok) { $itemIds = @($items.Json.songs | ForEach-Object { [long]$_.id }) }
        Add-Metric "playlistItemCount" $itemIds.Count
        Add-Metric "playlistHasSong" ($itemIds -contains $SongId)
        if (-not ($itemIds -contains $SongId)) { Add-Failure "新建歌单里没有加进去的歌(协议读回为空)" }
        # 4) 收藏切换(adbdb exec + query)
        $path = $detail.Json.path
        $pathHex = ConvertTo-SqlText $path
        $fAdd = Invoke-AdbDbCall "exec" ("INSERT OR REPLACE INTO favorites(filePath, startPosition, dateAdded) VALUES(" + $pathHex + ", 0, " + $nowMs + ")")
        $fQ1 = Invoke-AdbDbCall "query" ("SELECT COUNT(*) AS c FROM favorites WHERE filePath=" + $pathHex)
        $fDel = Invoke-AdbDbCall "exec" ("DELETE FROM favorites WHERE filePath=" + $pathHex)
        $fQ2 = Invoke-AdbDbCall "query" ("SELECT COUNT(*) AS c FROM favorites WHERE filePath=" + $pathHex)
        $c1 = $null; $c2 = $null
        if ($null -ne $fQ1.Json -and @($fQ1.Json.rows).Count -gt 0) { $c1 = [int]$fQ1.Json.rows[0].c }
        if ($null -ne $fQ2.Json -and @($fQ2.Json.rows).Count -gt 0) { $c2 = [int]$fQ2.Json.rows[0].c }
        Add-Metric "favoriteAfterAdd" $c1
        Add-Metric "favoriteAfterRemove" $c2
        Add-Metric "favoriteAddRaw" $fAdd.Raw
        if ($null -eq $c1 -or $c1 -lt 1) { Add-Failure "收藏切换(加)失败" }
        if ($null -ne $c2 -and $c2 -ne 0) { Add-Failure "收藏切换(删)失败" }
        # 清理临时歌单
        Invoke-AdbDbCall "exec" ("DELETE FROM playlist_entries WHERE playlistId IN (SELECT id FROM playlists WHERE name=" + $plHex + ")") | Out-Null
        Invoke-AdbDbCall "exec" ("DELETE FROM playlists WHERE name=" + $plHex) | Out-Null
        # 5) 扫描/排序:协议无入口,记录
        Add-Metric "scanTrigger" "SKIP: 协议无扫描入口(启动扫描由 UI/启动流程触发)"
        Save-Logcat | Out-Null
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        End-Case
    }
}

function Case-L5 {
    Start-Case 'L5'
    $tapName = "tap-l5.wav"
    try {
        $setup = Initialize-Seamless -Id $SongId -SeekMs 23000 -TimeoutMs 12000
        if (-not $setup.Ok) { Add-Failure "前置:无缝未起"; End-Case; return }
        $tap = Invoke-Api -Method POST -Path "/debug/tap" -Body @{ path = $tapName }
        if (-not $tap.Ok) { Add-Failure ("tap on 失败: HTTP " + $tap.Status); End-Case; return }
        $rnd = New-Object System.Random(20261115)
        $ops = 200
        $violations = 0
        $stalls = 0
        $claimFallbacks = 0
        $lastPos = -1
        $lastMoveAt = Now-Ms
        $samples = [System.Collections.ArrayList]::new()
        $crashAt = -1
        $t0 = Now-Ms
        for ($i = 0; $i -lt $ops; $i++) {
            $op = $rnd.Next(0, 16)
            switch ($op) {
                0 { Start-Play $SongId $true | Out-Null }
                1 { Toggle-PlayPause | Out-Null }
                2 { Invoke-Api -Method POST -Path "/player/next" -Body @{} | Out-Null }
                3 { Invoke-Api -Method POST -Path "/player/previous" -Body @{} | Out-Null }
                4 { Invoke-Seek ($rnd.Next(20000, 50000)) | Out-Null }
                5 { Invoke-Seek ($rnd.Next(0, 19000)) | Out-Null }
                6 { Invoke-Seek 247000 | Out-Null }
                7 { Set-Repeat @("one", "all", "off")[$rnd.Next(0, 3)] | Out-Null }
                8 { Start-Play $AltSongId $true | Out-Null }
                9 { Start-Play $UnconfirmedSongId $true | Out-Null }
                10 { Invoke-Api -Method POST -Path "/confirm" -Body @{ songId = $AnalysisSongId } | Out-Null }
                11 { Invoke-Api -Method POST -Path "/demote" -Body @{ songId = $AnalysisSongId } | Out-Null }
                12 { Invoke-Api -Method POST -Path "/analyze" -Body @{ songId = $AnalysisSongId } | Out-Null }
                13 { Invoke-Adb "input keyevent 3" | Out-Null }
                14 { Invoke-Adb "am start -n $Pkg/.MainActivity" | Out-Null }
                15 { Invoke-Api -Method POST -Path "/player/shuffle" -Body @{ on = $rnd.Next(0, 2) } | Out-Null }
            }
            Start-Sleep -Milliseconds ($rnd.Next(100, 800))
            $s = Get-State
            if ($null -eq $s) { continue }
            $smp = Get-Sample $s
            [void]$samples.Add($smp)
            # 一致性:engine 必须严格等于 claimActive；claimActive 必须严格等于 sourceActive。
            # claimed=true + sourceActive=false 是“回退到普通播放”的合法退化，单独计数，不算违规。
            $windowForCurrent = ($null -ne $smp.winSong) -and ($smp.winSong -eq $smp.songId)
            $claimActive = $smp.claimActive -eq $true
            $sourceActive = $smp.sourceActive -eq $true
            $engineLoop = $smp.engine -eq "loop"
            if ($engineLoop -ne $claimActive) { $violations++ }
            if ($claimActive -ne $sourceActive) { $violations++ }
            if ($claimActive -and -not $windowForCurrent) { $violations++ }
            if (($smp.claimed -eq $true) -and -not $sourceActive) { $claimFallbacks++ }
            # 卡死:playing 且位置 5s 不动
            if ($smp.playing -eq $true) {
                if ($smp.pos -ne $lastPos) { $lastPos = $smp.pos; $lastMoveAt = Now-Ms }
                elseif (((Now-Ms) - $lastMoveAt) -gt 5000) { $stalls++; $lastMoveAt = Now-Ms }
            } else { $lastMoveAt = Now-Ms }
            if ($i % 20 -eq 0) {
                $alive = Test-ProcessAlive
                if (-not $alive.Alive) { $crashAt = $i; break }
            }
        }
        $elapsed = (Now-Ms) - $t0
        Add-SampleSeries $samples
        Add-Metric "opsRequested" $ops
        Add-Metric "opsExecuted" $samples.Count
        Add-Metric "elapsedMs" $elapsed
        Add-Metric "consistencyViolations" $violations
        Add-Metric "claimFallbacks" $claimFallbacks
        Add-Metric "stalls" $stalls
        Add-Metric "crashAtOp" $crashAt
        if ($violations -gt 0) { Add-Failure ("claim/engine/source 一致性违反 " + $violations + " 次") }
        if ($stalls -gt 0) { Add-Failure ("卡死(playing 且位置 5s 不动) " + $stalls + " 次") }
        if ($crashAt -ge 0) { Add-Failure ("第 " + $crashAt + " 次操作后进程消失") }
        $fatal = Get-FatalLines 4000
        Add-Metric "fatalLines" $fatal.Count
        if ($fatal.Count -gt 0) { Add-Failure ("logcat FATAL: " + ($fatal -join " | ")) }
        # 收尾:恢复无缝播放
        Set-Repeat "one" | Out-Null
        $rec = Initialize-Seamless -Id $SongId -SeekMs 23000 -TimeoutMs 15000
        Add-Metric "recoveredSeamless" $rec.Ok
        if (-not $rec.Ok) { Add-Failure "风暴结束后 15s 内无法恢复正常无缝播放" }
        $off = Invoke-Api -Method POST -Path "/debug/tap" -Body @{ path = $null }
        if (-not $off.Ok) { Add-Metric "tapOffOk" $false }
        Start-Sleep -Milliseconds 800
        # tap 取证 + analyzer
        $dir = Join-Path $script:ArtifactsDir "l5"
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $wavLocal = Join-Path $dir "tap.wav"
        $jsonlLocal = Join-Path $dir "tap.jsonl"
        $okWav = Invoke-DeviceCat $tapName $wavLocal
        $okJsonl = Invoke-DeviceCat ($tapName + ".jsonl") $jsonlLocal
        $wavOk = Test-WavFile $wavLocal
        $jsonlLines = 0
        if (Test-Path $jsonlLocal) { $jsonlLines = @(Get-Content $jsonlLocal -Encoding utf8).Count }
        Add-Metric "wavBytes" $(if (Test-Path $wavLocal) { (Get-Item $wavLocal).Length } else { 0 })
        Add-Metric "wavIsRiff" $wavOk
        Add-Metric "jsonlLines" $jsonlLines
        $relWav = "l5/tap.wav"
        if (-not $script:Cur.Evidence.Contains($relWav)) { [void]$script:Cur.Evidence.Add($relWav) }
        if (-not $okWav -or -not $wavOk) { Add-Failure "风暴 tap WAV 拉取/校验失败" }
        if ($okJsonl -and $jsonlLines -ge 2 -and $wavOk) {
            $ana = Invoke-Analyzer $wavLocal $jsonlLocal $dir
            Add-Metric "analyzerExitCode" $ana.ExitCode
            if ($null -ne $ana.Report) {
                $crit = [ordered]@{}
                foreach ($k in @("A1", "A2", "A3", "A4", "B")) {
                    if ($null -ne $ana.Report.criteria.$k) { $crit[$k] = $ana.Report.criteria.$k.status }
                }
                Add-Metric "criteria" $crit
                Add-Metric "failedMust" (@($ana.Report.verdict.failedMust) -join ",")
                # 风暴期随机 pause/seek 必然产生喂数间隔(A4)与不完整轮(B):只对 A1/A2 判
                if ($crit["A1"] -ne "pass") { Add-Failure ("风暴 tap 的 A1 不通过(" + $crit["A1"] + ")") }
                if ($crit["A2"] -ne "pass") { Add-Failure ("风暴 tap 的 A2 不通过(" + $crit["A2"] + ")") }
            }
            $relRep = "l5/analyzer-report.json"
            if (-not $script:Cur.Evidence.Contains($relRep)) { [void]$script:Cur.Evidence.Add($relRep) }
        } else {
            Add-Metric "analyzerExitCode" "SKIP(无有效 tap)"
        }
        Save-Logcat | Out-Null
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        try { Invoke-Api -Method POST -Path "/debug/tap" -Body @{ path = $null } | Out-Null } catch { }
        Save-Logcat | Out-Null
        End-Case
    }
}

# ------------------------------------------------------------------ L3 PCM 取证

function Invoke-DeviceCat([string]$RemoteName, [string]$LocalPath) {
    New-Item -ItemType Directory -Force -Path (Split-Path $LocalPath) | Out-Null
    $cmdLine = '"{0}" -s {1} exec-out run-as {2} cat files/{3} > "{4}"' -f $script:Adb, $Serial, $Pkg, $RemoteName, $LocalPath
    & cmd.exe /c $cmdLine | Out-Null
    return (Test-Path $LocalPath) -and ((Get-Item $LocalPath).Length -gt 0)
}

function Test-WavFile([string]$Path) {
    if (-not (Test-Path $Path)) { return $false }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 12) { return $false }
    return ([System.Text.Encoding]::ASCII.GetString($bytes, 0, 4) -eq "RIFF")
}

function Invoke-Analyzer([string]$WavPath, [string]$JsonlPath, [string]$OutDir) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $reportPath = Join-Path $OutDir "analyzer-report.json"
    $stdoutPath = Join-Path $OutDir "analyzer-stdout.txt"
    $cliArgs = @("run", "tools/spike-analyze.ts", $WavPath, $JsonlPath, "--mode", "sink", "--json", $reportPath)
    $out = & bun @cliArgs 2>&1
    $code = $LASTEXITCODE
    ($out | Out-String) | Set-Content -Path $stdoutPath -Encoding utf8
    $report = $null
    if (Test-Path $reportPath) { try { $report = Get-Content $reportPath -Raw -Encoding utf8 | ConvertFrom-Json } catch { $report = $null } }
    return [pscustomobject]@{ ExitCode = $code; Report = $report; StdoutPath = $stdoutPath; ReportPath = $reportPath; Output = ($out | Out-String) }
}

function Invoke-WavCheck([string]$WavPath, [int]$AMs, [int]$BMs, [int]$StartMs, [string]$OutDir) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $outPath = Join-Path $OutDir "wav-check.txt"
    $cliArgs = @("run", "tools/loop-wav-check.ts", $WavPath, "--a", "$AMs", "--b", "$BMs", "--sr", "48000", "--startMs", "$StartMs")
    $out = & bun @cliArgs 2>&1
    $code = $LASTEXITCODE
    ($out | Out-String) | Set-Content -Path $outPath -Encoding utf8
    return [pscustomobject]@{ ExitCode = $code; Output = ($out | Out-String); Path = $outPath }
}

function Invoke-L3Capture {
    param(
        [string]$CaseId,
        [long]$TargetSongId,
        [int]$Seconds,
        [string]$TapName,
        [int]$AMs,
        [int]$BMs,
        [int]$SeekMs = 0,
        [int]$MinSeams = 0,
        [scriptblock]$Prepare = $null
    )
    Start-Case $CaseId
    try {
        if ($null -ne $Prepare) {
            $prepId = & $Prepare
            if ($null -eq $script:Cur) { return }
            if ($prepId -and [long]$prepId -gt 0) { $TargetSongId = [long]$prepId }
        }
        if ($TargetSongId -le 0) { Add-Failure "无有效目标歌"; End-Case; return }
        $setup = Initialize-Seamless -Id $TargetSongId -SeekMs $SeekMs -TimeoutMs 10000
        Add-SampleSeries $setup.Samples
        if (-not $setup.Ok) { Add-Failure "前置:window 未命中"; End-Case; return }
        $w = $setup.State.loop.window
        if ($AMs -le 0 -or $BMs -le 0) {
            $AMs = [int][math]::Round($w.loopStartUs / 1000.0)
            $BMs = [int][math]::Round($w.loopEndUs / 1000.0)
        }
        $tap = Invoke-Api -Method POST -Path "/debug/tap" -Body @{ path = $TapName }
        if (-not $tap.Ok) {
            [void]$script:Cur.Repro.Add($tap.Repro)
            Add-Failure ("tap on 失败: HTTP " + $tap.Status + " " + $tap.Text)
            End-Case; return
        }
        if ($SeekMs -gt 0) { Invoke-Seek $SeekMs | Out-Null }
        $seam0 = if ($null -ne $setup.State) { $setup.State.loop.seamCount } else { 0 }
        $deg0 = if ($null -ne $setup.State) { $setup.State.loop.degradeCount } else { 0 }
        $t0 = Now-Ms
        $samples = [System.Collections.ArrayList]::new()
        $stalled = 0
        $lastPos = -1
        while (((Now-Ms) - $t0) -lt ($Seconds * 1000)) {
            Start-Sleep -Milliseconds 500
            $s = Get-State
            if ($null -eq $s) { continue }
            $smp = Get-Sample $s
            [void]$samples.Add($smp)
            if ($smp.playing -eq $true) {
                if ($smp.pos -eq $lastPos) { $stalled++ } else { $stalled = 0 }
                $lastPos = $smp.pos
            }
            if ($stalled -ge 20) { Add-Failure "采样期播放卡住(位置 10s 不动)"; break }
        }
        $elapsed = (Now-Ms) - $t0
        Add-SampleSeries $samples
        $off = Invoke-Api -Method POST -Path "/debug/tap" -Body @{ path = $null }
        if (-not $off.Ok) { [void]$script:Cur.Repro.Add($off.Repro) }
        Start-Sleep -Milliseconds 700
        if ($samples.Count -eq 0) { Add-Failure "采样期间 /state 全部失败"; End-Case; return }
        $last = $samples[$samples.Count - 1]
        Add-Metric "songId" $TargetSongId
        Add-Metric "aMs" $AMs
        Add-Metric "bMs" $BMs
        Add-Metric "secondsRequested" $Seconds
        Add-Metric "secondsSampled" ([math]::Round($elapsed / 1000.0, 2))
        Add-Metric "seamCountStart" $seam0
        Add-Metric "seamCountEnd" $last.seams
        Add-Metric "seamDelta" ($last.seams - $seam0)
        Add-Metric "degradeCount" $last.degrade
        Add-Metric "degradeDelta" ($last.degrade - $deg0)
        Add-Metric "engine" $last.engine
        Add-Metric "windowSongId" $last.winSong
        if ($MinSeams -gt 0 -and ($last.seams - $seam0) -lt $MinSeams) { Add-Failure ("接缝数 " + ($last.seams - $seam0) + " < " + $MinSeams) }
        $l3dir = Join-Path $script:ArtifactsDir $CaseId.ToLower()
        New-Item -ItemType Directory -Force -Path $l3dir | Out-Null
        $wavLocal = Join-Path $l3dir "tap.wav"
        $jsonlLocal = Join-Path $l3dir "tap.jsonl"
        $okWav = Invoke-DeviceCat $TapName $wavLocal
        $okJsonl = Invoke-DeviceCat ($TapName + ".jsonl") $jsonlLocal
        $wavOk = Test-WavFile $wavLocal
        $wavBytes = if (Test-Path $wavLocal) { (Get-Item $wavLocal).Length } else { 0 }
        $jsonlLines = 0
        if (Test-Path $jsonlLocal) { $jsonlLines = @(Get-Content $jsonlLocal -Encoding utf8).Count }
        Add-Metric "wavPath" $wavLocal
        Add-Metric "wavBytes" $wavBytes
        Add-Metric "wavIsRiff" $wavOk
        Add-Metric "jsonlLines" $jsonlLines
        $relWav = ($CaseId.ToLower() + "/tap.wav")
        if (-not $script:Cur.Evidence.Contains($relWav)) { [void]$script:Cur.Evidence.Add($relWav) }
        $relJsonl = ($CaseId.ToLower() + "/tap.jsonl")
        if (-not $script:Cur.Evidence.Contains($relJsonl)) { [void]$script:Cur.Evidence.Add($relJsonl) }
        if (-not $okWav -or -not $wavOk) { Add-Failure ("WAV 拉取/校验失败(bytes=" + $wavBytes + ", riff=" + $wavOk + ")") }
        if (-not $okJsonl -or $jsonlLines -lt 2) { Add-Failure ("jsonl 侧车缺失/为空(lines=" + $jsonlLines + ")") }
        if (-not $wavOk -or $jsonlLines -lt 2) { Save-Logcat | Out-Null; End-Case; return }
        $ana = Invoke-Analyzer $wavLocal $jsonlLocal $l3dir
        Add-Metric "analyzerExitCode" $ana.ExitCode
        $failedMust = @()
        if ($null -ne $ana.Report) {
            $failedMust = @($ana.Report.verdict.failedMust)
            Add-Metric "failedMust" ($failedMust -join ",")
            $crit = [ordered]@{}
            foreach ($k in @("A1", "A2", "A3", "A4", "B")) {
                if ($null -ne $ana.Report.criteria.$k) { $crit[$k] = $ana.Report.criteria.$k.status }
            }
            Add-Metric "criteria" $crit
            if ($null -ne $ana.Report.verdict.exitCode) { Add-Metric "verdictExitCode" $ana.Report.verdict.exitCode }
        } else {
            Add-Metric "failedMust" "(无 report json)"
            Add-Failure ("analyzer 未产出 JSON 报告(退出码 " + $ana.ExitCode + ")")
        }
        $relRep = ($CaseId.ToLower() + "/analyzer-report.json")
        if (-not $script:Cur.Evidence.Contains($relRep)) { [void]$script:Cur.Evidence.Add($relRep) }
        $relOut = ($CaseId.ToLower() + "/analyzer-stdout.txt")
        if (-not $script:Cur.Evidence.Contains($relOut)) { [void]$script:Cur.Evidence.Add($relOut) }
        if ($ana.ExitCode -ne 0) { Add-Failure ("analyzer 退出码 " + $ana.ExitCode) }
        if ($failedMust.Count -gt 0) { Add-Failure ("failedMust = " + ($failedMust -join ",")) }
        $wc = Invoke-WavCheck $wavLocal $AMs $BMs $SeekMs $l3dir
        Add-Metric "wavCheckExitCode" $wc.ExitCode
        Add-Metric "wavCheckVerdict" $(if ($wc.Output -match "VERDICT: PASS") { "PASS" } else { "FAIL/INCONCLUSIVE" })
        $relWc = ($CaseId.ToLower() + "/wav-check.txt")
        if (-not $script:Cur.Evidence.Contains($relWc)) { [void]$script:Cur.Evidence.Add($relWc) }
        End-Case
    } catch {
        Add-Failure ("异常: " + $_.Exception.Message)
        try { Invoke-Api -Method POST -Path "/debug/tap" -Body @{ path = $null } | Out-Null } catch { }
        Save-Logcat | Out-Null
        End-Case
    }
}

function Case-L3-1 {
    Invoke-L3Capture -CaseId 'L3-1' -TargetSongId $SongId -Seconds $L3Seconds -TapName "tap-l3-1.wav" -AMs 20000 -BMs 50000 -SeekMs 0 -MinSeams 2
}

function Case-L3-2 {
    $prepare = {
        $off = 0
        $picked = $null
        if ($FlacSongId -gt 0) {
            $forced = Invoke-Api -Method GET -Path "/song/$FlacSongId"
            if ($forced.Ok) { $picked = $forced.Json } else { Add-Failure ("/song/" + $FlacSongId + " 读取失败"); return 0 }
        }
        while ($null -eq $picked -and $off -lt 3000) {
            $page = Invoke-Api -Method GET -Path "/library/songs?limit=500&offset=$off"
            if (-not $page.Ok) { break }
            $pageSongs = @($page.Json.songs)
            # 跳过 CUE 分轨:CUE 曲目只是整轨的一个区间,分析会按整轨解码并抛「曲目过长」
            $hit = @($pageSongs | Where-Object { $_.mimeType -match "flac" -and $_.isCue -eq $false -and $_.durationMs -ge 120000 } | Select-Object -First 1)
            if ($hit.Count -gt 0) { $picked = $hit[0] } 
            if ($pageSongs.Count -lt 500) { break }
            $off += 500
        }
        if ($null -eq $picked) { Add-Failure "库里没有 >=120s 的 FLAC"; return 0 }
        $flacId = [long]$picked.id
        Add-Metric "flacSongId" $flacId
        Add-Metric "flacTitle" $picked.title
        Add-Metric "flacPath" $picked.path
        Add-Metric "flacDurationMs" $picked.durationMs
        $st = Get-State
        if ($null -ne $st -and $st.analysis.analyzing) {
            Invoke-Api -Method POST -Path "/cancelAnalysis" -Body @{} | Out-Null
            Wait-State -Predicate { param($s) -not $s.analysis.analyzing } -TimeoutMs 10000 -NoCollect | Out-Null
        }
        $an = Invoke-Api -Method POST -Path "/analyze" -Body @{ songId = $flacId }
        if (-not $an.Ok) { Add-Failure ("FLAC /analyze 失败: HTTP " + $an.Status + " " + $an.Text); return 0 }
        Wait-State -Predicate { param($s) $s.analysis.phase -eq 'done' -or $s.analysis.phase -eq 'failed' } -TimeoutMs 180000 -IntervalMs 500 -NoCollect | Out-Null
        $full = Invoke-Api -Method GET -Path "/analysis"
        if (-not $full.Ok -or $full.Json.phase -ne "done") {
            Add-Metric "flacAnalysisPhase" $(if ($null -ne $full.Json) { $full.Json.phase } else { "?" })
            Add-Metric "flacAnalysisMessage" $(if ($null -ne $full.Json) { $full.Json.message } else { $null })
            Add-Failure ("FLAC 分析未 done(phase=" + $full.Json.phase + ", message=" + $full.Json.message + ")")
            return 0
        }
        $sr = [int]$full.Json.sampleRate
        Add-Metric "flacSampleRate" $sr
        $conf = Invoke-Api -Method POST -Path "/confirm" -Body @{ songId = $flacId; aSamples = ([long]20 * $sr); bSamples = ([long]50 * $sr) }
        if (-not $conf.Ok) { Add-Failure ("FLAC /confirm 失败: HTTP " + $conf.Status + " " + $conf.Text); return 0 }
        Add-Metric "flacConfirmA_ms" 20000
        Add-Metric "flacConfirmB_ms" 50000
        Write-Log ("   FLAC 已确认: id=" + $flacId + " sr=" + $sr + " A=20s B=50s") "DarkYellow"
        return $flacId
    }
    Invoke-L3Capture -CaseId 'L3-2' -TargetSongId 0 -Seconds $L3Seconds -TapName "tap-l3-2.wav" -AMs 20000 -BMs 50000 -SeekMs 0 -MinSeams 2 -Prepare $prepare
}

function Case-L3-4 {
    Invoke-L3Capture -CaseId 'L3-4' -TargetSongId $SongId -Seconds $LongRunSeconds -TapName "tap-l3-4.wav" -AMs 20000 -BMs 50000 -SeekMs 0 -MinSeams 8
}

# ------------------------------------------------------------------ 主流程

$allCases = @('A1', 'A2', 'A3', 'A4', 'A5', 'A6', 'C1', 'C2', 'C3', 'C4', 'C5', 'C6', 'C7', 'C8', 'C9', 'C10', 'C11', 'N1', 'N2', 'N3', 'N4', 'D1', 'D3')
$l3Cases = @('L3-1', 'L3-2', 'L3-4')
$l4Cases = @('L4-queue', 'L4-wrap', 'L4-repeat', 'L4-backup', 'L4-notify', 'L4-smoke')
$l5Cases = @('L5')

if ($Case.Count -gt 0) {
    $selected = @()
    foreach ($c in $Case) { foreach ($p in ($c -split ',')) { if ($p.Trim()) { $selected += $p.Trim() } } }
} elseif ($IncludeL3) {
    $selected = $allCases + $l3Cases
} else {
    $selected = $allCases
}
$valid = $allCases + $l3Cases + $l4Cases + $l5Cases
$unknown = @($selected | Where-Object { $valid -notcontains $_ })
if ($unknown.Count -gt 0) {
    Write-Log ("未知用例: " + ($unknown -join ",")) "Red"
    Write-Log ("可用: " + ($valid -join ",")) "Yellow"
    exit 2
}

Write-Log "== loop-e2e ==" "Cyan"
Write-Log ("   device=" + $Serial + " pkg=" + $Pkg + " base=" + $Base)
Write-Log ("   cases=" + ($selected -join ","))
Write-Log ("   results=" + $script:ResultsPath)
if (-not $AppendResults -and (Test-Path $script:ResultsPath)) { Remove-Item $script:ResultsPath -Force }
Ensure-Forward
$health = Invoke-Api -Method GET -Path "/state"
if (-not $health.Ok) {
    Write-Log ("FATAL: /state 不可用(HTTP " + $health.Status + "): " + $health.Text) "Red"
    Write-Log ("  复现: " + $health.Repro) "Red"
    exit 3
}
Write-Log ("   server uptime=" + $health.Json.server.uptimeMs + "ms songs=" + $health.Json.library.songCount + " confirmedLoops=" + $health.Json.library.confirmedLoopCount)

$sw = [System.Diagnostics.Stopwatch]::StartNew()
foreach ($c in $selected) {
    switch ($c) {
        'A1' { Case-A1 }
        'A2' { Case-A2 }
        'A3' { Case-A3 }
        'A4' { Case-A4 }
        'A5' { Case-A5 }
        'A6' { Case-A6 }
        'C1' { Case-C1 }
        'C2' { Case-C2 }
        'C3' { Case-C3 }
        'C4' { Case-C4 }
        'C5' { Case-C5 }
        'C6' { Case-C6 }
        'C7' { Case-C7 }
        'C8' { Case-C8 }
        'C9' { Case-C9 }
        'C10' { Case-C10 }
        'C11' { Case-C11 }
        'N1' { Case-N1 }
        'N2' { Case-N2 }
        'N3' { Case-N3 }
        'N4' { Case-N4 }
        'D1' { Case-D1 }
        'L4-queue' { Case-L4-queue }
        'L4-wrap' { Case-L4-wrap }
        'L4-repeat' { Case-L4-repeat }
        'L4-backup' { Case-L4-backup }
        'L4-notify' { Case-L4-notify }
        'L4-smoke' { Case-L4-smoke }
        'L5' { Case-L5 }
        'D3' { Case-D3 }
        'L3-1' { Case-L3-1 }
        'L3-2' { Case-L3-2 }
        'L3-4' { Case-L3-4 }
    }
}
$sw.Stop()
Save-Results

Write-Log ""
Write-Log "================ 汇总 ================" "Cyan"
$all = Get-JsonItems $script:ResultsPath
$pass = @($all | Where-Object { $_.status -eq "PASS" }).Count
$fail = @($all | Where-Object { $_.status -eq "FAIL" }).Count
$skip = @($all | Where-Object { $_.status -eq "SKIP" }).Count
$rows = @($all | ForEach-Object {
        [pscustomobject]@{
            id         = $_.id
            status     = $_.status
            durationMs = $_.durationMs
            detail     = if ($_.detail) { ($_.detail -replace '\r?\n', ' ') } else { "" }
        }
    })
($rows | Format-Table -AutoSize | Out-String -Width 240) | Write-Host
Write-Log ("PASS=" + $pass + "  FAIL=" + $fail + "  SKIP=" + $skip + "  总耗时=" + [math]::Round($sw.Elapsed.TotalSeconds, 1) + "s  forward 重建=" + $script:ForwardRepairs)
if ($fail -gt 0) {
    Write-Log ""
    Write-Log "失败用例与判据:" "Red"
    foreach ($r in @($all | Where-Object { $_.status -eq "FAIL" })) {
        Write-Log ("  - " + $r.id + ": " + $r.detail) "Red"
    }
}
Write-Log ("results: " + $script:ResultsPath)
Write-Log ("evidence: " + $script:ShotsDir)
if ($fail -gt 0) { exit 1 }
exit 0
