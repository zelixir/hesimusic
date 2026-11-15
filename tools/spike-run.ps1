[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('plain', 'baseline', 'window', 'sink', 'media')]
    [string]$Mode,

    # 设备上已有音频（不要往仓库加音频）
    [string]$Audio = '/sdcard/Music/(01)ANGEL.mp3',

    [int]$AMs = 20000,
    [int]$BMs = 50000,
    [int]$Seconds = 12,
    [int]$Loops = 100,
    [int]$Tap = 1,
    [int]$SeekToMs = -1,
    [int]$SeekDelayMs = 1000,
    [int]$PauseAtMs = -1,
    [int]$PauseForMs = 0,
    [string]$Tag = 'default',

    # 产物目录名（默认 <mode>-<tag>;M3' 用 sink2-<tag>）
    [string]$DirName = '',

    # 已拷贝过同一首音频时跳过拷贝
    [switch]$Reuse,

    # 0 = Seconds + 40
    [int]$TimeoutSec = 0
)

$ErrorActionPreference = 'Stop'

$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found: $adb" }
$repo = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrEmpty($DirName)) { $DirName = $Mode + "-" + $Tag }
$outDir = Join-Path $repo ("spike\out\" + $DirName)
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$state = & $adb get-state 2>&1
if ($state -ne 'device') { throw "no adb device (get-state=$state)" }

# 设备上的 /sdcard 读取权限受 OEM 限制（pm grant 被拒），统一走 shell->run-as 管道把音频复制进
# app 私有目录，再用绝对路径喂给 ExoPlayer（免 READ_MEDIA_AUDIO）。
$baseName = [System.IO.Path]::GetFileName($Audio)
$safe = ($baseName -replace '[^A-Za-z0-9._-]', '_')
$devPath = "/data/data/com.zjr.spike/files/media/$safe"

if (-not $Reuse) {
    Write-Host "[spike] copy '$Audio' -> $devPath"
    & $adb shell run-as com.zjr.spike mkdir -p files/media | Out-Null
    $copyCmd = "cat '" + $Audio + "' | run-as com.zjr.spike sh -c 'cat > files/media/$safe'"
    & $adb shell $copyCmd
    if ($LASTEXITCODE -ne 0) { throw "copy to app dir failed" }
}
$ls = & $adb shell run-as com.zjr.spike ls -l ("files/media/" + $safe) 2>&1
Write-Host "[spike] audio on device: $ls"

& $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb shell wm dismiss-keyguard | Out-Null

& $adb logcat -c
& $adb shell am force-stop com.zjr.spike | Out-Null

$argsLine = "am start -n com.zjr.spike/.SpikeActivity --es mode $Mode --es path $devPath " +
    "--ei aMs $AMs --ei bMs $BMs --ei seconds $Seconds --ei loops $Loops --ei tap $Tap " +
    "--ei seekToMs $SeekToMs --ei seekDelayMs $SeekDelayMs --ei pauseAtMs $PauseAtMs " +
    "--ei pauseForMs $PauseForMs --es tag $Tag"
Write-Host "[spike] adb shell $argsLine"
& $adb shell $argsLine | Out-Null

if ($TimeoutSec -le 0) { $TimeoutSec = $Seconds + 40 }
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$ended = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $log = & $adb shell run-as com.zjr.spike cat files/spike/latest.jsonl 2>$null
    if ($log -match '"ev":"end"') { $ended = $true; break }
}
if (-not $ended) { Write-Warning "[spike] timeout waiting for end event; pulling whatever exists" }

# 用 Start-Process 重定向原始字节，避免 PowerShell 文本重定向破坏二进制 WAV
function Pull-File([string]$remote, [string]$local) {
    $p = Start-Process -FilePath $adb -ArgumentList @('exec-out', 'run-as', 'com.zjr.spike', 'cat', $remote) -RedirectStandardOutput $local -NoNewWindow -Wait -PassThru
    if ($p.ExitCode -ne 0) { Write-Warning "pull failed($($p.ExitCode)): $remote" }
}
Pull-File 'files/spike/latest.wav' (Join-Path $outDir 'latest.wav')
Pull-File 'files/spike/latest.jsonl' (Join-Path $outDir 'latest.jsonl')
& $adb logcat -d -s Spike:V AndroidRuntime:E > (Join-Path $outDir 'logcat.txt') 2>&1

@{
    mode      = $Mode
    audio     = $Audio
    devPath   = $devPath
    aMs       = $AMs
    bMs       = $BMs
    seconds   = $Seconds
    loops     = $Loops
    tap       = $Tap
    seekToMs  = $SeekToMs
    seekDelay = $SeekDelayMs
    pauseAtMs = $PauseAtMs
    pauseForMs = $PauseForMs
    tag       = $Tag
    dirName   = $DirName
    startedAt = (Get-Date).ToString('s')
    ended     = $ended
} | ConvertTo-Json | Set-Content -Path (Join-Path $outDir 'run.json') -Encoding UTF8

Write-Host "[spike] artifacts -> $outDir"
Get-ChildItem $outDir | Select-Object Name, Length | Format-Table -AutoSize