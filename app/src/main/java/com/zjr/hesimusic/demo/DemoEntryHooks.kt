package com.zjr.hesimusic.demo

import kotlinx.coroutines.flow.StateFlow

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
 * 写入方只有 demo 源集;读取方只有 MainActivity。不放任何 demo 业务逻辑。
 */
object DemoEntryHooks {

    /** demo 会话;正式包恒为 null。provider 注册,进程生命周期内不变。 */
    @Volatile
    var session: DemoSession? = null
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
