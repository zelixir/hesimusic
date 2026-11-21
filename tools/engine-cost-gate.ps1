#requires -version 5.1
<#
.SYNOPSIS
  Engine cost gate: 10-minute continuous loop playback, SOURCE (ring band) vs M3' (original SDK loop).
  Primary metric is CPU time, not battery mAh.

.DESCRIPTION
  Two rounds on one device, one song, same loop window:
    A: engine=source
    B: engine=m3
  The screen is kept ON and unlocked. Volume, brightness, Wi-Fi, airplane mode and battery state are
  only READ and recorded; this script never locks/wakes the screen, never changes volume, and never
  freezes/repairs battery state.

  Metrics:
    CPU_ms       /proc/<pid>/stat utime+stime delta (user_ms + sys_ms)
    user_ms      utime delta
    sys_ms       stime delta
    CtxtV        /proc/<pid>/status voluntary_ctxt_switches delta
    CtxtNV       nonvoluntary_ctxt_switches delta
    MinFlt       /proc/<pid>/stat minflt delta
    MajFlt       /proc/<pid>/stat majflt delta
    Frames       SOURCE: /state.invariantProbe.writtenFrames delta
                 M3':   LoopPerf pumpWrites delta (replay output)
    Duty         frames / (sampleRate * measured seconds); full duty >= 0.98
    mAh          optional, read-only batterystats cumulative delta; N/A while charging

  Gate: source CPU_ms <= 1.10 * m3 CPU_ms, and both rounds full duty, and the engine stayed correct.
  Exit codes: 0 PASS, 1 FAIL, 2 setup/measurement ERROR.

.PARAMETER Device
  adb serial. Optional when exactly one device is attached.

.PARAMETER SongId
  Song id. 0 = auto-pick a confirmed-loop mp3.

.PARAMETER Ams
  Loop start in ms. >0 with -Bms forces an explicit confirmed window; requires -SongId.

.PARAMETER Bms
  Loop end in ms.

.PARAMETER Minutes
  Round length (default 10). Two rounds plus setup ~25-30 minutes.

.PARAMETER EngineOrder
  Two comma separated engines: source,m3 (default) or m3,source.

.PARAMETER OutDir
  Artifact directory. Default: e2e-artifacts/engine-cost/<timestamp>.

.PARAMETER WarmupSec
  Steady playback before the CPU baseline (default 20).

.PARAMETER SampleSec
  /state sampling period during a round (default 30).

.PARAMETER RingTimeoutSec
  Max wait for M3' replay/ring or SOURCE first wrap (default 240).

.PARAMETER SkipAnalyze
  Never run /analyze; fail if the song has no confirmed loop.

.PARAMETER SkipAppStop
  Do not force-stop other third-party apps.

.PARAMETER SelfTest
  Run parser/gate self-tests without touching a device.

.NOTES
  - USB adb is required. The script does NOT disable Wi-Fi/data or change airplane mode.
  - The device must already be unlocked and the screen ON. The script only asserts that state.
  - The screen must already be ON/unlocked. The script asserts screen_off_timeout is long enough for the run and never changes it.
  - It never sends KEYCODE_SLEEP/WAKEUP and never changes volume. It sends KEYCODE_F1 (unmapped no-op)
    every 4 minutes only to refresh the screen-off timer.
  - It uses cmd media_session dispatch pause at the end of each round (a media control, not a lock key).
  - batterystats is read-only and per-UID mAh may be absent while USB charging; N/A is allowed.
#>
[CmdletBinding()]
param(
    [string]$Device = "",
    [long]$SongId = 0,
    [int]$Ams = 0,
    [int]$Bms = 0,
    [int]$Minutes = 10,
    [string]$EngineOrder = "source,m3",
    [string]$OutDir = "",
    [int]$WarmupSec = 20,
    [int]$SampleSec = 30,
    [int]$RingTimeoutSec = 240,
    [int]$SetupTimeoutSec = 90,
    [switch]$SkipAnalyze,
    [switch]$SkipAppStop,
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
$script:CurrentRoundDir = ""
$script:LogPath = ""
$script:ClkTck = 100
$script:ScreenTimeoutMs = $null
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
    return ($s -replace ([string][char]0), "")
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
    try { Save-Text $Path ($Object | ConvertTo-Json -Depth 16) } catch { Write-Log ("WARN json " + $Path + ": " + $_.Exception.Message) "Yellow" }
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
    if ($code -ne 0 -and -not $AllowFail) { throw ("adb " + ($CmdArgs -join " ") + " failed (exit " + $code + "): " + $text) }
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
    throw "adb not found"
}

function Select-Device {
    $r = Invoke-Adb -CmdArgs @("devices") -AllowFail
    if ($r.Code -ne 0) { throw ("adb devices failed: " + $r.Text) }
    $serials = @()
    foreach ($line in $r.Text.Split([char]10)) { if ($line -match '^(\S+)\s+device\s*$') { $serials += $Matches[1] } }
    if ($script:Serial -ne "") {
        if (-not ($serials -contains $script:Serial)) { throw ("device not authorized/attached: " + $script:Serial + " (devices: " + ($serials -join ", ") + ")") }
        return
    }
    if ($serials.Count -eq 1) { $script:Serial = $serials[0]; return }
    if ($serials.Count -eq 0) { throw "no authorized adb device" }
    throw ("multiple devices; pass -Device: " + ($serials -join ", "))
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
    param([string]$Method = "GET", [string]$Path = "/state", $Body = $null, [int]$TimeoutSec = 30, [int]$Retries = 2)
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
                try { Invoke-Adb -CmdArgs @("forward", "--remove", "tcp:$($script:LocalPort)") -AllowFail | Out-Null; Ensure-Forward } catch { }
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
    throw ("debug API did not come up: " + $lastError)
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
    throw ("player controller did not connect (connected=" + (Get-P $last "playback.connected") + ")")
}

# ------------------------------------------------------------------ CPU/process parsers

