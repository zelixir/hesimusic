#requires -version 5.1
<#
.SYNOPSIS
  Engine cost baseline: measure the CPU cost of plain ExoPlayer single-track loop (repeatMode=ONE)
  for any installed package, with exactly the same /proc metric used by tools/engine-cost-gate.ps1.

.DESCRIPTION
  This script exists because the pre-seamless-loop baseline (commit c55ffe4) is installed as a
  side-by-side package and has no debug HTTP API. It therefore never talks to the app: it only
  reads /proc/<pid>/{stat,status} and AudioFlinger, and asserts preconditions through adb.

  Two ways to bring the player into the measured state:

    -Mode ui   playback is already running (started by touch input); the script verifies through
               dumpsys media_session that the package owns a PLAYING session, checks the app's
               playback_prefs repeat_mode (1 = REPEAT_MODE_ONE when -RequireRepeatMode one), and
               verifies AudioFlinger output frames are actually advancing.

    -Mode api  drive the debug HTTP API of the current build: POST /loop/enter for
               -SongId with -Ams/-Bms, wait until loop.engine=loop, loop.isRingComplete (or a
               position wrap / seamCount>0), then measure the same way.

  Metrics (identical names to engine-cost-gate):
    CPU_ms, User_ms, Sys_ms  /proc/<pid>/stat utime+stime
    CtxtV, CtxtNV            /proc/<pid>/status voluntary/nonvoluntary_ctxt_switches
    MinFlt, MajFlt           /proc/<pid>/stat minflt/majflt
    Frames                   AudioFlinger top-level output "Frames written" delta
    Duty                     Frames / (48000 * measured seconds); full duty >= 0.98

  The screen is never turned off/woken, volume/brightness/network are only read, never changed.
  KEYCODE_F1 (unmapped no-op) is sent every 4 minutes only to keep the screen-off timer alive.

.PARAMETER Package
  Package to measure (e.g. com.zjr.hesimusic.baseline or com.zjr.hesimusic).

.PARAMETER Mode
  ui (default) or api.

.PARAMETER SongId
  Song id for -Mode api (and recorded in the report).

.PARAMETER Ams / Bms
  Loop window in ms for -Mode api.

.PARAMETER SongLabel
  Free text recorded in the report (title / file for evidence).

.PARAMETER RequireRepeatMode
  one (default) | off | all | any. -Mode ui only. "any" skips the check.

.PARAMETER StopPackages
  Comma separated packages force-stopped before the round (default: com.zjr.hesimusic when
  measuring another package). Use "none" to skip.

.PARAMETER ComparePath
  Optional path to a previously measured round.json (the A round). After the round the script
  computes this round / A round CPU ratio and prints PASS (<= 1.10) / FAIL.

.PARAMETER Minutes / WarmupSec / SampleSec
  Round length (default 10), steady warmup (20), sampling period (30).

.PARAMETER OutDir
  Artifact directory (default e2e-artifacts/engine-cost-baseline/<timestamp>).

.PARAMETER SelfTest
  Run parser/report self-tests without touching a device.
#>
[CmdletBinding()]
param(
    [string]$Package = "com.zjr.hesimusic.baseline",
    [ValidateSet("ui", "api")][string]$Mode = "ui",
    [long]$SongId = 0,
    [int]$Ams = 0,
    [int]$Bms = 0,
    [string]$SongLabel = "",
    [ValidateSet("one", "off", "all", "any")][string]$RequireRepeatMode = "one",
    [string]$StopPackages = "",
    [string]$ComparePath = "",
    [int]$Minutes = 10,
    [int]$WarmupSec = 20,
    [int]$SampleSec = 30,
    [string]$OutDir = "",
    [string]$Device = "",
    [string]$FromRound = "",
    [switch]$SelfTest
)

$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$script:Adb = $null
$script:AdbArgs = @()
$script:Serial = ""
$script:RunDir = ""
$script:LogPath = ""
$script:ClkTck = 100
$script:LocalPort = 18765
$script:Base = "http://127.0.0.1:$($script:LocalPort)"
$script:SelfTestFailures = 0

function Write-Log {
    param([string]$Message, [string]$Color = "Gray")
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $Message
    Write-Host $line -ForegroundColor $Color
    if ($script:LogPath -ne "") { try { Add-Content -Path $script:LogPath -Value $line -Encoding utf8 } catch { } }
}

function Normalize-Text {
    param([string]$Text)
    if ($null -eq $Text) { return "" }
    return (($Text -replace ([string][char]13), "") -replace ([string][char]0), "")
}

function Convert-LongValue {
    param($Value)
    if ($null -eq $Value) { return $null }
    $s = ([string]$Value).Replace(",", "").Trim()
    if ($s -eq "") { return $null }
    $n = 0L
    if ([long]::TryParse($s, [ref]$n)) { return $n }
    return $null
}

function Format-Num {
    param($Value, [string]$Format = "{0:N2}", [string]$NullText = "n/a")
    if ($null -eq $Value) { return $NullText }
    return ($Format -f [double]$Value)
}

