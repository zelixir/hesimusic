package com.zjr.hesimusic.loop

import com.zjr.hesimusic.data.model.SongLoop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前无缝窗口的**唯一**来源:UI 的 A/B 标记、引擎的环回、位置映射都读这里。
 *
 * 它只是"当前无缝窗口"的持有者:
 * - 不做任何 DB 查询([refresh] 的 row 由调用方查好传入);
 * - 不做任何播放操作;
 * - 不起协程、不做定时/轮询。
 *
 * 资格由 [LoopEligibility] 唯一判定,**显式意图**是其中一个输入([refresh] 的
 * armedSongId 由调用方从 [LoopIntent] 读出):没有武装意图就没有窗口,哪怕
 * repeat 仍是 ONE、行也已确认。
 *
 * 任何状态变更事件(切歌 / repeat 变化 / 行变更 / 意图变化 / restore 完成)都只需
 * 重新调用一次 [refresh] —— 重算幂等,相同窗口不会重复 emit。
 */
@Singleton
class LoopRuntime @Inject constructor() {

    private val _window = MutableStateFlow<LoopWindow?>(null)

    /** 当前无缝窗口;null = 当前不无缝(走普通播放)。 */
    val window: StateFlow<LoopWindow?> = _window.asStateFlow()

    /**
     * 重算一次资格判定并写入(幂等:窗口值相同的连续调用不会重复 emit)。
     *
     * @param armedSongId 显式意图指向的歌([LoopIntent.armed] 的 songId);null = 未武装。
     */
    fun refresh(songId: Long?, repeatMode: Int, row: SongLoop?, armedSongId: Long?) {
        _window.value = LoopEligibility.derive(songId, repeatMode, row, armedSongId)
    }

    /** 清空窗口(停止/退出无缝场景)。 */
    fun clear() {
        _window.value = null
    }
}