function Parse-ProcStat {
    param([string]$Text)
    $t = Normalize-Text $Text
    $line = $null
    foreach ($l in $t.Split([char]10)) { if ($l -match '^\s*\d+\s+\(') { $line = $l; break } }
    if ($null -eq $line) { return $null }
    $close = $line.LastIndexOf(')')
    if ($close -lt 0) { return $null }
    $f = $line.Substring($close + 1).Trim() -split '\s+'
    if ($f.Count -lt 13) { return $null }
    $utime = Convert-LongValue $f[11]
    $stime = Convert-LongValue $f[12]
    if ($null -eq $utime -or $null -eq $stime) { return $null }
    return @{
        UtSt = ($utime + $stime); Utime = $utime; Stime = $stime
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

function Convert-PsTimeToSeconds {
    param([string]$Text)
    if ($null -eq $Text) { return $null }
    $s = $Text.Trim()
    if ($s -eq "") { return $null }
    $day = 0.0
    $dash = $s.IndexOf('-')
    if ($dash -gt 0) {
        $dayText = $s.Substring(0, $dash)
        if (-not [double]::TryParse($dayText, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$day)) { return $null }
        $s = $s.Substring($dash + 1)
    }
    $parts = @()
    foreach ($p in ($s -split ':')) {
        $v = 0.0
        if (-not [double]::TryParse($p, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$v)) { return $null }
        $parts += $v
    }
    if ($parts.Count -lt 2 -or $parts.Count -gt 3) { return $null }
    if ($parts.Count -eq 3) { return ($day * 86400.0 + $parts[0] * 3600.0 + $parts[1] * 60.0 + $parts[2]) }
    return ($day * 86400.0 + $parts[0] * 60.0 + $parts[1])
}

function Get-CpuSnapshot {
    param([int]$AppPid)
    $res = @{
        Ticks = $null; Utime = $null; Stime = $null; MinFlt = $null; MajFlt = $null
        CtxtVol = $null; CtxtNonvol = $null; VmRssKb = $null
        Seconds = $null; Method = "none"; Raw = ""
    }
    if ($AppPid -le 0) { return $res }
    $stat = Invoke-Adb -CmdArgs @("shell", "cat /proc/$AppPid/stat") -AllowFail
    if ($stat.Code -eq 0) {
        $p = Parse-ProcStat $stat.Text
        if ($null -ne $p) {
            $res.Ticks = $p.UtSt; $res.Utime = $p.Utime; $res.Stime = $p.Stime
            $res.MinFlt = $p.MinFlt; $res.MajFlt = $p.MajFlt
            $res.Method = "proc-stat"
            $res.Raw = $stat.Text
        }
    }
    $status = Invoke-Adb -CmdArgs @("shell", "cat /proc/$AppPid/status") -AllowFail
    if ($status.Code -eq 0) {
        $ps = Parse-ProcStatus $status.Text
        $res.CtxtVol = $ps.CtxtVol; $res.CtxtNonvol = $ps.CtxtNonvol; $res.VmRssKb = $ps.VmRssKb
        if ($res.Raw -ne "") { $res.Raw += [string][char]10 }
        $res.Raw += $status.Text
    }
    if ($res.Method -eq "none") {
        $r2 = Invoke-Adb -CmdArgs @("shell", "ps -p $AppPid -o PID,TIME,NAME") -AllowFail
        if ($r2.Code -eq 0) {
            foreach ($line in ($r2.Text.Split([char]10))) {
                if ($line -match ('^\s*' + $AppPid + '\s+(\S+)')) {
                    $sec = Convert-PsTimeToSeconds $Matches[1]
                    if ($null -ne $sec) { $res.Seconds = $sec; $res.Method = "ps-time"; $res.Raw = $line; break }
                }
            }
        }
    }
    return $res
}

# ------------------------------------------------------------------ misc parsers

function Parse-MusicVolume {
    param([string]$Text)
    $t = Normalize-Text $Text
    $m = [regex]::Match($t, '(?im)\bvolume is\s*(\d+)')
    if ($m.Success) { return [int]$m.Groups[1].Value }
    return $null
}

function Get-MusicVolume {
    $r = Invoke-Adb -CmdArgs @("shell", "cmd media_session volume --stream 3 --get") -AllowFail
    if ($r.Code -eq 0) { return (Parse-MusicVolume $r.Text) }
    return $null
}

function Parse-BatteryLevel {
    param([string]$Text)
    $t = Normalize-Text $Text
    $m = [regex]::Match($t, '(?im)^\s*level:\s*(\d+)')
    if (-not $m.Success) { return $null }
    $level = [double]$m.Groups[1].Value
    $ms = [regex]::Match($t, '(?im)^\s*scale:\s*(\d+)')
    if ($ms.Success -and [double]$ms.Groups[1].Value -gt 0) { return (100.0 * $level / [double]$ms.Groups[1].Value) }
    return $level
}

function Get-BatteryLevel {
    $r = Invoke-Adb -CmdArgs @("shell", "dumpsys battery") -AllowFail
    if ($r.Code -ne 0) { return $null }
    return (Parse-BatteryLevel $r.Text)
}

function Get-PowerSnapshot {
    param([string]$Text)
    $t = Normalize-Text $Text
    $wake = $null
    $m = [regex]::Match($t, '(?im)mWakefulness=(\w+)')
    if ($m.Success) { $wake = $m.Groups[1].Value }
    $display = $null
    $md = [regex]::Match($t, '(?im)Display Power:\s*state=(\w+)')
    if ($md.Success) { $display = $md.Groups[1].Value }
    return @{ Wakefulness = $wake; Display = $display }
}

function Get-PowerState {
    $r = Invoke-Adb -CmdArgs @("shell", "dumpsys power") -AllowFail
    if ($r.Code -ne 0) { return @{ Wakefulness = $null; Display = $null; Raw = "" } }
    $p = Get-PowerSnapshot $r.Text
    $p.Raw = $r.Text
    return $p
}

function Get-ScreenBrightness {
    $v = (Get-Setting "system" "screen_brightness")
    $mode = (Get-Setting "system" "screen_brightness_mode")
    return @{ Value = $v; Mode = $mode }
}

function Get-ScreenTimeoutMs {
    $v = Get-Setting "system" "screen_off_timeout"
    $n = Convert-LongValue $v
    if ($null -eq $n) { return $null }
    return [int]$n
}

function Keep-ScreenActive {
    # KEYCODE_F1 is an unmapped no-op here; it still counts as user activity and resets the
    # screen-off timer. It is NOT a sleep/wake/volume key and does not change any setting.
    Invoke-Adb -CmdArgs @("shell", "input keyevent KEYCODE_F1") -AllowFail | Out-Null
}

function Ensure-ScreenOn {
    $p = Get-PowerState
    if ($p.Wakefulness -in @("Asleep", "Dozing") -or $p.Display -eq "OFF") {
        throw ("screen is off (wakefulness=" + $p.Wakefulness + ", display=" + $p.Display + "); keep the device unlocked and screen ON")
    }
    return $p
}

# ------------------------------------------------------------------ app/engine

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
    throw "cannot resolve app uid"
}

function Get-ClkTck {
    $r = Invoke-Adb -CmdArgs @("shell", "getconf CLK_TCK") -AllowFail
    if ($r.Code -eq 0 -and $r.Text -match '(\d+)') {
        $v = [int]$Matches[1]
        if ($v -gt 0 -and $v -le 10000) { return $v }
    }
    return 100
}

function Start-App {
    $r = Invoke-Adb -CmdArgs @("shell", "am start -W -n $($script:Package)/.MainActivity") -AllowFail
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
    Start-App | Out-Null
    return (Wait-Api -TimeoutSec $SetupTimeoutSec)
}

function Read-EnginePrefs {
    $r = Invoke-Adb -CmdArgs @("shell", "run-as $($script:Package) cat shared_prefs/loop_settings.xml") -AllowFail
    if ($r.Code -ne 0 -or $r.Text -notmatch 'loop_engine') { return $null }
    $engine = $null; $main = $null
    $m1 = [regex]::Match($r.Text, '<string name="loop_engine">([^<]+)</string>')
    if ($m1.Success) { $engine = $m1.Groups[1].Value }
    $m2 = [regex]::Match($r.Text, '<string name="loop_engine_main">([^<]+)</string>')
    if ($m2.Success) { $main = $m2.Groups[1].Value }
    return @{ Engine = $engine; Main = $main }
}

function Set-Engine {
    param([string]$Mode)
    $expectedRaw = if ($Mode -eq "m3") { "m3" } else { "source" }
    Ensure-Api | Out-Null
    $on = ($Mode -eq "source")
    $r = Invoke-Api -Method POST -Path "/debug/source-mode" -Body @{ on = $on } -TimeoutSec 20
    if (-not $r.Ok) { throw ("/debug/source-mode failed: " + $r.Error) }
    $applied = Get-P $r.Data "loopSourceMode"
    Write-Log ("engine switch: " + $Mode + " -> " + $applied)
    if ($Mode -eq "source" -and $applied -ne "SOURCE") { throw ("engine switch mismatch: " + $applied) }
    if ($Mode -eq "m3" -and $applied -ne "M3_PRIME") { throw ("engine switch mismatch: " + $applied) }
    Start-Sleep -Milliseconds 1200
    Stop-App
    Start-App | Out-Null
    Wait-Api -TimeoutSec $SetupTimeoutSec | Out-Null
    $prefs = Read-EnginePrefs
    if ($null -ne $prefs) {
        if ($prefs.Engine -ne $expectedRaw -or $prefs.Main -ne $expectedRaw) {
            throw ("engine preference did not survive restart: " + $prefs.Engine + "/" + $prefs.Main)
        }
    }
    return $applied
}

function Stop-OtherApps {
    $r = Invoke-Adb -CmdArgs @("shell", "cmd package list packages -3") -AllowFail
    if ($r.Code -ne 0) { return 0 }
    $n = 0
    foreach ($line in ($r.Text.Split([char]10))) {
        if ($line -match '^package:(.+)$') {
            $pkg = $Matches[1].Trim()
            if ($pkg -eq "" -or $pkg -eq $script:Package) { continue }
            Invoke-Adb -CmdArgs @("shell", "am force-stop $pkg") -AllowFail | Out-Null
            $n++
        }
    }
    return $n
}

# ------------------------------------------------------------------ song/loop

function Get-Song {
    param([long]$Id)
    $r = Invoke-Api -Method GET -Path ("/song/" + $Id) -TimeoutSec 20
    if (-not $r.Ok) { throw ("GET /song/" + $Id + " failed: " + $r.Error) }
    return $r.Data
}

function Select-Song {
    param([long]$Id)
    if ($Id -gt 0) { return (Get-Song $Id) }
    $r = Invoke-Api -Method GET -Path "/library/songs?limit=200" -TimeoutSec 30
    if (-not $r.Ok) { throw ("GET /library/songs failed: " + $r.Error) }
    $songs = @($r.Data.songs | Where-Object { $null -ne $_ })
    if ($songs.Count -eq 0) { throw "library has no songs" }
    $pick = $null
    foreach ($s in $songs) { if ($s.loopConfirmed -eq $true -and $s.path -like "*.mp3") { $pick = $s; break } }
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
        $anSong = Get-P $a.Data "songId"
        if (($phase -eq "done" -or $phase -eq "failed") -and $null -ne $anSong -and [long]$anSong -eq $Id) { break }
        Write-Host ("    analysis " + $phase + " " + (Format-Num (Get-P $a.Data "progress") "{0:P0}"))
    }
    if ($null -eq $last -or (Get-P $last "phase") -ne "done") { throw ("analysis failed: " + (Get-P $last "message")) }
    return $last
}

