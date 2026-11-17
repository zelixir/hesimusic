package com.zjr.hesimusic.ui.library

/**
 * 「本次视图还没自动定位过当前播放歌」的哨兵 epoch(issue 00002)。
 *
 * 取 Int.MIN_VALUE 而不是 -1 / 0:视图 epoch 从 0 开始,哨兵必须永远不等于任何真实 epoch,
 * 否则首次进入列表时会被判定成"已经定位过",丢掉原有的首次自动定位行为。
 */
internal const val AUTO_SCROLL_NEVER = Int.MIN_VALUE

/**
 * 是否应该把列表自动滚动到「当前播放歌」(issue 00002)。
 *
 * 判定完全由 epoch 比较表达,而不是由 composable 是否重建 / remember 是否复位决定:
 * - **首次进入视图**:[autoScrolledViewEpoch] 是哨兵 [AUTO_SCROLL_NEVER],与 [viewEpoch] 不等 → true;
 * - **同一视图内重组**(从详情页 / 无缝循环分析页返回,列表会重新组合):epoch 未变 →
 *   `autoScrolledViewEpoch == viewEpoch` → false,保住用户原本的滚动位置,不跳回播放歌;
 * - **切换视图再切回**:MainScreen 在 tab 页变化时把 viewEpoch +1,旧标志与新 epoch 不等 → true,
 *   重新定位当前播放歌(00002 的回归点)。
 *
 * epoch 必须是**单调递增计数**,不能直接复用 pager 页号:0 → 1 → 0 切回来时页号回到旧值,
 * 被 SaveableStateHolder 恢复出来的旧标志会重新生效,正是 00002 的根因。
 *
 * epoch 传递路径:
 * `MainScreen.viewEpoch`(仅 pager 当前页变化时 +1,详情/分析页返回不变)
 * → `SongList(viewEpoch=...)` / `FolderList(viewEpoch=...)` / `PlaylistTabScreen(viewEpoch=...)`
 * → `rememberSaveable(viewEpoch) { ... }`(key 变化即丢弃旧保存值)+ 本函数二次校验
 * → `LaunchedEffect(..., viewEpoch)` 重新执行定位,成功后把 epoch 写回标志。
 *
 * @param autoScrolledViewEpoch 上一次自动定位成功时记录的视图 epoch,[AUTO_SCROLL_NEVER] 表示还没定位过
 * @param viewEpoch 当前视图 epoch
 * @param currentPlayingSongId 当前播放歌 id([com.zjr.hesimusic.data.model.Song.id] 的字符串形式)
 * @param hasItems 列表是否已有内容(空列表没有可滚动的目标)
 */
internal fun shouldAutoScrollToCurrentSong(
    autoScrolledViewEpoch: Int,
    viewEpoch: Int,
    currentPlayingSongId: String?,
    hasItems: Boolean
): Boolean {
    if (autoScrolledViewEpoch == viewEpoch) return false
    if (currentPlayingSongId == null) return false
    return hasItems
}