function Save-Text {
    param([string]$Path, [string]$Text)
    try {
        $dir = Split-Path -Parent $Path
        if ($dir -ne "" -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        Set-Content -Path $Path -Value $Text -Encoding utf8
    } catch { Write-Log ("WARN cannot save " + $Path + ": " + $_.Exception.Message) "Yellow" }
}

function Save-Json {
    param([string]$Path, $Object)
    try { Save-Text $Path ($Object | ConvertTo-Json -Depth 16) } catch { Write-Log ("WARN json " + $Path + ": " + $_.Exception.Message) "Yellow" }
}

# ------------------------------------------------------------------ adb

function Resolve-Adb {
    foreach ($c in @(
            (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
            $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }),
            $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" }))) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "adb not found"
}

function Invoke-Adb {
    param([string[]]$CmdArgs, [switch]$AllowFail)
    $all = @()
    if ($script:AdbArgs.Count -gt 0) { $all += $script:AdbArgs }
    $all += $CmdArgs
    $out = & $script:Adb @all 2>&1
    $code = $LASTEXITCODE
    $text = Normalize-Text (($out | ForEach-Object { [string]$_ }) -join ([string][char]10))
    if ($code -ne 0 -and -not $AllowFail) { throw ("adb " + ($CmdArgs -join " ") + " failed (exit " + $code + "): " + $text) }
    return @{ Text = $text; Code = $code }
}

function Shell-Text {
    param([string]$Command)
    return (Invoke-Adb -CmdArgs @("shell", $Command)).Text
}

function Select-Device {
    $r = Invoke-Adb -CmdArgs @("devices") -AllowFail
    $serials = @()
    foreach ($line in $r.Text.Split([char]10)) { if ($line -match '^(\S+)\s+device\s*$') { $serials += $Matches[1] } }
    if ($script:Serial -ne "") {
        if (-not ($serials -contains $script:Serial)) { throw ("device not attached: " + $script:Serial) }
        return
    }
    if ($serials.Count -eq 1) { $script:Serial = $serials[0]; return }
    if ($serials.Count -eq 0) { throw "no authorized adb device" }
    throw ("multiple devices; pass -Device: " + ($serials -join ", "))
}

# ------------------------------------------------------------------ parsers

function Parse-ProcStat {
    param([string]$Text)
    $line = $null
    foreach ($l in (Normalize-Text $Text).Split([char]10)) { if ($l -match '^\s*\d+\s+\(') { $line = $l; break } }
    if ($null -eq $line) { return $null }
    $close = $line.LastIndexOf(')')
    if ($close -lt 0) { return $null }
    $f = $line.Substring($close + 1).Trim() -split '\s+'
    if ($f.Count -lt 13) { return $null }
    return @{
        UtSt = ((Convert-LongValue $f[11]) + (Convert-LongValue $f[12]))
        Utime = (Convert-LongValue $f[11]); Stime = (Convert-LongValue $f[12])
        MinFlt = (Convert-LongValue $f[7]); MajFlt = (Convert-LongValue $f[9])
    }
}

function Parse-ProcStatus {
    param([string]$Text)
    $t = Normalize-Text $Text
    $out = @{ CtxtVol = $null; CtxtNonvol = $null; VmRssKb = $null }
    $m = [regex]::Match($t, '(?im)^\s*voluntary_ctxt_switches:\s*(\d+)')
    if ($m.Success) { $out.CtxtVol = Convert-LongValue $m.Groups[1].Value }
    $m = [regex]::Match($t, '(?im)^\s*nonvoluntary_ctxt_switches:\s*(\d+)')
    if ($m.Success) { $out.CtxtNonvol = Convert-LongValue $m.Groups[1].Value }
    $m = [regex]::Match($t, '(?im)^\s*VmRSS:\s*(\d+)\s*kB')
    if ($m.Success) { $out.VmRssKb = Convert-LongValue $m.Groups[1].Value }
    return $out
}

function Parse-MediaSession {
    param([string]$Text)
    # Returns the state/position of the first PLAYING/PAUSED session owned by -Package.
    $t = Normalize-Text $Text
    $sessions = [regex]::Matches($t, '(?s)package=([\w\.]+).*?state=PlaybackState\s*\{([^}]*)\}')
    foreach ($m in $sessions) { if ($m.Groups[1].Value -eq $Package) { return ([regex]::Match($m.Groups[2].Value, 'state=(\w+)\((\d+)\)')) } }
    return $null
}

function Get-MediaSessionState {
    if ($null -eq $script:Adb) { return $null }
    $r = Invoke-Adb -CmdArgs @("shell", "dumpsys media_session") -AllowFail
    if ($r.Code -ne 0) { return $null }
    $t = Normalize-Text $r.Text
    $idx = $t.IndexOf("package=" + $Package)
    if ($idx -lt 0) { return $null }
    $seg = $t.Substring($idx, [Math]::Min(2000, $t.Length - $idx))
    $m = [regex]::Match($seg, 'state=PlaybackState\s*\{\s*state=(\w+)\((\d+)\),\s*position=(\d+)')
    if (-not $m.Success) { $m = [regex]::Match($seg, 'state=PlaybackState\s*\{\s*state=(\w+)\((\d+)\)') }
    if (-not $m.Success) { return $null }
    $pos = $null
    if ($m.Groups.Count -gt 3 -and $m.Groups[3].Success) { $pos = Convert-LongValue $m.Groups[3].Value }
    return @{ State = $m.Groups[1].Value; Code = [int]$m.Groups[2].Value; Position = $pos }
}

function Parse-PrefsXml {
    param([string]$Text)
    $t = Normalize-Text $Text
    $out = @{}
    foreach ($m in [regex]::Matches($t, '<(int|long|boolean|string) name="([^"]+)" value="([^"]*)"')) {
        $out[$m.Groups[2].Value] = $m.Groups[3].Value
    }
    foreach ($m in [regex]::Matches($t, '<(int|long|boolean|string) name="([^"]+)" />')) { $out[$m.Groups[2].Value] = "" }
    return $out
}

function Get-PlaybackPrefs {
    $r = Invoke-Adb -CmdArgs @("shell", "run-as $Package cat shared_prefs/playback_prefs.xml") -AllowFail
    if ($r.Code -ne 0) { return $null }
    return (Parse-PrefsXml $r.Text)
}

function Get-AudioFlingerFrames {
    param([int]$Retries = 3)
    for ($i = 0; $i -lt $Retries; $i++) {
        $r = Invoke-Adb -CmdArgs @("shell", "dumpsys media.audio_flinger") -AllowFail
        if ($r.Code -eq 0) {
            $sum = 0L; $n = 0
            foreach ($m in [regex]::Matches((Normalize-Text $r.Text), '(?m)^  Frames written:\s*(\d+)')) {
                $v = Convert-LongValue $m.Groups[1].Value
                if ($null -ne $v) { $sum += $v; $n++ }
            }
            if ($n -gt 0) { return $sum }
        }
        Start-Sleep -Milliseconds 600
    }
    return $null
}

function Get-AppPid {
    $r = Invoke-Adb -CmdArgs @("shell", "pidof $Package") -AllowFail
    if ($r.Code -eq 0 -and $r.Text -match '(\d+)') { return [int]$Matches[1] }
    return 0
}

function Get-AppPidRetry {
    param([int]$Attempts = 3)
    for ($i = 0; $i -lt $Attempts; $i++) {
        $p = Get-AppPid
        if ($p -gt 0) { return $p }
        Start-Sleep -Milliseconds 400
    }
    return 0
}

function Get-CpuSnapshot {
    param([int]$AppPid)
    $res = @{ Ticks = $null; Utime = $null; Stime = $null; MinFlt = $null; MajFlt = $null; CtxtVol = $null; CtxtNonvol = $null; VmRssKb = $null; Raw = "" }
    if ($AppPid -le 0) { return $res }
    $stat = Invoke-Adb -CmdArgs @("shell", "cat /proc/$AppPid/stat") -AllowFail
    if ($stat.Code -eq 0) {
        $p = Parse-ProcStat $stat.Text
        if ($null -ne $p) {
            $res.Ticks = $p.UtSt; $res.Utime = $p.Utime; $res.Stime = $p.Stime
            $res.MinFlt = $p.MinFlt; $res.MajFlt = $p.MajFlt; $res.Raw = $stat.Text
        }
    }
    $status = Invoke-Adb -CmdArgs @("shell", "cat /proc/$AppPid/status") -AllowFail
    if ($status.Code -eq 0) {
        $ps = Parse-ProcStatus $status.Text
        $res.CtxtVol = $ps.CtxtVol; $res.CtxtNonvol = $ps.CtxtNonvol; $res.VmRssKb = $ps.VmRssKb
        if ($res.Raw -ne "") { $res.Raw += [string][char]10 }
        $res.Raw += $status.Text
    }
    return $res
}

function Get-ClkTck {
    $r = Invoke-Adb -CmdArgs @("shell", "getconf CLK_TCK") -AllowFail
    if ($r.Code -eq 0 -and $r.Text -match '(\d+)') { return [int]$Matches[1] }
    return 100
}

# ------------------------------------------------------------------ environment

function Get-Setting { param([string]$Ns, [string]$Key) return ((Invoke-Adb -CmdArgs @("shell", "settings get $Ns $Key") -AllowFail).Text).Trim() }

function Get-PowerSnapshot {
    $r = Invoke-Adb -CmdArgs @("shell", "dumpsys power") -AllowFail
    $t = Normalize-Text $r.Text
    $w = [regex]::Match($t, '(?im)mWakefulness=(\w+)')
    $d = [regex]::Match($t, '(?im)Display Power:\s*state=(\w+)')
    $display = $null
    if ($d.Success) { $display = $d.Groups[1].Value }
    else {
        # Some ColorOS builds print the DisplayManager binder object here instead of state=ON/OFF.
        $rd = Invoke-Adb -CmdArgs @("shell", "dumpsys display") -AllowFail
        $md = [regex]::Match((Normalize-Text $rd.Text), '(?im)mScreenState=(\w+)')
        if ($md.Success) { $display = $md.Groups[1].Value }
    }
    return @{ Wakefulness = $(if ($w.Success) { $w.Groups[1].Value } else { $null }); Display = $display }
}

function Ensure-ScreenOn {
    $p = Get-PowerSnapshot
    if ($p.Wakefulness -in @("Asleep", "Dozing") -or $p.Display -eq "OFF") {
        throw ("screen is off (wakefulness=" + $p.Wakefulness + ", display=" + $p.Display + "); keep the device unlocked and screen ON")
    }
    return $p
}

function Keep-ScreenActive { Invoke-Adb -CmdArgs @("shell", "input keyevent KEYCODE_F1") -AllowFail | Out-Null }

function Get-MusicVolume {
    $r = Invoke-Adb -CmdArgs @("shell", "cmd media_session volume --stream 3 --get") -AllowFail
    $m = [regex]::Match((Normalize-Text $r.Text), 'volume is\s*(\d+)')
    if ($m.Success) { return [int]$m.Groups[1].Value }
    return $null
}

function Get-DeviceInfo {
    return @{
        serial = $script:Serial
        model = (Shell-Text "getprop ro.product.model").Trim()
        release = (Shell-Text "getprop ro.build.version.release").Trim()
        sdk = (Shell-Text "getprop ro.build.version.sdk").Trim()
        clkTck = $script:ClkTck
    }
}

function Get-PackageInfo {
    $r = Invoke-Adb -CmdArgs @("shell", "dumpsys package $Package") -AllowFail
    $t = Normalize-Text $r.Text
    $vc = [regex]::Match($t, 'versionCode=(\d+)')
    $vn = [regex]::Match($t, 'versionName=([^\s]+)')
    $lu = [regex]::Match($t, 'lastUpdateTime=([^\r\n]+)')
    return @{
        package = $Package
        versionCode = $(if ($vc.Success) { [int]$vc.Groups[1].Value } else { $null })
        versionName = $(if ($vn.Success) { $vn.Groups[1].Value } else { $null })
        lastUpdateTime = $(if ($lu.Success) { $lu.Groups[1].Value.Trim() } else { $null })
    }
}

function Stop-OtherPackages {
    param([string]$List)
    if ($List -eq "" -and $Package -ne "com.zjr.hesimusic") { $List = "com.zjr.hesimusic" }
    if ($List -eq "" -or $List -eq "none") { return 0 }
    $n = 0
    foreach ($p in ($List -split ',')) {
        $p = $p.Trim()
        if ($p -eq "" -or $p -eq $Package) { continue }
        Invoke-Adb -CmdArgs @("shell", "am force-stop $p") -AllowFail | Out-Null
        $n++
    }
    return $n
}

# ------------------------------------------------------------------ API setup

function Invoke-Api {
    param([string]$Method = "GET", [string]$Path = "/state", $Body = $null, [int]$TimeoutSec = 20)
    $uri = $script:Base + $Path
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Compress
        return Invoke-RestMethod -Method $Method -Uri $uri -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec $TimeoutSec
    }
    return Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec $TimeoutSec
}

function Get-P { param($Object, [string]$Path) 
    $cur = $Object
    foreach ($seg in $Path.Split('.')) {
        if ($null -eq $cur) { return $null }
        if ($cur -is [System.Collections.IDictionary]) { if (-not $cur.Contains($seg)) { return $null }; $cur = $cur[$seg] }
        else { $prop = $cur.PSObject.Properties[$seg]; if ($null -eq $prop) { return $null }; $cur = $prop.Value }
    }
    return $cur
}

function Start-ApiLoop {
    Invoke-Adb -CmdArgs @("forward", "tcp:$($script:LocalPort)", "tcp:8765") -AllowFail | Out-Null
    $st = $null
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        try { $st = Invoke-Api -Path "/state"; break } catch { Start-Sleep -Milliseconds 800 }
    }
    if ($null -eq $st) { throw "debug API /state not reachable; current build with debug endpoints is required for -Mode api" }
    $song = Invoke-Api -Path ("/song/" + $SongId)
    Write-Log ("song " + $SongId + ": " + $song.title + " sr=" + $song.sampleRate + " window=" + $song.loopStartMs + ".." + $song.loopEndMs)
    $already = ((Get-P $st "loop.engine") -eq "loop" -and (Get-P $st "loop.loopPath") -eq "source" -and
        (Get-P $st "loop.window.songId") -eq $SongId -and (Get-P $st "playback.playing") -eq $true)
    if ($already) {
        # Calling /loop/enter again while the loop is already active re-arms the player and can
        # restore the previous normal queue. Leave a healthy loop untouched.
        Write-Log "already in the expected loop; skipping /loop/enter"
    } else {
        $r = Invoke-Api -Method POST -Path "/loop/enter" -Body @{ songId = $SongId; play = $true } -TimeoutSec 60
        Write-Log ("loop/enter -> entered=" + (Get-P $r "entered") + " songId=" + (Get-P $r "songId") + " playing=" + (Get-P $r "playing"))
    }
    $deadline = (Get-Date).AddSeconds(240)
    $ringSeen = $false
    $wrapped = $false
    $lastPos = $null
    while ((Get-Date) -lt $deadline) {
        $s = $null
        try { $s = Invoke-Api -Path "/state" } catch { }
        if ($null -ne $s) {
            if ((Get-P $s "loop.isRingComplete") -eq $true) { $ringSeen = $true }
            $pos = Get-P $s "playback.positionMs"
            if ($null -ne $pos) { $posL = [long]$pos; if ($null -ne $lastPos -and $posL -lt ($lastPos - 500)) { $wrapped = $true }; $lastPos = $posL }
            if ($ringSeen -or $wrapped) { break }
        }
        Start-Sleep -Milliseconds 700
    }
    if (-not ($ringSeen -or $wrapped)) { throw "loop did not reach steady state within 240s" }
    $final = Invoke-Api -Path "/state"
    $engine = Get-P $final "loop.engine"
    $loopPath = Get-P $final "loop.loopPath"
    $win = Get-P $final "loop.window"
    Write-Log ("loop active: engine=" + $engine + " loopPath=" + $loopPath + " window=" + (Get-P $win "songId") + " " + (Get-P $win "startMs") + ".." + (Get-P $win "endMs"))
    if ($engine -ne "loop") { throw ("loop.engine is " + $engine + " (expected loop); refusing to measure a non-ring-band round") }
    if ($loopPath -ne "source") { throw ("loopPath is " + $loopPath + ", not source; switch the engine to SOURCE before measuring the ring band") }
    if ($Ams -gt 0 -and $Bms -gt $Ams) {
        $wA = Get-P $win "startMs"; $wB = Get-P $win "endMs"
        if ($null -ne $wA -and [int]$wA -ne $Ams) { throw ("loop window startMs=" + $wA + " but -Ams=" + $Ams + " expected") }
        if ($null -ne $wB -and [int]$wB -ne $Bms) { throw ("loop window endMs=" + $wB + " but -Bms=" + $Bms + " expected") }
    }
    return @{ ringSeen = $ringSeen; wrapped = $wrapped; state = $final; engine = $engine; loopPath = $loopPath; window = $win }
}

