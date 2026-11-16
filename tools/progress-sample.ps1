$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb forward --remove-all 2>$null | Out-Null
& $adb forward tcp:18765 tcp:8765 | Out-Null
function Api($m, $p, $b = $null) {
    $u = "http://127.0.0.1:18765$p"
    if ($b) { Invoke-RestMethod -Method $m -Uri $u -ContentType "application/json" -Body ([Text.Encoding]::UTF8.GetBytes(($b | ConvertTo-Json -Compress))) -TimeoutSec 30 }
    else { Invoke-RestMethod -Method $m -Uri $u -TimeoutSec 30 }
}
Api POST "/analyze" @{ songId = 40904 } | Out-Null
$samples = @()
$t0 = Get-Date
while (((Get-Date) - $t0).TotalSeconds -lt 40) {
    $a = $null
    try { $a = Api GET "/analysis" } catch { }
    if ($a) {
        $ms = [int]((Get-Date) - $t0).TotalMilliseconds
        $samples += [pscustomobject]@{ ms = $ms; phase = $a.phase; p = $a.progress }
        if ($a.phase -eq "done" -or $a.phase -eq "failed") { break }
    }
    Start-Sleep -Milliseconds 40
}
$uniq = $samples | Group-Object -Property p | ForEach-Object { $_.Group | Select-Object -First 1 } | Sort-Object ms
"phase=" + $samples[-1].phase + " totalMs=" + $samples[-1].ms + " samples=" + $samples.Count + " distinct=" + $uniq.Count
$prev = -1.0
$maxStep = 0.0
$maxAt = 0
foreach ($s in $uniq) {
    if ($prev -ge 0) {
        $d = $s.p - $prev
        if ($d -gt $maxStep) { $maxStep = $d; $maxAt = $s.ms }
    }
    $prev = $s.p
}
"max distinct step=" + [math]::Round($maxStep, 3) + " at " + $maxAt + "ms"
$uniq | Select-Object -Last 6 | Format-Table -AutoSize | Out-String -Width 80
