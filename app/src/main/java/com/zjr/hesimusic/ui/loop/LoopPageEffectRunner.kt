package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.ui.loop.state.LoopPageEffect
import com.zjr.hesimusic.ui.loop.state.LoopPageMode

/**
 * effect runner 的最小宿主面。
 *
 * 生产实现 = LoopViewModel；单测 = FakeLoopPageHost。
 * 把它抽出来是为了让“effect 执行顺序 / Play 不二次 seek / 持久化后返回”可以被纯单测覆盖，
 * 不需要 Android/ExoPlayer/Room。
 */
interface LoopPageEffectHost {
    /**
     * @param seek true = 允许播放器按 startMs 做展示口径 seek；
     *             false = 同一批 effect 里已经 SeekTo 过，只从当前位置恢复播放。
     */
    fun play(mode: LoopPageMode, startMs: Long, seek: Boolean)

    fun pause()

    fun seekTo(positionMs: Long)

    fun startPrefill(window: LoopWindow)

    fun cancelPrefill()

    /**
     * SOURCE 模式：把新 A/B 应用到循环数据源（新 timeline / 新缓存 key）。
     * 默认 no-op，保证 M3′/测试宿主无需实现。
     */
    fun applyWindow(window: LoopWindow) {}


    fun persistConfirm(songId: Long, window: LoopWindow, onResult: (Boolean) -> Unit)

    fun persistDemote(songId: Long, onResult: (Boolean) -> Unit)

    fun persistDelete(songId: Long, onResult: (Boolean) -> Unit)

    /** 确认保存成功：VM 负责 release + onSaved(song)（播放该歌/REPLACE_ONE/pop）。 */
    fun onConfirmSaved()

    fun toast(text: String)

    fun navigateBack()

    fun restartAnalysis()
}

/**
 * 顺序执行 reducer 产出的 effect 列表。
 *
 * 幂等/顺序约定：
 * - reducer 对非法/重复事件返回空 effect，runner 天然不会被调用；
 * - 同一批 effect **严格按列表顺序**执行（CancelPrefill → StartPrefill → SeekTo → Play）；
 * - [LoopPageEffect.Play] 若同批前面已有同位置的 [LoopPageEffect.SeekTo]，则以 seek=false
 *   执行（只恢复播放，避免 PreviewPlayer 内部再次 rawSeekUs，00008）；
 * - 预填/落库是异步的，宿主必须用 token/回调保证过期结果不落地；runner 只保证发起顺序。
 */
class LoopPageEffectRunner(
    private val host: LoopPageEffectHost,
) {

    fun run(effects: List<LoopPageEffect>) {
        effects.forEachIndexed { index, effect ->
            when (effect) {
                is LoopPageEffect.Play -> {
                    val alreadySeeked = effects.subList(0, index).any {
                        it is LoopPageEffect.SeekTo && it.positionMs == effect.startMs
                    }
                    host.play(effect.mode, effect.startMs, seek = !alreadySeeked)
                }

                LoopPageEffect.Pause -> host.pause()

                is LoopPageEffect.SeekTo -> host.seekTo(effect.positionMs)

                is LoopPageEffect.StartPrefill -> host.startPrefill(effect.window)

                LoopPageEffect.CancelPrefill -> host.cancelPrefill()

                is LoopPageEffect.ApplyWindow -> host.applyWindow(effect.window)

                is LoopPageEffect.PersistConfirm -> host.persistConfirm(effect.songId, effect.window) { ok ->
                    if (ok) {
                        host.onConfirmSaved()
                    } else {
                        host.toast("保存失败，请重试")
                    }
                }

                is LoopPageEffect.PersistDemote -> host.persistDemote(effect.songId) { ok ->
                    host.toast(
                        if (ok) "已转为临时结果，不再无缝" else "操作失败，请重试",
                    )
                }

                is LoopPageEffect.PersistDelete -> host.persistDelete(effect.songId) { ok ->
                    if (ok) {
                        host.toast("已删除分析结果，下次进入将重新分析")
                        host.navigateBack()
                    } else {
                        host.toast("删除失败，请重试")
                    }
                }

                is LoopPageEffect.Toast -> host.toast(effect.text)

                LoopPageEffect.NavigateBack -> host.navigateBack()

                LoopPageEffect.RestartAnalysis -> host.restartAnalysis()
            }
        }
    }
}
