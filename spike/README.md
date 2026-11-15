# spike · P0 机制探针(默认不参与构建)

这个模块是 2026-11-15 P0 阶段用来**客观判定环回机制**的探针:它把真正喂给 AudioTrack 的 PCM 落 WAV、
把音频 sink 的每次调用落 JSONL,再由 `tools/spike-analyze.ts` 离线判定「零间隙 / 零漂移 / 零逐位差 / 零 underrun」。

它已经完成使命:**结论是 M3′(AudioSink 层 PCM 捕获 + 经 delegate 重放)**,证据见
`spike/REPORT-P0-android.md`、`spike/REPORT-P0-m3prime.md`、`spike/out/`(本地,不入库)。

常规构建**不包含**本模块(`settings.gradle.kts` 里已注释掉 `include(":spike")`),以节省构建时间与避免产出多余 APK。

需要复跑机制对比时:

1. 在 `settings.gradle.kts` 里取消 `include(":spike")` 注释;
2. `.\gradlew.bat :spike:installDebug`;
3. `powershell -NoProfile -File tools/spike-run.ps1 -Mode sink2 -Audio '<设备上音频路径>' ...`;
4. `bun run tools/spike-analyze.ts <tap.wav> <tap.jsonl> --mode sink`。

`spike/src/main/java/com/zjr/spike/loopsource/` 是当时 M1(压缩域环回源)的原型,已被判定淘汰,
保留仅供追溯(裁剪边界会被量化到解码帧,每轮多约一帧)。