# ------------------------------------------------------------------ round

function Invoke-MeasureRound {
    param([int]$WarmupSec, [int]$Minutes, [int]$SampleSec)
    $round = [ordered]@{
        schema = "engine-cost-round/1"
        package = $Package; mode = $Mode; startedAt = (Get-Date).ToString("o"); endedAt = $null
        songId = $SongId; songLabel = $SongLabel; ams = $Ams; bms = $Bms
        pid = 0; pidAfter = 0; seconds = $null
        cpuMs = $null; userMs = $null; sysMs = $null
        ctxtVol = $null; ctxtNonvol = $null; minFlt = $null; majFlt = $null; vmRssKb = $null
        frames = $null; expectedFrames = $null; duty = $null; fullDuty = $false; sampleRate = 48000
        repeatModePref = $null; repeatVerified = $false; mediaState = $null; loopPath = $null; loopEngine = $null; window = $null
        volume = $null; brightness = $null; brightnessMode = $null; wakefulness = $null; display = $null
        samples = @(); errors = @(); aborted = $null
    }

    $n = Stop-OtherPackages $StopPackages
    if ($n -gt 0) { Write-Log ("force-stopped " + $n + " package(s) to avoid media-session contention") }

    $appPid = Get-AppPidRetry
    if ($appPid -le 0) { throw ("package " + $Package + " is not running; start playback manually (or use -Mode api)") }
    $round.pid = $appPid
    Write-Log ("pid=" + $appPid)

    if ($Mode -eq "api") {
        $setup = Start-ApiLoop
        $round.loopPath = $setup.loopPath
        $round.loopEngine = $setup.engine
        $round.window = $setup.window
        Write-Log ("loop steady: ring=" + $setup.ringSeen + " wrap=" + $setup.wrapped + " loopPath=" + $setup.loopPath)
        $appPid = Get-AppPidRetry
        if ($appPid -le 0) { throw "app process disappeared after loop/enter" }
        $round.pid = $appPid
    }

    # precondition: the package owns a PLAYING media session
    $ms = $null
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        $ms = Get-MediaSessionState
        if ($null -ne $ms -and $ms.Code -eq 3) { break }
        Start-Sleep -Milliseconds 700
    }
    if ($null -eq $ms -or $ms.Code -ne 3) { throw ("package " + $Package + " has no PLAYING media session (state=" + $(if ($null -ne $ms) { $ms.State } else { "none" }) + ")") }
    Write-Log ("media_session: " + $ms.State + " position=" + $ms.Position)
    $round.mediaState = $ms.State

    if ($Mode -eq "ui" -and $RequireRepeatMode -ne "any") {
        $prefs = Get-PlaybackPrefs
        if ($null -ne $prefs) {
            $raw = $prefs["repeat_mode"]
            $round.repeatModePref = $raw
            Write-Log ("playback_prefs repeat_mode=" + $raw + " shuffle=" + $prefs["shuffle_mode_enabled"])
            $want = @{ one = "1"; off = "0"; all = "2" }[$RequireRepeatMode]
            if ($raw -ne $want) { throw ("repeat_mode=" + $raw + " but -RequireRepeatMode " + $RequireRepeatMode + " expects " + $want) }
            $round.repeatVerified = $true
        } elseif ($RequireRepeatMode -ne "one") {
            throw "cannot read shared_prefs/playback_prefs.xml via run-as; pass -RequireRepeatMode any to skip"
        } else {
            Write-Log "WARN cannot read playback prefs (non-debuggable package?); repeat mode unverified" "Yellow"
        }
    }

    # environment snapshot (read-only)
    $round.volume = Get-MusicVolume
    $round.brightness = Get-Setting "system" "screen_brightness"
    $round.brightnessMode = Get-Setting "system" "screen_brightness_mode"
    $power = Ensure-ScreenOn
    $round.wakefulness = $power.Wakefulness; $round.display = $power.Display
    Write-Log ("env: volume=" + (Format-Num $round.volume "{0}") + " brightness=" + $round.brightness + " mode=" + $round.brightnessMode + " wake=" + $round.wakefulness + " display=" + $round.display)

    # frames must be advancing before we start the timer
    $f1 = Get-AudioFlingerFrames
    Start-Sleep -Seconds 3
    $f2 = Get-AudioFlingerFrames
    if ($null -eq $f1 -or $null -eq $f2 -or ($f2 - $f1) -le 0) { throw ("AudioFlinger output frames are not advancing (" + $f1 + " -> " + $f2 + "); playback is not audible") }
    Write-Log ("frames advancing: +" + ($f2 - $f1) + " in 3s")
    if ($WarmupSec -gt 0) { Write-Log ("warmup " + $WarmupSec + "s"); Start-Sleep -Seconds $WarmupSec }

    # stability hold: the service may restore a saved queue/state shortly after /loop/enter
    for ($i = 0; $i -lt 3; $i++) {
        Start-Sleep -Seconds 5
        $hold = $null
        if ($Mode -eq "api") { try { $hold = Invoke-Api -Path "/state" } catch { } }
        if ($Mode -eq "api") {
            if ($null -ne $hold -and ((Get-P $hold "loop.loopPath") -ne "source" -or (Get-P $hold "playback.playing") -ne $true)) {
                throw ("loop state changed during the 15s stability hold (loopPath=" + (Get-P $hold "loop.loopPath") + ", playing=" + (Get-P $hold "playback.playing") + ")")
            }
        } else {
            $hms = Get-MediaSessionState
            if ($null -ne $hms -and $hms.Code -ne 3) { throw ("media session is " + $hms.State + " during the 15s stability hold") }
        }
    }

    $startTime = Get-Date
    $afStart = Get-AudioFlingerFrames
    $cpuStart = Get-CpuSnapshot $appPid
    Save-Text (Join-Path $script:RunDir "proc-before.txt") $cpuStart.Raw
    Save-Json (Join-Path $script:RunDir "cpu-before.json") $cpuStart
    $msStart = Get-MediaSessionState
    Save-Json (Join-Path $script:RunDir "media-before.json") $msStart
    if ($null -eq $msStart -or $msStart.Code -ne 3) { throw ("refusing to start the timer: media session is " + $(if ($null -ne $msStart) { $msStart.State } else { "missing" }) + ", expected PLAYING") }
    Write-Log ("timer start: afFrames=" + $afStart + " ticks=" + $cpuStart.Ticks + " media=" + $msStart.State)

    $endAt = $startTime.AddSeconds($Minutes * 60)
    $samples = New-Object System.Collections.ArrayList
    $stops = 0; $lastKeep = Get-Date; $lastLog = Get-Date
    $lastPos = $null; $wraps = 0
    while ((Get-Date) -lt $endAt) {
        $ms = Get-MediaSessionState
        $row = [ordered]@{
            t = [math]::Round(((Get-Date) - $startTime).TotalSeconds, 1)
            state = $(if ($null -ne $ms) { $ms.State } else { "none" })
            positionMs = $(if ($null -ne $ms) { $ms.Position } else { $null })
        }
        if ($Mode -eq "api") {
            $st = $null
            try { $st = Invoke-Api -Path "/state" } catch { }
            $row.engine = Get-P $st "loop.engine"
            $row.loopPath = Get-P $st "loop.loopPath"
            $row.loopSong = Get-P $st "loop.window.songId"
            $row.ring = Get-P $st "loop.isRingComplete"
            if ($null -ne $st -and ((Get-P $st "loop.engine") -ne "loop" -or (Get-P $st "loop.loopPath") -ne "source")) {
                [void]$samples.Add($row)
                $round.aborted = "loop engine/path changed (engine=" + $row.engine + ", path=" + $row.loopPath + ")"
                break
            }
        }
        [void]$samples.Add($row)
        if ($row.state -ne "PLAYING") { $stops++ } else { $stops = 0 }
        if ($stops -ge 2) { $round.aborted = "playback stopped"; break }
        if ($null -ne $row.positionMs) {
            $posL = [long]$row.positionMs
            if ($null -ne $lastPos -and $posL -lt ($lastPos - 500)) { $wraps++ }
            $lastPos = $posL
        }
        $nowPid = Get-AppPidRetry
        if ($nowPid -le 0) { $round.aborted = "app process disappeared"; break }
        if ($nowPid -ne $appPid) { $round.aborted = "app pid changed"; break }

        $elapsed = ((Get-Date) - $startTime).TotalSeconds
        $left = ($Minutes * 60) - $elapsed
        if ($left -le 0) { break }
        if (((Get-Date) - $lastKeep).TotalSeconds -ge 240) { $lastKeep = Get-Date; Keep-ScreenActive }
        if (((Get-Date) - $lastLog).TotalSeconds -ge 30) {
            $lastLog = Get-Date
            Write-Host ("    " + [timespan]::FromSeconds($elapsed).ToString("mm\:ss") + " / " + $Minutes + ":00 pos=" + $row.positionMs + " state=" + $row.state + " wraps=" + $wraps)
        }
        Start-Sleep -Seconds ([math]::Min($SampleSec, [math]::Max(1.0, $left)))
    }
    $round.samples = @($samples)

    $endTime = Get-Date
    $afEnd = Get-AudioFlingerFrames
    $cpuEnd = Get-CpuSnapshot $appPid
    Save-Text (Join-Path $script:RunDir "proc-after.txt") $cpuEnd.Raw
    Save-Json (Join-Path $script:RunDir "cpu-after.json") $cpuEnd
    $msEnd = Get-MediaSessionState
    Save-Json (Join-Path $script:RunDir "media-after.json") $msEnd
    $round.pidAfter = Get-AppPidRetry
    if ($round.pidAfter -le 0) { $round.aborted = "app died at end" }
    elseif ($round.pidAfter -ne $appPid) { $round.aborted = "app pid changed at end" }
    $round.endedAt = (Get-Date).ToString("o")
    $round.seconds = [math]::Round(($endTime - $startTime).TotalSeconds, 2)

    if ($null -ne $cpuStart.Ticks -and $null -ne $cpuEnd.Ticks) {
        $round.userMs = [math]::Round(([double]$cpuEnd.Utime - [double]$cpuStart.Utime) * 1000.0 / [double]$script:ClkTck, 1)
        $round.sysMs = [math]::Round(([double]$cpuEnd.Stime - [double]$cpuStart.Stime) * 1000.0 / [double]$script:ClkTck, 1)
        $round.cpuMs = [math]::Round(([double]$cpuEnd.Ticks - [double]$cpuStart.Ticks) * 1000.0 / [double]$script:ClkTck, 1)
    } else { $round.errors += "CPU time unavailable" }
    if ($null -ne $cpuStart.CtxtVol -and $null -ne $cpuEnd.CtxtVol) { $round.ctxtVol = [long]$cpuEnd.CtxtVol - [long]$cpuStart.CtxtVol }
    if ($null -ne $cpuStart.CtxtNonvol -and $null -ne $cpuEnd.CtxtNonvol) { $round.ctxtNonvol = [long]$cpuEnd.CtxtNonvol - [long]$cpuStart.CtxtNonvol }
    if ($null -ne $cpuStart.MinFlt -and $null -ne $cpuEnd.MinFlt) { $round.minFlt = [long]$cpuEnd.MinFlt - [long]$cpuStart.MinFlt }
    if ($null -ne $cpuStart.MajFlt -and $null -ne $cpuEnd.MajFlt) { $round.majFlt = [long]$cpuEnd.MajFlt - [long]$cpuStart.MajFlt }
    $round.vmRssKb = $cpuEnd.VmRssKb

    if ($null -ne $afStart -and $null -ne $afEnd -and $afEnd -ge $afStart) {
        $round.frames = [long]($afEnd - $afStart)
        $round.expectedFrames = [long][math]::Round(48000.0 * $round.seconds)
        if ($round.expectedFrames -gt 0) {
            $round.duty = [math]::Round([double]$round.frames / [double]$round.expectedFrames, 4)
            $round.fullDuty = ($round.duty -ge 0.98)
        }
    } else { $round.errors += "AudioFlinger frames unavailable" }

    Write-Log ("round done: " + (Format-Num $round.seconds "{0:N1}") + "s cpu=" + (Format-Num $round.cpuMs "{0:N0}") + "ms user=" + (Format-Num $round.userMs "{0:N0}") + " sys=" + (Format-Num $round.sysMs "{0:N0}") + " ctxt=" + (Format-Num $round.ctxtVol "{0}") + "/" + (Format-Num $round.ctxtNonvol "{0}") + " flt=" + (Format-Num $round.minFlt "{0}") + "/" + (Format-Num $round.majFlt "{0}") + " frames=" + (Format-Num $round.frames "{0:N0}") + " duty=" + (Format-Num $round.duty "{0:P2}") + " wraps=" + $wraps + " abort=" + $round.aborted)
    Save-Json (Join-Path $script:RunDir "round.json") $round
    return $round
}

