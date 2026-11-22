# 被 detect-legacy-app.ps1 / export-legacy-data.ps1 / v2-import-drill.ps1 dot-source。
# 作用:adb backup 会在设备上弹出系统确认框(com.android.backupconfirm),
# 无人值守时用 uiautomator 找到「备份我的数据」并点掉(密码留空 = 不加密)。

function Get-BackupDialogButton {
    param([string]$AdbPath, [string[]]$AdbTarget)

    $dumpPath = '/sdcard/hesi-backup-ui.xml'
    & $AdbPath @AdbTarget shell ('uiautomator dump ' + $dumpPath) 2>$null | Out-Null
    $xml = (& $AdbPath @AdbTarget shell ('cat ' + $dumpPath) 2>$null | Out-String)
    if ($xml -notmatch 'backupconfirm') { return $null }

    $candidates = @()
    foreach ($match in [regex]::Matches($xml, '<node\b[^>]*>')) {
        $node = $match.Value
        if ($node -notmatch 'class="android\.widget\.Button"') { continue }
        if ($node -notmatch 'clickable="true"') { continue }
        $text = ''
        if ($node -match 'text="([^"]*)"') { $text = $Matches[1] }
        if ($node -notmatch 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') { continue }
        $left = [int]$Matches[1]; $top = [int]$Matches[2]; $right = [int]$Matches[3]; $bottom = [int]$Matches[4]
        $candidates += [pscustomobject]@{
            Text = $text
            X = [int](($left + $right) / 2)
            Y = [int](($top + $bottom) / 2)
            Right = $right
        }
    }
    if ($candidates.Count -eq 0) { return $null }

    # ⚠️ 「不备份」里也含"备份"两字,必须先按精确文案匹配,绝不能只做包含匹配
    # (踩过:包含匹配 + 取第一个 → 点成"不备份",adb backup 退出码 0 但产出 0 字节)。
    $exact = $candidates | Where-Object {
        $_.Text -match '^(备份我的数据|Back up my data|BACK UP MY DATA|Backup my data|開始備份|开始备份)$'
    } | Select-Object -First 1
    if ($exact -ne $null) { return $exact }

    $positive = $candidates | Where-Object {
        $_.Text -match '备份|Back up' -and $_.Text -notmatch '^不|cancel|Cancel|取消'
    } | Select-Object -First 1
    if ($positive -ne $null) { return $positive }

    # 兜底:右下角那个按钮通常是正向操作(取消在左边)
    return ($candidates | Sort-Object -Property Right -Descending | Select-Object -First 1)
}

function Confirm-AdbBackupDialog {
    param(
        [string]$AdbPath,
        [string[]]$AdbTarget,
        [int]$WaitSeconds = 20
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    while ((Get-Date) -lt $deadline) {
        $top = (& $AdbPath @AdbTarget shell 'dumpsys activity activities' 2>$null | Out-String)
        if ($top -match 'com\.android\.backupconfirm') {
            $button = Get-BackupDialogButton -AdbPath $AdbPath -AdbTarget $AdbTarget
            if ($button -ne $null) {
                & $AdbPath @AdbTarget shell ('input tap ' + $button.X + ' ' + $button.Y) 2>$null | Out-Null
                return [pscustomobject]@{ Confirmed = $true; ButtonText = $button.Text }
            }
        }
        Start-Sleep -Milliseconds 500
    }
    return [pscustomobject]@{ Confirmed = $false; ButtonText = '' }
}
