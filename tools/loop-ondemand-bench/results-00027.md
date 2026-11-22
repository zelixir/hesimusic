# 00027 追问：按需解码离线基准原始输出

生成：tools/loop-ondemand-bench（编译生产类 LoopRing / PcmBlockCache / LoopingPcmSourceCore /
LoopingPcmTimeline / RingBandReader / Pcm16Segment，不复制实现）。
运行：powershell -NoProfile -File tools\loop-ondemand-bench\run.ps1 -Task <task>
JVM：-Xmx384m = 402,653,184B，与 00027 真机堆一致；窗口 A=23.301s B=261.457s（L=238.156s）。

## memory

jvm.maxHeap=384.00MiB  app.ringBudget(heap/6)=64.00MiB  app.blockCacheBudget=32.00MiB
L(s)	frames	ringData	ringData+coverage	cache2s	cache3s	cache5s	cache6s	capacityBlocks	reDecodesPerLoop
10.0	441000	1.68MiB	1.73MiB	344.5KiB	516.8KiB	861.3KiB	1.01MiB	190	0
30.0	1323000	5.05MiB	5.20MiB	344.5KiB	516.8KiB	861.3KiB	1.01MiB	190	0
60.0	2646000	10.09MiB	10.41MiB	344.5KiB	516.8KiB	861.3KiB	1.01MiB	190	0
120.0	5292000	20.19MiB	20.82MiB	344.5KiB	516.8KiB	861.3KiB	1.01MiB	190	0
150.0	6615000	25.23MiB	26.02MiB	344.5KiB	516.8KiB	861.3KiB	1.01MiB	190	0
238.2	10502680	40.06MiB	41.32MiB	344.5KiB	516.8KiB	861.3KiB	1.01MiB	190	49
300.0	13230000	50.47MiB	52.05MiB	344.5KiB	516.8KiB	861.3KiB	1.01MiB	190	110
380.0	16758000	63.93MiB	65.92MiB	344.5KiB	516.8KiB	861.3KiB	1.01MiB	190	190
measured.LoopRing(L=238.156s)=43.00MiB (data=40.06MiB, coverage=1.25MiB)
measured.PcmBlockCache(w=2)=358.9KiB cachedBytes=344.5KiB
measured.PcmBlockCache(w=3)=517.2KiB cachedBytes=516.8KiB
measured.PcmBlockCache(w=5)=862.0KiB cachedBytes=861.3KiB
measured.PcmBlockCache(w=6)=1.01MiB cachedBytes=1.01MiB
capacitySec(cacheBudget)=190.2
ringBudgetSec(heap/6)=380.4

## alloc

path=block.read.current reads=161 allocPerRead=61829B allocPerAudioSecond=162.0KiB readsPerSecond=2.69
path=block.read.pooled     allocTotal=816B (per audio second=0.0KiB)
path=block.install.current allocPerBlock=397377B (388.1KiB) allocPerAudioSecond=388.1KiB
path=block.install.pooled  allocPerBlock=229416B (224.0KiB) allocPerAudioSecond=224.0KiB
ring.fill coveredFrames=1323000/1323000 complete=true contiguous@0=16384
ring.read frames=2637824 shortReads=0 of 161
path=ring.read.current   allocPerRead=65668B allocPerAudioSecond=172.1KiB
sum.current(block)=550.1KiB  sum.pooled(block)=224.1KiB
observed(00027 fallback)=+0.15MB/s heap

## heapslope (1x 墙钟, 60s/phase, usedHeap 口径同 app logHeapIfDue)

heap.phase=block.current audioSeconds=60 slopeMiBPerSec=0.2336 slopeMiBPerHour=841 allocKiBPerSec=579.0 gcCountDelta=5 gcPauseMsDelta=7 maxUsedMiB=30.8 liveAfterGcMiB=6.2
heap.phase=block.pooled  audioSeconds=60 slopeMiBPerSec=0.0028 slopeMiBPerHour=10   allocKiBPerSec=220.7 gcCountDelta=5 gcPauseMsDelta=11 maxUsedMiB=16.9 liveAfterGcMiB=6.8

## readcost

ring.read reads=161 audioSec=60 wallMs=6.9 cpuPerAudioSecondMs=0.115 throughput=8696x realtime dummy=2637824

