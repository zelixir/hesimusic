package com.zjr.hesimusic.data

/**
 * SQL 歌单视图的 SQL 静态校验（纯字符串规则，可单测）。
 * 运行时的投影校验（列必须与 songs 表一致）在 SmartPlaylistRepository 中借助真实数据库完成。
 */
object SmartViewSql {

    /** 视图必须是查询语句；WITH 开头的 CTE 允许，但后续仍不得包含写操作关键词。 */
    private val allowedLeadingKeywords = setOf("select", "with")

    /** 出现在语句任意位置的写操作/危险关键词（词边界匹配）。 */
    private val forbiddenKeywordPattern = Regex(
        """(?i)\b(INSERT|UPDATE|DELETE|REPLACE|PRAGMA|ATTACH|DETACH|CREATE|DROP|ALTER|VACUUM|REINDEX|GRANT|REVOKE)\b"""
    )

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    fun validate(rawSql: String): ValidationResult {
        val sql = normalize(rawSql)
        if (sql.isEmpty()) return ValidationResult.Invalid("SQL 不能为空")
        if (sql.contains(';')) return ValidationResult.Invalid("仅允许单条语句（不能包含分号）")

        val leadingKeyword = sql.substringBeforeFirstWhitespace().lowercase()
        if (leadingKeyword !in allowedLeadingKeywords) {
            return ValidationResult.Invalid("视图必须是 SELECT 查询（当前开头: $leadingKeyword）")
        }
        forbiddenKeywordPattern.find(sql)?.let {
            return ValidationResult.Invalid("视图 SQL 中不允许出现写操作关键词: ${it.value.uppercase()}")
        }
        return ValidationResult.Valid
    }

    /** 去掉首尾空白与结尾的单个分号。 */
    fun normalize(rawSql: String): String {
        var sql = rawSql.trim()
        if (sql.endsWith(";")) sql = sql.dropLast(1).trim()
        return sql
    }

    /** 计算视图命中数量的外层查询。 */
    fun wrapCount(normalizedSql: String): String = "SELECT COUNT(*) FROM ($normalizedSql)"

    /** 跳过注释后取第一个词。 */
    private fun String.substringBeforeFirstWhitespace(): String {
        var s = this
        // 跳过前导注释（-- 单行 与 /* 块 */）
        while (true) {
            s = s.trimStart()
            when {
                s.startsWith("--") -> s = s.substringAfter('\n', missingDelimiterValue = "")
                s.startsWith("/*") -> s = s.substringAfter("*/", missingDelimiterValue = "")
                else -> return s.substringBefore(' ').substringBefore('\n').substringBefore('\t')
            }
        }
    }
}
