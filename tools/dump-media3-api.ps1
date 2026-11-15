param(
    [Parameter(Mandatory = $true)][string]$Class,
    [string]$Module = "media3-exoplayer",
    [switch]$Private,
    [switch]$Codes
)
# 从 gradle 缓存里的 media3 AAR 提取 classes.jar,用 javap 打印类签名。
# 用途:P0/P1 期间在没有源码(网络受限)的情况下核对 Media3 公开 API 的精确签名。
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$cache = Join-Path $env:TEMP "media3-api"
New-Item -ItemType Directory -Force -Path $cache | Out-Null

$base = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\androidx.media3\$Module"
$aar = Get-ChildItem $base -Recurse -Filter "*.aar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $aar) { throw "AAR not found for module $Module under $base" }
$jarOut = Join-Path $cache ("$Module.jar")
if (-not (Test-Path $jarOut) -or (Get-Item $jarOut).LastWriteTime -lt $aar.LastWriteTime) {
    $tmp = Join-Path $cache ("tmp_" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory($aar.FullName, $tmp)
    Copy-Item (Join-Path $tmp "classes.jar") $jarOut -Force
    Remove-Item $tmp -Recurse -Force
}

# 组装 classpath:所有 media3 模块 + android.jar(解析父类用)
$cp = @($jarOut)
Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\androidx.media3") -Recurse -Filter "classes.jar" -ErrorAction SilentlyContinue | ForEach-Object { $cp += $_.FullName }
$androidJar = Join-Path $env:ANDROID_HOME "platforms\android-36\android.jar"
if (-not (Test-Path $androidJar)) { $androidJar = (Get-ChildItem (Join-Path $env:ANDROID_HOME "platforms") -Directory | Sort-Object Name -Descending | Select-Object -First 1 | ForEach-Object { Join-Path $_.FullName "android.jar" }) }
if (Test-Path $androidJar) { $cp += $androidJar }

$args2 = @("-classpath", ($cp -join ';'))
if ($Private) { $args2 += "-p" }
if ($Codes) { $args2 += "-c" }
$args2 += $Class
& javap @args2
