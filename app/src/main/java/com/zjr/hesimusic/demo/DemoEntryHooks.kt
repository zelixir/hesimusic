package com.zjr.hesimusic.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * main 源集侧的 demo 入口契约(见 app/src/demo)。
 *
 * 为什么需要这层最小接口:demo 行为只能由 demo 源集提供(release/prod 不编译它),
 * 但 MainActivity 需要知道"demo 是否已准备好、该导航到哪首歌"。因此:
 * - 正式包:[session] 永远为 null,MainActivity 走原有启动路径(NavHost 起始 home),
 *   行为与加入 demo 包之前逐字一致;
 * - demo 包:DemoBootstrapProvider(ContentProvider,早于 Application.onCreate)在
 *   [DemoEntryHooks.session] 注册会话,MainActivity 仅在该值非空时才等待并导航。
 *
 * 写入方只有 demo 源集;读取方是 MainActivity(导航)与 LoopViewModel(首次自动试听)。
 * 这里只放跨源集的契约/一次性意图,不放任何 demo 业务逻辑。
 */
object DemoEntryHooks {

    /** demo 会话;正式包恒为 null。provider 注册,进程生命周期内不变。 */
    @Volatile
    var session: DemoSession? = null

    private val _autoAudition = MutableStateFlow<Long?>(null)

    /**
     * demo 冷启动首次进入结果页时要自动试听「仅循环段」的歌曲 id。
     *
     * 用 StateFlow 而不是一次性普通字段:页面可能因恢复/时序先进入 Ready、随后
     * bootstrap 才 arm;LoopViewModel 同时做「Ready 时消费一次」与「后续 arm 时消费」,
     * 两种顺序都不会漏自动试听。正式包恒为 null,不产生任何行为。
     */
    val autoAudition: StateFlow<Long?> = _autoAudition.asStateFlow()

    /** 每次 demo 启动都会 arm(幂等覆盖);消费后清空。 */
    fun armAutoAudition(songId: Long) {
        _autoAudition.value = songId
    }

    /** 清空待处理意图(demo 启动前清理旧值 / 测试隔离)。 */
    fun clearAutoAudition() {
        _autoAudition.value = null
    }

    /**
     * 原子消费一次自动试听意图。
     *
     * @return true = 该歌匹配且本次消费成功(调用方应立即触发一次「仅循环段」);
     *         false = 无待处理意图/歌曲不匹配(重复进入页面不会再次自动播放)。
     */
    @Synchronized
    fun consumeAutoAudition(songId: Long): Boolean {
        if (_autoAudition.value != songId) return false
        _autoAudition.value = null
        return true
    }
}

/** demo 启动会话(由 demo 源集实现)。 */
interface DemoSession {

    /**
     * 已写入数据库、可进入「无缝循环」页的歌曲 id。
     *
     * null = 仍在准备(复制素材 / 解码 / 写库);非 null 后 MainActivity 导航
     * loop/{songId}。准备失败时保持 null 并把原因写入 [failure]。
     */
    val songId: StateFlow<Long?>

    /** 准备失败原因;非 null 表示 demo 初始化失败,MainActivity 回退到正常 home。 */
    val failure: StateFlow<String?>
}
