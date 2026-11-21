#requires -version 5.1
<#
.SYNOPSIS
  Battery gate: 10-minute continuous loop playback, SOURCE (ring/band engine) vs M3' (original SDK loop engine).

.DESCRIPTION
  Runs two rounds on one device, one song, fixed volume, screen off, network off:
    A: engine=source   (loop PCM data source / ring band)
    B: engine=m3       (M3' LoopAudioSink capture + replay)
  Each round is preceded by 'dumpsys batterystats --reset'; data is collected immediately after playback is stopped.

  Metrics:
    mAh          Estimated power use for the app UID (batterystats)
    CPU_ms       utime+stime from /proc/<pid>/stat (ps TIME fallback)
    Wakeups      Wakeups for the app UID (batterystats)
    Frames       Output frames: SOURCE = /state.invariantProbe.writtenFrames (TapAudioSink -> AudioTrack)
                 M3'   = LoopPerf 'pumpWrites' (LoopAudioSink replay output)
    Duty         Frames / (sampleRate * measured seconds); full duty requires >= 0.98
    Audio_s      Frames / sampleRate (derived AudioTrack active seconds)

  Gate: source mAh <= 1.10 * m3 mAh, and both rounds full duty, and engine stayed as expected.

  Exit codes: 0 = PASS, 1 = gate FAIL, 2 = setup/measurement ERROR (no verdict).

.PARAMETER Device
  adb serial. Optional when exactly one device is attached.

.PARAMETER SongId
  Song id to loop. 0 = auto-pick a confirmed-loop song (or the first mp3).

.PARAMETER Ams
  Loop start in ms. >0 together with -Bms forces a new confirmed [A,B) window.
  0 = use the already confirmed loop row.

.PARAMETER Bms
  Loop end in ms. Must be greater than -Ams when -Ams > 0.

.PARAMETER Minutes
  Round length in minutes (default 10). Two rounds plus setup take ~25 minutes.

.PARAMETER Volume
  STREAM_MUSIC index to set (default 8, device max is normally 15).

.PARAMETER EngineOrder
  Two comma separated engines: source,m3 (default) or m3,source.
  The gate always compares the source round against the m3 round.

.PARAMETER OutDir
  Artifact directory. Default: e2e-artifacts/battery/<timestamp>.

.PARAMETER WarmupSec
  Seconds of steady playback before batterystats reset (default 20).

.PARAMETER SampleSec
  /state sampling period during a round (default 30 s). Keep it coarse to avoid polluting the measurement.

.PARAMETER RingTimeoutSec
  Max wait for M3' ring completeness (or SOURCE first wrap) before timing (default 240).

.PARAMETER SkipAnalyze
  Never run /analyze; fail if the song has no confirmed loop.

.PARAMETER SkipAppStop
  Do not force-stop other third-party apps.

.PARAMETER LeaveScreenOff
  Do not wake the screen in the final restore step.

.PARAMETER SelfTest
  Run parser self-tests without touching a device (used by CI / review).

.NOTES
  - The debug HTTP API must be available (debug build): adb forward tcp:18765 tcp:8765 -> /state.
  - Engine switch uses POST /debug/source-mode {on:bool}; it persists loop_engine/loop_engine_main in
    SharedPreferences, so the app is force-stopped and restarted, then /state.loop.loopPath is checked
    (engine is always "loop" when a loop window is active; loopPath distinguishes source/m3).
  - batterystats --reset wipes global battery history. Run only in an exclusive device window.
  - Use USB adb: the script disables Wi-Fi (svc wifi disable), so wireless adb would disconnect.
#>
[CmdletBinding()]
param(
    [string]$Device = "",
    [long]$SongId = 0,
    [int]$Ams = 0,
    [int]$Bms = 0,
    [int]$Minutes = 10,
    [int]$Volume = 8,
    [string]$EngineOrder = "source,m3",
    [string]$OutDir = "",
    [int]$WarmupSec = 20,
    [int]$SampleSec = 30,
    [int]$RingTimeoutSec = 240,
    [int]$SetupTimeoutSec = 90,
    [switch]$SkipAnalyze,
    [switch]$SkipAppStop,
    [switch]$LeaveScreenOff,
    [switch]$SelfTest
)

$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$script:Package = "com.zjr.hesimusic"
$script:LocalPort = 18765
$script:DevicePort = 8765
$script:Base = "http://127.0.0.1:$($script:LocalPort)"
$script:Adb = $null
$script:AdbArgs = @()
$script:Serial = ""
$script:RunDir = ""
$script:LogPath = ""
$script:ClkTck = 100
$script:Env = @{}
$script:EnvCaptured = $false
$script:SelfTestFailures = 0
$script:StartedAt = Get-Date
$script:DeviceInfo = @{}

# ------------------------------------------------------------------ generic helpers

function Write-Log {
    param([string]$Message, [string]$Color = "Gray")
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $Message
    Write-Host $line -ForegroundColor $Color
    if ($script:LogPath -ne "" -and (Test-Path (Split-Path -Parent $script:LogPath))) {
        try { Add-Content -Path $script:LogPath -Value $line -Encoding utf8 } catch { }
    }
}

function Normalize-Text {
    param([string]$Text)
    if ($null -eq $Text) { return "" }
    $s = $Text -replace ([string][char]13), ""
    $s = $s -replace ([string][char]0), ""
    return $s
}

function Save-Text {
    param([string]$Path, [string]$Text)
    try {
        $dir = Split-Path -Parent $Path
        if ($dir -ne "" -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        Set-Content -Path $Path -Value $Text -Encoding utf8
    } catch {
        Write-Log ("WARN cannot save " + $Path + ": " + $_.Exception.Message) "Yellow"
    }
}

function Save-Json {
    param([string]$Path, $Object)
    try {
        $json = $Object | ConvertTo-Json -Depth 16
        Save-Text $Path $json
    } catch {
        Write-Log ("WARN cannot save json " + $Path + ": " + $_.Exception.Message) "Yellow"
    }
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

function Get-P {
    param($Object, [string]$Path)
    if ($null -eq $Object) { return $null }
    $cur = $Object
    foreach ($seg in $Path.Split('.')) {
        if ($null -eq $cur) { return $null }
        if ($cur -is [System.Collections.IDictionary]) {
            if (-not $cur.Contains($seg)) { return $null }
            $cur = $cur[$seg]
        } else {
            $prop = $cur.PSObject.Properties[$seg]
            if ($null -eq $prop) { return $null }
            $cur = $prop.Value
        }
    }
    return $cur
}

function Get-UidLabel {
    param([int]$Uid)
    if ($Uid -lt 10000) { return ([string]$Uid) }
    $user = [int][math]::Floor($Uid / 100000)
    $appId = $Uid % 100000
    return ("u{0}a{1}" -f $user, ($appId - 10000))
}

# ------------------------------------------------------------------ adb

function Invoke-Adb {
    param([string[]]$CmdArgs, [switch]$AllowFail)
    $all = @()
    if ($script:AdbArgs.Count -gt 0) { $all += $script:AdbArgs }
    $all += $CmdArgs
    $oldEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $out = & $script:Adb @all 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $oldEap
    $text = Normalize-Text (($out | ForEach-Object { [string]$_ }) -join ([string][char]10))
    if ($code -ne 0 -and -not $AllowFail) {
        throw ("adb " + ($CmdArgs -join " ") + " failed (exit " + $code + "): " + $text)
    }
    return @{ Text = $text; Code = $code }
}

function Resolve-Adb {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe") }
    foreach ($c in $candidates) { if ($c -ne "" -and (Test-Path $c)) { return $c } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "adb not found: set ANDROID_HOME or add platform-tools to PATH"
}

function Select-Device {
    $r = Invoke-Adb -CmdArgs @("devices") -AllowFail
    if ($r.Code -ne 0) { throw ("adb devices failed: " + $r.Text) }
    $serials = @()
    foreach ($line in $r.Text.Split([char]10)) {
        if ($line -match '^(\S+)\s+device\s*$') { $serials += $Matches[1] }
    }
    if ($script:Serial -ne "") {
        if (-not ($serials -contains $script:Serial)) {
            throw ("device not attached or unauthorized: " + $script:Serial + " (adb devices: " + ($serials -join ", ") + ")")
        }
        return
    }
    if ($serials.Count -eq 1) { $script:Serial = $serials[0]; return }
    if ($serials.Count -eq 0) { throw "no adb device in 'device' state" }
    throw ("multiple devices attached; pass -Device <serial>: " + ($serials -join ", "))
}

function Shell-Text {
    param([string]$Command)
    return (Invoke-Adb -CmdArgs @("shell", $Command)).Text
}

function Ensure-Forward {
    $r = Invoke-Adb -CmdArgs @("forward", "tcp:$($script:LocalPort)", "tcp:$($script:DevicePort)") -AllowFail
    if ($r.Code -ne 0) { Write-Log ("WARN adb forward failed: " + $r.Text) "Yellow" }
    Start-Sleep -Milliseconds 200
}

function Get-Setting {
    param([string]$Namespace, [string]$Key)
    $r = Invoke-Adb -CmdArgs @("shell", "settings get $Namespace $Key") -AllowFail
    return (Normalize-Text $r.Text).Trim()
}

# ------------------------------------------------------------------ HTTP

function Invoke-Api {
    param(
        [string]$Method = "GET",
        [string]$Path = "/state",
        $Body = $null,
        [int]$TimeoutSec = 30,
        [int]$Retries = 2
    )
    $uri = $script:Base + $Path
    for ($attempt = 0; $attempt -le $Retries; $attempt++) {
        try {
            if ($null -ne $Body) {
                $json = $Body | ConvertTo-Json -Compress -Depth 8
                $data = Invoke-RestMethod -Method $Method -Uri $uri -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec $TimeoutSec
            } else {
                $data = Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec $TimeoutSec
            }
            return @{ Ok = $true; Status = 200; Data = $data; Error = $null }
        } catch {
            $status = 0
            $respText = ""
            try {
                if ($null -ne $_.Exception.Response) {
                    $status = [int]$_.Exception.Response.StatusCode
                    $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                    $respText = $sr.ReadToEnd()
                }
            } catch { }
            if ($attempt -lt $Retries) {
                try {
                    Invoke-Adb -CmdArgs @("forward", "--remove", "tcp:$($script:LocalPort)") -AllowFail | Out-Null
                    Ensure-Forward
                } catch { }
                Start-Sleep -Milliseconds 600
                continue
            }
            return @{ Ok = $false; Status = $status; Data = $null; Error = ($_.Exception.Message + " " + $respText).Trim() }
        }
    }
}

function Get-State {
    param([int]$Retries = 0)
    $r = Invoke-Api -Method GET -Path "/state" -TimeoutSec 25 -Retries $Retries
    if ($r.Ok) { return $r.Data }
    return $null
}

function Wait-Api {
    param([int]$TimeoutSec = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $lastError = $null
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-Api -Method GET -Path "/state" -TimeoutSec 10 -Retries 0
        if ($r.Ok) { return $r.Data }
        $lastError = $r.Error
        Start-Sleep -Milliseconds 800
    }
    throw ("debug API did not come up within " + $TimeoutSec + "s: " + $lastError)
}

function Wait-PlayerReady {
    param([int]$TimeoutSec = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        $s = Get-State -Retries 0
        if ($null -ne $s) {
            $last = $s
            if ((Get-P $s "playback.connected") -eq $true) { return $s }
        }
        Start-Sleep -Milliseconds 500
    }
    $desc = "connected=" + (Get-P $last "playback.connected") + " reason=" + (Get-P $last "playback.reason")
    throw ("player controller did not connect within " + $TimeoutSec + "s (" + $desc + ")")
}

# ------------------------------------------------------------------ parsers

function Parse-BatteryStats {
    param([string]$Text, [int]$Uid)
    $t = Normalize-Text $Text
    $hasSection = ($t.IndexOf("Estimated power use", [System.StringComparison]::OrdinalIgnoreCase) -ge 0)
    $out = @{
        Mah = $null; CpuMah = $null; ComputedDrain = $null; MatchedLine = $null
        UidLabel = (Get-UidLabel $Uid); HasSection = $hasSection
    }
    if ($t.Trim() -eq "") { return $out }

    $labels = @()
    $labels += (Get-UidLabel $Uid)
    $labels += ([string]$Uid)
    $labels += ("u0a" + ($Uid % 100000))
    if (($Uid % 100000) -ge 10000) {
        $uidUser = [int][math]::Floor($Uid / 100000)
        $labels += ("u{0}a{1:D4}" -f $uidUser, (($Uid % 100000) - 10000))
    }
    $labels = $labels | Select-Object -Unique

    $mComputed = [regex]::Match($t, '(?im)Computed drain:\s*([0-9]+(?:\.[0-9]+)?)')
    if ($mComputed.Success) { $out.ComputedDrain = [double]$mComputed.Groups[1].Value }

    $lines = $t.Split([char]10)
    $sectionIdx = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '(?i)Estimated power use') { $sectionIdx = $i; break }
    }
    $searchFrom = 0
    $searchTo = $lines.Count
    if ($sectionIdx -ge 0) {
        $searchFrom = $sectionIdx
        $searchTo = [math]::Min($lines.Count, $sectionIdx + 400)
    }
    for ($i = $searchFrom; $i -lt $searchTo; $i++) {
        $line = $lines[$i]
        foreach ($label in $labels) {
            $rx = '(?i)^\s*Uid\s+' + [regex]::Escape($label) + '\s*:\s*([0-9]+(?:\.[0-9]+)?)'
            $mm = [regex]::Match($line, $rx)
            if ($mm.Success) {
                $out.Mah = [double]$mm.Groups[1].Value
                $out.MatchedLine = $line.Trim()
                $mc = [regex]::Match($line, '(?i)\bcpu=([0-9]+(?:\.[0-9]+)?)')
                if ($mc.Success) { $out.CpuMah = [double]$mc.Groups[1].Value }
                return $out
            }
        }
    }

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        foreach ($label in $labels) {
            $rx = '(?i)^\s*Uid\s+' + [regex]::Escape($label) + '\s*:\s*([0-9]+(?:\.[0-9]+)?)'
            $mm = [regex]::Match($line, $rx)
            if ($mm.Success) {
                $out.Mah = [double]$mm.Groups[1].Value
                $out.MatchedLine = $line.Trim()
                $mc = [regex]::Match($line, '(?i)\bcpu=([0-9]+(?:\.[0-9]+)?)')
                if ($mc.Success) { $out.CpuMah = [double]$mc.Groups[1].Value }
                return $out
            }
        }
    }
    return $out
}

function Parse-Wakeups {
    param([string]$Text)
    $t = Normalize-Text $Text
    $wake = @()
    $alarms = @()
    foreach ($m in [regex]::Matches($t, '(?im)^\s*Wakeups:\s*([\d,]+)\s+alarms,\s*([\d,]+)\s+wakeups')) {
        $a = Convert-LongValue $m.Groups[1].Value
        $w = Convert-LongValue $m.Groups[2].Value
        if ($null -ne $a) { $alarms += $a }
        if ($null -ne $w) { $wake += $w }
    }
    foreach ($m in [regex]::Matches($t, '(?im)^\s*Wakeups:\s*([\d,]+)\s*$')) {
        $w = Convert-LongValue $m.Groups[1].Value
        if ($null -ne $w) { $wake += $w }
    }
    foreach ($m in [regex]::Matches($t, '(?im)^\s*Wakeups:\s*([\d,]+)\s+wakeups')) {
        $w = Convert-LongValue $m.Groups[1].Value
        if ($null -ne $w) { $wake += $w }
    }
    foreach ($m in [regex]::Matches($t, '(?im)^\s*Alarms:\s*([\d,]+)\s*,\s*Wakeups:\s*([\d,]+)')) {
        $a = Convert-LongValue $m.Groups[1].Value
        $w = Convert-LongValue $m.Groups[2].Value
        if ($null -ne $a) { $alarms += $a }
        if ($null -ne $w) { $wake += $w }
    }
    $out = @{ Wakeups = $null; Alarms = $null }
    if ($wake.Count -gt 0) { $out.Wakeups = ($wake | Measure-Object -Maximum).Maximum }
    if ($alarms.Count -gt 0) { $out.Alarms = ($alarms | Measure-Object -Maximum).Maximum }
    return $out
}

function Parse-LogcatField {
    param([string]$Text, [string]$Field)
    $t = Normalize-Text $Text
    $rx = [regex]('(?m)^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+.*?' + [regex]::Escape($Field) + '=(\d+)')
    $rows = @()
    foreach ($m in $rx.Matches($t)) {
        try {
            $ts = [datetime]::ParseExact($m.Groups[1].Value, "MM-dd HH:mm:ss.fff", [System.Globalization.CultureInfo]::InvariantCulture)
            $rows += [pscustomobject]@{ Ts = $ts; Value = [long]$m.Groups[2].Value }
        } catch { }
    }
    return $rows
}

function Get-TimeSpanSeconds {
    param([datetime]$Start, [datetime]$End)
    $span = ($End - $Start).TotalSeconds
    if ($span -lt 0) { $span += 86400 }
    return $span
}

function Parse-ProcStat {
    param([string]$Text)
    $t = Normalize-Text $Text
    $line = $null
    foreach ($l in $t.Split([char]10)) {
        if ($l -match '^\s*\d+\s+\(') { $line = $l; break }
    }
    if ($null -eq $line) { return $null }
    $close = $line.LastIndexOf(')')
    if ($close -lt 0) { return $null }
    $rest = $line.Substring($close + 1).Trim()
    $f = $rest -split '\s+'
    if ($f.Count -lt 13) { return $null }
    $utime = Convert-LongValue $f[11]
    $stime = Convert-LongValue $f[12]
    if ($null -eq $utime -or $null -eq $stime) { return $null }
    return @{ Ticks = ($utime + $stime); Utime = $utime; Stime = $stime }
}

function Convert-PsTimeToSeconds {
    param([string]$Text)
    if ($null -eq $Text) { return $null }
    $s = $Text.Trim()
    if ($s -eq "") { return $null }
    # Accepted shapes: mm:ss[.cc], hh:mm:ss[.cc], dd-hh:mm:ss[.cc]
    $parts = @()
    $day = 0.0
    $dash = $s.IndexOf('-')
    if ($dash -gt 0) {
        $dayText = $s.Substring(0, $dash)
        if (-not [double]::TryParse($dayText, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$day)) { return $null }
        $s = $s.Substring($dash + 1)
    }
    foreach ($p in ($s -split ':')) {
        $v = 0.0
        if (-not [double]::TryParse($p, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$v)) { return $null }
        $parts += $v
    }
    if ($parts.Count -lt 2 -or $parts.Count -gt 3) { return $null }
    $sec = 0.0
    if ($parts.Count -eq 3) {
        $sec = $parts[0] * 3600.0 + $parts[1] * 60.0 + $parts[2]
    } else {
        $sec = $parts[0] * 60.0 + $parts[1]
    }
    return ($day * 86400.0 + $sec)
}

function Parse-BatteryLevel {
    param([string]$Text)
    $t = Normalize-Text $Text
    $m = [regex]::Match($t, '(?im)^\s*level:\s*(\d+)')
    if (-not $m.Success) { return $null }
    $level = [double]$m.Groups[1].Value
    $ms = [regex]::Match($t, '(?im)^\s*scale:\s*(\d+)')
    if ($ms.Success) {
        $scale = [double]$ms.Groups[1].Value
        if ($scale -gt 0) { return (100.0 * $level / $scale) }
    }
    return $level
}

function Get-PowerSnapshot {
    param([string]$Text)
    $t = Normalize-Text $Text
    $wake = $null
    $m = [regex]::Match($t, '(?im)mWakefulness=(\w+)')
    if ($m.Success) { $wake = $m.Groups[1].Value }
    if ($null -eq $wake) {
        $m = [regex]::Match($t, '(?im)^\s*Wakefulness:\s*(\w+)')
        if ($m.Success) { $wake = $m.Groups[1].Value }
    }
    $display = $null
    $md = [regex]::Match($t, '(?im)Display Power:\s*state=(\w+)')
    if ($md.Success) { $display = $md.Groups[1].Value }
    return @{ Wakefulness = $wake; Display = $display }
}

function Parse-MusicVolume {
    param([string]$Text)
    $t = Normalize-Text $Text
    $m = [regex]::Match($t, '(?im)\bvolume is\s*(\d+)')
    if ($m.Success) { return [int]$m.Groups[1].Value }
    $m = [regex]::Match($t, '(?is)STREAM_MUSIC:.{0,800}?Current:\s*(\d+)')
    if ($m.Success) { return [int]$m.Groups[1].Value }
    return $null
}

function Parse-AudioFlinger {
    param([string]$Text, [int]$AppPid)
    $t = Normalize-Text $Text
    $active = 0
    foreach ($m in [regex]::Matches($t, '(?im)Tracks of which\s+(\d+)\s+are active')) {
        $active += [int]$m.Groups[1].Value
    }
    $ourActive = 0
    foreach ($line in $t.Split([char]10)) {
        if ($line -match '^\s*\d+\s+\d+\s+(?i:yes)\s+(\d+)\s+(\d+)\s+') {
            if ($AppPid -gt 0 -and ([int]$Matches[1] -eq $AppPid -or [int]$Matches[2] -eq $AppPid)) { $ourActive++ }
        }
    }
    return @{ ActiveTracks = $active; OurPidActiveTracks = $ourActive }
}

function Parse-AudioService {
    param([string]$Text)
    $t = Normalize-Text $Text
    $idx = $t.IndexOf("Audio playback", [System.StringComparison]::OrdinalIgnoreCase)
    $snippet = ""
    if ($idx -ge 0) {
        $len = [math]::Min(20000, $t.Length - $idx)
        $snippet = $t.Substring($idx, $len)
    }
    $started = ([regex]::Matches($snippet, '(?i)state:\s*started')).Count
    $ours = $snippet -match [regex]::Escape($script:Package)
    return @{ ActivePlayers = $started; PackageMentioned = $ours; Snippet = $snippet }
}

# ------------------------------------------------------------------ device state

function Get-AppPid {
    $r = Invoke-Adb -CmdArgs @("shell", "pidof $($script:Package)") -AllowFail
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

function Get-AppUid {
    $r = Invoke-Adb -CmdArgs @("shell", "cmd package list packages -U") -AllowFail
    if ($r.Code -eq 0) {
        foreach ($line in ($r.Text.Split([char]10))) {
            if ($line -match ('^package:' + [regex]::Escape($script:Package) + '\s+uid:(\d+)')) { return [int]$Matches[1] }
        }
    }
    $r2 = Invoke-Adb -CmdArgs @("shell", "dumpsys package $($script:Package)") -AllowFail
    if ($r2.Code -eq 0 -and $r2.Text -match 'userId=(\d+)') { return [int]$Matches[1] }
    throw "cannot resolve uid for $($script:Package)"
}

function Get-ClkTck {
    $r = Invoke-Adb -CmdArgs @("shell", "getconf CLK_TCK") -AllowFail
    if ($r.Code -eq 0 -and $r.Text -match '(\d+)') {
        $v = [int]$Matches[1]
        if ($v -gt 0 -and $v -le 10000) { return $v }
    }
    return 100
}

function Get-CpuSnapshot {
    param([int]$AppPid)
    $res = @{ Ticks = $null; Seconds = $null; Method = "none"; Raw = "" }
    if ($AppPid -le 0) { return $res }
    $r = Invoke-Adb -CmdArgs @("shell", "cat /proc/$AppPid/stat") -AllowFail
    if ($r.Code -eq 0 -and $r.Text -match '^\s*\d+\s+\(') {
        $p = Parse-ProcStat $r.Text
        if ($null -ne $p) {
            $res.Ticks = $p.Ticks
            $res.Method = "proc-stat"
            $res.Raw = $r.Text
            return $res
        }
    }
    $r2 = Invoke-Adb -CmdArgs @("shell", "ps -p $AppPid -o PID,TIME,NAME") -AllowFail
    if ($r2.Code -eq 0) {
        foreach ($line in ($r2.Text.Split([char]10))) {
            if ($line -match ('^\s*' + $AppPid + '\s+(\S+)')) {
                $sec = Convert-PsTimeToSeconds $Matches[1]
                if ($null -ne $sec) {
                    $res.Seconds = $sec
                    $res.Method = "ps-time"
                    $res.Raw = $line
                    return $res
                }
            }
        }
    }
    $r3 = Invoke-Adb -CmdArgs @("shell", "ps -A -o PID,TIME,NAME") -AllowFail
    if ($r3.Code -eq 0) {
        foreach ($line in ($r3.Text.Split([char]10))) {
            if ($line -match ('^\s*' + $AppPid + '\s+(\S+)')) {
                $sec = Convert-PsTimeToSeconds $Matches[1]
                if ($null -ne $sec) {
                    $res.Seconds = $sec
                    $res.Method = "ps-time"
                    $res.Raw = $line
                    return $res
                }
            }
        }
    }
    return $res
}

function Get-MusicVolume {
    $r = Invoke-Adb -CmdArgs @("shell", "media volume --stream 3 --get") -AllowFail
    if ($r.Code -eq 0) {
        $v = Parse-MusicVolume $r.Text
        if ($null -ne $v) { return $v }
    }
    $r2 = Invoke-Adb -CmdArgs @("shell", "dumpsys audio") -AllowFail
    if ($r2.Code -eq 0) { return (Parse-MusicVolume $r2.Text) }
    return $null
}

function Set-MusicVolume {
    param([int]$Level)
    $detail = ""
    $r = Invoke-Adb -CmdArgs @("shell", "media volume --stream 3 --set $Level") -AllowFail
    $detail += $r.Text
    Start-Sleep -Milliseconds 400
    $cur = Get-MusicVolume
    if ($cur -ne $Level) {
        $r2 = Invoke-Adb -CmdArgs @("shell", "cmd media_session volume --stream 3 --set $Level") -AllowFail
        $detail += " | " + $r2.Text
        Start-Sleep -Milliseconds 500
        $cur = Get-MusicVolume
    }
    $ok = ($null -ne $cur -and [int]$cur -eq $Level)
    return @{ Ok = $ok; Applied = $cur; Detail = $detail.Trim() }
}

function Get-BatteryLevel {
    $r = Invoke-Adb -CmdArgs @("shell", "dumpsys battery") -AllowFail
    if ($r.Code -ne 0) { return $null }
    return (Parse-BatteryLevel $r.Text)
}

function Get-PowerState {
    $r = Invoke-Adb -CmdArgs @("shell", "dumpsys power") -AllowFail
    if ($r.Code -ne 0) { return @{ Wakefulness = $null; Display = $null; Raw = "" } }
    $p = Get-PowerSnapshot $r.Text
    $p.Raw = $r.Text
    return $p
}

function Wake-Screen {
    Invoke-Adb -CmdArgs @("shell", "input keyevent KEYCODE_WAKEUP") -AllowFail | Out-Null
    Invoke-Adb -CmdArgs @("shell", "wm dismiss-keyguard") -AllowFail | Out-Null
    Start-Sleep -Milliseconds 800
}

function Pause-Playback {
    # media pause is idempotent (unlike /player/play-pause, which toggles based on isPlaying and can
    # accidentally resume when the player is buffering with playWhenReady=true).
    for ($i = 0; $i -lt 4; $i++) {
        $s = Get-State -Retries 0
        if ($null -ne $s -and (Get-P $s "playback.playing") -ne $true -and (Get-P $s "playback.playWhenReady") -ne $true) { return $true }
        Invoke-Adb -CmdArgs @("shell", "input keyevent KEYCODE_MEDIA_PAUSE") -AllowFail | Out-Null
        Start-Sleep -Milliseconds 500
        $s = Get-State -Retries 0
        if ($null -ne $s -and (Get-P $s "playback.playing") -ne $true -and (Get-P $s "playback.playWhenReady") -ne $true) { return $true }
        if ($null -ne $s -and (Get-P $s "playback.playing") -eq $true) {
            Invoke-Api -Method POST -Path "/player/play-pause" -Body @{} -TimeoutSec 10 -Retries 0 | Out-Null
            Start-Sleep -Milliseconds 400
        }
    }
    return $false
}

function Ensure-ScreenOff {
    $p = Get-PowerState
    if ($p.Display -eq "OFF" -or $p.Wakefulness -in @("Asleep", "Dozing")) { return $p }
    Invoke-Adb -CmdArgs @("shell", "input keyevent KEYCODE_SLEEP") -AllowFail | Out-Null
    Start-Sleep -Seconds 3
    $p = Get-PowerState
    if ($p.Display -ne "OFF" -and -not ($p.Wakefulness -in @("Asleep", "Dozing"))) {
        Invoke-Adb -CmdArgs @("shell", "input keyevent KEYCODE_POWER") -AllowFail | Out-Null
        Start-Sleep -Seconds 3
        $p = Get-PowerState
    }
    if ($p.Display -ne "OFF" -and -not ($p.Wakefulness -in @("Asleep", "Dozing"))) {
        throw ("cannot turn screen off (wakefulness=" + $p.Wakefulness + ", display=" + $p.Display + ")")
    }
    return $p
}

function Start-App {
    $r = Invoke-Adb -CmdArgs @("shell", "am start -W -n $($script:Package)/.MainActivity") -AllowFail
    if ($r.Code -ne 0) {
        $r = Invoke-Adb -CmdArgs @("shell", "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $($script:Package)/.MainActivity") -AllowFail
    }
    if ($r.Code -ne 0) { throw ("am start failed: " + $r.Text) }
    return $r.Text
}

function Stop-App {
    Invoke-Adb -CmdArgs @("shell", "am force-stop $($script:Package)") -AllowFail | Out-Null
    Start-Sleep -Milliseconds 900
}

function Ensure-Api {
    $s = Get-State -Retries 0
    if ($null -ne $s) { return $s }
    Wake-Screen
    Start-App | Out-Null
    return (Wait-Api -TimeoutSec $SetupTimeoutSec)
}

function Read-EnginePrefs {
    $r = Invoke-Adb -CmdArgs @("shell", "run-as $($script:Package) cat shared_prefs/loop_settings.xml") -AllowFail
    if ($r.Code -ne 0 -or $r.Text -notmatch 'loop_engine') { return $null }
    $engine = $null
    $main = $null
    $m1 = [regex]::Match($r.Text, '<string name="loop_engine">([^<]+)</string>')
    if ($m1.Success) { $engine = $m1.Groups[1].Value }
    $m2 = [regex]::Match($r.Text, '<string name="loop_engine_main">([^<]+)</string>')
    if ($m2.Success) { $main = $m2.Groups[1].Value }
    return @{ Engine = $engine; Main = $main }
}

function Set-Engine {
    param([string]$Mode)
    $expectedRaw = "source"
    if ($Mode -eq "m3") { $expectedRaw = "m3" }
    Ensure-Api | Out-Null
    $on = ($Mode -eq "source")
    $r = Invoke-Api -Method POST -Path "/debug/source-mode" -Body @{ on = $on } -TimeoutSec 20
    if (-not $r.Ok) { throw ("/debug/source-mode failed: " + $r.Error) }
    $applied = Get-P $r.Data "loopSourceMode"
    Write-Log ("engine switch requested: " + $Mode + " -> loopSourceMode=" + $applied)
    if ($Mode -eq "source" -and $applied -ne "SOURCE") { throw ("engine switch mismatch: expected SOURCE, got " + $applied) }
    if ($Mode -eq "m3" -and $applied -ne "M3_PRIME") { throw ("engine switch mismatch: expected M3_PRIME, got " + $applied) }
    Start-Sleep -Milliseconds 1200
    Stop-App
    Wake-Screen
    Start-App | Out-Null
    Wait-Api -TimeoutSec $SetupTimeoutSec | Out-Null
    $prefs = Read-EnginePrefs
    if ($null -ne $prefs) {
        Write-Log ("persisted prefs after restart: loop_engine=" + $prefs.Engine + " loop_engine_main=" + $prefs.Main)
        if ($prefs.Engine -ne $expectedRaw -or $prefs.Main -ne $expectedRaw) {
            throw ("engine preference did not survive restart: expected " + $expectedRaw + ", got " + $prefs.Engine + "/" + $prefs.Main)
        }
    } else {
        Write-Log "WARN cannot read engine prefs via run-as; relying on /state.loop.loopPath after playback" "Yellow"
    }
    return $applied
}

function Stop-OtherApps {
    $r = Invoke-Adb -CmdArgs @("shell", "cmd package list packages -3") -AllowFail
    if ($r.Code -ne 0) { Write-Log ("WARN cannot list third-party packages: " + $r.Text) "Yellow"; return 0 }
    $stopped = 0
    foreach ($line in ($r.Text.Split([char]10))) {
        if ($line -match '^package:(.+)$') {
            $pkg = $Matches[1].Trim()
            if ($pkg -eq "" -or $pkg -eq $script:Package) { continue }
            Invoke-Adb -CmdArgs @("shell", "am force-stop $pkg") -AllowFail | Out-Null
            $stopped++
        }
    }
    return $stopped
}

function Reset-BatteryStats {
    $r = Invoke-Adb -CmdArgs @("shell", "dumpsys batterystats --reset") -AllowFail
    if ($r.Text -match '(?i)(permission denial|exception|unknown option|error)') {
        throw ("dumpsys batterystats --reset rejected: " + $r.Text)
    }
    return $r.Text
}

# ------------------------------------------------------------------ collection

function Collect-BatteryStats {
    param([string]$RoundDir, [int]$Uid)
    $result = @{
        Mah = $null; CpuMah = $null; Wakeups = $null; Alarms = $null
        ComputedDrain = $null; MatchedLine = $null; Files = @(); Source = "none"
    }
    # Lazy fallbacks: package-scoped --charged is the cheapest and is usually enough; every
    # additional dump is only executed when mAh or Wakeups is still missing.
    $commands = @(
        @{ Name = "batterystats-pkg.txt"; Cmd = "dumpsys batterystats $($script:Package)" },
        @{ Name = "batterystats-pkg-charged.txt"; Cmd = "dumpsys batterystats --charged $($script:Package)" },
        @{ Name = "batterystats-all.txt"; Cmd = "dumpsys batterystats" },
        @{ Name = "batterystats-full.txt"; Cmd = "dumpsys batterystats --charged" }
    )
    foreach ($c in $commands) {
        if ($null -ne $result.Mah -and $null -ne $result.Wakeups) { break }
        $r = Invoke-Adb -CmdArgs @("shell", $c.Cmd) -AllowFail
        if ($r.Code -ne 0 -and $r.Text.Trim() -eq "") { continue }
        $path = Join-Path $RoundDir $c.Name
        Save-Text $path $r.Text
        $result.Files += $path
        if ($null -eq $result.Mah) {
            $p = Parse-BatteryStats $r.Text $Uid
            if ($null -ne $p.Mah) {
                $result.Mah = $p.Mah
                $result.CpuMah = $p.CpuMah
                $result.ComputedDrain = $p.ComputedDrain
                $result.MatchedLine = $p.MatchedLine
                $result.Source = $c.Name
            }
        }
        if ($null -eq $result.Wakeups) {
            $w = Parse-Wakeups $r.Text
            if ($null -ne $w.Wakeups) {
                $result.Wakeups = $w.Wakeups
                $result.Alarms = $w.Alarms
            }
        }
    }
    return $result
}

function Collect-AudioEvidence {
    param([string]$RoundDir, [int]$AppPid)
    $result = @{ AudioFlingerActiveTracks = $null; OurPidActiveTracks = $null; AudioServiceActivePlayers = $null; PackageMentioned = $null }
    $r = Invoke-Adb -CmdArgs @("shell", "dumpsys media.audio_flinger") -AllowFail
    if ($r.Code -eq 0) {
        Save-Text (Join-Path $RoundDir "dumpsys-audio-flinger.txt") $r.Text
        $p = Parse-AudioFlinger $r.Text $AppPid
        $result.AudioFlingerActiveTracks = $p.ActiveTracks
        $result.OurPidActiveTracks = $p.OurPidActiveTracks
    }
    $r2 = Invoke-Adb -CmdArgs @("shell", "dumpsys audio") -AllowFail
    if ($r2.Code -eq 0) {
        Save-Text (Join-Path $RoundDir "dumpsys-audio.txt") $r2.Text
        $p2 = Parse-AudioService $r2.Text
        $result.AudioServiceActivePlayers = $p2.ActivePlayers
        $result.PackageMentioned = $p2.PackageMentioned
    }
    return $result
}

# ------------------------------------------------------------------ song / loop setup

function Get-Song {
    param([long]$Id)
    $r = Invoke-Api -Method GET -Path ("/song/" + $Id) -TimeoutSec 20
    if (-not $r.Ok) { throw ("GET /song/" + $Id + " failed: " + $r.Error) }
    return $r.Data
}

function Select-Song {
    param([long]$Id)
    if ($Id -gt 0) {
        $s = Get-Song $Id
        return $s
    }
    $r = Invoke-Api -Method GET -Path "/library/songs?limit=200" -TimeoutSec 30
    if (-not $r.Ok) { throw ("GET /library/songs failed: " + $r.Error) }
    $songs = @($r.Data.songs | Where-Object { $null -ne $_ })
    if ($songs.Count -eq 0) { throw "library has no songs" }
    $pick = $null
    foreach ($s in $songs) {
        if ($s.loopConfirmed -eq $true -and $s.path -like "*.mp3") { $pick = $s; break }
    }
    if ($null -eq $pick) { foreach ($s in $songs) { if ($s.loopConfirmed -eq $true) { $pick = $s; break } } }
    if ($null -eq $pick) { foreach ($s in $songs) { if ($s.path -like "*.mp3") { $pick = $s; break } } }
    if ($null -eq $pick) { $pick = $songs[0] }
    return (Get-Song ([long]$pick.id))
}

function Invoke-Analyze {
    param([long]$Id)
    $r = Invoke-Api -Method POST -Path "/analyze" -Body @{ songId = $Id } -TimeoutSec 30
    if (-not $r.Ok) { throw ("POST /analyze failed: " + $r.Error) }
    $deadline = (Get-Date).AddSeconds(300)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        $a = Invoke-Api -Method GET -Path "/analysis" -TimeoutSec 20
        if (-not $a.Ok) { continue }
        $last = $a.Data
        $phase = Get-P $a.Data "phase"
        $anSongNow = Get-P $a.Data "songId"
        if (($phase -eq "done" -or $phase -eq "failed") -and $null -ne $anSongNow -and [long]$anSongNow -eq $Id) { break }
        Write-Host ("    analysis " + $phase + " " + (Format-Num (Get-P $a.Data "progress") "{0:P0}"))
    }
    if ($null -eq $last) { throw "analysis did not return state" }
    $phase = Get-P $last "phase"
    $anSong = Get-P $last "songId"
    if ($phase -ne "done") { throw ("analysis " + $phase + ": " + (Get-P $last "message")) }
    if ($null -ne $anSong -and [long]$anSong -ne $Id) { throw ("analysis is for song " + $anSong + ", not " + $Id) }
    return $last
}

function Ensure-LoopConfirmed {
    param([long]$Id, [int]$Ams, [int]$Bms)
    $song = Get-Song $Id
    if ($Ams -gt 0 -and $Bms -le $Ams) { throw "-Bms must be greater than -Ams" }
    if ($Ams -gt 0 -and ($Bms - $Ams) -lt 1000) { throw "-Bms must be at least 1000ms after -Ams" }
    if ($Ams -gt 0 -and $null -ne $song.durationMs -and [long]$song.durationMs -gt 0 -and [long]$Bms -gt [long]$song.durationMs) {
        throw ("-Bms (" + $Bms + "ms) exceeds song duration (" + $song.durationMs + "ms)")
    }
    $explicit = ($Ams -gt 0)
    $analysis = $null
    if ($explicit) {
        $sr = $song.sampleRate
        if ($null -eq $sr -or [int]$sr -lt 8000) {
            if ($SkipAnalyze) { throw "explicit A/B needs sampleRate, but song has no confirmed loop (and -SkipAnalyze is set)" }
            Write-Log "analysis needed to obtain sampleRate for explicit A/B" "Yellow"
            $analysis = Invoke-Analyze $Id
            $sr = Get-P $analysis "sampleRate"
        }
        if ($null -eq $sr -or [int]$sr -lt 8000) { throw "cannot determine sampleRate" }
        $aSm = [long]([math]::Round(([double]$Ams * [double]$sr) / 1000.0))
        $bSm = [long]([math]::Round(([double]$Bms * [double]$sr) / 1000.0))
        Write-Log ("confirm explicit window A=" + $Ams + "ms B=" + $Bms + "ms (samples " + $aSm + ".." + $bSm + ", sr=" + $sr + ")")
        $r = Invoke-Api -Method POST -Path "/confirm" -Body @{ songId = $Id; aSamples = $aSm; bSamples = $bSm } -TimeoutSec 30
        if (-not $r.Ok) { throw ("POST /confirm (explicit) failed: " + $r.Error) }
    } elseif ($song.loopConfirmed -ne $true) {
        if ($SkipAnalyze) { throw ("song " + $Id + " has no confirmed loop and -SkipAnalyze is set") }
        Write-Log ("song " + $Id + " has no confirmed loop; analyzing")
        $analysis = Invoke-Analyze $Id
        $r = Invoke-Api -Method POST -Path "/confirm" -Body @{ songId = $Id; useCandidate = 0 } -TimeoutSec 30
        if (-not $r.Ok) { throw ("POST /confirm failed: " + $r.Error) }
    }

    $song = Get-Song $Id
    if ($song.loopConfirmed -ne $true) { throw ("song " + $Id + " still has no confirmed loop after confirm") }
    if ($null -eq $song.sampleRate -or [int]$song.sampleRate -lt 8000) { throw ("song " + $Id + " confirmed loop has invalid sampleRate") }
    if ($null -eq $song.loopStartMs -or $null -eq $song.loopEndMs -or [int]$song.loopEndMs -le [int]$song.loopStartMs) {
        throw ("song " + $Id + " confirmed loop has invalid window")
    }
    if ($explicit) {
        $da = [math]::Abs([double]$song.loopStartMs - [double]$Ams)
        $db = [math]::Abs([double]$song.loopEndMs - [double]$Bms)
        if ($da -gt 5.0 -or $db -gt 5.0) {
            throw ("stored window (" + $song.loopStartMs + ".." + $song.loopEndMs + ") differs from requested (" + $Ams + ".." + $Bms + ")")
        }
    }
    return $song
}

# ------------------------------------------------------------------ playback

function Start-LoopPlayback {
    param([long]$Id, [string]$ExpectedPath, [int]$Ams)
    $r = $null
    $enterDeadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $enterDeadline) {
        $r = Invoke-Api -Method POST -Path "/loop/enter" -Body @{ songId = $Id; play = $true } -TimeoutSec 60
        if ($r.Ok) { break }
        if ($r.Status -ne 503 -and $r.Status -ne 0) { break }
        Start-Sleep -Milliseconds 700
    }
    if (-not $r.Ok) { throw ("POST /loop/enter failed: " + $r.Error) }
    $deadline = (Get-Date).AddSeconds(60)
    $s = $null
    $ok = $false
    while ((Get-Date) -lt $deadline) {
        $s = Get-State -Retries 0
        if ($null -ne $s) {
            $playing = Get-P $s "playback.playing"
            $engine = Get-P $s "loop.engine"
            $path = Get-P $s "loop.loopPath"
            $winSong = Get-P $s "loop.window.songId"
            if ($playing -eq $true -and $engine -eq "loop" -and $winSong -eq $Id -and $path -eq $ExpectedPath) { $ok = $true; break }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ok) {
        $desc = "engine=" + (Get-P $s "loop.engine") + " loopPath=" + (Get-P $s "loop.loopPath") + " playing=" + (Get-P $s "playback.playing") + " winSong=" + (Get-P $s "loop.window.songId")
        throw ("loop did not become active (" + $ExpectedPath + "): " + $desc)
    }

    if ($Ams -gt 1500) {
        $seekMs = [math]::Max(0, $Ams - 1200)
        Write-Log ("seeking near A: " + $seekMs + "ms")
        Invoke-Api -Method POST -Path "/player/seek" -Body @{ positionMs = $seekMs } -TimeoutSec 20 | Out-Null
        Start-Sleep -Milliseconds 800
        $after = Get-State
        $pos = Get-P $after "playback.positionMs"
        if ($null -ne $pos -and [long]$pos -gt ($seekMs + 4000)) {
            Invoke-Api -Method POST -Path "/player/seek" -Body @{ positionMs = $seekMs } -TimeoutSec 20 | Out-Null
            Start-Sleep -Milliseconds 800
        }
    }
    Start-Sleep -Seconds 2
    $s = Get-State
    if ((Get-P $s "loop.loopPath") -ne $ExpectedPath) {
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 500
            $s = Get-State -Retries 0
            if ($null -ne $s -and (Get-P $s "loop.loopPath") -eq $ExpectedPath -and (Get-P $s "loop.window.songId") -eq $Id) { break }
        }
        if ((Get-P $s "loop.loopPath") -ne $ExpectedPath) { throw ("loopPath left " + $ExpectedPath + " after seek") }
    }
    return $s
}

