<#
.SYNOPSIS
    兜底导出:不依赖 run-as / adb backup,只用上一版 app 已有的 adb 数据库接口导出。

.DESCRIPTION
    等价于:tools/export-legacy-data.ps1 -Method provider
    保留这个文件名,是因为 docs/import-legacy-data.md 把"adb backup 不可用时的兜底"单独列了一节。

    适用:上一版不是 debuggable(runas 不可用)+ 设备 ROM 禁用了 adb backup。
    限制:拿不到 SharedPreferences(设置),产物里设置段为空;数据库版本号也为空。
    要求:上一版包含 AdbDbProvider(content://<包名>.adbdb,见 docs/adb-api.md)。
    安全:全程只读(query),不写上一版任何数据。
#>
[CmdletBinding()]
param(
    [string]$Serial = '',

    [string]$ApplicationId = 'com.zjr.hesimusic',

    [string]$WorkDir = '',

    [string]$DeviceDir = '',

    [switch]$SkipPush,

    [int]$PageSize = 500
)

$ErrorActionPreference = 'Stop'

$arguments = @(
    '-Method', 'provider',
    '-ApplicationId', $ApplicationId,
    '-PageSize', $PageSize
)
if ($Serial -ne '') { $arguments += @('-Serial', $Serial) }
if ($WorkDir -ne '') { $arguments += @('-WorkDir', $WorkDir) }
if ($DeviceDir -ne '') { $arguments += @('-DeviceDir', $DeviceDir) }
if ($SkipPush) { $arguments += @('-SkipPush') }

$script = Join-Path $PSScriptRoot 'export-legacy-data.ps1'
& $script @arguments
if (-not $?) { exit 1 }
exit 0
