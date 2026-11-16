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
    Start-Sleep -Milliseconds 100
}
"phase=" + $samples[-1].phase + " totalMs=" + $samples[-1].ms
$prev = -1.0
$jumps = @()
foreach ($s in $samples) {
    if ($prev -ge 0 -and $s.p -gt $prev + 0.0001) {
        $d = [math]::Round($s.p - $prev, 3)
        if ($d -gt 0) { $jumps += [pscustomobject]@{ atMs = $s.ms; from = [math]::Round($prev,3); to = [math]::Round($s.p,3); delta = $d } }
    }
    $prev = $s.p
}
"progress updates: " + $jumps.Count
$jumps | Sort-Object -Property delta -Descending | Select-Object -First 8 | Format-Table -AutoSize | Out-String -Width 120
"max step=" + (($jumps | Measure-Object -Property delta -Maximum).Maximum)
