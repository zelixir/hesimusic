$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$apk = "D:\Code\hesimusic\app\build\outputs\apk\debug\app-debug.apk"
$log = "D:\Code\hesimusic\e2e-artifacts\seam-verify-1.7.9.log"
"waiting for device..." | Set-Content -Path $log -Encoding utf8
$dev = $null
for ($i = 0; $i -lt 120; $i++) {
    $out = (& $adb devices) -join "`n"
    $m = [regex]::Match($out, "(?m)^(\S+)\s+device$")
    if ($m.Success) { $dev = $m.Groups[1].Value; break }
    Start-Sleep -Seconds 5
}
if (-not $dev) { "no device after 10 min" | Add-Content $log -Encoding utf8; exit 1 }
"device=$dev" | Add-Content $log -Encoding utf8
& $adb -s $dev install -r -t $apk 2>&1 | Add-Content $log -Encoding utf8
& $adb -s $dev shell am force-stop com.zjr.hesimusic
Start-Sleep -Milliseconds 800
& $adb -s $dev shell am start -n com.zjr.hesimusic/com.zjr.hesimusic.MainActivity | Out-Null
Start-Sleep -Seconds 12
& $adb -s $dev forward --remove-all 2>$null | Out-Null
& $adb -s $dev forward tcp:18765 tcp:8765 | Out-Null
function Api($m, $p, $b = $null) {
    $u = "http://127.0.0.1:18765$p"
    try {
        if ($b) { Invoke-RestMethod -Method $m -Uri $u -ContentType "application/json" -Body ([Text.Encoding]::UTF8.GetBytes(($b | ConvertTo-Json -Compress))) -TimeoutSec 20 }
        else { Invoke-RestMethod -Method $m -Uri $u -TimeoutSec 20 }
    } catch { $null }
}
$seam = Api POST "/debug/seam" @{ enable = $true; route = $true }
"seam=" + ($seam | ConvertTo-Json -Compress) | Add-Content $log -Encoding utf8
Api POST "/player/repeat" @{ mode = "all" } | Out-Null
& $adb -s $dev logcat -c
Api POST "/loop/enter" @{ songId = 40874; play = $true } | Out-Null
Start-Sleep -Seconds 3
$w = Api GET "/state"
"window=" + ($w.loop.window | ConvertTo-Json -Compress) | Add-Content $log -Encoding utf8
Api POST "/player/seek" @{ positionMs = 77000 } | Out-Null
Start-Sleep -Seconds 9
$s = Api GET "/state"
"pos=" + $s.playback.positionMs + " engine=" + $s.loop.engine + " degrade=" + $s.loop.degradeCount | Add-Content $log -Encoding utf8
& $adb -s $dev logcat -d | Select-String -Pattern "seam |seam\.|LoopPerf: state=" | Select-Object -Last 14 | ForEach-Object { $_.Line } | Add-Content $log -Encoding utf8
Api POST "/debug/seam" @{ enable = $false; route = $false } | Out-Null
"done" | Add-Content $log -Encoding utf8