function Ensure-LoopConfirmed {
    param([long]$Id, [int]$Ams, [int]$Bms)
    $song = Get-Song $Id
    if ($Ams -gt 0 -and $Bms -le $Ams) { throw "-Bms must be greater than -Ams" }
    if ($Ams -gt 0 -and ($Bms - $Ams) -lt 1000) { throw "-Bms must be at least 1000ms after -Ams" }
    if ($Ams -gt 0 -and $null -ne $song.durationMs -and [long]$song.durationMs -gt 0 -and [long]$Bms -gt [long]$song.durationMs) { throw "-Bms exceeds song duration" }
    $explicit = ($Ams -gt 0)
    if ($explicit) {
        $sr = $song.sampleRate
        if ($null -eq $sr -or [int]$sr -lt 8000) {
            if ($SkipAnalyze) { throw "explicit A/B needs sampleRate" }
            $analysis = Invoke-Analyze $Id
            $sr = Get-P $analysis "sampleRate"
        }
        if ($null -eq $sr -or [int]$sr -lt 8000) { throw "cannot determine sampleRate" }
        $aSm = [long]([math]::Round(([double]$Ams * [double]$sr) / 1000.0))
        $bSm = [long]([math]::Round(([double]$Bms * [double]$sr) / 1000.0))
        Write-Log ("confirm explicit window A=" + $Ams + " B=" + $Bms + " sr=" + $sr)
        $r = Invoke-Api -Method POST -Path "/confirm" -Body @{ songId = $Id; aSamples = $aSm; bSamples = $bSm } -TimeoutSec 30
        if (-not $r.Ok) { throw ("POST /confirm failed: " + $r.Error) }
    } elseif ($song.loopConfirmed -ne $true) {
        if ($SkipAnalyze) { throw "song has no confirmed loop" }
        Invoke-Analyze $Id | Out-Null
        $r = Invoke-Api -Method POST -Path "/confirm" -Body @{ songId = $Id; useCandidate = 0 } -TimeoutSec 30
        if (-not $r.Ok) { throw ("POST /confirm failed: " + $r.Error) }
    }
    $song = Get-Song $Id
    if ($song.loopConfirmed -ne $true) { throw "song still has no confirmed loop" }
    if ($null -eq $song.sampleRate -or [int]$song.sampleRate -lt 8000) { throw "invalid sampleRate" }
    if ($null -eq $song.loopStartMs -or $null -eq $song.loopEndMs -or [int]$song.loopEndMs -le [int]$song.loopStartMs) { throw "invalid loop window" }
    return $song
}

function Start-LoopPlayback {
    param([long]$Id, [string]$ExpectedPath, [int]$Ams)
    $r = $null
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
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
            if ((Get-P $s "playback.playing") -eq $true -and (Get-P $s "loop.engine") -eq "loop" -and
                (Get-P $s "loop.claimed") -eq $true -and (Get-P $s "loop.sourceActive") -eq $true -and
                (Get-P $s "loop.window.songId") -eq $Id -and (Get-P $s "loop.loopPath") -eq $ExpectedPath) { $ok = $true; break }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ok) { throw ("loop did not become active (" + $ExpectedPath + ")") }
    if ($Ams -gt 1500) {
        $seekMs = [math]::Max(0, $Ams - 1200)
        Invoke-Api -Method POST -Path "/player/seek" -Body @{ positionMs = $seekMs } -TimeoutSec 20 | Out-Null
        Start-Sleep -Milliseconds 1200
    }
    Start-Sleep -Seconds 2
    $s = Get-State
    if ((Get-P $s "loop.loopPath") -ne $ExpectedPath) {
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 500
            $s = Get-State -Retries 0
            if ($null -ne $s -and (Get-P $s "loop.loopPath") -eq $ExpectedPath) { break }
        }
        if ((Get-P $s "loop.loopPath") -ne $ExpectedPath) { throw "loopPath left expected value after seek" }
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
            if ((Get-P $s "playback.playing") -ne $true) { throw "playback stopped during steady wait" }
            if ((Get-P $s "loop.loopPath") -ne $ExpectedPath) { throw "loopPath changed during steady wait" }
            if ((Get-P $s "loop.sourceActive") -ne $true) { throw "loop.sourceActive became false during steady wait (claimed without source)" }
            if ((Get-P $s "loop.window.songId") -ne $Id) { throw "loop window lost during steady wait" }
            $pos = Get-P $s "playback.positionMs"
            if ($null -ne $pos) {
                $posL = [long]$pos
                if ($null -ne $lastPos -and $posL -lt ($lastPos - 500)) { $wrapped = $true }
                $lastPos = $posL
            }
            if ((Get-P $s "loop.isRingComplete") -eq $true) { $ring = $true }
            $replay = ((Get-P $s "loop.isReplaying") -eq $true)
            $seam = Get-P $s "loop.seamCount"
            if ($null -ne $seam -and [long]$seam -gt 0) { $replay = $true }
            if ($ExpectedPath -eq "m3" -and ($ring -or $replay)) { return $s }
            if ($ExpectedPath -eq "source" -and $wrapped) { return $s }
        }
        Start-Sleep -Milliseconds 500
    }
    if ($ExpectedPath -eq "m3" -and -not $ring) {
        if ($script:CurrentRoundDir -ne "") {
            try { Save-Json (Join-Path $script:CurrentRoundDir "steady-timeout-state.json") $s } catch { }
            try { Save-Text (Join-Path $script:CurrentRoundDir "steady-timeout-logcat.txt") (Invoke-Adb -CmdArgs @("shell", "logcat -d -v time -s LoopPerf:D") -AllowFail).Text } catch { }
        }
        throw ("M3 replay/ring did not reach steady state within " + $TimeoutSec + "s (state/logcat saved)")
    }
    throw "steady wait failed"
}

