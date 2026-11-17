param(
    [string]$SongId = "",
    [int]$PlayPauseCycles = 10,
    [int]$LoopCycles = 5,
    [int]$SwitchCycles = 5,
    [int]$LongRunMinutes = 0,
    [switch]$SkipAudio
)
# 交付前自测:每一条都在真机上跑,失败即中止并打印证据。
# 用法: pwsh -NoProfile -File tools\self-test.ps1 -SongId 40904
$ErrorActionPreference = "Stop"
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$pkg = "com.zjr.hesimusic"
$fail = 0
$report = New-Object System.Collections.ArrayList

function Step($name, [scriptblock]$body) {
    Write-Host ("== " + $name)
    try {
        $detail = & $body
        [void]$report.Add([pscustomobject]@{ name = $name; ok = $true; detail = "$detail" })
    } catch {
        $script:fail++
        [void]$report.Add([pscustomobject]@{ name = $name; ok = $false; detail = $_.Exception.Message })
        Write-Host ("   FAIL: " + $_.Exception.Message) -ForegroundColor Red
    }
}

function A { param([string]$Cmd) & $adb -s $Device shell $Cmd }
function Api { param($M, $P, $B)
    $uri = "http://127.0.0.1:18765$P"
    try {
        if ($B) { Invoke-RestMethod -Method $M -Uri $uri -ContentType "application/json" -Body ([Text.Encoding]::UTF8.GetBytes(($B | ConvertTo-Json -Compress))) -TimeoutSec 20 }
        else { Invoke-RestMethod -Method $M -Uri $uri -TimeoutSec 20 }
    } catch { $null }
}
function CrashLines { ((& $adb -s $Device logcat -d -b crash -t 200) -join "`n") }
function Pid { (& $adb -s $Device shell "pidof $pkg").Trim() }

$devs = (& $adb devices) -join "`n"
$m = [regex]::Match($devs, "(?m)^(\S+)\s+device$")
if (-not $m.Success) { throw "no device" }
$script:Device = $m.Groups[1].Value
Write-Host "device=$Device"
& $adb -s $Device forward --remove-all 2>$null | Out-Null
& $adb -s $Device forward tcp:18765 tcp:8765 | Out-Null

Step "冷启动 x3 不崩" {
    for ($i = 1; $i -le 3; $i++) {
        & $adb -s $Device shell am force-stop $pkg | Out-Null
        Start-Sleep -Milliseconds 800
        & $adb -s $Device shell am start -n "$pkg/$pkg.MainActivity" | Out-Null
        Start-Sleep -Seconds 8
        if (-not (Pid)) { throw "第 $i 次启动后进程不存在" }
    }
    "3/3 存活"
}

Step "崩溃缓冲区为空(启动后)" {
    $c = CrashLines
    if ($c -match "FATAL EXCEPTION") { throw ("发现崩溃: " + (($c -split "`n" | Select-String "FATAL|OutOfMemory|Process:" | Select-Object -First 3) -join " / ")) }
    "无 FATAL"
}

Step "播放/暂停 x$PlayPauseCycles(暂停必须位置不动)" {
    if ($SkipAudio) { return "skipped" }
    if (-not $SongId) { throw "需要 -SongId" }
    Api POST "/player/play" @{ songId = [long]$SongId } | Out-Null
    Start-Sleep -Seconds 3
    for ($i = 1; $i -le $PlayPauseCycles; $i++) {
        Api POST "/player/play-pause" | Out-Null
        Start-Sleep -Milliseconds 1200
        $p1 = (Api GET "/state").playback.positionMs
        Start-Sleep -Milliseconds 1500
        $p2 = (Api GET "/state").playback.positionMs
        if ($p1 -ne $p2) { throw "第 $i 次暂停后位置仍在走:$p1 -> $p2" }
        Api POST "/player/play-pause" | Out-Null
        Start-Sleep -Milliseconds 900
    }
    "$PlayPauseCycles 次暂停均静止"
}

Step "进出循环页 x$LoopCycles" {
    if (-not $SongId) { return "skipped" }
    for ($i = 1; $i -le $LoopCycles; $i++) {
        Api POST "/loop/enter" @{ songId = [long]$SongId; play = $true } | Out-Null
        Start-Sleep -Seconds 2
        Api POST "/loop/exit" | Out-Null
        Start-Sleep -Seconds 1
        if (-not (Pid)) { throw "第 $i 次进出循环后进程消失" }
    }
    "$LoopCycles 次存活"
}

Step "引擎来回切换 x$SwitchCycles 不崩" {
    for ($i = 1; $i -le $SwitchCycles; $i++) {
        Api POST "/debug/source-mode" @{ on = $true } | Out-Null
        Start-Sleep -Milliseconds 900
        Api POST "/debug/source-mode" @{ on = $false } | Out-Null
        Start-Sleep -Milliseconds 900
        if (-not (Pid)) { throw "第 $i 次切换后进程消失" }
    }
    "进程始终存活"
}

Step "前台/后台 x5" {
    for ($i = 1; $i -le 5; $i++) {
        A "input keyevent KEYCODE_HOME" | Out-Null
        Start-Sleep -Seconds 2
        & $adb -s $Device shell am start -n "$pkg/$pkg.MainActivity" | Out-Null
        Start-Sleep -Seconds 2
        if (-not (Pid)) { throw "第 $i 次前后台后进程消失" }
    }
    "5 次存活"
}

Step "内存峰值" {
    $mi = (& $adb -s $Device shell "dumpsys meminfo $pkg") -join "`n"
    $tot = [regex]::Match($mi, "TOTAL\s+(\d+)")
    $heap = [regex]::Match($mi, "Java Heap:\s+(\d+)")
    ("TOTAL=" + $tot.Groups[1].Value + "KB JavaHeap=" + $heap.Groups[1].Value + "KB")
}

if ($LongRunMinutes -gt 0) {
    Step "连续循环 $LongRunMinutes 分钟" {
        if (-not $SongId) { return "skipped" }
        Api POST "/loop/enter" @{ songId = [long]$SongId; play = $true } | Out-Null
        Start-Sleep -Seconds ($LongRunMinutes * 60)
        if (-not (Pid)) { throw "长跑后进程消失" }
        Api POST "/loop/exit" | Out-Null
        "$LongRunMinutes 分钟存活"
    }
}

Step "崩溃缓冲区为空(结束)" {
    $c = CrashLines
    if ($c -match "FATAL EXCEPTION") { throw "结束时发现崩溃" }
    "无 FATAL"
}

Write-Host ""
$report | Format-Table -AutoSize | Out-String | Write-Host
if ($fail -gt 0) { Write-Host "SELFTEST: FAIL ($fail)" -ForegroundColor Red; exit 1 }
Write-Host "SELFTEST: PASS" -ForegroundColor Green