function Wait-SteadyLoop {
    param([long]$Id, [string]$ExpectedPath, [int]$TimeoutSec)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $lastPos = $null
    $wrapped = $false
    $ring = $false
    while ((Get-Date) -lt $deadline) {
        $s = Get-State -Retries 0
        if ($null -ne $s) {
            if ((Get-P $s "playback.playing") -ne $true) { throw "playback stopped during steady-state wait" }
            if ((Get-P $s "loop.loopPath") -ne $ExpectedPath) { throw ("loopPath changed to " + (Get-P $s "loop.loopPath") + " during steady-state wait") }
            if ((Get-P $s "loop.window.songId") -ne $Id) { throw "loop window lost during steady-state wait" }
            $pos = Get-P $s "playback.positionMs"
            if ($null -ne $pos) {
                $posL = [long]$pos
                if ($null -ne $lastPos -and $posL -lt ($lastPos - 500)) { $wrapped = $true }
                $lastPos = $posL
            }
            if ((Get-P $s "loop.isRingComplete") -eq $true) { $ring = $true }
            if ($ExpectedPath -eq "m3" -and $ring) { return $s }
            if ($ExpectedPath -eq "source" -and $wrapped) { return $s }
        }
        Start-Sleep -Milliseconds 500
    }
    if ($ExpectedPath -eq "m3" -and -not $ring) { throw ("M3' ring did not complete within " + $TimeoutSec + "s (window too long or ring prefiller stuck)") }
    if ($ExpectedPath -eq "source" -and -not $wrapped) { throw ("SOURCE loop did not wrap within " + $TimeoutSec + "s (loop not active?)") }
    throw "steady-state wait failed"
}

