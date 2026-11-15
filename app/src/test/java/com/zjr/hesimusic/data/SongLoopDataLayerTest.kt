package com.zjr.hesimusic.data

import com.zjr.hesimusic.data.model.SongLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * song_loop 数据层迁移链的纯 JVM 守卫(不需要 Robolectric / Mockito)。
 *
 * 说明:迁移体内部会调用 android.util.Log,在纯 JVM 单测环境(未开启
 * unitTests.returnDefaultValues、也没有 Robolectric)下执行 migrate() 会抛
 * "not mocked",因此这里**不执行** migrate(),只钉死可静态验证的契约:
 * 迁移版本号连续、模型默认值符合"存量行视为已确认"的语义、DAO 访问器存在。
 *
 * SQL 正文与备份分支的逐字一致性由 git diff 核对,建表结果与 Room 生成的
 * 期望 schema 一致(交付报告 §4 附有 sqlite3 实测输出)。
 */
class SongLoopDataLayerTest {

    @Test
    fun `迁移版本号连续覆盖 7 到 9`() {
        assertEquals("MIGRATION_7_8 起点应为 7", 7, AppDatabase.MIGRATION_7_8.startVersion)
        assertEquals("MIGRATION_7_8 终点应为 8", 8, AppDatabase.MIGRATION_7_8.endVersion)
        assertEquals("MIGRATION_8_9 起点应为 8", 8, AppDatabase.MIGRATION_8_9.startVersion)
        assertEquals("MIGRATION_8_9 终点应为 9", 9, AppDatabase.MIGRATION_8_9.endVersion)
        assertEquals(
            "7→8 的终点必须衔接 8→9 的起点,否则旧开发包升级链断裂",
            AppDatabase.MIGRATION_7_8.endVersion,
            AppDatabase.MIGRATION_8_9.startVersion
        )
    }

    @Test
    fun `SongLoop 行默认视为已确认`() {
        val row = SongLoop(
            songId = 1L,
            loopStartSamples = 44_100L,
            loopEndSamples = 132_300L,
            sampleRate = 44_100,
            loopStartMs = 1_000L,
            loopEndMs = 3_000L,
            score = 0.9,
            noteDistance = 0.12,
            loudnessDifference = 0.34,
            createdAt = 1_700_000_000_000L
        )
        assertTrue("confirmed 默认值应为 true,保证存量行语义为已确认", row.confirmed)
    }

    @Test
    fun `AppDatabase 暴露 songLoopDao 访问器`() {
        val accessor = AppDatabase::class.java.declaredMethods.firstOrNull { it.name == "songLoopDao" }
        assertTrue("AppDatabase 应声明 songLoopDao() 抽象方法", accessor != null)
        assertEquals(
            "songLoopDao() 应返回 SongLoopDao",
            "com.zjr.hesimusic.data.dao.SongLoopDao",
            accessor!!.returnType.name
        )
        assertTrue("songLoopDao() 应为无参方法", accessor.parameterCount == 0)
    }
}
