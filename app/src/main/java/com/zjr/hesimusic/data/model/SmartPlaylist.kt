package com.zjr.hesimusic.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SQL 歌单（智能视图）：内容不是静态条目，而是 [sqlText] 在曲库上的实时求值结果。
 * 只能通过 ADB 接口或备份导入创建/修改；界面上仅支持删除。
 *
 * [sqlText] 必须是返回歌曲行的 SELECT（推荐 `SELECT s.* FROM songs s WHERE ...`），
 * 校验规则见 [com.zjr.hesimusic.data.SmartViewSql]。
 */
@Entity(tableName = "smart_playlists")
data class SmartPlaylist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val sqlText: String
)