function Get-SnapshotRow {
    param($State, [datetime]$StartTime)
    $t = ((Get-Date) - $StartTime).TotalSeconds
    return [pscustomobject]@{
        t = [math]::Round($t, 1)
        posMs = Get-P $State "playback.positionMs"
        playing = Get-P $State "playback.playing"
        engine = Get-P $State "loop.engine"
        loopPath = Get-P $State "loop.loopPath"
        windowSong = Get-P $State "loop.window.songId"
        seam = Get-P $State "loop.seamCount"
        degrade = Get-P $State "loop.degradeCount"
        ring = Get-P $State "loop.isRingComplete"
        frames = Get-P $State "invariantProbe.writtenFrames"
    }
}

# ------------------------------------------------------------------ round

function Invoke-BatteryRound {
    param(
        [string]$RoundId,
        [string]$Engine,
        [long]$Id,
        [int]$Ams,
        [int]$Bms,
        [int]$SampleRate,
        [int]$Uid,
        [int]$Minutes,
        [int]$Volume,
        [int]$WarmupSec,
        [int]$SampleSec,
        [int]$RingTimeoutSec
    )
    $dir = Join-Path $script:RunDir ("round-" + $RoundId)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $expectedPath = "source"
    if ($Engine -eq "m3") { $expectedPath = "m3" }
    Write-Log ("=== round " + $RoundId + ": engine=" + $Engine + " (expected loopPath=" + $expectedPath + ") ===") "Cyan"

    if (-not $SkipAppStop) {
        $n = Stop-OtherApps
        Write-Log ("round setup: force-stopped third-party apps: " + $n)
    }

    $round = @{
        Id = $RoundId; Engine = $Engine; ExpectedLoopPath = $expectedPath
        SongId = $Id; SampleRate = $SampleRate; Ams = $Ams; Bms = $Bms
        StartedAt = (Get-Date).ToString("o"); EndedAt = $null
        Seconds = $null; Mah = $null; CpuMah = $null; CpuMs = $null; CpuMethod = "none"
        Wakeups = $null; Alarms = $null; BatteryLevelStart = $null; BatteryLevelEnd = $null
        Frames = $null; FrameSeconds = $null; ExpectedFrames = $null; Duty = $null; FullDuty = $false
        AudioSeconds = $null; AudioFlingerActiveTracks = $null; OurPidActiveTracks = $null
        AudioServiceActivePlayers = $null
        Pid = 0; PidAfter = 0; RingComplete = $null; Window = $null
        Abort = $null; Errors = @(); Samples = @(); Artifacts = $dir
    }

    Set-Engine $Engine | Out-Null
    Wait-PlayerReady -TimeoutSec $SetupTimeoutSec | Out-Null
    $appPid = Get-AppPid
    if ($appPid -le 0) { throw "app process not running after engine restart" }
    $round.Pid = $appPid
    Write-Log ("app pid=" + $appPid + " uid=" + $Uid)

    $volResult = Set-MusicVolume $Volume
    Write-Log ("volume set -> " + (Format-Num $volResult.Applied "{0}") + " (requested " + $Volume + ")")
    if (-not $volResult.Ok) { throw ("cannot set/verify STREAM_MUSIC volume to " + $Volume + ": " + $volResult.Detail) }

    $state = Start-LoopPlayback $Id $expectedPath $Ams
    Save-Json (Join-Path $dir "state-loop-started.json") $state
    $win = Get-P $state "loop.window"
    $round.Window = $win
    $liveA = $null; $liveB = $null
    if ($null -ne $win) {
        $liveA = [long][math]::Round(([double](Get-P $win "loopStartUs")) / 1000.0)
        $liveB = [long][math]::Round(([double](Get-P $win "loopEndUs")) / 1000.0)
    }
    Write-Log ("loop window: song=" + (Get-P $state "loop.window.songId") + " A=" + $liveA + "ms B=" + $liveB + "ms engine=" + (Get-P $state "loop.engine") + " loopPath=" + (Get-P $state "loop.loopPath"))
    if ($Ams -gt 0 -and $liveA -ne $null -and ([math]::Abs([double]$liveA - [double]$Ams) -gt 20.0)) {
        throw ("live window A=" + $liveA + "ms does not match requested A=" + $Ams + "ms")
    }

    $state = Wait-SteadyLoop $Id $expectedPath $RingTimeoutSec
    $round.RingComplete = Get-P $state "loop.isRingComplete"
    Write-Log ("steady loop reached (ringComplete=" + $round.RingComplete + ")")

    $power = Ensure-ScreenOff
    Write-Log ("screen state: wakefulness=" + $power.Wakefulness + " display=" + $power.Display)
    Start-Sleep -Seconds 2
    $s1 = Get-State
    Start-Sleep -Seconds 1
    $s2 = Get-State
    if ((Get-P $s2 "playback.playing") -ne $true) { throw "playback stopped after screen off (foreground service did not keep playing)" }
    $p1 = Get-P $s1 "playback.positionMs"; $p2 = Get-P $s2 "playback.positionMs"
    if ($null -ne $p1 -and $null -ne $p2 -and [long]$p2 -eq [long]$p1) { Write-Log "WARN position did not advance in 1s after screen off" "Yellow" }
    Save-Text (Join-Path $dir "dumpsys-power.txt") $power.Raw

    if ($WarmupSec -gt 0) {
        Write-Log ("warmup " + $WarmupSec + "s")
        Start-Sleep -Seconds $WarmupSec
    }
    $s = Get-State
    if ((Get-P $s "playback.playing") -ne $true) { throw "playback stopped during warmup" }
    if ((Get-P $s "loop.loopPath") -ne $expectedPath) { throw ("loopPath changed to " + (Get-P $s "loop.loopPath") + " during warmup") }

    if ($Engine -eq "source") {
        $probeDeadline = (Get-Date).AddSeconds(6)
        $probeFrames = $null
        while ((Get-Date) -lt $probeDeadline) {
            $probeFrames = Get-P (Get-State) "invariantProbe.writtenFrames"
            if ($null -ne $probeFrames) { break }
            Start-Sleep -Milliseconds 300
        }
        if ($null -eq $probeFrames) { throw "SOURCE output-frame probe is unavailable (/state.invariantProbe.writtenFrames is null)" }
    }

    Invoke-Adb -CmdArgs @("shell", "logcat -c") -AllowFail | Out-Null
    $frameStartRow = $null
    if ($Engine -eq "m3") {
        $wait = (Get-Date).AddSeconds(6)
        while ((Get-Date) -lt $wait) {
            $log = (Invoke-Adb -CmdArgs @("shell", "logcat -d -v time -s LoopPerf:D") -AllowFail).Text
            $rows = Parse-LogcatField $log "pumpWrites"
            if ($rows.Count -gt 0) { $frameStartRow = $rows[$rows.Count - 1]; break }
            Start-Sleep -Milliseconds 500
        }
        if ($null -eq $frameStartRow) { throw "M3' steady playback is not producing LoopPerf pumpWrites lines" }
        Write-Log ("M3 frame baseline: pumpWrites=" + $frameStartRow.Value + " at " + $frameStartRow.Ts.ToString("HH:mm:ss.fff"))
    }

    $resetText = Reset-BatteryStats
    Save-Text (Join-Path $dir "batterystats-reset.txt") $resetText
    Start-Sleep -Milliseconds 600

    $startTime = Get-Date
    $startState = Get-State
    if ($null -eq $startState) { throw "state unavailable at round start" }
    if ((Get-P $startState "playback.playing") -ne $true) { throw "playback is not running at round start" }
    $startFrames = Get-P $startState "invariantProbe.writtenFrames"
    $batteryStartText = (Invoke-Adb -CmdArgs @("shell", "dumpsys battery") -AllowFail).Text
    Save-Text (Join-Path $dir "dumpsys-battery-before.txt") $batteryStartText
    $round.BatteryLevelStart = Parse-BatteryLevel $batteryStartText
    $cpuStart = Get-CpuSnapshot $appPid
    Save-Text (Join-Path $dir "proc-stat-before.txt") ($cpuStart.Raw + [string][char]10 + "method=" + $cpuStart.Method)
    $round.CpuMethod = $cpuStart.Method
    Save-Json (Join-Path $dir "state-start.json") $startState

    Write-Log ("round timer start: engine=" + $Engine + " playing=" + (Get-P $startState "playback.playing") + " frames=" + $startFrames + " battery=" + (Format-Num $round.BatteryLevelStart "{0:N0}%"))

    $endAt = $startTime.AddSeconds($Minutes * 60)
    $samples = New-Object System.Collections.ArrayList
    $stops = 0
    $stateFailures = 0
    $lastLog = Get-Date
    while ((Get-Date) -lt $endAt) {
        $state = Get-State -Retries 0
        if ($null -eq $state) {
            $stateFailures++
            if ($stateFailures -ge 3) { $round.Abort = "state endpoint unavailable during round"; break }
        } else {
            $stateFailures = 0
            $row = Get-SnapshotRow $state $startTime
            [void]$samples.Add($row)
            if ($row.playing -ne $true) {
                $stops++
                if ($stops -ge 2) { $round.Abort = "playback stopped during round"; break }
            } else {
                $stops = 0
            }
            if ($row.loopPath -ne $expectedPath) { $round.Abort = ("loopPath changed to " + $row.loopPath); break }
            if ($null -ne $row.windowSong -and [long]$row.windowSong -ne $Id) { $round.Abort = "loop window song changed"; break }
            $nowPid = Get-AppPidRetry
            if ($nowPid -le 0) { $round.Abort = "app process disappeared"; break }
            if ($nowPid -ne $appPid) { $round.Abort = ("app pid changed " + $appPid + " -> " + $nowPid); break }
        }
        $elapsed = ((Get-Date) - $startTime).TotalSeconds
        $left = ($Minutes * 60) - $elapsed
        if ($left -le 0) { break }
        if (((Get-Date) - $lastLog).TotalSeconds -ge 30) {
            $lastLog = Get-Date
            $pos = $null
            if ($samples.Count -gt 0) { $pos = $samples[$samples.Count - 1].posMs }
            $playingText = "?"
            if ($samples.Count -gt 0) { $playingText = $samples[$samples.Count - 1].playing }
            Write-Host ("    [" + $RoundId + "/" + $Engine + "] " + [timespan]::FromSeconds($elapsed).ToString("mm\:ss") + " / " + $Minutes + ":00  pos=" + $pos + "ms  playing=" + $playingText)
        }
        $sleep = [math]::Min($SampleSec, [math]::Max(1.0, $left))
        Start-Sleep -Seconds $sleep
    }
    $round.Samples = @($samples)

    $endState = Get-State
    $endTime = Get-Date
    $endFrames = $null
    if ($null -ne $endState) { $endFrames = Get-P $endState "invariantProbe.writtenFrames" }
    $playingAtEnd = $null
    if ($null -ne $endState) { $playingAtEnd = Get-P $endState "playback.playing" }
    $round["PlayingAtEnd"] = $playingAtEnd
    $paused = Pause-Playback
    if (-not $paused) { Write-Log "WARN playback did not stop after pause attempts" "Yellow" }
    Start-Sleep -Milliseconds 400
    $round.EndedAt = (Get-Date).ToString("o")

    $battery = Collect-BatteryStats $dir $Uid
    $round.Mah = $battery.Mah
    $round.CpuMah = $battery.CpuMah
    $round.Wakeups = $battery.Wakeups
    $round.Alarms = $battery.Alarms

    $batteryEndText = (Invoke-Adb -CmdArgs @("shell", "dumpsys battery") -AllowFail).Text
    Save-Text (Join-Path $dir "dumpsys-battery-after.txt") $batteryEndText
    $round.BatteryLevelEnd = Parse-BatteryLevel $batteryEndText

    $cpuEnd = Get-CpuSnapshot $appPid
    Save-Text (Join-Path $dir "proc-stat-after.txt") ($cpuEnd.Raw + [string][char]10 + "method=" + $cpuEnd.Method)
    Save-Text (Join-Path $dir "ps-after.txt") ((Invoke-Adb -CmdArgs @("shell", "ps -p $appPid -o PID,TIME,NAME") -AllowFail).Text)
    if ($cpuStart.Method -eq "proc-stat" -and $cpuEnd.Method -eq "proc-stat") {
        $deltaTicks = [double]$cpuEnd.Ticks - [double]$cpuStart.Ticks
        if ($deltaTicks -lt 0) { $round.Errors += "cpu ticks went backwards"; $deltaTicks = 0 }
        $round.CpuMs = [math]::Round($deltaTicks * 1000.0 / [double]$script:ClkTck, 1)
        $round.CpuMethod = "proc-stat"
    } elseif ($null -ne $cpuStart.Seconds -and $null -ne $cpuEnd.Seconds) {
        $deltaSec = [double]$cpuEnd.Seconds - [double]$cpuStart.Seconds
        if ($deltaSec -lt 0) { $round.Errors += "ps TIME went backwards"; $deltaSec = 0 }
        $round.CpuMs = [math]::Round($deltaSec * 1000.0, 0)
        $round.CpuMethod = "ps-time"
    } else {
        $round.Errors += "cpu time unavailable (proc-stat and ps TIME both failed)"
        $round.CpuMethod = "none"
    }

    $logText = (Invoke-Adb -CmdArgs @("shell", "logcat -d -v time -s LoopPerf:D") -AllowFail).Text
    Save-Text (Join-Path $dir "logcat-loop-perf.txt") $logText
    Save-Json (Join-Path $dir "state-end.json") $endState

    $round.Seconds = [math]::Round(($endTime - $startTime).TotalSeconds, 2)
    if ($Engine -eq "source") {
        if ($null -ne $startFrames -and $null -ne $endFrames) {
            $frames = [double]$endFrames - [double]$startFrames
            if ($frames -ge 0) {
                $round.Frames = [long]$frames
                $round.FrameSeconds = [math]::Round($round.Seconds, 2)
            } else {
                $round.Errors += "SOURCE frame counter went backwards"
            }
        }
        if ($null -eq $round.Frames) {
            $rows = Parse-LogcatField $logText "writtenFrames"
            if ($rows.Count -ge 2) {
                $first = $rows[0]; $last = $rows[$rows.Count - 1]
                if ($last.Value -ge $first.Value) {
                    $round.Frames = $last.Value - $first.Value
                    $round.FrameSeconds = [math]::Round((Get-TimeSpanSeconds $first.Ts $last.Ts), 2)
                    $round.Errors += "used tapDuty logcat fallback for frames"
                }
            }
        }
    } else {
        $rows = Parse-LogcatField $logText "pumpWrites"
        if ($rows.Count -ge 2 -and $null -ne $frameStartRow) {
            $last = $rows[$rows.Count - 1]
            if ($last.Value -ge $frameStartRow.Value) {
                $round.Frames = $last.Value - $frameStartRow.Value
                $round.FrameSeconds = [math]::Round((Get-TimeSpanSeconds $frameStartRow.Ts $last.Ts), 2)
            } else {
                $round.Errors += "M3 pumpWrites counter reset during round"
            }
        } elseif ($null -eq $frameStartRow) {
            $round.Errors += "missing M3 pumpWrites baseline"
        } else {
            $round.Errors += "missing M3 pumpWrites end line"
        }
    }

    if ($null -ne $round.Frames -and $null -ne $round.FrameSeconds -and $SampleRate -gt 0) {
        $round.ExpectedFrames = [long][math]::Round([double]$SampleRate * [double]$round.FrameSeconds)
        if ($round.ExpectedFrames -gt 0) {
            $round.Duty = [math]::Round([double]$round.Frames / [double]$round.ExpectedFrames, 4)
            $round.FullDuty = ($round.Duty -ge 0.98)
            $round.AudioSeconds = [math]::Round([double]$round.Frames / [double]$SampleRate, 1)
        }
    }

    $audio = Collect-AudioEvidence $dir $appPid
    $round.AudioFlingerActiveTracks = $audio.AudioFlingerActiveTracks
    $round.OurPidActiveTracks = $audio.OurPidActiveTracks
    $round.AudioServiceActivePlayers = $audio.AudioServiceActivePlayers
    $round.PidAfter = Get-AppPidRetry
    if ($round.PidAfter -le 0) { $round.Abort = "app died before end-of-round collection" }
    elseif ($round.PidAfter -ne $appPid) { $round.Abort = "app pid changed before end-of-round collection" }

    if ($null -eq $round.Mah) { $round.Errors += "batterystats did not expose Estimated power use for uid" }
    if ($null -eq $round.Wakeups) { $round.Errors += "batterystats did not expose Wakeups" }
    if ($null -eq $round.Frames) { $round.Errors += "output frames unavailable" }
    if ($null -eq $round.Duty) { $round.Errors += "duty unavailable" }
    if ($null -ne $round.FrameSeconds -and $round.FrameSeconds -lt ($Minutes * 60 * 0.9)) {
        $round.Errors += "frame window shorter than expected"
    }

    Write-Log ("round " + $RoundId + " done: " + (Format-Num $round.Seconds "{0:N1}") + "s mAh=" + (Format-Num $round.Mah "{0:N3}") + " cpu=" + (Format-Num $round.CpuMs "{0:N0}") + "ms wakeups=" + (Format-Num $round.Wakeups "{0}") + " frames=" + (Format-Num $round.Frames "{0:N0}") + " duty=" + (Format-Num $round.Duty "{0:P2}") + " abort=" + $round.Abort)
    Save-Json (Join-Path $dir "round.json") $round
    return $round
}

