package com.zjr.hesimusic.ui.common

/**
 * main 源集侧的导航钩子(契约 docs/loop-v3-ui-contract.md §1 / v2 §5.1)。
 *
 * 为什么放在 main:debug 源集里的调试协议服务需要驱动导航,但 main 不能反向引用
 * debug 源集(release 构建不编译 debug)。所以 main 提供这个只写钩子,由 MainActivity
 * 在组合时注册真实 navController;debug 服务只读它。
 *
 * - 未注册时值为 null(调试服务应回 501/409,由服务端决定);
 * - 允许任意线程写,@Volatile 保证可见性;真实实现内部自行切主线程(navController
 *   只能在主线程调用)。
 */
object UiNavigationHooks {

    /** 导航回调;route 与 NavController.navigate 的 route 格式一致。 */
    @Volatile
    var navigate: ((String) -> Unit)? = null
}
