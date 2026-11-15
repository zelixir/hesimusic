# spike-analyze fixtures

本目录由 `bun run tools/spike-analyze.ts --selftest` **现场生成**(WAV + JSONL 均不入库,见 .gitignore),用于验证分析器判定正确。

| 文件 | 场景 | 期望 |
|---|---|---|
| 01-continuous-sine | 连续 997Hz 正弦,plain 单段、无 seam | A1/A2 PASS(空真)、A4 PASS、B SKIP |
| 02-seam-silence-5ms | baseline,前奏 44100 帧,接缝处插入 221 帧(5.011ms)静音 | A1 FAIL,定位起点帧 44100、长度 221 帧 |
| 03-seam-drift-100 | baseline,两处接缝帧距 L+100;并伪造一次 20ms PTS 前跳 | A2 FAIL(偏差 +100 帧);PTS 不连续 >=1 |
| 04-identical-rounds | 前奏 + 3 轮逐位相同的 PCM | B PASS、A2 PASS |
| 04b-identical-rounds-tampered | 同 04,第 3 轮帧 12345 改 1 个采样 | B FAIL,定位绝对帧 144645、轮内帧 12345 |
| 05-flush-and-gap | 稳态 t=1200ms 伪造 1 次 flush,喂数间隔 20ms | A4 FAIL(flush>0 硬判) |
| 05b-feed-gap-45ms | 稳态喂数间隔 45ms,无 flush | A4 WARN(参考项,不 FAIL;退出码 0) |
| 05c(复用 05b 产物) | 同上 + 显式 --feed-gap-ms 30 | A4 FAIL(喂数间隔硬判,退出码 1) |
| 06-pts-continuation | 部分消费续写(ret=false / 同 pts 无 ret)+ 1 次真实 PTS 跳变 | 仅 1 处 discontinuity,3 条续写被合并 |
| 07-partial-last-round | 前奏 + 2 完整轮 + 1000 帧收尾部分轮 | B PASS(参与比较 2/3 轮,丢弃 1 轮/1000 帧) |
| 07b-mismatched-middle-round | 中间轮 44000 帧(短于首轮 44100) | B FAIL(轮长不一致) |
| 08-full-b-prelude | B=176400 帧,3 个 seam(src=sink2/trigger=pump) | A3 自动识别 full-b 并 PASS;期望 = B+(N-1)×L;B PASS(2/3 轮) |
| 09-log-only | tap=0,只有 JSONL(占位 WAV 非 RIFF) | 降级为日志判定:A1/B SKIP、A2/A3 PASS、退出码 0 |

重新生成:

    bun run tools/spike-analyze.ts --selftest