# ------------------------------------------------------------------ report

function Get-RatioVerdict {
    param($CurrentCpu, $BaselineCpu, [double]$Limit = 1.10)
    if ($null -eq $CurrentCpu -or $null -eq $BaselineCpu -or [double]$BaselineCpu -le 0) { return @{ ratio = $null; verdict = "ERROR" } }
    $ratio = [math]::Round([double]$CurrentCpu / [double]$BaselineCpu, 4)
    return @{ ratio = $ratio; verdict = $(if ($ratio -le $Limit) { "PASS" } else { "FAIL" }); limit = $Limit }
}

function Write-RunReport {
    param($Round, $Pkg, $Compare)
    $lines = @()
    $lines += "engine-cost-baseline round"
    $lines += ("package=" + $Pkg.package + " versionCode=" + $Pkg.versionCode + " versionName=" + $Pkg.versionName + " lastUpdateTime=" + $Pkg.lastUpdateTime)
    $lines += ("mode=" + $Round.mode + " song=" + $Round.songId + " " + $Round.songLabel + " window=" + $Round.ams + ".." + $Round.bms + "ms")
    $lines += ("minutes=" + (Format-Num ($Round.seconds / 60.0) "{0:N2}") + " warmup=" + $WarmupSec + " sample=" + $SampleSec)
    $lines += ("repeat: pref=" + $Round.repeatModePref + " verified=" + $Round.repeatVerified + " media=" + $Round.mediaState)
    $lines += ("env: volume=" + (Format-Num $Round.volume "{0}") + " brightness=" + $Round.brightness + " mode=" + $Round.brightnessMode + " wake=" + $Round.wakefulness + " display=" + $Round.display)
    $lines += ""
    $fmt = '{0,-34} {1,14}'
    $lines += ($fmt -f "CPU_ms (user+sys)", (Format-Num $Round.cpuMs "{0:N0}"))
    $lines += ($fmt -f "user_ms", (Format-Num $Round.userMs "{0:N0}"))
    $lines += ($fmt -f "sys_ms", (Format-Num $Round.sysMs "{0:N0}"))
    $lines += ($fmt -f "CtxtV", (Format-Num $Round.ctxtVol "{0}"))
    $lines += ($fmt -f "CtxtNV", (Format-Num $Round.ctxtNonvol "{0}"))
    $lines += ($fmt -f "MinFlt", (Format-Num $Round.minFlt "{0}"))
    $lines += ($fmt -f "MajFlt", (Format-Num $Round.majFlt "{0}"))
    $lines += ($fmt -f "AudioFlinger frames", (Format-Num $Round.frames "{0:N0}"))
    $lines += ($fmt -f "duty (48000 Hz)", (Format-Num $Round.duty "{0:P2}"))
    $lines += ($fmt -f "full duty", (Format-Num $Round.fullDuty "{0}"))
    if ($null -ne $Compare) {
        $cmp = Get-RatioVerdict $Round.cpuMs $Compare.cpuMs
        $lines += ""
        $lines += ("compare with " + $Compare.package + " (mode " + $Compare.mode + "): CPU " + (Format-Num $Round.cpuMs "{0:N0}") + " / " + (Format-Num $Compare.cpuMs "{0:N0}") + " ms")
        if ($null -ne $cmp.ratio) { $lines += ("this/baseline CPU ratio = " + (Format-Num $cmp.ratio "{0:N4}") + " (limit " + $cmp.limit + ") -> " + $cmp.verdict) }
        else { $lines += "ratio unavailable -> ERROR" }
    }
    if ($Round.errors.Count -gt 0) { $lines += "Errors:"; foreach ($e in $Round.errors) { $lines += ("  - " + $e) } }
    if ($null -ne $Round.aborted) { $lines += ("ABORTED: " + $Round.aborted) }
    $text = ($lines -join [Environment]::NewLine)
    Save-Text (Join-Path $script:RunDir "summary.txt") $text
    Write-Host ""
    Write-Host $text
}