## seamcheck

window A=23301ms B=261457ms loopFrames=10502679 blocks=239 lastBlockFrames=6879 lastBlockMs=156.0
wrap#1 beforeFrame=11523375 (lastBlock=238) afterFrame=1027575 (block=0 isA=true) lastBlockMs=156.0 block0ReadyBeforeWrap=false readNullAtWrap=false
wrap#2 ... 同
wrap#3 ... 同
framesRead=31508037 wraps=3 readStalls=427 decodeCalls=433 readyBlocks=191

## lazyring (steady 20-40ms, 10min/场景, cache=32.00MiB)

L(s)	w	blocks	capacity	decodes/10min	gapEvents	minQueueMs
90	2	90	190	90	0	0
90	3	90	190	90	0	0
90	6	90	190	90	0	0
150	2	150	190	150	0	0
150	3	150	190	150	0	0
150	6	150	190	150	0	0
190	2	190	190	190	0	0
190	3	190	190	190	0	0
190	6	190	190	190	0	0
239	2	239	190	386	0	0
239	3	239	190	386	0	0
239	6	239	190	386	0	0

## prefetch (ahead=5s 生产档, 10min/场景, 固定种子 seed=20261121)

w	model	gapEvents	gapMs	seamGaps	decodes	avgLat	maxLat
1	steady 20-40ms	0	0	0	384	30.5	40
1	tail p99 800ms	0	0	0	384	31.6	763
1	hiccup 2% 300ms	0	0	0	384	35.5	290
1	throttled 150-350ms	0	0	0	384	249.4	350
1	suspend 0.5% 1500ms	0	0	0	384	40.5	1610
2	steady 20-40ms	0	0	0	384	30.6	40
2	tail p99 800ms	0	0	0	384	39.0	773
2	hiccup 2% 300ms	0	0	0	384	34.6	286
2	throttled 150-350ms	0	0	0	384	255.7	350
2	suspend 0.5% 1500ms	0	0	0	384	37.9	1633
3	steady 20-40ms	0	0	0	384	30.0	40
3	tail p99 800ms	0	0	0	384	37.3	701
3	hiccup 2% 300ms	0	0	0	384	33.6	287
3	throttled 150-350ms	0	0	0	384	247.0	350
3	suspend 0.5% 1500ms	0	0	0	384	45.8	1753
5	steady 20-40ms	0	0	0	384	30.0	40
5	tail p99 800ms	0	0	0	384	30.3	468
5	hiccup 2% 300ms	0	0	0	384	34.7	290
5	throttled 150-350ms	0	0	0	384	249.4	350
5	suspend 0.5% 1500ms	0	0	0	384	33.3	1225
6	steady 20-40ms	0	0	0	384	29.8	40
6	tail p99 800ms	0	0	0	384	36.0	686
6	hiccup 2% 300ms	0	0	0	384	32.6	231
6	throttled 150-350ms	0	0	0	384	249.5	350
6	suspend 0.5% 1500ms	0	0	0	384	33.8	1365
=> 生产档 25 个组合全部 0 缺口（含 1.6–1.8s 尾部代价）。

## prefetch (ahead=1s 收紧档, 同种子)

1	suspend 0.5% 1500ms	0	0	0	384
2	suspend 0.5% 1500ms	1	4	0	384
3	suspend 0.5% 1500ms	3	14	0	384
5	suspend 0.5% 1500ms	3	16	0	384
6	suspend 0.5% 1500ms	2	10	0	384
其余模型（steady/tail/hiccup/throttled）在两档下均 0 缺口。
=> 收紧档下 1.5s 冻结的缺口与 w 不单调：抗冻结靠发射预算，不靠加深预取。

## prefetchgate (L=238.156s, ahead=5s, 10min)

w	gate	loaderInlineDecodes	loaderInlineStallMs	decodes
2	prod(readyCount<w)	382	11666	384
2	fix(needsDecode)	2	72	402
3	prod	381	11335	384
3	fix	2	49	466
6	prod	380	11466	384
6	fix	2	47	667
（两档 gapEvents 均为 0；fix 档多解的块全部落在播放头之前 ≥w 秒处。）