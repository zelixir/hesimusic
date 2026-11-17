param(
    [long]$SongId = 40904,
    [int]$Seconds = 40,
    [string]$TaskTag = "src"
)
$ErrorActionPreference = "Stop"
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "e2e-artifacts"
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Api { param($Method, $Path, $Body)
    $uri = "http://127.0.0.1:18765$Path"
    try {
        if ($Body) {
            Invoke-RestMethod -Method $Method -Uri $uri -ContentType "application/json" -Body ([Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress))) -TimeoutSec 20
        } else {
            Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec 20
        }
    } catch { $null }
}

$devices = (& $adb devices) -join "`n"
$m = [regex]::Match($devices, "(?m)^(\S+)\s+device$")
if (-not $m.Success) { throw "no device" }
$device = $m.Groups[1].Value
Write-Host "device=$device"

& $adb -s $device forward --remove-all 2>$null | Out-Null
& $adb -s $device forward tcp:18765 tcp:8765 | Out-Null
$mode = Api POST "/debug/source-mode" @{ on = $true }
Write-Host ("source mode: " + ($mode | ConvertTo-Json -Compress))

function LastTick { (& $adb -s $device logcat -d -t 300 | Select-String -Pattern "tick posMs" | Select-Object -Last 1).Line }

$started = $false
for ($i = 0; $i -lt 6 -and -not $started; $i++) {
    Api POST "/navigate" @{ route = "home" } | Out-Null
    Start-Sleep -Milliseconds 800
    Api POST "/navigate" @{ route = "loop/$SongId" } | Out-Null
    Start-Sleep -Seconds 6
    & $adb -s $device logcat -c
    foreach ($xy in @(@(105,1333), @(458,1285), @(138,1783), @(340,1783))) {
        & $adb -s $device shell input tap $xy[0] $xy[1] | Out-Null
        Start-Sleep -Seconds 2
        if (LastTick) { Write-Host "playing after tap $($xy[0]),$($xy[1])"; $started = $true; break }
    }
}
if (-not $started) { throw "could not start preview playback" }

$tapName = "$TaskTag-$SongId-$(Get-Date -Format HHmmss).wav"
Write-Host "tap: $tapName"
Api POST "/debug/tap" @{ path = $tapName } | Out-Null
Start-Sleep -Seconds $Seconds
Api POST "/debug/tap" @{ path = $null } | Out-Null
Start-Sleep -Milliseconds 800

$remote = "/data/user/0/com.zjr.hesimusic/files/$tapName"
$localWav = Join-Path $out $tapName
$localJson = Join-Path $out "$tapName.jsonl"
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $adb
$psi.Arguments = "-s $device exec-out run-as com.zjr.hesimusic cat $remote"
$psi.RedirectStandardOutput = $true
$psi.UseShellExecute = $false
$p = [System.Diagnostics.Process]::Start($psi)
$fs = [System.IO.File]::Create($localWav)
$p.StandardOutput.BaseStream.CopyTo($fs)
$fs.Close(); $p.WaitForExit()
$jsonOut = & $adb -s $device exec-out run-as com.zjr.hesimusic cat "$remote.jsonl"
if ($jsonOut) { Set-Content -Path $localJson -Value ($jsonOut -join [Environment]::NewLine) -Encoding utf8 }
Write-Host ("wav bytes=" + (Get-Item $localWav).Length)

& $adb -s $device shell "run-as com.zjr.hesimusic rm files/$tapName files/$tapName.jsonl" | Out-Null

$bun = Join-Path $env:USERPROFILE ".bun\bin\bun.exe"
if (-not (Test-Path $bun)) { $bun = "bun" }
& $bun run (Join-Path $PSScriptRoot "loop-smoothness.ts") $localWav --events $localJson