# ------------------------------------------------------------------ self test

function Invoke-SelfTest {
    function Assert-Equal { param([string]$Name, $Actual, $Expected, [double]$Tolerance = 0.0)
        $ok = $false
        if ($null -eq $Actual -and $null -eq $Expected) { $ok = $true }
        elseif ($null -ne $Actual -and $null -ne $Expected) {
            if ($Actual -is [double] -or $Expected -is [double]) { $ok = ([math]::Abs([double]$Actual - [double]$Expected) -le $Tolerance) }
            else { $ok = ([string]$Actual -eq [string]$Expected) }
        }
        if (-not $ok) { $script:SelfTestFailures++; Write-Host ("FAIL " + $Name + ": got [" + $Actual + "] want [" + $Expected + "]") -ForegroundColor Red }
    }
    $stat = "1234 (com.zjr.hesimusic.baseline) S 1 1234 1234 0 -1 4194560 700 0 0 0 100 50 0 0 20 0 12 0 5000 1000000 200 18446744073709551615 1 1 0 0 0 0 0 0 0 0 0 0 17 4 0 0 0 0 0"
    $p = Parse-ProcStat $stat
    Assert-Equal "utime" $p.Utime 100
    Assert-Equal "stime" $p.Stime 50
    Assert-Equal "ticks" $p.UtSt 150
    Assert-Equal "minflt" $p.MinFlt 700
    $t = [char]9
    $ps = Parse-ProcStatus ("Name: x" + [char]10 + "VmRSS:" + $t + "12345 kB" + [char]10 + "voluntary_ctxt_switches:" + $t + "42" + [char]10 + "nonvoluntary_ctxt_switches:" + $t + "7")
    Assert-Equal "ctxtv" $ps.CtxtVol 42
    Assert-Equal "ctxtnv" $ps.CtxtNonvol 7
    $prefs = Parse-PrefsXml ("<map>" + [char]10 + '    <int name="repeat_mode" value="1" />' + [char]10 + '    <boolean name="shuffle_mode_enabled" value="false" />' + [char]10 + "</map>")
    Assert-Equal "pref repeat" $prefs["repeat_mode"] "1"
    Assert-Equal "pref shuffle" $prefs["shuffle_mode_enabled"] "false"
    $r = Get-RatioVerdict 11000 10000
    Assert-Equal "ratio" $r.ratio 1.10 0.0001
    Assert-Equal "ratio pass" $r.verdict "PASS"
    $r2 = Get-RatioVerdict 11100 10000
    Assert-Equal "ratio fail" $r2.verdict "FAIL"
    $r3 = Get-RatioVerdict 10000 0
    Assert-Equal "ratio error" $r3.verdict "ERROR"
    if ($script:SelfTestFailures -gt 0) { Write-Host ("SELFTEST FAIL (" + $script:SelfTestFailures + ")") -ForegroundColor Red; exit 1 }
    Write-Host "SELFTEST PASS"; exit 0
}

