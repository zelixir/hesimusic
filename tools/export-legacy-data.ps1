<#
.SYNOPSIS
    把设备上「上一版」海瑟音乐(com.zjr.hesimusic)的歌单 / 循环记录 / 收藏 / 隐藏 / SQL 歌单 / 设置
    导出成 Hesi v2 可导入的 JSON,并推到设备的 Download/hessi-import/<时间戳>/。

.DESCRIPTION
    红线:本脚本只读上一版 app —— 不装包、不卸载、不改它的数据、不加任何"导出按钮"。
    三条导出路径(auto 依次尝试):

      runas     用 run-as 直接拉 databases/hesimusic_db(+wal/shm)与 shared_prefs/*.xml。
                要求上一版是 debuggable 构建(日常开发包满足);保真度最高,含设置。
      provider  走上一版已经存在的 adb 数据库接口(content://com.zjr.hesimusic.adbdb),
                用 content call --method query 分页读表。任何构建都能用(release 也可),
                但拿不到 SharedPreferences(设置)与数据库版本号。
      backup    adb backup 出 .ab,再解包(去头 + inflate + tar)读库。不需要 debuggable,
                但 Android 12+ 部分 ROM 已禁用它,且设备上要手动确认。

    产物:legacy-export.json = 上一版 app 的备份方言(version 4)+ 只读元信息,
    见 docs/import-legacy-data.md。

.PARAMETER Method
    auto(默认)/ runas / provider / backup。auto = runas -> provider -> backup,前一个不可用才降级。

.PARAMETER Serial
    多设备时指定 adb serial(默认唯一在线的设备)。

.PARAMETER ApplicationId
    上一版包名,默认 com.zjr.hesimusic。测试时可指向 com.zjr.hesimusic.dev。

.PARAMETER WorkDir
    PC 侧工作目录(默认 <repo>/backups/exports/<时间戳>,该目录已被 .gitignore 忽略)。

.PARAMETER DeviceDir
    推到设备的目标目录,默认 /sdcard/Download/hessi-import/<时间戳>。

.PARAMETER SkipPush
    只生成本地 JSON,不往设备推(如需先人工检查)。

.PARAMETER ForceStop
    导出前先 am force-stop 上一版,保证数据库快照一致。会打断正在播放的音乐。

.PARAMETER PageSize
    provider 模式每页行数(上一版接口单次最多 500 行)。

.EXAMPLE
    pwsh -File tools/export-legacy-data.ps1
    pwsh -File tools/export-legacy-data.ps1 -Method provider -SkipPush
    pwsh -File tools/export-legacy-data.ps1 -ApplicationId com.zjr.hesimusic.dev -WorkDir backups\exports\dev
#>

[CmdletBinding()]
param(
    [ValidateSet('auto', 'runas', 'provider', 'backup')]
    [string]$Method = 'auto',

    [string]$Serial = '',

    [string]$ApplicationId = 'com.zjr.hesimusic',

    [string]$WorkDir = '',

    [string]$DeviceDir = '',

    [switch]$SkipPush,

    [switch]$ForceStop,

    # adb backup 的系统确认框由脚本自动点掉(uiautomator),用于无人值守演练。
    [switch]$AutoConfirm,

    # 复用已经存在的 .ab(例如 detect-legacy-app.ps1 -ProbeOutput 的产物),跳过再次备份。
    [string]$AbFile = '',

    # adb backup 最长等待秒数(自动确认模式下)。
    [int]$BackupTimeoutSeconds = 300,

    [int]$PageSize = 500
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'legacy-backup-confirm.ps1')

function Write-Step([string]$Message) { Write-Host ('==> ' + $Message) -ForegroundColor Cyan }
function Write-Note([string]$Message) { Write-Host ('    ' + $Message) -ForegroundColor DarkGray }
function Write-Warn([string]$Message) { Write-Host ('[!] ' + $Message) -ForegroundColor Yellow }

# 设备返回 UTF-8 中文;PS 5.1 默认按 OEM 代码页解码会把标题弄成乱码。
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }
try { $OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }

$repoRoot = Split-Path -Parent $PSScriptRoot
$AdbTarget = @()
if ($Serial -ne '') { $AdbTarget = @('-s', $Serial) }

function Resolve-Adb {
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

$script:Adb = Resolve-Adb

function Invoke-Adb([string[]]$AdbArgs) {
    $output = & $script:Adb @AdbTarget @AdbArgs 2>&1
    $code = $LASTEXITCODE
    $lines = @()
    foreach ($line in @($output)) { $lines += [string]$line }
    return [pscustomobject]@{ Lines = $lines; ExitCode = $code; Text = [string]::Join([Environment]::NewLine, $lines) }
}

function Invoke-AdbShell([string]$Command) {
    return Invoke-Adb @('shell', $Command)
}

# 二进制安全地把 adb 的 stdout 写进文件(不能用 PS 的 > 重定向:它会把输出当文本转码)。
function Export-AdbStdout([string[]]$AdbArgs, [string]$FilePath) {
    $errorFile = $FilePath + '.stderr'
    $startArgs = @{
        FilePath = $script:Adb
        ArgumentList = ($AdbTarget + $AdbArgs)
        NoNewWindow = $true
        Wait = $true
        PassThru = $true
        RedirectStandardOutput = $FilePath
        RedirectStandardError = $errorFile
    }
    $process = Start-Process @startArgs
    $errorText = ''
    if (Test-Path $errorFile) {
        $errorText = [System.IO.File]::ReadAllText($errorFile)
        Remove-Item $errorFile -Force -ErrorAction SilentlyContinue
    }
    return [pscustomobject]@{ ExitCode = $process.ExitCode; Error = $errorText }
}

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

# ---------------------------------------------------------------------------
# 0. 设备与包信息
# ---------------------------------------------------------------------------

Write-Step '检查设备与目标包'
$state = Invoke-Adb @('get-state')
if ($state.ExitCode -ne 0 -or $state.Text.Trim() -ne 'device') {
    $devices = Invoke-Adb @('devices')
    throw ('没有可用的设备(adb get-state 失败):' + [Environment]::NewLine + $devices.Text)
}
$release = (Invoke-AdbShell 'getprop ro.build.version.release').Text.Trim()
$model = (Invoke-AdbShell 'getprop ro.product.model').Text.Trim()
$deviceSerial = (Invoke-AdbShell 'getprop ro.serialno').Text.Trim()
if ($Serial -ne '') { $deviceSerial = $Serial }
Write-Note ('设备: ' + $model + ' / Android ' + $release + ' / serial ' + $deviceSerial)

$packagePath = Invoke-AdbShell ('pm path ' + $ApplicationId)
if ($packagePath.Text -notmatch 'package:') {
    throw ('设备上没有安装 ' + $ApplicationId + ':先确认包名,或安装一个可测试的构建。')
}
$packageDump = Invoke-AdbShell ('dumpsys package ' + $ApplicationId)
$versionName = ''
$versionCode = 0
$debuggable = $false
foreach ($line in $packageDump.Lines) {
    if ($line -match 'versionName=(\S+)') { $versionName = $Matches[1] }
    if ($line -match 'versionCode=(\d+)') { $versionCode = [int64]$Matches[1] }
    if ($line -match 'DEBUGGABLE') { $debuggable = $true }
}
Write-Note ('包: ' + $ApplicationId + ' ' + $versionName + ' (versionCode ' + $versionCode + ', debuggable=' + $debuggable + ')')

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if ($WorkDir -eq '') { $WorkDir = Join-Path $repoRoot ('backups\exports\' + $stamp) }
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$WorkDir = (Resolve-Path $WorkDir).Path
if ($DeviceDir -eq '') { $DeviceDir = '/sdcard/Download/hessi-import/' + $stamp }
Write-Note ('工作目录: ' + $WorkDir)
Write-Note ('设备目录: ' + $DeviceDir)

# ---------------------------------------------------------------------------
# 1. 试探三条路径是否可用
# ---------------------------------------------------------------------------

function Test-RunasAvailable {
    if ($debuggable -ne $true) { return $false }
    $probe = Invoke-AdbShell ('run-as ' + $ApplicationId + ' ls databases/hesimusic_db')
    return ($probe.Text -match 'hesimusic_db')
}

function Test-ProviderAvailable {
    $command = "content call --uri content://$ApplicationId.adbdb --method query --arg 'SELECT COUNT(*) AS n FROM songs'"
    $command = $command.Replace('"', '\"')
    $probe = Invoke-AdbShell $command
    return ($probe.Text -match '"rows"')
}

function Test-BackupAvailable {
    $help = Invoke-Adb @('backup')
    return ($help.Text -match 'backup|Backup|usage')
}

$available = [ordered]@{ runas = (Test-RunasAvailable); provider = (Test-ProviderAvailable); backup = (Test-BackupAvailable) }
Write-Note ('可用路径: ' + (($available.GetEnumerator() | Where-Object { $_.Value } | ForEach-Object { $_.Key }) -join ', '))

$usedMethod = ''
if ($Method -eq 'auto') {
    foreach ($candidate in @('runas', 'provider', 'backup')) {
        if ($available[$candidate]) { $usedMethod = $candidate; break }
    }
    if ($usedMethod -eq '') { throw '三条导出路径都不可用:请确认上一版包名,或在设备上允许 adb 调试。' }
} else {
    if (-not $available[$Method]) {
        throw ('指定的方法不可用: ' + $Method + '(runas 需要 debuggable 构建;provider 需要上一版包含 adb 数据库接口;backup 需要 adb 支持)')
    }
    $usedMethod = $Method
}
Write-Step ('使用导出路径: ' + $usedMethod)

if ($ForceStop) {
    Write-Warn '按 -ForceStop 先停止上一版(会打断正在播放的音乐),保证数据库快照一致'
    Invoke-AdbShell ('am force-stop ' + $ApplicationId) | Out-Null
    Start-Sleep -Milliseconds 500
}

# ---------------------------------------------------------------------------
# 2. 拉数据
# ---------------------------------------------------------------------------

$dbDir = Join-Path $WorkDir 'db'
$prefsDir = Join-Path $WorkDir 'prefs'
$tablesDir = Join-Path $WorkDir 'tables'

# 拉一个设备文件到本地(二进制安全)。adb 偶尔会在服务器刚起来时产出 0 字节,这里重试几次。
function Export-AdbFile([string]$RemotePath, [string]$LocalPath, [switch]$Required) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $result = Export-AdbStdout @('exec-out', ('run-as ' + $ApplicationId + ' cat ' + $RemotePath)) $LocalPath
        if ($result.ExitCode -eq 0 -and (Test-Path $LocalPath) -and (Get-Item $LocalPath).Length -gt 0) {
            return $true
        }
        Start-Sleep -Milliseconds 300
    }
    if ($Required) {
        throw ('run-as 拉取 ' + $RemotePath + ' 失败' + $(if ($result.Error -ne '') { ': ' + $result.Error } else { '' }))
    }
    Remove-Item $LocalPath -Force -ErrorAction SilentlyContinue
    return $false
}

function Get-DatabaseRunAs {
    New-Item -ItemType Directory -Force -Path $dbDir | Out-Null
    [void](Export-AdbFile 'databases/hesimusic_db' (Join-Path $dbDir 'hesimusic_db') -Required)
    Write-Note ('hesimusic_db: ' + (Get-Item (Join-Path $dbDir 'hesimusic_db')).Length + ' bytes')
    foreach ($name in @('hesimusic_db-wal', 'hesimusic_db-shm')) {
        $target = Join-Path $dbDir $name
        if (Export-AdbFile ('databases/' + $name) $target) {
            Write-Note ($name + ': ' + (Get-Item $target).Length + ' bytes')
        } else {
            Write-Note ($name + ': 设备上不存在或为空,跳过')
        }
    }

    New-Item -ItemType Directory -Force -Path $prefsDir | Out-Null
    $listing = Invoke-AdbShell ('run-as ' + $ApplicationId + ' ls shared_prefs')
    $wanted = @('playback_prefs.xml', 'scan_prefs.xml', 'sort_prefs.xml')
    foreach ($name in $wanted) {
        if ($listing.Text -notmatch [regex]::Escape($name)) {
            Write-Note ('设备上没有 ' + $name + ',跳过')
            continue
        }
        $target = Join-Path $prefsDir $name
        if (-not (Export-AdbFile ('shared_prefs/' + $name) $target)) {
            Write-Warn ('拉取 ' + $name + ' 失败,本次不带这份设置')
        }
    }
    $prefCount = 0
    if (Test-Path $prefsDir) { $prefCount = @(Get-ChildItem -File $prefsDir -Filter *.xml).Count }
    Write-Note ('设置文件: ' + $prefCount + ' 个(只取 playback_prefs / scan_prefs / sort_prefs)')
}

function ConvertFrom-ContentCall([string]$Raw) {
    $index = $Raw.IndexOf('result=')
    if ($index -lt 0) {
        if ($Raw -match 'error=') { throw ('上一版 adb 接口报错: ' + $Raw) }
        throw ('无法解析 content call 输出: ' + $Raw.Substring(0, [Math]::Min(200, $Raw.Length)))
    }
    # 输出形如: Result: Bundle[{result={...}, ok=true}] —— 从尾部往前收缩找第一个能解析的 JSON。
    $json = $Raw.Substring($index + 7).Trim()
    $position = $json.LastIndexOf('}')
    while ($position -ge 0) {
        $candidate = $json.Substring(0, $position + 1)
        try {
            $parsed = ConvertFrom-Json -InputObject $candidate
            if ($parsed -ne $null) { return $parsed }
        } catch { }
        $position = $json.LastIndexOf('}', $position - 1)
    }
    throw 'content call 返回的 JSON 解析失败(可能是输出被截断):可改用 -Method runas 或 backup'
}

function Invoke-ProviderQuery([string]$Sql) {
    $command = "content call --uri content://$ApplicationId.adbdb --method query"
    $escaped = $Sql.Replace("'", "'\''")
    $command = $command + " --arg '" + $escaped + "'"
    $command = $command.Replace('"', '\"')
    $response = Invoke-AdbShell $command
    if ($response.ExitCode -ne 0) { throw ('content call 失败: ' + $response.Text) }
    return ConvertFrom-ContentCall $response.Text
}

function Get-TableProvider([string]$Table, [string]$KeyColumn) {
    $rows = New-Object System.Collections.ArrayList
    $last = 0
    $page = 0
    while ($true) {
        $page++
        $sql = 'SELECT * FROM ' + $Table + ' WHERE ' + $KeyColumn + ' > ' + $last + ' ORDER BY ' + $KeyColumn
        $parsed = Invoke-ProviderQuery $sql
        $pageRows = @()
        if ($parsed.PSObject.Properties.Name -contains 'rows') { $pageRows = @($parsed.rows) }
        if ($pageRows.Count -eq 0) { break }
        foreach ($row in $pageRows) { [void]$rows.Add($row) }
        $lastValue = $pageRows[$pageRows.Count - 1].$KeyColumn
        if ($lastValue -eq $null) { break }
        $last = [int64]$lastValue
        if ($pageRows.Count -lt $PageSize) { break }
    }
    Write-Note ('表 ' + $Table + ': ' + $rows.Count + ' 行( ' + $page + ' 页 )')
    return , $rows
}

function Get-DatabaseProvider {
    New-Item -ItemType Directory -Force -Path $tablesDir | Out-Null
    $specs = @(
        @{ Table = 'songs'; File = 'songs'; Key = 'id' },
        @{ Table = 'song_loop'; File = 'song_loop'; Key = 'songId' },
        @{ Table = 'playlists'; File = 'playlists'; Key = 'id' },
        @{ Table = 'playlist_entries'; File = 'playlist_entries'; Key = 'id' },
        @{ Table = 'favorites'; File = 'favorites'; Key = 'rowid' },
        @{ Table = 'hidden_songs'; File = 'hidden_songs'; Key = 'rowid' },
        @{ Table = 'smart_playlists'; File = 'smart_playlists'; Key = 'id' }
    )
    foreach ($spec in $specs) {
        $rows = Get-TableProvider $spec.Table $spec.Key
        $json = ConvertTo-Json -InputObject @($rows) -Depth 10 -Compress
        if ($rows.Count -eq 0) { $json = '[]' }
        Write-Utf8NoBom (Join-Path $tablesDir ($spec.File + '.json')) $json
    }
    Write-Warn 'provider 路径拿不到 SharedPreferences(设置)与数据库版本号;需要设置请改用 -Method runas / backup'
}

$abPath = Join-Path $WorkDir 'legacy.ab'

switch ($usedMethod) {
    'runas' { Get-DatabaseRunAs }
    'provider' { Get-DatabaseProvider }
    'backup' {
        if ($AbFile -ne '') {
            if (-not (Test-Path $AbFile)) { throw ('找不到 -AbFile 指定的备份文件: ' + $AbFile) }
            $sourceAb = (Resolve-Path $AbFile).Path
            if ($sourceAb -ne $abPath) {
                Copy-Item -Force $sourceAb $abPath
            }
            Write-Note ('复用已有 .ab: ' + $sourceAb)
        } else {
        $backupArgs = @('backup', '-f', $abPath, '-noapk', '-noshared', $ApplicationId)
        if ($AutoConfirm) {
            Write-Note '自动确认模式:系统确认框会由 uiautomator 点掉(密码留空 = 不加密)'
            $startArgs = @{
                FilePath = $script:Adb
                ArgumentList = ($AdbTarget + $backupArgs)
                NoNewWindow = $true
                PassThru = $true
                RedirectStandardOutput = ($abPath + '.out')
                RedirectStandardError = ($abPath + '.err')
            }
            $process = Start-Process @startArgs
            $confirm = Confirm-AdbBackupDialog -AdbPath $script:Adb -AdbTarget $AdbTarget -WaitSeconds 20
            if ($confirm.Confirmed) {
                Write-Note ('已点掉确认框: ' + $confirm.ButtonText)
            } else {
                Write-Warn '没找到确认框,继续等待(可能设备已锁屏)'
            }
            $deadline = (Get-Date).AddSeconds($BackupTimeoutSeconds)
            while ((Get-Date) -lt $deadline -and -not $process.HasExited) {
                Start-Sleep -Milliseconds 500
            }
            if (-not $process.HasExited) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                throw ('adb backup 超过 ' + $BackupTimeoutSeconds + ' 秒未完成,已中断')
            }
            $stderrText = ''
            if (Test-Path ($abPath + '.err')) { $stderrText = [System.IO.File]::ReadAllText($abPath + '.err') }
            Remove-Item ($abPath + '.out') -Force -ErrorAction SilentlyContinue
            Remove-Item ($abPath + '.err') -Force -ErrorAction SilentlyContinue
            if ($process.ExitCode -ne 0) {
                throw ('adb backup 失败(exit=' + $process.ExitCode + '): ' + $stderrText)
            }
        } else {
            Write-Warn '接下来请在手机上点「备份我的数据」;密码留空(=不加密)'
            $backup = Invoke-Adb $backupArgs
            if (-not (Test-Path $abPath) -or (Get-Item $abPath).Length -eq 0) {
                throw ('adb backup 没有产出文件(部分 ROM 已禁用该命令):' + [Environment]::NewLine + $backup.Text)
            }
        }
        }
        if (-not (Test-Path $abPath) -or (Get-Item $abPath).Length -eq 0) {
            throw 'adb backup 没有产出有效文件(可能被 ROM 禁用,或确认框里选了"不备份")'
        }
        Write-Note ('backup 文件: ' + [math]::Round((Get-Item $abPath).Length / 1MB, 2) + ' MB')
    }
}

# ---------------------------------------------------------------------------
# 3. 转成 v2 导入格式
# ---------------------------------------------------------------------------

Write-Step '生成 legacy-export.json'
$bun = Get-Command bun -ErrorAction SilentlyContinue
if ($bun -eq $null) { throw '找不到 bun:请先安装 Bun(https://bun.sh),脚本用 bun 解析 SQLite / .ab。' }

$sourceJson = Join-Path $WorkDir 'source.json'
$sourceInfo = [ordered]@{
    method = $usedMethod
    serial = $deviceSerial
    applicationId = $ApplicationId
    versionName = $versionName
    versionCode = $versionCode
    androidRelease = $release
    deviceModel = $model
    pulledAt = [int64]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
}
Write-Utf8NoBom $sourceJson (ConvertTo-Json -InputObject ([pscustomobject]$sourceInfo) -Depth 5)

$outFile = Join-Path $WorkDir 'legacy-export.json'
$buildMode = 'db'
if ($usedMethod -eq 'provider') { $buildMode = 'tables' }
if ($usedMethod -eq 'backup') { $buildMode = 'ab' }

$builderArgs = @('run', (Join-Path $PSScriptRoot 'legacy-export-build.ts'), '--mode', $buildMode, '--work', $WorkDir, '--out', $outFile, '--source', $sourceJson)
if ($buildMode -eq 'ab') { $builderArgs += @('--ab', $abPath) }
$builderOutput = & $bun.Source @builderArgs 2>&1
if ($LASTEXITCODE -ne 0) {
    throw ('legacy-export-build.ts 失败:' + [Environment]::NewLine + [string]::Join([Environment]::NewLine, @($builderOutput)))
}
$summaryLine = $null
foreach ($line in @($builderOutput)) {
    $text = [string]$line
    if ($text.TrimStart().StartsWith('{')) { $summaryLine = $text }
}
if ($summaryLine -eq $null) { throw ('没有拿到构建摘要:' + [string]::Join([Environment]::NewLine, @($builderOutput))) }
$summary = ConvertFrom-Json -InputObject $summaryLine

Write-Note ('文件: ' + $summary.out + ' (' + [math]::Round($summary.bytes / 1KB, 1) + ' KB)')
Write-Note ('歌曲 ' + $summary.songs + ' / 循环记录 ' + $summary.songLoops + ' / 歌单 ' + $summary.playlists + '(' + $summary.playlistEntries + ' 条)')
Write-Note ('收藏 ' + $summary.favorites + ' / 隐藏 ' + $summary.hiddenSongs + ' / SQL 歌单 ' + $summary.smartPlaylists + ' / 设置 ' + $summary.preferenceFiles + ' 文件 ' + $summary.preferenceEntries + ' 项')

# ---------------------------------------------------------------------------
# 4. 推到设备
# ---------------------------------------------------------------------------

$deviceFile = $DeviceDir + '/legacy-export.json'
if ($SkipPush) {
    Write-Warn '按 -SkipPush,不推送到设备'
} else {
    Write-Step '推送到设备'
    Invoke-AdbShell ('mkdir -p ' + $DeviceDir) | Out-Null
    $push = Invoke-Adb @('push', $outFile, $deviceFile)
    if ($push.ExitCode -ne 0) { throw ('adb push 失败: ' + $push.Text) }
    $listing = Invoke-AdbShell ('ls -l ' + $deviceFile)
    Write-Note ($listing.Text.Trim())
}

# ---------------------------------------------------------------------------
# 5. 收尾提示
# ---------------------------------------------------------------------------

Write-Host ''
Write-Host '导出完成。' -ForegroundColor Green
Write-Host ('  本地文件: ' + $outFile)
if (-not $SkipPush) { Write-Host ('  设备文件: ' + $deviceFile) }
Write-Host ''
Write-Host '下一步(不构建、不安装,交给 lead):' -ForegroundColor Cyan
Write-Host '  1) 安装 v2 包:com.zjr.hesimusic.v2(与既有 com.zjr.hesimusic 并存,互不影响)'
Write-Host '  2) 打开桌面上的「导入上一版数据」图标,或执行:'
Write-Host ('     adb shell am start -n com.zjr.hesimusic.v2/com.zjr.hesimusic.v2.legacyimport.LegacyImportActivity -d file://' + $deviceFile)
Write-Host '  3) 在页面里选择导出目录,确认预览数字后点「开始导入」'
Write-Host ''
Write-Host '注意:' -ForegroundColor Yellow
Write-Host '  - 导入按 (文件路径, CUE 起点) 匹配歌曲,可重复执行(幂等);'
Write-Host '  - 建议先在 v2 里扫描完曲库再导入,否则本机没有的歌对应的循环记录/歌单条目会被跳过并报告;'
Write-Host '  - v2 重新全量扫描会重建歌曲 id,之后重新导入一次可把歌单条目/循环记录接回新 id。'