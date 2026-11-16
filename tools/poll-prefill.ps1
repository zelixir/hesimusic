$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$t0 = Get-Date
$gone = -1
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 600
    & $adb shell uiautomator dump /sdcard/u.xml 2>&1 | Out-Null
    $raw = & $adb shell cat /sdcard/u.xml
    $xml = ($raw -join "")
    if ($xml -notmatch '准备环缓冲') { $gone = [int]((Get-Date) - $t0).TotalMilliseconds; break }
}
if ($gone -ge 0) { "hint gone after $gone ms" } else { "hint still there after 24s" }
& $adb pull /sdcard/u.xml "$PSScriptRoot\..\e2e-artifacts\u-now.xml" | Out-Null
$doc = Get-Content "$PSScriptRoot\..\e2e-artifacts\u-now.xml" -Raw
$m = [regex]::Matches($doc, 'text="([^"]*)"')
$texts = @()
foreach ($x in $m) { if ($x.Groups[1].Value) { $texts += $x.Groups[1].Value } }
($texts | Select-Object -First 12) -join " | "
