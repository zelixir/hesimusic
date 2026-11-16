param([string]$Mode = "dump", [string]$Text = "")
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
function Dump-Nodes {
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    & $adb pull /sdcard/ui.xml "$PSScriptRoot\..\e2e-artifacts\ui-last.xml" | Out-Null
    [xml]$doc = Get-Content "$PSScriptRoot\..\e2e-artifacts\ui-last.xml"
    $out = @()
    foreach ($n in $doc.SelectNodes("//node")) {
        $t = $n.text; $d = $n.'content-desc'; $c = $n.clickable
        if (-not $t -and -not $d) { continue }
        $b = $n.bounds -replace '\[|\]', ',' -split ',' | Where-Object { $_ -ne '' }
        $x1 = [int]$b[0]; $y1 = [int]$b[1]; $x2 = [int]$b[2]; $y2 = [int]$b[3]
        $out += [pscustomobject]@{ text = $t; desc = $d; clickable = $c; cx = [int](($x1 + $x2) / 2); cy = [int](($y1 + $y2) / 2) }
    }
    return $out
}
if ($Mode -eq "dump") { Dump-Nodes | Format-Table -AutoSize | Out-String -Width 200 }
elseif ($Mode -eq "tap") {
    $hit = Dump-Nodes | Where-Object { $_.text -eq $Text -or $_.desc -eq $Text } | Select-Object -First 1
    if ($hit) { "tap '$Text' at $($hit.cx),$($hit.cy)"; & $adb shell input tap $hit.cx $hit.cy } else { "not found: $Text"; Dump-Nodes | Where-Object { $_.text } | Select-Object -First 20 | Format-Table -AutoSize | Out-String -Width 200 }
}
