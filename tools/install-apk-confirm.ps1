#requires -version 5.1
<#
.SYNOPSIS
  Install an APK on a ColorOS/OPPO/OnePlus device whose package installer intercepts adb installs.

.DESCRIPTION
  Empirically (OnePlus 8T, Android 14, ColorOS):
    - "adb install" stages the APK, then com.oplus.appdetail shows its own "来自电脑端未知来源" page.
    - The adb session itself is cancelled after ~1-2 s ("OPLUS_ADB_INSTALL_CANCEL"), so adb exits with
      "Failure [-99]" even though the staged APK is still installable from that page.
    - Tapping "继续安装" on that page installs the staged package.
    - Passing -g makes the install fail for a different reason: com.oplus.appdetail does not hold
      android.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS, so the commit fails with
      "You need the android.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS permission ...".
      Grant runtime permissions afterwards via the app UI or pm grant where allowed.

  Therefore this script: starts "adb install -r", taps 继续安装 as soon as the guide appears, keeps
  polling until 安装完成 / 安装失败, and finally verifies the package really got installed.

.PARAMETER Apk
  APK path.
.PARAMETER PackageName
  Expected package name (verified with pm list packages at the end).
.PARAMETER Device
  adb serial (optional when one device is attached).
.PARAMETER TimeoutSec
  Max wall time for the whole flow (default 300).
#>
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$PackageName,
    [string]$Device = "",
    [int]$TimeoutSec = 300
)
$ErrorActionPreference = "Continue"
$adb = $null
foreach ($c in @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", "$env:ANDROID_HOME\platform-tools\adb.exe")) {
    if (Test-Path $c) { $adb = $c; break }
}
if (-not $adb) { $adb = (Get-Command adb -ErrorAction Stop).Source }
$prefix = @()
if ($Device -ne "") { $prefix = @("-s", $Device) }

function Get-UiXml {
    return ((& $adb @prefix shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml") -join "")
}

function Find-ButtonBounds {
    param([string]$Xml, [string[]]$Patterns)
    foreach ($pat in $Patterns) {
        $rx = [regex]('<node[^>]*text="' + [regex]::Escape($pat) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        $m = $rx.Match($Xml)
        if ($m.Success) {
            return @{
                Text = $pat
                X = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
                Y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
            }
        }
    }
    return $null
}

function Test-PackageInstalled {
    $out = (& $adb @prefix shell "pm list packages") -join " "
    return ($out -match ('package:' + [regex]::Escape($PackageName) + '(\s|$)'))
}

if (Test-PackageInstalled) { Write-Output ("already installed: " + $PackageName); exit 0 }

$log = Join-Path $env:TEMP ("install-" + [guid]::NewGuid().ToString("N") + ".txt")
$p = Start-Process -FilePath $adb -ArgumentList ($prefix + @("install", "-r", $Apk)) -NoNewWindow -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru
Write-Output "adb install started (its Failure [-99] is expected on ColorOS; the guide page does the real install)"

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$confirmTaps = 0
$result = ""
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 1200
    $xml = Get-UiXml
    if ($xml -match '安装完成') { $result = "success"; break }
    if ($xml -match '安装失败|安装包异常') { $result = "failed"; break }
    $btn = Find-ButtonBounds $xml @("继续安装", "安装", "立即安装")
    if ($null -ne $btn) {
        & $adb @prefix shell input tap $btn.X $btn.Y | Out-Null
        $confirmTaps++
        Write-Host ("tapped '" + $btn.Text + "' at " + $btn.X + "," + $btn.Y)
        Start-Sleep -Milliseconds 900
    }
    if ($p.HasExited -and $null -eq $btn -and (Test-PackageInstalled)) { $result = "success"; break }
}
if (-not $p.HasExited) { try { $p.Kill() } catch { } }
Start-Sleep -Seconds 1
if ($result -eq "" -and (Test-PackageInstalled)) { $result = "success" }
# dismiss the result page
$xml = Get-UiXml
$done = Find-ButtonBounds $xml @("完成", "打开")
if ($null -ne $done) { & $adb @prefix shell input tap $done.X $done.Y | Out-Null }

Write-Output ("confirm taps: " + $confirmTaps + " result: " + $result)
Write-Output ("adb output: " + ((Get-Content $log -ErrorAction SilentlyContinue) -join " "))
if ($result -eq "success" -and (Test-PackageInstalled)) {
    Write-Output ("OK installed: " + $PackageName)
    exit 0
}
Write-Output ("FAILED to install: " + $PackageName)
exit 1
