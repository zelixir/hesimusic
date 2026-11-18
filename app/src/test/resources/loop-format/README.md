# 格式矩阵样本(loop-format)

5 个**短小真实编码**样本,JVM 单测与真机脚本共用。全部由同一段确定性合成信号编码而来,
因此可以跨容器对比"取 [A,B) 的边界行为",同时体积都在 200KB 以内(<3.2s / 4.0s)。

| 文件 | 容器/编码 | 采样率 | 声道 | 容器总样本 | 编码器前导 | 用户时长 | 大小 |
|---|---|---|---|---|---|---|---|
| css-44100-flac.flac | FLAC (16bit) | 44100 | 2 | 141120 | 0 | 3.2s | 174KB |
| css-44100-vorbis.ogg | Ogg Vorbis (q3) | 44100 | 2 | 176400 | 0 | 4.0s | 35KB |
| css-48000-opus.opus | Ogg Opus (96k) | 48000 | 2 | 192312 | 312 (pre-skip) | 4.0s | 88KB |
| css-48000-m4a-aac-lc.m4a | MP4 / AAC-LC (128k) | 48000 | 2 | 193024 | 1024 (elst) | 4.0s | 65KB |
| css-44100-mp3-lame.mp3 | MP3 / LAME (128k) | 44100 | 2 | 178560 | 2160 (delay+padding) | 4.0s | 64KB |

容器事实由 `app/src/test/java/.../loop/analysis/AudioContainerProbe.kt` 直接解析字节得到,
`RingBandFormatMatrixTest` 会把 5 个文件逐一钉住(采样率/声道/总样本/priming/seek 网格)。

## 信号内容

- 左:440Hz + 1319Hz + 3131Hz 三音叠加;
- 右:523.25Hz + 1567Hz + 2637Hz 三音叠加;
- 每 250ms 一个 1ms 的 2k/3k 瞬态尖峰 —— 用于人耳/波形对齐检查;
- 确定性(无随机噪声),同一 ffmpeg 版本可复现。

## 生成命令(ffmpeg 6+,已在本机验证)

```powershell
$L = "0.30*sin(2*PI*440*t)+0.18*sin(2*PI*1319*t)+0.10*sin(2*PI*3131*t)+if(lt(mod(t,0.25),0.001),0.55*sin(2*PI*2000*t),0)"
$R = "0.28*sin(2*PI*523.25*t)+0.17*sin(2*PI*1567*t)+0.09*sin(2*PI*2637*t)+if(lt(mod(t,0.25),0.001),0.50*sin(2*PI*3000*t),0)"

ffmpeg -y -f lavfi -i "aevalsrc=exprs='$L|$R':s=44100:d=3.2" -c:a flac -sample_fmt s16 -compression_level 8 -map_metadata -1 -fflags +bitexact css-44100-flac.flac
ffmpeg -y -f lavfi -i "aevalsrc=exprs='$L|$R':s=44100:d=4" -c:a libvorbis -q:a 3 -map_metadata -1 -fflags +bitexact css-44100-vorbis.ogg
ffmpeg -y -f lavfi -i "aevalsrc=exprs='$L|$R':s=48000:d=4" -c:a libopus -b:a 96k -map_metadata -1 -fflags +bitexact css-48000-opus.opus
ffmpeg -y -f lavfi -i "aevalsrc=exprs='$L|$R':s=48000:d=4" -c:a aac -b:a 128k -movflags +faststart -map_metadata -1 -fflags +bitexact css-48000-m4a-aac-lc.m4a
ffmpeg -y -f lavfi -i "aevalsrc=exprs='$L|$R':s=44100:d=4" -c:a libmp3lame -b:a 128k -map_metadata -1 -fflags +bitexact css-44100-mp3-lame.mp3
```

SHA-256(替换样本时同步更新):

```
ca036d2d825539d79916ee67e1a8347a4304f07e70f44d9005bea532476afa0b  css-44100-flac.flac
82e6deed3575fc02f7f5688594161a2ab35b9970eadf01817805fddd6dbe2620  css-44100-mp3-lame.mp3
88373ab99066993d38c79e131e0a6bb19de8063fd101abf1849b5ceb9b386c5c  css-44100-vorbis.ogg
f71c9cad26c36f627b6a47892cdeeeb322b22b01887b3f6619665fb143398e17  css-48000-m4a-aac-lc.m4a
91b69f989efdf7e3457c04bdcdfee32e4d00623f6298410aacb0d3a8b78e488b  css-48000-opus.opus
```

## 两条验证通道

1. **JVM(本仓库单测,无设备)**:`RingBandFormatMatrixTest` —— 只验证容器事实与 [A,B) 帧号算术;
   不伪造 PCM,因为 `PcmDecoder` 依赖真实 `MediaCodec`。
2. **真机(唯一能给出逐字节/对齐偏差的通道)**:`tools/format-matrix.ps1` ——
   推送这些文件到 `/sdcard/Music/demo/`,调 `POST /debug/decode-range`,
   对同一区间解两次比较 SHA-256(确定性),并比较宽窗口裁剪后的帧数与 PCM 尾部。
