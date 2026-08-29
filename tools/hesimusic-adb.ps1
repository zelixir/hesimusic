# 海瑟音乐 ADB 数据库接口封装（PowerShell）
# 用法示例：
#   .\tools\hesimusic-adb.ps1 schema
#   .\tools\hesimusic-adb.ps1 query "SELECT id,name FROM playlists"
#   .\tools\hesimusic-adb.ps1 exec "INSERT INTO playlists(name) VALUES('通勤')"
#   .\tools\hesimusic-adb.ps1 create-view "近年日语歌" "SELECT s.* FROM songs s WHERE s.year >= '2023' ORDER BY s.title"
#   .\tools\hesimusic-adb.ps1 list-views
#   .\tools\hesimusic-adb.ps1 delete-view -Name "近年日语歌"
#   .\tools\hesimusic-adb.ps1 export -Out backup.json
#   .\tools\hesimusic-adb.ps1 import -File backup.json
# 协议文档见 docs/adb-api.md
param(
    [Parameter(Position = 0, Mandatory = $true)]
    [string]$Command,

    [Parameter(Position = 1)]
    [string]$Arg1,

    [Parameter(Position = 2)]
    [string]$Arg2,

    [string]$Name,
    [string]$Out,
    [string]$File
)

$ErrorActionPreference = "Stop"
# 让设备返回的 UTF-8 中文正常显示
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# 定位 adb：优先 PATH，其次默认 SDK 位置
$Adb = "adb"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdb) { $Adb = $sdkAdb }
    else { throw "未找到 adb，请将 platform-tools 加入 PATH" }
}

$Uri = "content://com.zjr.hesimusic.adbdb"
$Pkg = "com.zjr.hesimusic"

function Invoke-Db([string]$Method, [string]$DbArg = "") {
    # 设备 shell 会对参数做二次解析：整体用单引号包住，内部单引号按 '\'' 转义
    $cmd = "content call --uri $Uri --method $Method"
    if ($DbArg -ne "") {
        $escaped = $DbArg.Replace("'", "'\''")
        $cmd = "$cmd --arg '$escaped'"
    }
    $raw = & $Adb shell $cmd
    Write-Output ($raw -join "`n")
}

switch ($Command) {
    "query"       { Invoke-Db "query" $Arg1 }
    "exec"        { Invoke-Db "exec" $Arg1 }
    "schema"      { Invoke-Db "schema" }
    "list-views"  { Invoke-Db "list_views" }
    "export"      {
        Invoke-Db "export" | Out-Null
        $json = & $Adb shell run-as $Pkg cat files/adb_export.json
        if ($Out -ne "") {
            $json | Out-File -FilePath $Out -Encoding utf8
            Write-Output "[已保存到 $Out]"
        } else {
            Write-Output $json
        }
    }
    "import"      {
        if ($File -eq "") { throw "import 需要 -File <本地备份json>" }
        & $Adb push $File /data/local/tmp/hesi_import.json
        & $Adb shell "cat /data/local/tmp/hesi_import.json | run-as $Pkg sh -c 'cat > files/adb_restore.json'"
        & $Adb shell rm /data/local/tmp/hesi_import.json
        Invoke-Db "import"
    }
    { $_ -in "create-view", "update-view" } {
        if ($Arg1 -eq "" -or $Arg2 -eq "") {
            if ($Name -eq "" -or $Arg1 -eq "") { throw "用法: $Command <名称> <SQL> 或 -Name <名称> <SQL>" }
            $Arg2 = $Arg1; $Arg1 = $Name
        }
        # 组装 JSON：SQL 内部的双引号转义后作为单个 --arg 传给设备 shell
        $json = '{"name":"' + ($Arg1 -replace '"', '\"') + '","sql":"' + ($Arg2 -replace '"', '\"') + '"}'
        Invoke-Db "create_view" $json
    }
    "validate-view" {
        if ($Arg1 -eq "") { throw "用法: validate-view <SQL>" }
        $json = '{"sql":"' + ($Arg1 -replace '"', '\"') + '"}'
        Invoke-Db "validate_view" $json
    }
    "delete-view" {
        if ($Name -ne "") {
            $json = '{"name":"' + $Name + '"}'
        } elseif ($Arg1 -match '^\d+$') {
            $json = '{"id":' + $Arg1 + '}'
        } else {
            $json = '{"name":"' + $Arg1 + '"}'
        }
        Invoke-Db "delete_view" $json
    }
    default { throw "未知命令: $Command（支持 query/exec/schema/list-views/create-view/update-view/validate-view/delete-view/export/import）" }
}
