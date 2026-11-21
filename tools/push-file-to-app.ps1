#requires -version 5.1
<#
.SYNOPSIS
  Copy a file from the host into a debuggable app's private files/databases via run-as.

.DESCRIPTION
  On Android 14 a debuggable app's data dir is only reachable through "run-as", and
  "adb shell ... < file" / adb's PTY mangles binary stdin (a 86 KB SQLite file arrived truncated
  and corrupt). This helper sends the file as base64 chunks (ASCII only) and decodes it on device
  with toybox base64, then verifies sha256.

  Use it for seeding a Room database row, preparing an import file, etc.

.PARAMETER Package
  Debuggable package, e.g. com.zjr.hesimusic.baseline.
.PARAMETER Source
  Host file path.
.PARAMETER Destination
  Path relative to the app data dir, e.g. databases/hesimusic_db or files/adb_restore.json.
.PARAMETER Device
  adb serial (optional when exactly one device is attached).
#>
param(
    [Parameter(Mandatory = $true)][string]$Package,
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Destination,
    [string]$Device = ""
)
$ErrorActionPreference = "Stop"
$adb = $null
foreach ($c in @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", "$env:ANDROID_HOME\platform-tools\adb.exe")) {
    if (Test-Path $c) { $adb = $c; break }
}
if (-not $adb) { $adb = (Get-Command adb -ErrorAction Stop).Source }
$prefix = @()
if ($Device -ne "") { $prefix = @("-s", $Device) }

$bytes = [System.IO.File]::ReadAllBytes($Source)
$b64 = [Convert]::ToBase64String($bytes)
$tmp = "files/.push-" + [guid]::NewGuid().ToString("N") + ".b64"

& $adb @prefix shell "run-as $Package rm -f '$tmp'" | Out-Null
$chunk = 3000
for ($off = 0; $off -lt $b64.Length; $off += $chunk) {
    $part = $b64.Substring($off, [Math]::Min($chunk, $b64.Length - $off))
    & $adb @prefix shell "run-as $Package sh -c 'printf %s $part >> $tmp'" | Out-Null
}
$devSize = (& $adb @prefix shell "run-as $Package stat -c %s $tmp").Trim()
if ($devSize -ne [string]$b64.Length) { throw ("base64 transfer size mismatch: " + $devSize + " != " + $b64.Length) }
& $adb @prefix shell "run-as $Package sh -c 'base64 -d $tmp > $Destination'" | Out-Null
& $adb @prefix shell "run-as $Package rm -f '$tmp'" | Out-Null

$hostHash = (Get-FileHash $Source -Algorithm SHA256).Hash
$devHash = (& $adb @prefix shell "run-as $Package sha256sum '$Destination'").Split(' ')[0].ToUpper()
Write-Output ("host sha256: " + $hostHash)
Write-Output ("dev  sha256: " + $devHash)
if ($hostHash -ne $devHash) { throw "sha256 mismatch after push" }
Write-Output ("OK " + $Package + ":" + $Destination)
