$adb="$env:ANDROID_HOME\platform-tools\adb.exe"
function Pos {
  & $adb -s b1e84dad shell uiautomator dump /sdcard/p.xml | Out-Null
  & $adb -s b1e84dad pull /sdcard/p.xml e2e-artifacts/p.xml | Out-Null
  $x = Get-Content e2e-artifacts/p.xml -Raw
  $m = [regex]::Match($x, "text=.(\d\d:\d\d\.\d\d\d) /")
  if ($m.Success) { $m.Groups[1].Value } else { "?" }
}
$p1 = Pos; "p1=$p1"; Start-Sleep -Seconds 5; $p2 = Pos; "p2=$p2"
& $adb -s b1e84dad logcat -d -t 250 | Select-String -Pattern "tick posMs" | Select-Object -Last 2 | ForEach-Object { ($_.Line -split "LoopPreview: ")[-1] }