package com.zjr.hesimusic.ui.common

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * issue 00030：「去授权」跳转的 Intent 口径（Robolectric，只为拿到真实 Intent/Settings 常量）。
 *
 * - 主跳转：ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION + package:<pkg>；
 * - 系统没有该页面（ActivityNotFoundException）→ 退回应用详情页；
 * - 都不可用时返回 null（对话框本身不崩）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AllFilesAccessLauncherTest {

    private val pkg = "com.zjr.hesimusic"

    @Test
    fun primaryIntentOpensTheAllFilesAccessPageForThisPackage() {
        val started = mutableListOf<Intent>()
        val used = AllFilesAccessLauncher.start(pkg) { started += it }
        assertEquals("只启动一次", 1, started.size)
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, used?.action)
        assertEquals("package:" + pkg, used?.data?.toString())
        assertTrue("带包名才会落到本应用那一页", used?.data.toString().endsWith(pkg))
    }

    @Test
    fun missingAllFilesAccessPageFallsBackToTheAppDetailsPage() {
        val started = mutableListOf<Intent>()
        val used = AllFilesAccessLauncher.start(pkg) { intent ->
            started += intent
            if (intent.action == Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) {
                throw ActivityNotFoundException("no activity for all-files-access")
            }
        }
        assertEquals("先主跳转再退回", 2, started.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, used?.action)
        assertEquals("package:" + pkg, used?.data?.toString())
    }

    @Test
    fun whenBothPagesAreMissingNothingIsReportedAsLaunched() {
        val used = AllFilesAccessLauncher.start(pkg) { throw ActivityNotFoundException("nothing resolves") }
        assertNull(used)
    }

    @Test
    fun launchingFromTheApplicationContextAddsNewTask() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AllFilesAccessLauncher.launch(app!!)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, intent.action)
        assertEquals("package:" + app.packageName, intent.data.toString())
        assertTrue(
            "非 Activity context 必须带 NEW_TASK，否则 startActivity 会抛异常",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }
}