# ------------------------------------------------------------------ gate + report

function Format-RoundTable {
    param($Rounds)
    $rows = @()
    foreach ($r in $Rounds) {
        $full = "NO"
        if ($r.FullDuty) { $full = "yes" }
        $verdict = "DUTY"
        if ($null -ne $r.Abort) { $verdict = "ABORT" } elseif ($r.FullDuty) { $verdict = "OK" }
        $rows += [pscustomobject]@{
            Round = $r.Id
            Engine = $r.Engine
            Minutes = (Format-Num ($r.Seconds / 60.0) "{0:N2}")
            mAh = (Format-Num $r.Mah "{0:N3}")
            CPU_ms = (Format-Num $r.CpuMs "{0:N0}")
            Wakeups = (Format-Num $r.Wakeups "{0}")
            Frames = (Format-Num $r.Frames "{0:N0}")
            Duty = (Format-Num $r.Duty "{0:P2}")
            Full = $full
            Audio_s = (Format-Num $r.AudioSeconds "{0:N1}")
            LoopPath = $r.ExpectedLoopPath
            Verdict = $verdict
        }
    }
    return (($rows | Format-Table -AutoSize | Out-String -Width 240).TrimEnd())
}

function Write-RunReport {
    param($Rounds, $Gate, $Song, $Env, $Params)
    $run = [ordered]@{
        schema = "battery-gate/1"
        startedAt = $script:StartedAt.ToString("o")
        finishedAt = (Get-Date).ToString("o")
        device = $script:DeviceInfo
        song = @{
            id = $Song.id; title = $Song.title; artist = $Song.artist; path = $Song.path
            durationMs = $Song.durationMs; sampleRate = $Song.sampleRate
            loopStartMs = $Song.loopStartMs; loopEndMs = $Song.loopEndMs
        }
        params = $Params
        environment = $Env
        rounds = $Rounds
        gate = $Gate
    }
    Save-Json (Join-Path $script:RunDir "run.json") $run
    $table = Format-RoundTable $Rounds
    $lines = @()
    $lines += "battery-gate summary"
    $lines += ("device=" + $script:DeviceInfo.serial + " model=" + $script:DeviceInfo.model + " android=" + $script:DeviceInfo.release + " sdk=" + $script:DeviceInfo.sdk)
    $lines += ("song=" + $Song.id + " " + $Song.title + " window=" + $Song.loopStartMs + ".." + $Song.loopEndMs + "ms sr=" + $Song.sampleRate)
    $lines += ("minutes=" + $Params.Minutes + " volume=" + $Params.Volume + " order=" + $Params.EngineOrder + " started=" + $script:StartedAt.ToString("o"))
    $lines += ""
    $lines += $table
    $lines += ""
    if ($null -ne $Gate.Ratio) { $lines += ("Source/M3 mAh ratio = " + (Format-Num $Gate.Ratio "{0:N4}") + " (limit " + (Format-Num $Gate.Limit "{0:N2}") + ")") }
    if ($Gate.Reasons.Count -gt 0) {
        $lines += "Reasons:"
        foreach ($rr in $Gate.Reasons) { $lines += ("  - " + $rr) }
    }
    $lines += ("RESULT: " + $Gate.Verdict + " (exit " + $Gate.ExitCode + ")")
    $text = ($lines -join [Environment]::NewLine)
    Save-Text (Join-Path $script:RunDir "summary.txt") $text
    Write-Host ""
    Write-Host $text
}

