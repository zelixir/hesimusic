package com.zjr.hesimusic.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * v9 → v10 迁移的可执行 JVM 测试(issue 00016 §6)。
 *
 * 约束:本项目**没有** room-testing / Robolectric(不引入新依赖、不改 gradle),
 * 因此不能跑 `MigrationTestHelper`。这里用 `java.lang.reflect.Proxy` 造一个假的
 * [SupportSQLiteDatabase],**真实执行** [AppDatabase.MIGRATION_9_10].migrate(),
 * 断言它发出的 SQL:
 * - 只有一条 `ALTER TABLE song_loop ADD COLUMN candidatesJson TEXT`(纯增量);
 * - 绝不包含 DROP/DELETE/UPDATE/RENAME(存量行必须保留,旧数据仍可读);
 * - 列必须可空、无默认值,且与 Room 生成的建表 SQL 逐字一致(否则 Room 打开数据库时
 *   的 schema 校验会失败)。
 *
 * 真·SQLite 上的"v9 建库 → 迁移 → 旧行仍可读"由交付报告里的 sqlite 实测脚本佐证。
 */
class AppDatabaseMigration9To10Test {

    /** 记录所有 execSQL 的假数据库;其余方法返回类型零值(迁移体不会用到)。 */
    private class RecordingDatabase : InvocationHandler {
        val statements = mutableListOf<String>()

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            when (method.name) {
                "execSQL" -> {
                    statements += args?.firstOrNull() as String
                    null
                }

                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "RecordingSupportSQLiteDatabase"
                else -> zeroValue(method.returnType)
            }

        fun database(): SupportSQLiteDatabase = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            this,
        ) as SupportSQLiteDatabase

        private fun zeroValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }

    @Test
    fun `迁移只发出一条ADD_COLUMN语句`() {
        val recorder = RecordingDatabase()
        AppDatabase.MIGRATION_9_10.migrate(recorder.database())

        assertEquals("v9→v10 只应有一条 DDL", 1, recorder.statements.size)
        assertEquals(
            "列名/类型必须与 Room 期望 schema 一致(可空 TEXT,无默认值)",
            "ALTER TABLE `song_loop` ADD COLUMN `candidatesJson` TEXT",
            recorder.statements.single(),
        )
    }

    @Test
    fun `迁移是纯增量_不丢旧行`() {
        val recorder = RecordingDatabase()
        AppDatabase.MIGRATION_9_10.migrate(recorder.database())
        val sql = recorder.statements.joinToString(" ").uppercase()

        assertTrue("必须是 ALTER TABLE ADD COLUMN", sql.contains("ALTER TABLE") && sql.contains("ADD COLUMN"))
        listOf("DROP", "DELETE", "TRUNCATE", "UPDATE ", "INSERT ", "RENAME").forEach { forbidden ->
            assertFalse("迁移不得包含 $forbidden(会丢/改存量行)", sql.contains(forbidden))
        }
        assertTrue("必须落在 song_loop 表上", sql.contains("SONG_LOOP"))
        assertTrue("必须新增 candidatesJson 列", sql.contains("CANDIDATESJSON"))
    }

    @Test
    fun `迁移SQL与Room生成的建表SQL逐字一致`() {
        val generated = generatedSource("com/zjr/hesimusic/data/AppDatabase_Impl.java")
        assumeTrue(
            "找不到 Room 生成源码(非 Gradle 标准布局时跳过);CI/本地 testDebugUnitTest 下必定存在",
            generated != null,
        )
        val createLine = generated!!.lineSequence()
            .firstOrNull { it.contains("CREATE TABLE IF NOT EXISTS `song_loop`") }
        assertTrue("生成源码里应有 song_loop 建表语句", createLine != null)
        assertTrue(
            "实体列必须是可空、无默认值的 TEXT,与 MIGRATION_9_10 的 ADD COLUMN 对齐",
            createLine!!.contains("`candidatesJson` TEXT, PRIMARY KEY(`songId`)"),
        )
        assertFalse(
            "若实体给了 SQL 默认值,迁移也必须写 DEFAULT,否则 Room 校验失败",
            createLine.contains("`candidatesJson` TEXT DEFAULT"),
        )
    }

    private fun generatedSource(relative: String): String? {
        val candidates = listOf(
            File("build/generated/ksp/debug/java/$relative"),
            File("build/generated/ksp/release/java/$relative"),
            File("app/build/generated/ksp/debug/java/$relative"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
    }
}
