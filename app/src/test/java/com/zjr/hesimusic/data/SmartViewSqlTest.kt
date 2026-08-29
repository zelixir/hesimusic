package com.zjr.hesimusic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartViewSqlTest {

    private fun assertValid(sql: String) {
        val result = SmartViewSql.validate(sql)
        assertTrue("期望通过但失败: ${(result as? SmartViewSql.ValidationResult.Invalid)?.reason}", result is SmartViewSql.ValidationResult.Valid)
    }

    private fun assertInvalid(sql: String, contains: String? = null) {
        val result = SmartViewSql.validate(sql)
        assertTrue("期望拒绝但通过: $sql", result is SmartViewSql.ValidationResult.Invalid)
        if (contains != null) {
            assertTrue("错误信息应包含 '$contains': ${(result as SmartViewSql.ValidationResult.Invalid).reason}", result.reason.contains(contains))
        }
    }

    @Test
    fun validSelect() {
        assertValid("SELECT s.* FROM songs s")
        assertValid("SELECT s.* FROM songs s WHERE s.artist = 'HOYO-MiX' ORDER BY s.title")
        assertValid("select s.* from songs s limit 10")
    }

    @Test
    fun validCte() {
        assertValid(
            """
            WITH recent AS (SELECT * FROM songs WHERE dateAdded > 0)
            SELECT s.* FROM recent s ORDER BY s.title
            """.trimIndent()
        )
    }

    @Test
    fun trailingSemicolonAllowed() {
        assertValid("SELECT s.* FROM songs s;")
        assertEquals("SELECT s.* FROM songs s", SmartViewSql.normalize("SELECT s.* FROM songs s;  \n"))
    }

    @Test
    fun leadingCommentsAllowed() {
        assertValid("-- 最近添加的歌曲\nSELECT s.* FROM songs s ORDER BY s.dateAdded DESC")
        assertValid("/* 块注释 */ SELECT s.* FROM songs s")
    }

    @Test
    fun rejectNonSelect() {
        assertInvalid("DELETE FROM songs", "SELECT")
        assertInvalid("UPDATE songs SET title = 'x'")
        assertInvalid("PRAGMA table_info(songs)")
        assertInvalid("CREATE TABLE x(a)")
        assertInvalid("DROP TABLE songs")
        assertInvalid("ATTACH DATABASE 'x' AS y")
        assertInvalid("VACUUM")
    }

    @Test
    fun rejectWriteKeywordInsideCte() {
        // WITH 开头但体内含写操作
        assertInvalid("WITH d AS (SELECT * FROM songs) DELETE FROM songs")
        assertInvalid("WITH d AS (SELECT * FROM songs) INSERT INTO logs(message) VALUES('x')")
    }

    @Test
    fun rejectMultipleStatements() {
        assertInvalid("SELECT 1; SELECT 2")
        assertInvalid("SELECT 1; DROP TABLE songs")
    }

    @Test
    fun rejectEmpty() {
        assertInvalid("")
        assertInvalid("   ")
        assertInvalid(";", "不能为空")
    }

    @Test
    fun keywordSubstringDoesNotFalsePositive() {
        // "deleted" 含 "delete" 子串但非独立关键词，不应误报
        assertValid("SELECT s.* FROM songs s WHERE s.title = 'The Deleted One'")
    }

    @Test
    fun wrapCountContainsOriginal() {
        val wrapped = SmartViewSql.wrapCount("SELECT s.* FROM songs s")
        assertTrue(wrapped.startsWith("SELECT COUNT(*)"))
        assertTrue(wrapped.contains("SELECT s.* FROM songs s"))
    }
}