function Compute-Gate {
    param($Rounds)
    $source = $null; $m3 = $null
    foreach ($r in $Rounds) {
        if ($r.Engine -eq "source") { $source = $r }
        if ($r.Engine -eq "m3") { $m3 = $r }
    }
    $gate = @{ SourceRound = $null; M3Round = $null; Ratio = $null; Limit = 1.10; Verdict = "ERROR"; ExitCode = 2; Reasons = @() }
    if ($null -eq $source -or $null -eq $m3) {
        $gate.Reasons += "missing source or m3 round"
        return $gate
    }
    $gate.SourceRound = $source.Id
    $gate.M3Round = $m3.Id

    $fail = @()
    $errorReasons = @()
    foreach ($r in @($source, $m3)) {
        if ($null -ne $r.Abort) { $fail += ("round " + $r.Id + " aborted: " + $r.Abort) }
        if ($r.FullDuty -ne $true) { $fail += ("round " + $r.Id + " output duty " + (Format-Num $r.Duty "{0:P2}") + " < 98%") }
        if ($null -eq $r.Frames) { $errorReasons += ("round " + $r.Id + ": output frames unavailable") }
        if ($null -eq $r.SampleRate -or $r.SampleRate -lt 8000) { $errorReasons += ("round " + $r.Id + ": sampleRate invalid") }
    }
    if ($null -eq $source.Mah) { $errorReasons += "source round mAh unavailable" }
    if ($null -eq $m3.Mah) { $errorReasons += "m3 round mAh unavailable" }
    if ($null -ne $source.Mah -and $null -ne $m3.Mah) {
        if ([double]$m3.Mah -le 0.0) {
            $errorReasons += "m3 mAh is zero (batterystats reset/attribution failed)"
        } else {
            $gate.Ratio = [math]::Round(([double]$source.Mah / [double]$m3.Mah), 4)
            if ($gate.Ratio -gt $gate.Limit) {
                $fail += ("source mAh " + (Format-Num $source.Mah "{0:N3}") + " > " + (Format-Num $m3.Mah "{0:N3}") + " x 1.10 (ratio " + (Format-Num $gate.Ratio "{0:N4}") + ")")
            }
            if (([double]$source.Mah + [double]$m3.Mah) -lt 2.0) {
                $gate.Reasons += "WARN low signal: combined mAh < 2; consider a longer run"
            }
        }
    }
    if ($errorReasons.Count -gt 0) {
        $gate.Verdict = "ERROR"
        $gate.ExitCode = 2
        $gate.Reasons += $errorReasons
        return $gate
    }
    if ($fail.Count -gt 0) {
        $gate.Verdict = "FAIL"
        $gate.ExitCode = 1
        $gate.Reasons += $fail
        return $gate
    }
    $gate.Verdict = "PASS"
    $gate.ExitCode = 0
    return $gate
}

