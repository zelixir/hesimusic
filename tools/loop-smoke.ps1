param(
    [string]$Base = "http://127.0.0.1:18765",
    [long]$SongId = 0,
    [int]$Seconds = 35,
    [switch]$SkipAnalyze,
    [switch]$NoConfirm,
    [int]$AMs = 0,
    [int]$BMs = 0,
    [string]$TapPath = ""
)
# 无缝循环主链巡检(L2 冒烟):/state -> /analyze -> /confirm -> /play -> 采样断言
# 用法: powershell -NoProfile -File tools/loop-smoke.ps1 [-SongId 123] [-Seconds 35] [-TapPath /data/user/0/com.zjr.hesimusic/files/tap.wav]
$ErrorActionPreference = "Stop"
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$PKG = "com.zjr.hesimusic"

function Ensure-Forward {
    $list = (& $adb forward --list) 2>$null
    if (-not ($list -match "tcp:18765")) {
        & $adb forward tcp:18765 tcp:8765 | Out-Null
        Start-Sleep -Milliseconds 200
    }
}

function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body = $null)
    $uri = "$Base$Path"
    try {
        if ($null -ne $Body) {
            $json = ($Body | ConvertTo-Json -Compress)
            return Invoke-RestMethod -Method $Method -Uri $uri -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 30
        }
        return Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec 30
    } catch {
        # forward 被 adb/系统重启清掉是这台设备的已知现象:重建后重试一次
        try {
            & $adb forward --remove tcp:18765 2>$null | Out-Null
            & $adb forward tcp:18765 tcp:8765 | Out-Null
            Start-Sleep -Milliseconds 300
            if ($null -ne $Body) {
                $json = ($Body | ConvertTo-Json -Compress)
                return Invoke-RestMethod -Method $Method -Uri $uri -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 30
            }
            return Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec 30
        } catch { }
        $msg = $_.Exception.Message
        $resp = $null
        if ($_.Exception.Response -ne $null) {
            try {
                $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $resp = $sr.ReadToEnd()
            } catch { }
        }
        throw "HTTP $Method $Path failed: $msg $resp"
    }
}

function Now-Ms { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }

Write-Host "== forward ==" ; & $adb forward tcp:18765 tcp:8765 | Out-Null
Write-Host "== state ==" ; $state = Invoke-Api GET "/state"
$pb = $state.playback
Write-Host ("engine=" + $pb.engine + " playing=" + $pb.playing + " repeat=" + $pb.repeatMode + " songId=" + $pb.songId)

if ($SongId -le 0) {
    $songs = Invoke-Api GET "/library/songs?limit=60"
    $pick = $songs.songs | Where-Object { $_.path -like "*.mp3" } | Select-Object -First 1
    if ($null -eq $pick) { $pick = $songs.songs | Select-Object -First 1 }
    $SongId = $pick.id
    Write-Host ("picked songId=" + $SongId + " title=" + $pick.title)
}

if ($TapPath -ne "") {
    try { $r = Invoke-Api POST "/debug/tap" @{ path = $TapPath }; Write-Host ("tap on: " + ($r | ConvertTo-Json -Compress)) }
    catch { Write-Warning ("tap endpoint unavailable: " + $_.Exception.Message) }
}

if (-not $SkipAnalyze) {
    Write-Host "== analyze =="
    $t0 = Now-Ms
    try { $a = Invoke-Api POST "/analyze" @{ songId = $SongId }; Write-Host ("analyze started: " + ($a | ConvertTo-Json -Compress)) }
    catch { Write-Warning ("analyze rejected: " + $_.Exception.Message) }
    $phases = New-Object System.Collections.ArrayList
    for ($i = 0; $i -lt 240; $i++) {
        Start-Sleep -Milliseconds 500
        $an = Invoke-Api GET "/analysis"
        [void]$phases.Add(("{0}:{1:N2}" -f $an.phase, [double]$an.progress))
        if ($an.phase -eq "done" -or $an.phase -eq "failed") { break }
    }
    $elapsed = (Now-Ms) - $t0
    Write-Host ("analysis elapsed=" + $elapsed + "ms phase=" + $an.phase + " candidates=" + $an.candidateCount)
    $uniq = ($phases | Select-Object -Unique) -join " -> "
    Write-Host ("progress samples: " + $uniq)
    if ($an.phase -eq "failed") { throw ("analysis failed: " + $an.message) }
}

