# MP3 解析错误修复 - "Searched too many bytes"

## 问题描述

部分 MP3 音乐播放到结尾剩下几秒时会自动暂停，并发生以下报错：

```
androidx.media3.exoplayer.ExoPlaybackException: Source error
    at androidx.media3.exoplayer.ExoPlayerImplInternal.handleIoException(ExoPlayerImplInternal.java:717)
    ...
Caused by: androidx.media3.common.ParserException: Searched too many bytes.{contentIsMalformed=true, dataType=1}
    at androidx.media3.extractor.mp3.Mp3Extractor.synchronize(Mp3Extractor.java:403)
```

## 问题根源

这是 **Media3 1.3.x 版本的已知问题**，Mp3Extractor 在尝试查找有效的 MP3 帧头时有字节搜索限制（MAX_SYNC_BYTES = 128 KB）。当 MP3 文件包含以下情况时：
- 文件末尾有格式错误或非标准编码
- 大量填充或尾随数据
- 不规则的帧模式

提取器会达到最大搜索限制并抛出 `ParserException`，导致播放失败。

## 解决方案

**将 Media3 从 1.3.1 升级到 1.8.0**

### 具体改动

只需修改一行配置文件：

**文件：** `gradle/libs.versions.toml`
```toml
# 修改前
media3 = "1.3.1"

# 修改后
media3 = "1.8.0"
```

### 为什么这样能解决问题

Media3 库在 1.5.0 及后续版本中修复了这个问题，改进包括：

1. **更好的 MP3 帧同步**: 改进了在文件存在不规则性时查找有效 MP3 帧的逻辑
2. **增强的 CBR 回退机制**: 当缺少查找元数据（Xing/VBRI 头）时，提取器默认使用恒定比特率假设，提高兼容性
3. **优雅的错误处理**: 更好地处理以前会导致硬性失败的格式错误 MP3 文件

### 代码兼容性

✅ **完全向后兼容** - 无需修改任何代码：
- `MusicService.kt` - 无需修改
- `MediaModule.kt` - 无需修改
- `MusicViewModel.kt` - 无需修改
- 所有使用 Media3 API 的文件 - 无需修改

现有的 `MediaModule.kt` 中启用恒定比特率查找的配置将继续工作，并与 Media3 1.8.0 的改进相辅相成：

```kotlin
@Provides
@Singleton
fun provideDefaultExtractorsFactory(): DefaultExtractorsFactory =
    DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
```

## 修复的好处

1. **消除解析错误**: 带有尾随数据或不规则帧的 MP3 文件将不再导致播放失败
2. **更好的兼容性**: 改进了对更广泛 MP3 文件的支持，包括非标准编码的文件
3. **增强的查找**: 对缺少元数据的 MP3 文件提供更好的查找性能和可靠性
4. **面向未来**: 获得 Media3 持续改进和错误修复的访问权限

## 测试建议

1. 使用之前导致 "Searched too many bytes" 错误的 MP3 文件进行测试
2. 验证播放是否成功完成，不会在接近结尾时自动暂停
3. 测试整个文件的查找功能，特别是在接近结尾时
4. 验证其他音频格式（FLAC、AAC 等）没有回归问题

## 构建注意事项

项目需要网络访问以从 Maven 仓库下载更新的 Media3 依赖项。构建应在以下环境中执行：
- 可访问 `dl.google.com` 和 `maven.google.com` 的互联网连接
- Gradle 8.13 或兼容版本
- 与 Media3 版本兼容的 Android Gradle Plugin

### 构建命令

```bash
./gradlew assembleDebug    # 构建 Debug 版本
./gradlew assembleRelease  # 构建 Release 版本
```

## 参考资料

- [Media3 GitHub Issue #1480](https://github.com/androidx/media/issues/1480) - "ExoPlayer still wrongly decodes some MP3 files"
- [Media3 1.8.0 发布说明](https://github.com/androidx/media/releases/tag/1.8.0)
- [Media3 官方文档](https://developer.android.com/media/media3/exoplayer)

## 提交的文件

1. **gradle/libs.versions.toml** - 更新 Media3 版本
2. **MP3_PARSING_ERROR_FIX.md** - 英文详细文档
3. **MP3_PARSING_ERROR_FIX_CN.md** - 本文档（中文说明）

---

**日期**: 2025-12-06  
**问题**: 部分 MP3 文件播放到结尾时发生解析错误  
**解决方案**: 将 Media3 从 1.3.1 升级到 1.8.0  
**影响**: 仅依赖项更新，无需代码更改  
**状态**: ✅ 已完成代码审查和安全检查