# ------------------------------------------------------------------ main

if ($SelfTest) { Invoke-SelfTest }

$exit = 2
try {
    if ($Minutes -lt 1) { throw "-Minutes must be >= 1" }
    if ($Mode -eq "api" -and $SongId -le 0) { throw "-Mode api requires -SongId" }
    $script:Adb = Resolve-Adb
    if ($Device -ne "") { $script:Serial = $Device }
    Select-Device
    if ($script:Serial -ne "") { $script:AdbArgs = @("-s", $script:Serial) }
    $script:ClkTck = Get-ClkTck

    $root = Split-Path -Parent $PSScriptRoot
    if ($OutDir -eq "") { $OutDir = Join-Path (Join-Path $root "e2e-artifacts\engine-cost-baseline") (Get-Date -Format "yyyyMMdd-HHmmss") }
    elseif (-not [System.IO.Path]::IsPathRooted($OutDir)) { $OutDir = Join-Path $root $OutDir }
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $script:RunDir = $OutDir
    $script:LogPath = Join-Path $OutDir "run.log"
    Write-Log ("artifacts: " + $OutDir)

    $pkg = Get-PackageInfo
    Write-Log ("package=" + $pkg.package + " versionCode=" + $pkg.versionCode + " versionName=" + $pkg.versionName + " lastUpdateTime=" + $pkg.lastUpdateTime)
    $dev = Get-DeviceInfo
    Write-Log ("device=" + $dev.serial + " model=" + $dev.model + " android=" + $dev.release + " CLK_TCK=" + $dev.clkTck)
    Save-Json (Join-Path $OutDir "device.json") @{ device = $dev; package = $pkg; mode = $Mode; params = @{ minutes = $Minutes; warmupSec = $WarmupSec; sampleSec = $SampleSec; requireRepeatMode = $RequireRepeatMode; songId = $SongId; ams = $Ams; bms = $Bms } }

    $compare = $null
    if ($FromRound -ne "") {
        # Re-render / compare an existing round artifact without touching the device.
        if (-not (Test-Path $FromRound)) { throw ("-FromRound not found: " + $FromRound) }
        $round = Get-Content $FromRound -Raw | ConvertFrom-Json
        $script:RunDir = Split-Path -Parent (Resolve-Path $FromRound).Path
        $script:LogPath = Join-Path $script:RunDir "run.log"
        if ($ComparePath -ne "") {
            if (-not (Test-Path $ComparePath)) { throw ("-ComparePath not found: " + $ComparePath) }
            $compare = Get-Content $ComparePath -Raw | ConvertFrom-Json
        }
        Write-RunReport $round $pkg $compare
        if ($null -ne $round.aborted -or $null -eq $round.cpuMs -or $null -eq $round.frames) { exit 2 }
        elseif (-not $round.fullDuty) { exit 1 }
        else { exit 0 }
    }
    $round = Invoke-MeasureRound -WarmupSec $WarmupSec -Minutes $Minutes -SampleSec $SampleSec
    if ($ComparePath -ne "") {
        if (-not (Test-Path $ComparePath)) { throw ("-ComparePath not found: " + $ComparePath) }
        $compare = Get-Content $ComparePath -Raw | ConvertFrom-Json
    }
    Write-RunReport $round $pkg $compare
    if ($null -ne $round.aborted -or $null -eq $round.cpuMs -or $null -eq $round.frames) { $exit = 2 }
    elseif (-not $round.fullDuty) { $exit = 1 }
    else { $exit = 0 }
}
catch {
    Write-Log ("FATAL: " + $_.Exception.Message) "Red"
    Write-Log ($_.ScriptStackTrace) "DarkGray"
    if ($script:RunDir -ne "" -and (Test-Path $script:RunDir)) { Save-Text (Join-Path $script:RunDir "fatal.txt") ($_.Exception.ToString()) }
    $exit = 2
}
finally { try { Write-Log "measurement finished; no system setting was modified" } catch { } }
exit $exit