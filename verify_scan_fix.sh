#!/bin/bash
# 扫描进度修复验证脚本
# 此脚本帮助验证扫描进度更新功能是否正常工作

echo "================================================"
echo "  扫描音乐进度更新修复 - 验证清单"
echo "================================================"
echo ""

echo "📋 修复内容检查："
echo ""

# 检查关键文件是否存在修改
echo "✓ 检查修改的文件..."
files=(
    "hesimusic-client/app/src/main/java/zelixir/hesimusic/scan/ScanManager.kt"
    "hesimusic-client/app/src/main/java/com/hesimusic/MainActivity.kt"
    "hesimusic-client/app/src/main/java/zelixir/hesimusic/scan/ScanWorker.kt"
    "hesimusic-client/app/src/main/java/zelixir/hesimusic/scan/ScanWebBridge.kt"
)

for file in "${files[@]}"; do
    if [ -f "$file" ]; then
        echo "  ✓ $file 存在"
    else
        echo "  ✗ $file 不存在"
    fi
done

echo ""
echo "✓ 检查关键代码更改..."

# 检查 ScanManager 是否有 progressCallback
if grep -q "progressCallback" hesimusic-client/app/src/main/java/zelixir/hesimusic/scan/ScanManager.kt; then
    echo "  ✓ ScanManager.kt 包含 progressCallback"
else
    echo "  ✗ ScanManager.kt 缺少 progressCallback"
fi

# 检查 ScanManager 是否有 setProgressCallback
if grep -q "setProgressCallback" hesimusic-client/app/src/main/java/zelixir/hesimusic/scan/ScanManager.kt; then
    echo "  ✓ ScanManager.kt 包含 setProgressCallback()"
else
    echo "  ✗ ScanManager.kt 缺少 setProgressCallback()"
fi

# 检查 ScanProgress 是否有 finished 字段
if grep -q "finished: Boolean" hesimusic-client/app/src/main/java/zelixir/hesimusic/scan/ScanManager.kt; then
    echo "  ✓ ScanProgress 包含 finished 字段"
else
    echo "  ✗ ScanProgress 缺少 finished 字段"
fi

# 检查 MainActivity 是否注册了 progressCallback
if grep -q "setProgressCallback" hesimusic-client/app/src/main/java/com/hesimusic/MainActivity.kt; then
    echo "  ✓ MainActivity.kt 注册了 progressCallback"
else
    echo "  ✗ MainActivity.kt 未注册 progressCallback"
fi

# 检查 MainActivity 是否调用了 __music_api_emit__
if grep -q "__music_api_emit__.*scanProgress" hesimusic-client/app/src/main/java/com/hesimusic/MainActivity.kt; then
    echo "  ✓ MainActivity.kt 推送 scanProgress 事件"
else
    echo "  ✗ MainActivity.kt 未推送 scanProgress 事件"
fi

# 检查 ScanWorker 是否在完成时设置 finished=true
if grep -q "finished = true" hesimusic-client/app/src/main/java/zelixir/hesimusic/scan/ScanWorker.kt; then
    echo "  ✓ ScanWorker.kt 在完成时设置 finished=true"
else
    echo "  ✗ ScanWorker.kt 未在完成时设置 finished=true"
fi

# 检查 ScanWebBridge 是否支持 options.folders
if grep -q "options.*folders" hesimusic-client/app/src/main/java/zelixir/hesimusic/scan/ScanWebBridge.kt; then
    echo "  ✓ ScanWebBridge.kt 支持 options.folders"
else
    echo "  ✗ ScanWebBridge.kt 不支持 options.folders"
fi

echo ""
echo "================================================"
echo "  运行时验证步骤"
echo "================================================"
echo ""
echo "1. 构建并安装 APK 到设备"
echo "   cd hesimusic-client"
echo "   ./gradlew assembleDebug"
echo "   adb install -r app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "2. 启动应用并打开扫描页面"
echo ""
echo "3. 打开 Chrome DevTools (chrome://inspect)"
echo "   - 连接到设备上的 WebView"
echo "   - 打开 Console 标签"
echo ""
echo "4. 点击'添加文件夹'选择包含音乐文件的目录"
echo ""
echo "5. 点击'开始扫描'并观察："
echo "   a) Console 应该显示："
echo "      [scanApi] startScan -> calling MusicBridge"
echo "      [musicBridge] calling ScanBridge.startScanFromJs"
echo "      [musicBridge] __music_api_emit__ { name: 'scanProgress', payload: {...} }"
echo ""
echo "   b) UI 应该显示："
echo "      '已扫描: X' (数字持续增加)"
echo "      '当前文件: /path/to/file' (不断更新)"
echo ""
echo "   c) 扫描完成后："
echo "      Console 显示 finished: true"
echo "      按钮从'扫描中...'恢复到'开始扫描'"
echo ""
echo "6. 查看 Logcat 日志 (可选)："
echo "   adb logcat | grep -E 'ScanManager|MainActivity|ScanWorker'"
echo ""
echo "   应该看到："
echo "   - ScanManager: updateProgress called"
echo "   - MainActivity: Forwarding scanProgress to webview"
echo ""
echo "================================================"
echo "  预期结果"
echo "================================================"
echo ""
echo "✅ 点击'开始扫描'后立即看到进度更新"
echo "✅ '已扫描'数字实时增加"
echo "✅ '当前文件'路径实时更新"
echo "✅ 扫描完成后按钮状态正确恢复"
echo "✅ 不再出现超时错误"
echo ""
echo "================================================"
echo "  故障排除"
echo "================================================"
echo ""
echo "如果进度仍然不更新："
echo ""
echo "1. 检查 WebView JavaScript 是否启用"
echo "   - MainActivity.kt: webView.settings.javaScriptEnabled = true"
echo ""
echo "2. 检查 ScanBridge 是否正确注入"
echo "   - MainActivity.kt: webView.addJavascriptInterface(ScanWebBridge(...), 'ScanBridge')"
echo ""
echo "3. 检查是否有 JavaScript 错误"
echo "   - Chrome DevTools Console 是否有错误信息"
echo ""
echo "4. 检查参数是否正确传递"
echo "   - Console 中查看 startScanFromJs 的 payload 参数"
echo "   - 应该包含 { options: { folders: [...] } }"
echo ""
echo "5. 检查回调是否被调用"
echo "   - Logcat 中搜索 'Progress callback'"
echo "   - 应该看到多次回调被触发"
echo ""
echo "================================================"

# 检查是否有 git 更改
echo ""
echo "📊 Git 状态："
git status --short
echo ""

echo "✅ 验证脚本完成"
echo ""