# ------------------------------------------------------------------ self test

function Invoke-BatteryGateSelfTest {
    function Assert-Equal {
        param([string]$Name, $Actual, $Expected, [double]$Tolerance = 0.0)
        $ok = $false
        if ($null -eq $Actual -and $null -eq $Expected) { $ok = $true }
        elseif ($null -ne $Actual -and $null -ne $Expected) {
            if ($Actual -is [double] -or $Expected -is [double]) { $ok = ([math]::Abs([double]$Actual - [double]$Expected) -le $Tolerance) }
            else { $ok = ([string]$Actual -eq [string]$Expected) }
        }
        if (-not $ok) {
            $script:SelfTestFailures++
            Write-Host ("FAIL " + $Name + ": actual=" + $Actual + " expected=" + $Expected) -ForegroundColor Red
        } else {
            Write-Host ("ok   " + $Name)
        }
    }

    Assert-Equal "uid label 10159" (Get-UidLabel 10159) "u0a159"
    Assert-Equal "uid label 10096" (Get-UidLabel 10096) "u0a96"

    $bt = @'
Estimated power use (mAh):
    Capacity: 5000, Computed drain: 123.45, excess: 12.3
    Uid u0a159: 18.42 ( cpu=12.10 audio=4.20 wifi=0.10 )
    Uid u0a248: 2.00 ( cpu=1.00 )
'@
    $p = Parse-BatteryStats $bt 10159
    Assert-Equal "batterystats mAh" $p.Mah 18.42 0.0001
    Assert-Equal "batterystats cpu mAh" $p.CpuMah 12.10 0.0001
    Assert-Equal "batterystats computed drain" $p.ComputedDrain 123.45 0.0001
    $p2 = Parse-BatteryStats "  Uid 10159: 5.5 ( cpu=1.5 )" 10159
    Assert-Equal "batterystats full uid" $p2.Mah 5.5 0.0001

    $wk = Parse-Wakeups ("  Wakeups: 4 alarms, 12 wakeups" + [string][char]10 + "  Wakeups: 1 alarms, 3 wakeups")
    Assert-Equal "wakeups max" $wk.Wakeups 12
    Assert-Equal "alarms max" $wk.Alarms 4
    $wk2 = Parse-Wakeups "  Alarms: 7, Wakeups: 9"
    Assert-Equal "alarms/wakeups combined" $wk2.Wakeups 9

    $log = @'
11-21 10:00:01.000  1234  1234 D LoopPerf: state=REPLAYING pumpWrites=123456 calls=1
11-21 10:00:02.000  1234  1234 D LoopPerf: state=REPLAYING pumpWrites=171456 calls=2
11-21 10:00:11.000  1234  1234 D LoopPerf: state=REPLAYING pumpWrites=603456 calls=3
'@
    $rows = Parse-LogcatField $log "pumpWrites"
    Assert-Equal "logcat rows" $rows.Count 3
    Assert-Equal "logcat first" $rows[0].Value 123456
    Assert-Equal "logcat last" $rows[$rows.Count - 1].Value 603456
    $span = Get-TimeSpanSeconds $rows[0].Ts $rows[$rows.Count - 1].Ts
    Assert-Equal "logcat span" $span 10.0 0.001

    $ps = "12345 (com.zjr.hesimusic) S 1 1 0 0 -1 4194560 123 0 0 0 100 50 0 0 20 0 1 0 999 0 0"
    $st = Parse-ProcStat $ps
    Assert-Equal "proc utime" $st.Utime 100
    Assert-Equal "proc stime" $st.Stime 50
    Assert-Equal "proc ticks" $st.Ticks 150

    Assert-Equal "ps time mm:ss" (Convert-PsTimeToSeconds "01:23") 83.0 0.001
    Assert-Equal "ps time hh:mm:ss" (Convert-PsTimeToSeconds "01:01:23") 3683.0 0.001
    Assert-Equal "ps time dd-hh:mm:ss" (Convert-PsTimeToSeconds "1-02:03:04") 93784.0 0.001

    Assert-Equal "battery level" (Parse-BatteryLevel ("  level: 83" + [string][char]10 + "  scale: 100")) 83.0 0.001
    $pw = Get-PowerSnapshot ("mWakefulness=Asleep" + [string][char]10 + "Display Power: state=OFF")
    Assert-Equal "wakefulness" $pw.Wakefulness "Asleep"
    Assert-Equal "display" $pw.Display "OFF"
    Assert-Equal "volume get" (Parse-MusicVolume "volume is 8") 8
    Assert-Equal "volume dumpsys" (Parse-MusicVolume ("- STREAM_MUSIC:" + [string][char]10 + "   Muted: false" + [string][char]10 + "   Min: 0, Max: 15, Current: 8 (speaker): 8")) 8

    $af = Parse-AudioFlinger ("Output thread 0x1:" + [string][char]10 + "  Tracks of which 1 are active" + [string][char]10 + "    1  21  yes  16857  16873  1  2  14144  1920  80  1.0  1  0") 16857
    Assert-Equal "audioflinger active" $af.ActiveTracks 1
    Assert-Equal "audioflinger our pid" $af.OurPidActiveTracks 1

    $as = Parse-AudioService ("Audio playback (lastly played comes first)" + [string][char]10 + "  piid:1 state:started com.zjr.hesimusic")
    Assert-Equal "audio service started" $as.ActivePlayers 1
    Assert-Equal "audio service package" $as.PackageMentioned $true

    $mkRound = {
        param($id, $engine, $mah, $duty)
        return @{
            Id = $id; Engine = $engine; Mah = $mah; Duty = $duty; FullDuty = ($duty -ge 0.98)
            Frames = 28800000; SampleRate = 48000; Abort = $null; ExpectedLoopPath = $(if ($engine -eq 'm3') { 'm3' } else { 'source' })
        }
    }
    $gate = Compute-Gate @((& $mkRound 'A' 'source' 10.0 1.0), (& $mkRound 'B' 'm3' 10.0 1.0))
    Assert-Equal "gate pass verdict" $gate.Verdict "PASS"
    Assert-Equal "gate pass exit" $gate.ExitCode 0
    $gate2 = Compute-Gate @((& $mkRound 'A' 'source' 11.1 1.0), (& $mkRound 'B' 'm3' 10.0 1.0))
    Assert-Equal "gate ratio fail verdict" $gate2.Verdict "FAIL"
    Assert-Equal "gate ratio fail ratio" $gate2.Ratio 1.11 0.0001
    $gate3 = Compute-Gate @((& $mkRound 'A' 'source' 10.0 0.97), (& $mkRound 'B' 'm3' 10.0 1.0))
    Assert-Equal "gate duty fail verdict" $gate3.Verdict "FAIL"
    $gate4 = Compute-Gate @((& $mkRound 'A' 'source' 10.0 1.0), (& $mkRound 'B' 'm3' 0.0 1.0))
    Assert-Equal "gate zero m3 verdict" $gate4.Verdict "ERROR"
    Assert-Equal "gate zero m3 exit" $gate4.ExitCode 2

    if ($script:SelfTestFailures -gt 0) {
        Write-Host ("SELFTEST FAIL (" + $script:SelfTestFailures + ")") -ForegroundColor Red
        exit 1
    }
    Write-Host "SELFTEST PASS"
    exit 0
}

