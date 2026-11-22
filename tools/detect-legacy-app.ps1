<#
.SYNOPSIS
    一次性判定设备上的「上一版」海瑟音乐能不能导出、该走哪条路(A adb backup / B run-as / C 不可用)。

.DESCRIPTION
    接上正式机后先跑这个,再决定导出命令。检测项:
      - 包是否存在 / versionName / versionCode
      - debuggable(run-as 是否可用)
      - allowBackup(manifest 是否允许备份)
      - adb backup 是否真的能产出数据(默认做一次快速探针:自动点掉系统确认框,几秒后中断并检查文件是否在长)
      - 上一版是否带 adb 数据库接口(兜底路径,只有库没有设置)

    探针默认开启:会在设备上短暂出现系统「完全备份」确认框(脚本自动点「备份我的数据」),
    几秒后中断;不改设备上任何 app 的数据,也不留文件(除非 -KeepProbeFile)。

.PARAMETER Serial
    多设备时指定 adb serial。

.PARAMETER ApplicationId
    上一版包名,默认 com.zjr.hesimusic。

.PARAMETER Json
    只输出一行 JSON(给脚本消费);默认输出人类可读报告。

.PARAMETER SkipBackupProbe
    跳过 adb backup 探针(只根据 allowBackup 推断,速度快但结论不确定)。

.PARAMETER ProbeSeconds
    探针确认对话框后继续传输多少秒再中断(默认 6)。

.PARAMETER KeepProbeFile
    保留探针产出的部分 .ab(调试用;默认删除)。

.EXAMPLE
    pwsh -File tools/detect-legacy-app.ps1
    pwsh -File tools/detect-legacy-app.ps1 -Json
    pwsh -File tools/detect-legacy-app.ps1 -SkipBackupProbe
#>
[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$ApplicationId = 'com.zjr.hesimusic',
    [switch]$Json,
    [switch]$SkipBackupProbe,

    # 探针最长等待秒数(备份是"整包备份",完成前 .ab 一直是 0 字节,所以必须等进程结束才能判定)。
    [int]$ProbeTimeoutSeconds = 180,

    # 把探针产出的完整 .ab 留在指定路径(之后可传给 export-legacy-data.ps1 -AbFile 复用,免第二次备份)。
    [string]$ProbeOutput = '',

    [switch]$KeepProbeFile
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'legacy-backup-confirm.ps1')

try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }
try { $OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }

$AdbTarget = @()
if ($Serial -ne '') { $AdbTarget = @('-s', $Serial) }

function Resolve-AdbPath {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command -ne $null) { return $command.Source }
    $candidates = @()
    if ($env:ANDROID_HOME -ne $null -and $env:ANDROID_HOME -ne '') {
        $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')
    }
    if ($env:LOCALAPPDATA -ne $null -and $env:LOCALAPPDATA -ne '') {
        $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    }
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }
    throw '找不到 adb:请把 platform-tools 加入 PATH,或设置 ANDROID_HOME。'
}

$adb = Resolve-AdbPath

function Invoke-Adb([string[]]$AdbArgs) {
    $output = & $adb @AdbTarget @AdbArgs 2>&1
    $code = $LASTEXITCODE
    $lines = @()
    foreach ($line in @($output)) { $lines += [string]$line }
    return [pscustomobject]@{ Lines = $lines; ExitCode = $code; Text = [string]::Join([Environment]::NewLine, $lines) }
}

$result = [ordered]@{
    applicationId = $ApplicationId
    serial = ''
    installed = $false
    versionName = ''
    versionCode = 0
    debuggable = $false
    allowBackup = $false
    runAsAvailable = $false
    providerAvailable = $false
    adbBackupAvailable = $false
    adbBackupState = 'skipped'
    adbBackupProbe = 'skipped'
    recommendedMethod = 'unavailable'
    path = 'C'
    recommendedCommand = ''
    androidRelease = ''
    deviceModel = ''
    checkedAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
}

$state = Invoke-Adb @('get-state')
if ($state.ExitCode -ne 0 -or $state.Text.Trim() -ne 'device') {
    if ($Json) {
        $result.path = 'C'
        $result.recommendedMethod = 'unavailable'
        $result.adbBackupProbe = 'no-device'
        [pscustomobject]$result | ConvertTo-Json -Compress
        exit 0
    }
    throw ('没有可用设备:' + [Environment]::NewLine + (Invoke-Adb @('devices')).Text)
}

