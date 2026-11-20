package com.zjr.hesimusic.ui.loop

import android.os.Looper

/**
 * 预览播放器只读探针（issue 00023 的量测用）。
 *
 * 为什么放在 main 源集而不是 debug 源集：debug 源集的 HTTP 端点（/debug/preview、/debug/preview-seek）
 * 需要读**当前页面那一个** PreviewPlayer 实例；但它由 Hilt 注入 LoopViewModel，不在 DI 单例里，
 * debug 侧拿不到。main 不能反向引用 debug，所以这里只放一个"最后创建的实例"引用与一个只读快照方法，
 * 生产路径除了挂引用之外零副作用（和 LoopPreviewDebugBridge 同一套思路）。
 *
 * 读取约定：
 * - [snapshot] 可在任意线程调用：ExoPlayer 的位置只能在主线程读，所以非主线程会 post 到主线程并等回填；
 * - 主线程跳帧/卡死时超时返回上一次缓存的快照（带 stale 标记由 PreviewPlayer 填），绝不阻塞 HTTP 线程。
 */
object PreviewProbe {

    @Volatile
    private var current: PreviewPlayer? = null

    fun attach(player: PreviewPlayer) {
        current = player
    }

    fun detach(player: PreviewPlayer) {
        if (current === player) current = null
    }

    /** 是否有预览播放器在（页面打开且已 prepare）。 */
    fun attached(): Boolean = current != null

    fun snapshot(): Map<String, Any?>? {
        val player = current ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) return player.debugSnapshot()
        return player.debugSnapshotOnMain(400L)
    }
}
