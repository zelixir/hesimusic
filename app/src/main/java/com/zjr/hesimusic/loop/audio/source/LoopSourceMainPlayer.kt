package com.zjr.hesimusic.loop.audio.source

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 主播放器接入 SOURCE 的独立开关，默认 **false**。
 *
 * 预览页只看 LoopSourceMode；主播放器必须同时满足：
 * - LoopSourceMainPlayer.enabled == true
 * - LoopSourceMode.isSource()
 *
 * renderers 与 LoopAwareMediaSourceFactory 用同一个纯函数
 * [shouldUseSourceForMainPlayer]，避免“换了输出通路、播放源还是普通文件”。
 */
object LoopSourceMainPlayer {
    private const val TAG = "LoopSourceMainPlayer"
    private const val SETTING_KEY = "hesi_loop_source_main"

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var debugOverride: Boolean? = null

    /** ExoPlayer 构建时锁定的主播放器路线；避免运行中预览开关变化造成半切换。 */
    @Volatile
    var isMainPlayerActive: Boolean = false
        private set

    fun shouldUseSourceForMainPlayer(
        mainEnabled: Boolean = (debugOverride ?: enabled),
        sourceMode: Boolean = LoopSourceMode.isSource(),
    ): Boolean = mainEnabled && sourceMode

    /** MediaModule 构建主播放器时调用一次，renderers/factory/coordinator 共用此快照。 */
    fun activateMainPlayer(active: Boolean) {
        isMainPlayerActive = active
    }

    /** 读取 global setting（0/1），默认 false；debugOverride 优先。 */
    fun refreshFromSettings(context: Context) {
        debugOverride?.let { return }
        enabled = try {
            Settings.Global.getInt(context.contentResolver, SETTING_KEY, 0) == 1
        } catch (t: Throwable) {
            Log.w(TAG, "read $SETTING_KEY failed", t)
            false
        }
    }

    /** 测试/调试用，进程内覆盖主开关。 */
    fun setEnabledForTest(value: Boolean?) {
        debugOverride = value
        enabled = value ?: false
    }
}