# ------------------------------------------------------------------ main

function Initialize-Device {
    $script:Adb = Resolve-Adb
    if ($Device -ne "") { $script:Serial = $Device }
    Select-Device
    if ($script:Serial -ne "") { $script:AdbArgs = @("-s", $script:Serial) }
    Ensure-Forward
    $s = Get-State -Retries 0
    if ($null -eq $s) {
        Wake-Screen
        Start-App | Out-Null
    }
    Wait-Api -TimeoutSec 60 | Out-Null
    $script:ClkTck = Get-ClkTck
    $model = (Shell-Text "getprop ro.product.model").Trim()
    $release = (Shell-Text "getprop ro.build.version.release").Trim()
    $sdk = (Shell-Text "getprop ro.build.version.sdk").Trim()
    $script:DeviceInfo = @{ serial = $script:Serial; model = $model; release = $release; sdk = $sdk }
    Write-Log ("device=" + $script:Serial + " model=" + $model + " android=" + $release + " sdk=" + $sdk + " CLK_TCK=" + $script:ClkTck)
}

function Capture-Environment {
    $env = @{
        WifiBefore = (Get-Setting "global" "wifi_on")
        DataBefore = (Get-Setting "global" "mobile_data")
        VolumeBefore = (Get-MusicVolume)
        StayOnBefore = (Get-Setting "global" "stay_on_while_plugged_in")
        BatteryLevel = (Get-BatteryLevel)
        ScreenBefore = ((Get-PowerState).Wakefulness)
    }
    $script:Env = $env
    $script:EnvCaptured = $true
    return $env
}

