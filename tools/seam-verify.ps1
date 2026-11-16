param([long]$SongId = 40904, [int]$SeekMs = 229000, [int]$WaitSec = 8, [switch]$Enable)
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb forward --remove-all 2>$null | Out-Null
& $adb forward tcp:18765 tcp:8765 | Out-Null
function Api($m, $p, $b = $null) {
    $u = "http://127.0.0.1:18765$p"
    if ($b) { Invoke-RestMethod -Method $m -Uri $u -ContentType "application/json" -Body ([Text.Encoding]::UTF8.GetBytes(($b | ConvertTo-Json -Compress))) -TimeoutSec 20 }
    else { Invoke-RestMethod -Method $m -Uri $u -TimeoutSec 20 }
}
if ($Enable) {
    $seam = Api POST "/debug/seam" @{ enable = $true; route = $true }
    "seam: " + ($seam | ConvertTo-Json -Compress)
}
Api POST "/player/repeat" @{ mode = "all" } | Out-Null
& $adb logcat -c
Api POST "/loop/enter" @{ songId = $SongId; play = $true } | Out-Null
Start-Sleep -Seconds 3
$w = Api GET "/state"
"window: " + ($w.loop.window | ConvertTo-Json -Compress)
Api POST "/player/seek" @{ positionMs = $SeekMs } | Out-Null
Start-Sleep -Seconds $WaitSec
$s = Api GET "/state"
"pos=" + $s.playback.positionMs + " engine=" + $s.loop.engine + " replay=" + $s.loop.isReplaying + " degrade=" + $s.loop.degradeCount + " ring=" + $s.loop.isRingComplete
"--- LoopPerf (seam/perf) ---"
& $adb logcat -d | Select-String -Pattern "LoopPerf: state=|seam |seam->|seam skip" | Select-Object -Last 6 | ForEach-Object { $_.Line }
