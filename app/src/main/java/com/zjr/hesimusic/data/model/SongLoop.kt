package com.zjr.hesimusic.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 无缝循环分析结果(契约 docs/loop-seamless.md §2.3 / v6 §10.1)。
 *
 * 一首歌至多一条结果(以 songId 为主键);分析 Done 自动落库**临时结果**(confirmed=0),
 * 用户「确认并保存」后置 confirmed=1(已确认行不被后续分析覆盖)。
 * 桥接接管条件:song_loop 行存在即生效(临时结果同样无缝播放)。
 */
@Entity(tableName = "song_loop")
data class SongLoop(
    @PrimaryKey val songId: Long,
    val loopStartSamples: Long,
    val loopEndSamples: Long,
    val sampleRate: Int,
    val loopStartMs: Long,
    val loopEndMs: Long,
    val score: Double,
    val noteDistance: Double,
    val loudnessDifference: Double,
    val createdAt: Long,
    /** v6 §10.1:false = 分析自动落库的临时结果;true = 用户确认的最终结果。存量行迁移默认 1。 */
    @ColumnInfo(defaultValue = "1")
    val confirmed: Boolean = true
)