function Prepare-Environment {
    param([switch]$SkipAppStop)
    if (-not $SkipAppStop) {
        $n = Stop-OtherApps
        Write-Log ("force-stopped third-party apps: " + $n)
    }
    $wifi = Invoke-Adb -CmdArgs @("shell", "svc wifi disable") -AllowFail
    $data = Invoke-Adb -CmdArgs @("shell", "svc data disable") -AllowFail
    Write-Log ("network off: wifi exit=" + $wifi.Code + " data exit=" + $data.Code + " (wifi_on now " + (Get-Setting "global" "wifi_on") + ")")
    Invoke-Adb -CmdArgs @("shell", "settings put global stay_on_while_plugged_in 0") -AllowFail | Out-Null
    Start-Sleep -Milliseconds 500
    Wake-Screen
}

function Restore-Environment {
    if (-not $script:EnvCaptured) { return }
    try {
        if ($null -ne $script:Env.VolumeBefore) {
            Invoke-Adb -CmdArgs @("shell", "media volume --stream 3 --set $($script:Env.VolumeBefore)") -AllowFail | Out-Null
        }
        if ($script:Env.WifiBefore -eq "1") { Invoke-Adb -CmdArgs @("shell", "svc wifi enable") -AllowFail | Out-Null }
        if ($script:Env.WifiBefore -eq "0") { Invoke-Adb -CmdArgs @("shell", "svc wifi disable") -AllowFail | Out-Null }
        if ($script:Env.DataBefore -eq "1") { Invoke-Adb -CmdArgs @("shell", "svc data enable") -AllowFail | Out-Null }
        if ($script:Env.DataBefore -eq "0") { Invoke-Adb -CmdArgs @("shell", "svc data disable") -AllowFail | Out-Null }
        if ($script:Env.StayOnBefore -eq "null" -or $script:Env.StayOnBefore -eq "") {
            Invoke-Adb -CmdArgs @("shell", "settings delete global stay_on_while_plugged_in") -AllowFail | Out-Null
        } else {
            Invoke-Adb -CmdArgs @("shell", "settings put global stay_on_while_plugged_in $($script:Env.StayOnBefore)") -AllowFail | Out-Null
        }
        try { Pause-Playback | Out-Null } catch { }
        if (-not $LeaveScreenOff) { Wake-Screen }
        Write-Log "environment restored (network, volume, stay-awake, screen)"
    } catch {
        Write-Log ("WARN restore failed: " + $_.Exception.Message) "Yellow"
    }
}

function Main {
    $script:StartedAt = Get-Date
    $engines = @()
    foreach ($e in ($EngineOrder -split ',')) {
        $x = $e.Trim().ToLowerInvariant()
        if ($x -ne "") { $engines += $x }
    }
    if ($engines.Count -ne 2) { throw "-EngineOrder must contain exactly two engines, e.g. source,m3" }
    foreach ($e in $engines) { if ($e -ne "source" -and $e -ne "m3") { throw ("unknown engine in -EngineOrder: " + $e) } }
    if ($engines[0] -eq $engines[1]) { throw "-EngineOrder must contain source and m3" }
    if ($Minutes -lt 1) { throw "-Minutes must be >= 1" }
    if ($Volume -lt 0 -or $Volume -gt 100) { throw "-Volume out of range" }
    if ($Ams -lt 0) { throw "-Ams must be >= 0" }
    if ($Ams -eq 0 -and $Bms -ne 0) { throw "-Bms requires -Ams > 0" }
    if ($Ams -gt 0 -and $SongId -le 0) { throw "-Ams/-Bms require an explicit -SongId" }

    $root = Split-Path -Parent $PSScriptRoot
    if ($OutDir -eq "") {
        $OutDir = Join-Path (Join-Path $root "e2e-artifacts\battery") (Get-Date -Format "yyyyMMdd-HHmmss")
    } elseif (-not [System.IO.Path]::IsPathRooted($OutDir)) {
        $OutDir = Join-Path $root $OutDir
    }
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $script:RunDir = $OutDir
    $script:LogPath = Join-Path $OutDir "run.log"
    Write-Log ("artifacts: " + $OutDir)

    Initialize-Device
    $envState = Capture-Environment
    Save-Json (Join-Path $OutDir "env-before.json") $envState
    Prepare-Environment -SkipAppStop:$SkipAppStop

    Ensure-Api | Out-Null
    $appPid = Get-AppPid
    if ($appPid -le 0) { throw "app process not running" }
    $uid = Get-AppUid
    Write-Log ("app uid=" + $uid + " (" + (Get-UidLabel $uid) + ") pid=" + $appPid)

    $song = Select-Song $SongId
    Write-Log ("song: id=" + $song.id + " title=" + $song.title + " path=" + $song.path + " durationMs=" + $song.durationMs)
    $song = Ensure-LoopConfirmed ([long]$song.id) $Ams $Bms
    Write-Log ("loop confirmed: window=" + $song.loopStartMs + ".." + $song.loopEndMs + "ms sr=" + $song.sampleRate + " samples=" + $song.loopStartSamples + ".." + $song.loopEndSamples)
    $songId = [long]$song.id
    $sampleRate = [int]$song.sampleRate

    Ensure-ScreenOff | Out-Null

    $params = @{
        Device = $script:Serial; SongId = $songId; Ams = $Ams; Bms = $Bms; Minutes = $Minutes
        Volume = $Volume; EngineOrder = $EngineOrder; WarmupSec = $WarmupSec; SampleSec = $SampleSec
        RingTimeoutSec = $RingTimeoutSec
    }
    $rounds = @()
    $index = 0
    foreach ($engine in $engines) {
        $index++
        $roundId = [string][char]([int][char]'A' + $index - 1)
        $round = Invoke-BatteryRound -RoundId $roundId -Engine $engine -Id $songId -Ams $Ams -Bms $Bms -SampleRate $sampleRate -Uid $uid -Minutes $Minutes -Volume $Volume -WarmupSec $WarmupSec -SampleSec $SampleSec -RingTimeoutSec $RingTimeoutSec
        $rounds += $round
    }

    $gate = Compute-Gate $rounds
    Write-RunReport $rounds $gate $song $envState $params
    foreach ($r in $rounds) {
        if ($r.Errors.Count -gt 0) {
            foreach ($e in $r.Errors) { Write-Log ("round " + $r.Id + " note: " + $e) "Yellow" }
        }
    }
    return $gate.ExitCode
}

if ($SelfTest) { Invoke-BatteryGateSelfTest }

$exit = 2
try {
    $exit = Main
} catch {
    Write-Log ("FATAL: " + $_.Exception.Message) "Red"
    Write-Log ($_.ScriptStackTrace) "DarkGray"
    if ($script:RunDir -ne "" -and (Test-Path $script:RunDir)) {
        Save-Text (Join-Path $script:RunDir "fatal.txt") ($_.Exception.ToString() + [string][char]10 + $_.ScriptStackTrace)
        try {
            $gate = @{ Verdict = "ERROR"; ExitCode = 2; Reasons = @($_.Exception.Message); Ratio = $null; Limit = 1.10 }
            Save-Json (Join-Path $script:RunDir "gate-fatal.json") $gate
            $state = Get-State -Retries 0
            if ($null -ne $state) { Save-Json (Join-Path $script:RunDir "state-fatal.json") $state }
        } catch { }
    }
    $exit = 2
} finally {
    try { Restore-Environment } catch { }
}
exit $exit