function Pause-Playback {
    for ($i = 0; $i -lt 4; $i++) {
        $s = Get-State -Retries 0
        if ($null -ne $s -and (Get-P $s "playback.playing") -ne $true -and (Get-P $s "playback.playWhenReady") -ne $true) { return $true }
        Invoke-Adb -CmdArgs @("shell", "cmd media_session dispatch pause") -AllowFail | Out-Null
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

function Get-SnapshotRow {
    param($State, [datetime]$StartTime)
    return [pscustomobject]@{
        t = [math]::Round(((Get-Date) - $StartTime).TotalSeconds, 1)
        posMs = Get-P $State "playback.positionMs"
        playing = Get-P $State "playback.playing"
        engine = Get-P $State "loop.engine"
        loopPath = Get-P $State "loop.loopPath"
        windowSong = Get-P $State "loop.window.songId"
        ring = Get-P $State "loop.isRingComplete"
        replay = Get-P $State "loop.isReplaying"
        seam = Get-P $State "loop.seamCount"
        frames = Get-P $State "invariantProbe.writtenFrames"
    }
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

function Parse-BatteryMah {
    param([string]$Text, [int]$Uid)
    $t = Normalize-Text $Text
    $labels = @((Get-UidLabel $Uid), ([string]$Uid))
    foreach ($label in ($labels | Select-Object -Unique)) {
        $m = [regex]::Match($t, ('(?im)^\s*Uid\s+' + [regex]::Escape($label) + '\s*:\s*([0-9]+(?:\.[0-9]+)?)'))
        if ($m.Success) { return [double]$m.Groups[1].Value }
    }
    return $null
}

function Get-BatteryMah {
    param([int]$Uid)
    $r = Invoke-Adb -CmdArgs @("shell", "dumpsys batterystats $($script:Package)") -AllowFail
    if ($r.Code -ne 0) { return $null }
    return (Parse-BatteryMah $r.Text $Uid)
}

function Get-AudioFlingerFrames {
    # Sum of top-level output-thread "Frames written" counters (2-space indent). Nested HAL
    # counters are 6-space indented and would double-count. This covers SOURCE and M3' alike,
    # including M3's pass-through periods where LoopPerf pumpWrites does not advance.
    $r = Invoke-Adb -CmdArgs @("shell", "dumpsys media.audio_flinger") -AllowFail
    if ($r.Code -ne 0) { return $null }
    $sum = 0L
    $n = 0
    foreach ($m in [regex]::Matches((Normalize-Text $r.Text), '(?m)^  Frames written:\s*(\d+)')) {
        $v = Convert-LongValue $m.Groups[1].Value
        if ($null -ne $v) { $sum += $v; $n++ }
    }
    if ($n -eq 0) { return $null }
    return $sum
}

# ------------------------------------------------------------------ round

function Invoke-CostRound {
    param([string]$RoundId, [string]$Engine, [long]$Id, [int]$Ams, [int]$Bms, [int]$SampleRate, [int]$Uid, [int]$Minutes, [int]$WarmupSec, [int]$SampleSec, [int]$RingTimeoutSec)
    $dir = Join-Path $script:RunDir ("round-" + $RoundId)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $script:CurrentRoundDir = $dir
    $expectedPath = if ($Engine -eq "m3") { "m3" } else { "source" }
    Write-Log ("=== round " + $RoundId + ": engine=" + $Engine + " (expected loopPath=" + $expectedPath + ") ===") "Cyan"

    if (-not $SkipAppStop) {
        $n = Stop-OtherApps
        Write-Log ("round setup: force-stopped third-party apps: " + $n)
    }

    $round = @{
        Id = $RoundId; Engine = $Engine; ExpectedLoopPath = $expectedPath
        SongId = $Id; SampleRate = $SampleRate; Ams = $Ams; Bms = $Bms
        StartedAt = (Get-Date).ToString("o"); EndedAt = $null
        Seconds = $null; FrameSeconds = $null; Frames = $null; ExpectedFrames = $null; Duty = $null; FullDuty = $false
        CpuMs = $null; UserMs = $null; SysMs = $null; CpuMethod = "none"; DutySampleRate = $SampleRate
        CtxtVol = $null; CtxtNonvol = $null; MinFlt = $null; MajFlt = $null; VmRssKb = $null
        Volume = $null; Brightness = $null; BrightnessMode = $null; Wakefulness = $null; Display = $null
        BatteryLevelStart = $null; BatteryLevelEnd = $null; MahStart = $null; MahEnd = $null; Mah = $null
        Pid = 0; PidAfter = 0; Abort = $null; Errors = @(); Samples = @(); Artifacts = $dir
    }

    Set-Engine $Engine | Out-Null
    Wait-PlayerReady -TimeoutSec $SetupTimeoutSec | Out-Null
    $appPid = Get-AppPid
    if ($appPid -le 0) { throw "app process not running after restart" }
    $round.Pid = $appPid
    Write-Log ("app pid=" + $appPid + " uid=" + $Uid)

    $state = Start-LoopPlayback $Id $expectedPath $Ams
    $win = Get-P $state "loop.window"
    Write-Log ("loop window: song=" + (Get-P $state "loop.window.songId") + " engine=" + (Get-P $state "loop.engine") + " loopPath=" + (Get-P $state "loop.loopPath"))
    $state = Wait-SteadyLoop $Id $expectedPath $RingTimeoutSec
    Keep-ScreenActive
    Write-Log ("steady loop reached (ring=" + (Get-P $state "loop.isRingComplete") + " replay=" + (Get-P $state "loop.isReplaying") + " seam=" + (Get-P $state "loop.seamCount") + ")")

    $round.Volume = Get-MusicVolume
    $br = Get-ScreenBrightness
    $round.Brightness = $br.Value; $round.BrightnessMode = $br.Mode
    $power = Ensure-ScreenOn
    $round.Wakefulness = $power.Wakefulness; $round.Display = $power.Display
    $round.BatteryLevelStart = Get-BatteryLevel
    $round.MahStart = Get-BatteryMah $Uid
    Write-Log ("env: volume=" + (Format-Num $round.Volume "{0}") + " brightness=" + $round.Brightness + " mode=" + $round.BrightnessMode + " wakefulness=" + $round.Wakefulness + " display=" + $round.Display + " battery=" + (Format-Num $round.BatteryLevelStart "{0:N0}%") + " mAh(cum)=" + (Format-Num $round.MahStart "{0:N3}"))
    Save-Text (Join-Path $dir "dumpsys-power.txt") $power.Raw

    if ($WarmupSec -gt 0) { Write-Log ("warmup " + $WarmupSec + "s"); Start-Sleep -Seconds $WarmupSec }
    $s = Get-State
    if ((Get-P $s "playback.playing") -ne $true) { throw "playback stopped during warmup" }

    Invoke-Adb -CmdArgs @("shell", "logcat -c") -AllowFail | Out-Null
    $startTime = Get-Date
    $startState = Get-State
    if ($null -eq $startState -or (Get-P $startState "playback.playing") -ne $true) { throw "playback not running at round start" }
    $startFrames = Get-P $startState "invariantProbe.writtenFrames"
    $afStart = Get-AudioFlingerFrames
    $cpuStart = Get-CpuSnapshot $appPid
    Save-Text (Join-Path $dir "proc-before.txt") $cpuStart.Raw
    Save-Json (Join-Path $dir "cpu-before.json") $cpuStart
    $round.CpuMethod = $cpuStart.Method
    Save-Json (Join-Path $dir "state-start.json") $startState
    Write-Log ("timer start: cpu=" + $cpuStart.Method + " ticks=" + $cpuStart.Ticks + " frames=" + $startFrames)

    $endAt = $startTime.AddSeconds($Minutes * 60)
    $samples = New-Object System.Collections.ArrayList
    $stops = 0
    $stateFailures = 0
    $lastLog = Get-Date
    $lastKeepAlive = Get-Date
    while ((Get-Date) -lt $endAt) {
        $state = Get-State -Retries 0
        if ($null -eq $state) {
            $stateFailures++
            if ($stateFailures -ge 3) { $round.Abort = "state endpoint unavailable"; break }
        } else {
            $stateFailures = 0
            $row = Get-SnapshotRow $state $startTime
            [void]$samples.Add($row)
            if ($row.playing -ne $true) { $stops++; if ($stops -ge 2) { $round.Abort = "playback stopped"; break } } else { $stops = 0 }
            if ($row.loopPath -ne $expectedPath) { $round.Abort = "loopPath changed"; break }
            if ($null -ne $row.windowSong -and [long]$row.windowSong -ne $Id) { $round.Abort = "window song changed"; break }
            $nowPid = Get-AppPidRetry
            if ($nowPid -le 0) { $round.Abort = "app process disappeared"; break }
            if ($nowPid -ne $appPid) { $round.Abort = "app pid changed"; break }
        }
        $elapsed = ((Get-Date) - $startTime).TotalSeconds
        $left = ($Minutes * 60) - $elapsed
        if ($left -le 0) { break }
        if (((Get-Date) - $lastKeepAlive).TotalSeconds -ge 240) {
            $lastKeepAlive = Get-Date
            Keep-ScreenActive
            Write-Host "    screen keepalive (KEYCODE_F1 no-op)"
        }
        if (((Get-Date) - $lastLog).TotalSeconds -ge 30) {
            $lastLog = Get-Date
            $pos = $null; $playingText = "?"
            if ($samples.Count -gt 0) { $pos = $samples[$samples.Count - 1].posMs; $playingText = $samples[$samples.Count - 1].playing }
            Write-Host ("    [" + $RoundId + "/" + $Engine + "] " + [timespan]::FromSeconds($elapsed).ToString("mm\:ss") + " / " + $Minutes + ":00  pos=" + $pos + "ms playing=" + $playingText)
        }
        Start-Sleep -Seconds ([math]::Min($SampleSec, [math]::Max(1.0, $left)))
    }
    $round.Samples = @($samples)

    $endState = Get-State
    $endTime = Get-Date
    $endFrames = $null
    if ($null -ne $endState) { $endFrames = Get-P $endState "invariantProbe.writtenFrames" }
    $afEnd = Get-AudioFlingerFrames
    if ($null -ne $endState -and (Get-P $endState "playback.playing") -eq $true) { Pause-Playback | Out-Null }
    Start-Sleep -Milliseconds 400
    $round.EndedAt = (Get-Date).ToString("o")

    $cpuEnd = Get-CpuSnapshot $appPid
    Save-Text (Join-Path $dir "proc-after.txt") $cpuEnd.Raw
    Save-Json (Join-Path $dir "cpu-after.json") $cpuEnd
    $round.BatteryLevelEnd = Get-BatteryLevel
    $round.MahEnd = Get-BatteryMah $Uid
    $logText = (Invoke-Adb -CmdArgs @("shell", "logcat -d -v time -s LoopPerf:D") -AllowFail).Text
    Save-Text (Join-Path $dir "logcat-loop-perf.txt") $logText
    Save-Json (Join-Path $dir "state-end.json") $endState
    $round.PidAfter = Get-AppPidRetry
    if ($round.PidAfter -le 0) { $round.Abort = "app died at end" }
    elseif ($round.PidAfter -ne $appPid) { $round.Abort = "app pid changed at end" }

    if ($cpuStart.Method -eq "proc-stat" -and $cpuEnd.Method -eq "proc-stat") {
        $round.UserMs = [math]::Round(([double]$cpuEnd.Utime - [double]$cpuStart.Utime) * 1000.0 / [double]$script:ClkTck, 1)
        $round.SysMs = [math]::Round(([double]$cpuEnd.Stime - [double]$cpuStart.Stime) * 1000.0 / [double]$script:ClkTck, 1)
        $round.CpuMs = [math]::Round(([double]$cpuEnd.Ticks - [double]$cpuStart.Ticks) * 1000.0 / [double]$script:ClkTck, 1)
        $round.CpuMethod = "proc-stat"
    } elseif ($null -ne $cpuStart.Seconds -and $null -ne $cpuEnd.Seconds) {
        $round.CpuMs = [math]::Round(([double]$cpuEnd.Seconds - [double]$cpuStart.Seconds) * 1000.0, 0)
        $round.CpuMethod = "ps-time"
    } else {
        $round.Errors += "CPU time unavailable"
    }
    if ($null -ne $cpuStart.CtxtVol -and $null -ne $cpuEnd.CtxtVol) { $round.CtxtVol = [long]$cpuEnd.CtxtVol - [long]$cpuStart.CtxtVol }
    if ($null -ne $cpuStart.CtxtNonvol -and $null -ne $cpuEnd.CtxtNonvol) { $round.CtxtNonvol = [long]$cpuEnd.CtxtNonvol - [long]$cpuStart.CtxtNonvol }
    if ($null -ne $cpuStart.MinFlt -and $null -ne $cpuEnd.MinFlt) { $round.MinFlt = [long]$cpuEnd.MinFlt - [long]$cpuStart.MinFlt }
    if ($null -ne $cpuStart.MajFlt -and $null -ne $cpuEnd.MajFlt) { $round.MajFlt = [long]$cpuEnd.MajFlt - [long]$cpuStart.MajFlt }
    $round.VmRssKb = $cpuEnd.VmRssKb
    if ($null -ne $round.MahStart -and $null -ne $round.MahEnd -and [double]$round.MahEnd -ge [double]$round.MahStart) {
        $round.Mah = [math]::Round([double]$round.MahEnd - [double]$round.MahStart, 3)
    }

    $round.Seconds = [math]::Round(($endTime - $startTime).TotalSeconds, 2)
    if ($Engine -eq "source") {
        if ($null -ne $startFrames -and $null -ne $endFrames) {
            $frames = [double]$endFrames - [double]$startFrames
            if ($frames -ge 0) { $round.Frames = [long]$frames; $round.FrameSeconds = $round.Seconds }
            else { $round.Errors += "SOURCE frame counter went backwards" }
        }
        if ($null -eq $round.Frames) {
            $rows = Parse-LogcatField $logText "writtenFrames"
            if ($rows.Count -ge 2 -and $rows[$rows.Count - 1].Value -ge $rows[0].Value) {
                $round.Frames = $rows[$rows.Count - 1].Value - $rows[0].Value
                $round.FrameSeconds = [math]::Round((Get-TimeSpanSeconds $rows[0].Ts $rows[$rows.Count - 1].Ts), 2)
                $round.Errors += "used tapDuty logcat fallback for frames"
            }
        }
    } else {
        # M3' alternates replay and pass-through (prefix-only prefill in 2.3.5), so pumpWrites
        # does not cover the whole round. Use the AudioFlinger output-thread frame counter.
        if ($null -ne $afStart -and $null -ne $afEnd) {
            $frames = [double]$afEnd - [double]$afStart
            if ($frames -ge 0) {
                $round.Frames = [long]$frames
                $round.FrameSeconds = $round.Seconds
                $round.DutySampleRate = 48000
            } else { $round.Errors += "AudioFlinger frame counter reset" }
        } else { $round.Errors += "AudioFlinger output frames unavailable" }
    }
    if ($null -ne $round.Frames -and $null -ne $round.FrameSeconds -and $round.DutySampleRate -gt 0) {
        $round.ExpectedFrames = [long][math]::Round([double]$round.DutySampleRate * [double]$round.FrameSeconds)
        if ($round.ExpectedFrames -gt 0) {
            $round.Duty = [math]::Round([double]$round.Frames / [double]$round.ExpectedFrames, 4)
            $round.FullDuty = ($round.Duty -ge 0.98)
        }
    }
    if ($null -eq $round.CpuMs) { $round.Errors += "CPU time unavailable" }
    if ($null -eq $round.Frames) { $round.Errors += "output frames unavailable" }
    if ($null -eq $round.Duty) { $round.Errors += "duty unavailable" }

    Write-Log ("round " + $RoundId + " done: " + (Format-Num $round.Seconds "{0:N1}") + "s cpu=" + (Format-Num $round.CpuMs "{0:N0}") + "ms (user=" + (Format-Num $round.UserMs "{0:N0}") + " sys=" + (Format-Num $round.SysMs "{0:N0}") + ") ctxt=" + (Format-Num $round.CtxtVol "{0}") + "/" + (Format-Num $round.CtxtNonvol "{0}") + " flt=" + (Format-Num $round.MinFlt "{0}") + "/" + (Format-Num $round.MajFlt "{0}") + " frames=" + (Format-Num $round.Frames "{0:N0}") + " duty=" + (Format-Num $round.Duty "{0:P2}") + " abort=" + $round.Abort)
    Save-Json (Join-Path $dir "round.json") $round
    return $round
}

# ------------------------------------------------------------------ gate/report

function Format-RoundTable {
    param($Rounds)
    # Manual fixed-width table: Format-Table drops trailing columns in non-interactive hosts,
    # which previously hid Frames/Duty/Full from summary.txt.
    $fmt = '{0,-5} {1,-6} {2,7} {3,9} {4,9} {5,8} {6,7} {7,7} {8,8} {9,7} {10,11} {11,7} {12,5} {13,7} {14,-8} {15,-6}'
    $lines = @()
    $lines += ($fmt -f 'Round','Engine','Minutes','CPU_ms','User_ms','Sys_ms','CtxtV','CtxtNV','MinFlt','MajFlt','Frames','Duty','Full','mAh','LoopPath','Verdict')
    $lines += ($fmt -f '-----','------','-------','------','-------','------','-----','------','------','------','----------','------','-----','------','--------','------')
    foreach ($r in $Rounds) {
        $full = if ($r.FullDuty) { 'yes' } else { 'NO' }
        $verdict = if ($null -ne $r.Abort) { 'ABORT' } elseif ($r.FullDuty) { 'OK' } else { 'DUTY' }
        $mahText = 'N/A'
        if ($null -ne $r.Mah -and [double]$r.Mah -gt 0) { $mahText = ('{0:N3}' -f $r.Mah) }
        $lines += ($fmt -f $r.Id, $r.Engine, (Format-Num ($r.Seconds / 60.0) '{0:N2}'), (Format-Num $r.CpuMs '{0:N0}'), (Format-Num $r.UserMs '{0:N0}'), (Format-Num $r.SysMs '{0:N0}'), (Format-Num $r.CtxtVol '{0}'), (Format-Num $r.CtxtNonvol '{0}'), (Format-Num $r.MinFlt '{0}'), (Format-Num $r.MajFlt '{0}'), (Format-Num $r.Frames '{0:N0}'), (Format-Num $r.Duty '{0:P2}'), $full, $mahText, $r.ExpectedLoopPath, $verdict)
    }
    return ($lines -join [Environment]::NewLine)
}

function Compute-Gate {
    param($Rounds)
    $source = $null; $m3 = $null
    foreach ($r in $Rounds) { if ($r.Engine -eq "source") { $source = $r }; if ($r.Engine -eq "m3") { $m3 = $r } }
    $gate = @{ SourceRound = $null; M3Round = $null; Ratio = $null; Limit = 1.10; Verdict = "ERROR"; ExitCode = 2; Reasons = @(); Warnings = @() }
    if ($null -eq $source -or $null -eq $m3) { $gate.Reasons += "missing source or m3 round"; return $gate }
    $gate.SourceRound = $source.Id; $gate.M3Round = $m3.Id
    $fail = @(); $errors = @()
    foreach ($r in @($source, $m3)) {
        if ($null -ne $r.Abort) { $fail += ("round " + $r.Id + " aborted: " + $r.Abort) }
        if ($r.FullDuty -ne $true) { $fail += ("round " + $r.Id + " duty " + (Format-Num $r.Duty "{0:P2}") + " < 98%") }
        if ($null -eq $r.CpuMs) { $errors += ("round " + $r.Id + ": CPU unavailable") }
        if ($null -eq $r.Frames) { $errors += ("round " + $r.Id + ": frames unavailable") }
    }
    if ($null -ne $source.CpuMs -and $null -ne $m3.CpuMs) {
        if ([double]$m3.CpuMs -le 0) { $errors += "m3 CPU is zero" }
        else {
            $gate.Ratio = [math]::Round([double]$source.CpuMs / [double]$m3.CpuMs, 4)
            if ($gate.Ratio -gt $gate.Limit) { $fail += ("source CPU " + (Format-Num $source.CpuMs "{0:N0}") + "ms > m3 CPU " + (Format-Num $m3.CpuMs "{0:N0}") + "ms x 1.10 (ratio " + (Format-Num $gate.Ratio "{0:N4}") + ")") }
        }
    }
    if ($null -eq $source.Mah -or $null -eq $m3.Mah) { $gate.Warnings += "per-UID mAh N/A (USB charging / read-only); not part of the gate" }
    if ($null -ne $source.Volume -and $null -ne $m3.Volume -and [int]$source.Volume -ne [int]$m3.Volume) { $fail += ("volume changed between rounds: " + $source.Volume + " -> " + $m3.Volume) }
    if ($errors.Count -gt 0) { $gate.Verdict = "ERROR"; $gate.ExitCode = 2; $gate.Reasons += $errors; return $gate }
    if ($fail.Count -gt 0) { $gate.Verdict = "FAIL"; $gate.ExitCode = 1; $gate.Reasons += $fail; return $gate }
    $gate.Verdict = "PASS"; $gate.ExitCode = 0; return $gate
}

function Write-RunReport {
    param($Rounds, $Gate, $Song, $Params)
    $run = [ordered]@{
        schema = "engine-cost-gate/1"
        startedAt = $script:StartedAt.ToString("o"); finishedAt = (Get-Date).ToString("o")
        device = $script:DeviceInfo
        song = @{ id = $Song.id; title = $Song.title; artist = $Song.artist; path = $Song.path; durationMs = $Song.durationMs; sampleRate = $Song.sampleRate; loopStartMs = $Song.loopStartMs; loopEndMs = $Song.loopEndMs }
        params = $Params; rounds = $Rounds; gate = $Gate
    }
    Save-Json (Join-Path $script:RunDir "run.json") $run
    $lines = @()
    $lines += "engine-cost-gate summary (primary metric: CPU time; screen stays on)"
    $lines += ("device=" + $script:DeviceInfo.serial + " model=" + $script:DeviceInfo.model + " android=" + $script:DeviceInfo.release)
    $lines += ("song=" + $Song.id + " " + $Song.title + " window=" + $Song.loopStartMs + ".." + $Song.loopEndMs + "ms sr=" + $Song.sampleRate)
    $lines += ("minutes=" + $Params.Minutes + " order=" + $Params.EngineOrder)
    $lines += ""
    $lines += (Format-RoundTable $Rounds)
    $lines += ""
    if ($null -ne $Gate.Ratio) { $lines += ("Source/M3 CPU ratio = " + (Format-Num $Gate.Ratio "{0:N4}") + " (limit 1.10)") }
    if ($Gate.Reasons.Count -gt 0) { $lines += "Reasons:"; foreach ($r in $Gate.Reasons) { $lines += ("  - " + $r) } }
    if ($Gate.Warnings.Count -gt 0) { $lines += "Warnings:"; foreach ($w in $Gate.Warnings) { $lines += ("  - " + $w) } }
    $lines += ("RESULT: " + $Gate.Verdict + " (exit " + $Gate.ExitCode + ")")
    $text = ($lines -join [Environment]::NewLine)
    Save-Text (Join-Path $script:RunDir "summary.txt") $text
    Write-Host ""
    Write-Host $text
}

# ------------------------------------------------------------------ self test

function Invoke-EngineCostSelfTest {
    function Assert-Equal {
        param([string]$Name, $Actual, $Expected, [double]$Tolerance = 0.0)
        $ok = $false
        if ($null -eq $Actual -and $null -eq $Expected) { $ok = $true }
        elseif ($null -ne $Actual -and $null -ne $Expected) {
            if ($Actual -is [double] -or $Expected -is [double]) { $ok = ([math]::Abs([double]$Actual - [double]$Expected) -le $Tolerance) }
            else { $ok = ([string]$Actual -eq [string]$Expected) }
        }
        if (-not $ok) { $script:SelfTestFailures++; Write-Host ("FAIL " + $Name + ": actual=" + $Actual + " expected=" + $Expected) -ForegroundColor Red }
        else { Write-Host ("ok   " + $Name) }
    }
    $stat = "12345 (com.zjr.hesimusic) S 1 1 0 0 -1 4194560 700 10 20 30 100 50 0 0 20 0 1 0 999 0 0"
    $p = Parse-ProcStat $stat
    Assert-Equal "utime" $p.Utime 100
    Assert-Equal "stime" $p.Stime 50
    Assert-Equal "ticks" $p.UtSt 150
    Assert-Equal "minflt" $p.MinFlt 700
    Assert-Equal "majflt" $p.MajFlt 20
    $ps = Parse-ProcStatus ("Name: com.zjr.hesimusic" + [char]10 + "VmRSS:	12345 kB" + [char]10 + "voluntary_ctxt_switches:	42" + [char]10 + "nonvoluntary_ctxt_switches:	7")
    Assert-Equal "ctxt vol" $ps.CtxtVol 42
    Assert-Equal "ctxt nonvol" $ps.CtxtNonvol 7
    Assert-Equal "rss" $ps.VmRssKb 12345
    Assert-Equal "ps time" (Convert-PsTimeToSeconds "00:01:23") 83.0 0.001
    $log = @'
11-21 10:00:01.000 1234 1234 D LoopPerf: state=REPLAYING pumpWrites=123456 calls=1
11-21 10:00:11.000 1234 1234 D LoopPerf: state=REPLAYING pumpWrites=603456 calls=2
'@
    $rows = Parse-LogcatField $log "pumpWrites"
    Assert-Equal "rows" $rows.Count 2
    Assert-Equal "span" (Get-TimeSpanSeconds $rows[0].Ts $rows[1].Ts) 10.0 0.001
    Assert-Equal "volume" (Parse-MusicVolume "volume is 8") 8
    $pw = Get-PowerSnapshot ("mWakefulness=Awake" + [char]10 + "Display Power: state=ON")
    Assert-Equal "awake" $pw.Wakefulness "Awake"
    Assert-Equal "display" $pw.Display "ON"
    Assert-Equal "mah parse" (Parse-BatteryMah "  UID u0a159: 1.234 ( cpu=1.0 )" 10159) 1.234 0.0001

    $tblRows = @(
        @{ Id = 'A'; Engine = 'source'; Seconds = 600.0; CpuMs = 10000; UserMs = 9000; SysMs = 1000; CtxtVol = 1; CtxtNonvol = 2; MinFlt = 3; MajFlt = 0; Frames = 28800000; Duty = 1.0; FullDuty = $true; Mah = $null; ExpectedLoopPath = 'source'; Abort = $null },
        @{ Id = 'B'; Engine = 'm3'; Seconds = 600.0; CpuMs = 10000; UserMs = 9000; SysMs = 1000; CtxtVol = 1; CtxtNonvol = 2; MinFlt = 3; MajFlt = 0; Frames = 28800000; Duty = 1.0; FullDuty = $true; Mah = 0; ExpectedLoopPath = 'm3'; Abort = $null }
    )
    $tbl = Format-RoundTable $tblRows
    Assert-Equal "table has CPU" ($tbl -match 'CPU_ms') $true
    Assert-Equal "table has frames" ($tbl -match 'Frames') $true
    Assert-Equal "table has duty" ($tbl -match 'Duty') $true
    Assert-Equal "table has N/A mah" ($tbl -match 'N/A') $true

    $mk = {
        param($id, $engine, $cpu, $duty)
        return @{ Id = $id; Engine = $engine; CpuMs = $cpu; Duty = $duty; FullDuty = ($duty -ge 0.98); Frames = 28800000; SampleRate = 48000; Abort = $null; Mah = $null; Volume = 8; ExpectedLoopPath = $(if ($engine -eq "m3") { "m3" } else { "source" }) }
    }
    $g = Compute-Gate @((& $mk "A" "source" 10000 1.0), (& $mk "B" "m3" 10000 1.0))
    Assert-Equal "gate pass" $g.Verdict "PASS"
    $g2 = Compute-Gate @((& $mk "A" "source" 11100 1.0), (& $mk "B" "m3" 10000 1.0))
    Assert-Equal "gate cpu fail" $g2.Verdict "FAIL"
    $g3 = Compute-Gate @((& $mk "A" "source" 10000 0.97), (& $mk "B" "m3" 10000 1.0))
    Assert-Equal "gate duty fail" $g3.Verdict "FAIL"
    $r4 = & $mk "B" "m3" 0 1.0
    $g4 = Compute-Gate @((& $mk "A" "source" 10000 1.0), $r4)
    Assert-Equal "gate zero cpu" $g4.Verdict "ERROR"
    if ($script:SelfTestFailures -gt 0) { Write-Host ("SELFTEST FAIL (" + $script:SelfTestFailures + ")") -ForegroundColor Red; exit 1 }
    Write-Host "SELFTEST PASS"; exit 0
}

# ------------------------------------------------------------------ main

function Initialize-Device {
    $script:Adb = Resolve-Adb
    if ($Device -ne "") { $script:Serial = $Device }
    Select-Device
    if ($script:Serial -ne "") { $script:AdbArgs = @("-s", $script:Serial) }
    Ensure-Forward
    $s = Get-State -Retries 0
    if ($null -eq $s) { Start-App | Out-Null }
    Wait-Api -TimeoutSec 60 | Out-Null
    $script:ClkTck = Get-ClkTck
    $script:DeviceInfo = @{ serial = $script:Serial; model = (Shell-Text "getprop ro.product.model").Trim(); release = (Shell-Text "getprop ro.build.version.release").Trim(); sdk = (Shell-Text "getprop ro.build.version.sdk").Trim() }
    Write-Log ("device=" + $script:Serial + " model=" + $script:DeviceInfo.model + " android=" + $script:DeviceInfo.release + " CLK_TCK=" + $script:ClkTck)
}

function Main {
    $script:StartedAt = Get-Date
    $engines = @()
    foreach ($e in ($EngineOrder -split ',')) { $x = $e.Trim().ToLowerInvariant(); if ($x -ne "") { $engines += $x } }
    if ($engines.Count -ne 2 -or $engines[0] -eq $engines[1]) { throw "-EngineOrder must be source,m3 (in any order)" }
    foreach ($e in $engines) { if ($e -ne "source" -and $e -ne "m3") { throw ("unknown engine: " + $e) } }
    if ($Minutes -lt 1) { throw "-Minutes must be >= 1" }
    if ($Ams -lt 0) { throw "-Ams must be >= 0" }
    if ($Ams -eq 0 -and $Bms -ne 0) { throw "-Bms requires -Ams > 0" }
    if ($Ams -gt 0 -and $SongId -le 0) { throw "-Ams/-Bms require -SongId" }

    $root = Split-Path -Parent $PSScriptRoot
    if ($OutDir -eq "") { $OutDir = Join-Path (Join-Path $root "e2e-artifacts\engine-cost") (Get-Date -Format "yyyyMMdd-HHmmss") }
    elseif (-not [System.IO.Path]::IsPathRooted($OutDir)) { $OutDir = Join-Path $root $OutDir }
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $script:RunDir = $OutDir; $script:LogPath = Join-Path $OutDir "run.log"
    Write-Log ("artifacts: " + $OutDir)

    Initialize-Device
    $power0 = Ensure-ScreenOn
    Keep-ScreenActive
    $script:ScreenTimeoutMs = Get-ScreenTimeoutMs
    $neededMs = ($Minutes * 2 + 8) * 60 * 1000
    if ($null -ne $script:ScreenTimeoutMs -and $script:ScreenTimeoutMs -gt 0 -and $script:ScreenTimeoutMs -lt $neededMs) {
        throw ("screen_off_timeout=" + $script:ScreenTimeoutMs + "ms is shorter than the run (~" + $neededMs + "ms); keep the screen on (longer timeout / manual touch) and retry")
    }
    Write-Log ("screen: wakefulness=" + $power0.Wakefulness + " display=" + $power0.Display + " timeout_ms=" + (Format-Num $script:ScreenTimeoutMs "{0}") + " stay_on=" + (Get-Setting "global" "stay_on_while_plugged_in") + " (read-only, not modified)")

    $envInfo = @{
        WifiOn = (Get-Setting "global" "wifi_on"); Airplane = (Get-Setting "global" "airplane_mode_on")
        Volume = (Get-MusicVolume); Brightness = (Get-ScreenBrightness)
        Battery = (Get-BatteryLevel); Wakefulness = $power0.Wakefulness
        ScreenTimeoutMs = $script:ScreenTimeoutMs; StayOn = (Get-Setting "global" "stay_on_while_plugged_in")
    }
    Save-Json (Join-Path $OutDir "env-before.json") $envInfo
    Write-Log ("env: wifi=" + $envInfo.WifiOn + " airplane=" + $envInfo.Airplane + " volume=" + (Format-Num $envInfo.Volume "{0}") + " brightness=" + $envInfo.Brightness.Value + " mode=" + $envInfo.Brightness.Mode + " battery=" + (Format-Num $envInfo.Battery "{0:N0}%") + " wake=" + $envInfo.Wakefulness)

    if (-not $SkipAppStop) { $n = Stop-OtherApps; Write-Log ("force-stopped third-party apps: " + $n) }
    Ensure-Api | Out-Null
    $appPid = Get-AppPid
    if ($appPid -le 0) { throw "app not running" }
    $uid = Get-AppUid
    $song = Select-Song $SongId
    Write-Log ("song: id=" + $song.id + " title=" + $song.title + " durationMs=" + $song.durationMs)
    $song = Ensure-LoopConfirmed ([long]$song.id) $Ams $Bms
    Write-Log ("loop confirmed: window=" + $song.loopStartMs + ".." + $song.loopEndMs + "ms sr=" + $song.sampleRate)
    $songId = [long]$song.id; $sampleRate = [int]$song.sampleRate

    $params = @{ Device = $script:Serial; SongId = $songId; Ams = $Ams; Bms = $Bms; Minutes = $Minutes; EngineOrder = $EngineOrder; WarmupSec = $WarmupSec; SampleSec = $SampleSec; RingTimeoutSec = $RingTimeoutSec }
    $rounds = @(); $index = 0
    foreach ($engine in $engines) {
        $index++
        $roundId = [string][char]([int][char]'A' + $index - 1)
        $rounds += Invoke-CostRound -RoundId $roundId -Engine $engine -Id $songId -Ams $Ams -Bms $Bms -SampleRate $sampleRate -Uid $uid -Minutes $Minutes -WarmupSec $WarmupSec -SampleSec $SampleSec -RingTimeoutSec $RingTimeoutSec
    }
    $gate = Compute-Gate $rounds
    Write-RunReport $rounds $gate $song $params
    foreach ($r in $rounds) { if ($r.Errors.Count -gt 0) { foreach ($e in $r.Errors) { Write-Log ("round " + $r.Id + " note: " + $e) "Yellow" } } }
    return $gate.ExitCode
}

if ($SelfTest) { Invoke-EngineCostSelfTest }

$exit = 2
try { $exit = Main }
catch {
    Write-Log ("FATAL: " + $_.Exception.Message) "Red"
    Write-Log ($_.ScriptStackTrace) "DarkGray"
    if ($script:RunDir -ne "" -and (Test-Path $script:RunDir)) {
        Save-Text (Join-Path $script:RunDir "fatal.txt") ($_.Exception.ToString() + [string][char]10 + $_.ScriptStackTrace)
    }
    $exit = 2
} finally {
    try { Pause-Playback | Out-Null } catch { }
    try { Write-Log "playback paused; system settings untouched" } catch { }
}
exit $exit