if (-not $NoConfirm) {
    Write-Host "== confirm =="
    $body = if ($AMs -gt 0 -and $BMs -gt $AMs) {
        $sr = if ($an -and $an.sampleRate) { [int]$an.sampleRate } else { 48000 }
        @{ songId = $SongId; aSamples = [long]$AMs * $sr / 1000; bSamples = [long]$BMs * $sr / 1000 }
    } else { @{ songId = $SongId; useCandidate = 0 } }
    try { $c = Invoke-Api POST "/confirm" $body; Write-Host ("confirm: " + ($c | ConvertTo-Json -Compress -Depth 4)) }
    catch { Write-Warning ("confirm failed: " + $_.Exception.Message) }
}

Write-Host "== repeat=one + play =="
Invoke-Api POST "/player/repeat" @{ mode = "one" } | Out-Null
$play = Invoke-Api POST "/player/play" @{ songId = $SongId; repeatOne = $true }
Write-Host ("play: " + ($play | ConvertTo-Json -Compress))

Write-Host "== sampling =="
$samples = New-Object System.Collections.ArrayList
$t1 = Now-Ms
while (((Now-Ms) - $t1) -lt ($Seconds * 1000)) {
    Start-Sleep -Milliseconds 200
    $s = Invoke-Api GET "/state"
    $lp = $s.loop
    $row = [ordered]@{
        t = (Now-Ms) - $t1
        pos = $s.playback.positionMs
        dur = $s.playback.durationMs
        playing = $s.playback.playing
        engine = $s.playback.engine
        windowSong = if ($lp -and $lp.window) { $lp.window.songId } else { $null }
        aMs = if ($lp -and $lp.window) { [math]::Round($lp.window.loopStartUs / 1000) } else { $null }
        bMs = if ($lp -and $lp.window) { [math]::Round($lp.window.loopEndUs / 1000) } else { $null }
        replaying = if ($lp) { $lp.isReplaying } else { $null }
        seams = if ($lp) { $lp.seamCount } else { $null }
        degrade = if ($lp) { $lp.degradeCount } else { $null }
        ringOk = if ($lp) { $lp.isRingComplete } else { $null }
    }
    [void]$samples.Add([pscustomobject]$row)
}

$first = $samples[0]; $last = $samples[-1]
$seamFirst = $first.seams; $seamLast = $last.seams
$degradeLast = $last.degrade
$positions = $samples | ForEach-Object { [int]$_.pos }
$maxPos = ($positions | Measure-Object -Maximum).Maximum
$minPos = ($positions | Measure-Object -Minimum).Minimum

Write-Host ""
Write-Host "== summary =="
Write-Host ("window: songId=" + $last.windowSong + " A=" + $last.aMs + "ms B=" + $last.bMs + "ms")
Write-Host ("seams: " + $seamFirst + " -> " + $seamLast + " ; degrade=" + $degradeLast + " ; ringOk=" + $last.ringOk)
Write-Host ("position: min=" + $minPos + " max=" + $maxPos + " last=" + $last.pos + " playing=" + $last.playing)
$drop = 0
for ($i = 1; $i -lt $positions.Count; $i++) { if ($positions[$i] -lt $positions[$i-1] - 1500) { $drop++ } }
Write-Host ("backward jumps(>1.5s): " + $drop)

$result = [ordered]@{
    songId = $SongId
    window = @{ songId = $last.windowSong; aMs = $last.aMs; bMs = $last.bMs }
    seamsStart = $seamFirst; seamsEnd = $seamLast; degrade = $degradeLast; ringOk = $last.ringOk
    positionMin = $minPos; positionMax = $maxPos; backwardJumps = $drop
    samples = $samples
}
$out = Join-Path (Get-Location) "e2e-artifacts"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$file = Join-Path $out ("smoke-" + $SongId + "-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".json")
$result | ConvertTo-Json -Depth 6 | Set-Content -Path $file -Encoding utf8
Write-Host ("saved: " + $file)

$fail = @()
if ($null -eq $last.windowSong) { $fail += "loop window is null (bridge not wired, or not seamless)" }
else {
    if ($last.windowSong -ne $SongId) { $fail += "window.songId != requested song" }
    if ($null -ne $seamFirst -and $null -ne $seamLast -and $seamLast -le $seamFirst) { $fail += "seamCount did not grow" }
    if ($null -ne $degradeLast -and $degradeLast -gt 0) { $fail += "degradeCount > 0" }
    if ($null -ne $last.bMs -and $maxPos -gt ($last.bMs + 1500)) { $fail += "position exceeded B+1.5s" }
}
if ($fail.Count -gt 0) { Write-Warning ("SMOKE FAIL: " + ($fail -join "; ")); exit 1 }
Write-Host "SMOKE PASS"
exit 0