$result.serial = (Invoke-Adb @('shell', 'getprop ro.serialno')).Text.Trim()
if ($Serial -ne '') { $result.serial = $Serial }
$result.androidRelease = (Invoke-Adb @('shell', 'getprop ro.build.version.release')).Text.Trim()
$result.deviceModel = (Invoke-Adb @('shell', 'getprop ro.product.model')).Text.Trim()

$packagePath = Invoke-Adb @('shell', ('pm path ' + $ApplicationId))
$result.installed = ($packagePath.Text -match 'package:')
if ($result.installed) {
    $dump = Invoke-Adb @('shell', ('dumpsys package ' + $ApplicationId))
    foreach ($line in $dump.Lines) {
        if ($line -match 'versionName=(\S+)') { $result.versionName = $Matches[1] }
        if ($line -match 'versionCode=(\d+)') { $result.versionCode = [int64]$Matches[1] }
    }
    $result.debuggable = ($dump.Text -match 'DEBUGGABLE')
    $result.allowBackup = ($dump.Text -match 'ALLOW_BACKUP')

    if ($result.debuggable) {
        $runAsProbe = Invoke-Adb @('shell', ('run-as ' + $ApplicationId + ' ls databases/hesimusic_db'))
        $result.runAsAvailable = ($runAsProbe.Text -match 'hesimusic_db')
    }

    $providerCommand = "content call --uri content://$ApplicationId.adbdb --method query --arg 'SELECT COUNT(*) AS n FROM songs'"
    $providerCommand = $providerCommand.Replace('"', '\"')
    $providerProbe = Invoke-Adb @('shell', $providerCommand)
    $result.providerAvailable = ($providerProbe.Text -match '"rows"')
}

# ---- adb backup 探针 ----
# 注意:.ab 在备份**全部完成前一直是 0 字节**(adb 先遍历文件树,再一次性写出流),
# 所以"几秒后看文件大小"会误判;必须等进程结束(或超时)才能下结论。
if ($result.installed -and -not $SkipBackupProbe) {
    if (-not $result.allowBackup) {
        $result.adbBackupState = 'unavailable'
        $result.adbBackupProbe = 'allowBackup=false,必然失败'
    } else {
        $keepFile = ($KeepProbeFile -or $ProbeOutput -ne '')
        if ($ProbeOutput -ne '') {
            $probeFile = $ProbeOutput
            $probeDir = Split-Path -Parent $probeFile
            if ($probeDir -ne '' -and -not (Test-Path $probeDir)) {
                New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
            }
        } else {
            $probeFile = Join-Path ([System.IO.Path]::GetTempPath()) ('hesi-backup-probe-' + [guid]::NewGuid().ToString('N') + '.ab')
        }
        $outFile = $probeFile + '.out'
        $errFile = $probeFile + '.err'
        $process = $null
        $startedAt = Get-Date
        try {
            $startArgs = @{
                FilePath = $adb
                ArgumentList = ($AdbTarget + @('backup', '-f', $probeFile, '-noapk', '-noshared', $ApplicationId))
                NoNewWindow = $true
                PassThru = $true
                RedirectStandardOutput = $outFile
                RedirectStandardError = $errFile
            }
            $process = Start-Process @startArgs
            $confirm = Confirm-AdbBackupDialog -AdbPath $adb -AdbTarget $AdbTarget -WaitSeconds 20
            $deadline = (Get-Date).AddSeconds($ProbeTimeoutSeconds)
            while (-not $process.HasExited -and (Get-Date) -lt $deadline) {
                Start-Sleep -Milliseconds 500
            }
            $elapsed = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)
            $size = 0
            if (Test-Path $probeFile) { $size = (Get-Item $probeFile).Length }
            if (-not $process.HasExited) {
                $result.adbBackupState = 'unknown'
                $result.adbBackupProbe = '超过 ' + $ProbeTimeoutSeconds + ' 秒仍未完成(可能只是慢),已中断'
            } elseif ($process.ExitCode -eq 0 -and $size -gt 1024) {
                $result.adbBackupState = 'available'
                $result.adbBackupAvailable = $true
                $confirmText = '确认框未出现(直接备份)'
                if ($confirm.Confirmed) { $confirmText = '已自动点掉确认框' }
                $result.adbBackupProbe = $confirmText + ', ' + $elapsed + ' 秒产出 ' + [math]::Round($size / 1MB, 2) + ' MB'
            } else {
                $result.adbBackupState = 'unavailable'
                $errText = ''
                if (Test-Path $errFile) { $errText = ([System.IO.File]::ReadAllText($errFile)).Trim() }
                $result.adbBackupProbe = 'adb backup 退出码 ' + $process.ExitCode + ',文件 ' + $size + ' 字节'
                if ($errText -ne '') { $result.adbBackupProbe = $result.adbBackupProbe + ';' + $errText }
            }
        } catch {
            $result.adbBackupState = 'unknown'
            $result.adbBackupProbe = '探针异常: ' + $_.Exception.Message
        } finally {
            if ($process -ne $null) {
                try {
                    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
                } catch { }
            }
            Remove-Item $outFile -Force -ErrorAction SilentlyContinue
            Remove-Item $errFile -Force -ErrorAction SilentlyContinue
            if ($keepFile) {
                $result.adbBackupProbe = $result.adbBackupProbe + '(探针文件: ' + $probeFile + ')'
            } else {
                # 让设备侧的备份会话结束(部分 ROM 需要返回键退出确认页)
                Invoke-Adb @('shell', 'input keyevent 4') | Out-Null
                Remove-Item $probeFile -Force -ErrorAction SilentlyContinue
            }
        }
    }
} elseif ($result.installed) {
    $result.adbBackupState = 'skipped'
    $result.adbBackupAvailable = $result.allowBackup
    $result.adbBackupProbe = '未探测(按 allowBackup 推断)'
}

