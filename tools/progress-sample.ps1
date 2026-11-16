# issue 00018:真机分析进度采样与平滑度判据(设备需已装 dev 包并处于可分析状态)。
# 用法:powershell -NoProfile -File tools/progress-sample.ps1 [songId]
# 判据(与 docs/loop-v3-e2e-plan.md A1 一致):
#   - 相邻不同值台阶 <= 0.08
#   - 任意 1s 窗口内至少变化一次(即最大"不变"间隔 <= 1000ms)
#   - 开局 0.5s 内 <= 0.15
#   - 收尾 0.95 -> 1.0 在 1~2 步内完成
param([int]$SongId = 40904, [int]$TimeoutSec = 60)

$ErrorActionPreference = "Stop"
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "找不到 adb:$adb(检查 ANDROID_HOME)" }
if (-not (& $adb devices | Select-String -Pattern "device$")) { throw "没有已连接的设备:先插上手机/开启 USB 调试" }

& $adb forward --remove-all 2>$null | Out-Null
& $adb forward tcp:18765 tcp:8765 | Out-Null

function Api($method, $path, $body = $null) {
    $uri = "http://127.0.0.1:18765$path"
    if ($body) {
        Invoke-RestMethod -Method $method -Uri $uri -ContentType "application/json" `
            -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))) -TimeoutSec 30
    } else {
        Invoke-RestMethod -Method $method -Uri $uri -TimeoutSec 30
    }
}

Api POST "/analyze" @{ songId = $SongId } | Out-Null

$raw = New-Object System.Collections.ArrayList
$t0 = Get-Date
while (((Get-Date) - $t0).TotalSeconds -lt $TimeoutSec) {
    $a = $null
    try { $a = Api GET "/analysis" } catch { }
    if ($a) {
        $ms = [int]((Get-Date) - $t0).TotalMilliseconds
        [void]$raw.Add([pscustomobject]@{ ms = $ms; phase = "$($a.phase)"; p = [double]$a.progress })
        if ("$($a.phase)" -eq "done" -or "$($a.phase)" -eq "failed") { break }
    }
    Start-Sleep -Milliseconds 40
}

if ($raw.Count -eq 0) { throw "采样为空:/analysis 无响应" }
$samples = $raw | Sort-Object ms

# 只保留"值发生变化"的样本(轮询比上报快,重复值不算更新)。
$uniq = New-Object System.Collections.ArrayList
$lastValue = -1.0
foreach ($s in $samples) {
    if ($s.p -ne $lastValue) { [void]$uniq.Add($s); $lastValue = $s.p }
}

$steps = @()
$maxGap = 0
$maxGapFrom = 0
$maxGapTo = 0
for ($i = 1; $i -lt $uniq.Count; $i++) {
    $d = $uniq[$i].p - $uniq[$i - 1].p
    $steps += [pscustomobject]@{ atMs = $uniq[$i].ms; from = $uniq[$i - 1].p; to = $uniq[$i].p; delta = $d }
    $gap = $uniq[$i].ms - $uniq[$i - 1].ms
    if ($gap -gt $maxGap) { $maxGap = $gap; $maxGapFrom = $uniq[$i - 1].ms; $maxGapTo = $uniq[$i].ms }
}
$regressions = @($steps | Where-Object { $_.delta -lt -1e-6 })
$maxStep = if ($steps.Count -gt 0) { ($steps | Measure-Object -Property delta -Maximum).Maximum } else { 0.0 }

$at500 = 0.0
foreach ($s in $samples) { if ($s.ms -le 500) { $at500 = $s.p } }
$tail = @($uniq | Select-Object -Last 3)
$beforeOne = @($uniq | Where-Object { $_.p -lt 0.999 } | Select-Object -Last 1)
$tailSteps = if ($beforeOne.Count -gt 0) { $steps | Where-Object { $_.atMs -ge $beforeOne[0].ms } } else { @() }

$last = $samples[$samples.Count - 1]
"song=$SongId phase=$($last.phase) totalMs=$($last.ms) samples=$($samples.Count) updates=$($uniq.Count)"
"maxStep=" + [math]::Round($maxStep, 3) + " (<=0.08)  maxGap=" + $maxGap + "ms " +
    "(" + $maxGapFrom + "ms -> " + $maxGapTo + "ms,<=1000ms)  at500ms=" + [math]::Round($at500, 3) + " (<=0.15)  tailSteps=" + $tailSteps.Count + " (1~2)"

$fail = @()
if ($last.phase -ne "done") { $fail += "未以 done 结束(phase=$($last.phase))" }
if ($regressions.Count -gt 0) { $fail += "进度出现回退 $($regressions.Count) 次" }
if ($maxStep -gt 0.08 + 1e-6) { $fail += "相邻台阶过大:$([math]::Round($maxStep, 3))" }
if ($maxGap -gt 1000) { $fail += "超过 1s 无变化:${maxGap}ms" }
if ($at500 -gt 0.15 + 1e-6) { $fail += "开局 0.5s 进度过高:$([math]::Round($at500, 3))" }
if ($tailSteps.Count -lt 1 -or $tailSteps.Count -gt 2) { $fail += "收尾 0.95->1.0 步数异常:$($tailSteps.Count)" }

if ($tail.Count -gt 0) {
    "tail:" + (($tail | ForEach-Object { "$($_.ms)ms=" + [math]::Round($_.p, 3) }) -join "  ")
}
if ($fail.Count -eq 0) {
    "RESULT: PASS"
} else {
    "RESULT: FAIL -> " + ($fail -join "; ")
    exit 1
}