# ---- 结论 ----
if (-not $result.installed) {
    $result.path = 'C'
    $result.recommendedMethod = 'unavailable'
    $result.recommendedCommand = ''
} elseif ($result.runAsAvailable) {
    $result.path = 'B'
    $result.recommendedMethod = 'runas'
    $result.recommendedCommand = 'pwsh -File tools/export-legacy-data.ps1 -Method runas'
} elseif ($result.adbBackupAvailable) {
    $result.path = 'A'
    $result.recommendedMethod = 'backup'
    $result.recommendedCommand = 'pwsh -File tools/export-legacy-data.ps1 -Method backup -AutoConfirm'
} elseif ($result.providerAvailable) {
    $result.path = 'C'
    $result.recommendedMethod = 'provider'
    $result.recommendedCommand = 'pwsh -File tools/export-detected-json.ps1'
} else {
    $result.path = 'C'
    $result.recommendedMethod = 'unavailable'
    $result.recommendedCommand = ''
}

if ($Json) {
    [pscustomobject]$result | ConvertTo-Json -Compress
    exit 0
}

$installedText = '否'
if ($result.installed) { $installedText = '是' }
$debugText = '否'
if ($result.debuggable) { $debugText = '是(run-as 可用)' }
$allowText = '否'
if ($result.allowBackup) { $allowText = '是' }
$backupText = '不可用'
if ($result.adbBackupAvailable) { $backupText = '可用' }
$runAsText = '不可用'
if ($result.runAsAvailable) { $runAsText = '可用' }
$providerText = '不可用'
if ($result.providerAvailable) { $providerText = '可用(兜底:只有库,没有设置)' }

Write-Host ''
Write-Host ('==== 上一版 app 探测: ' + $ApplicationId + ' ====') -ForegroundColor Cyan
Write-Host ('  设备            ' + $result.deviceModel + ' / Android ' + $result.androidRelease + ' / ' + $result.serial)
Write-Host ('  已安装          ' + $installedText)
if ($result.installed) {
    Write-Host ('  versionName     ' + $result.versionName)
    Write-Host ('  versionCode     ' + $result.versionCode)
    Write-Host ('  debuggable      ' + $debugText)
    Write-Host ('  allowBackup     ' + $allowText)
    Write-Host ('  adb backup      ' + $backupText + ' —— ' + $result.adbBackupProbe)
    Write-Host ('  run-as 读库     ' + $runAsText)
    Write-Host ('  adbdb 接口      ' + $providerText)
}
Write-Host '---- 结论 ----' -ForegroundColor Cyan
switch ($result.path) {
    'B' { Write-Host '  推荐路径: B(run-as 读库)—— 保真度最高,含设置' -ForegroundColor Green }
    'A' { Write-Host '  推荐路径: A(adb backup)—— 自动点掉确认框后解包' -ForegroundColor Green }
    default {
        if ($result.recommendedMethod -eq 'provider') {
            Write-Host '  推荐路径: C(两条主路都不可用),只能走 adbdb 接口兜底(不含设置)' -ForegroundColor Yellow
        } else {
            Write-Host '  推荐路径: C(不可用)—— 需要手动处理(截图/手抄歌单与循环记录,或换 debuggable 构建)' -ForegroundColor Red
        }
    }
}
if ($result.recommendedCommand -ne '') {
    Write-Host ('  导出命令: ' + $result.recommendedCommand) -ForegroundColor Green
}
Write-Host ''
