package com.zjr.hesimusic.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.zjr.hesimusic.data.model.SongLoop
import kotlinx.coroutines.flow.Flow

/**
 * song_loop 的轻量投影:只带列表状态符号需要的两列。
 *
 * 列表在序号下方显示 ∞ 状态时,必须**一次**整表查询构建 songId → confirmed 的 map,
 * 不允许逐行查询(见 issue 00007)。整行 [SongLoop] 含采样点/评分等大量列,没必要读。
 */
data class SongLoopStatus(
    val songId: Long,
    val confirmed: Boolean
)

@Dao
interface SongLoopDao {

    /** 响应式观察某首歌的循环结果(UI 刷新用)。 */
    @Query("SELECT * FROM song_loop WHERE songId = :songId LIMIT 1")
    fun getBySongIdFlow(songId: Long): Flow<SongLoop?>

    /** 一次性查询(菜单入口分流:有结果→播放模式,无→分析页)。 */
    @Query("SELECT * FROM song_loop WHERE songId = :songId LIMIT 1")
    suspend fun getBySongId(songId: Long): SongLoop?

    /** 确认保存/覆盖保存。 */
    @Upsert
    suspend fun upsert(songLoop: SongLoop)

    /** 删除分析结果(「删除分析结果」入口)。 */
    @Query("DELETE FROM song_loop WHERE songId = :songId")
    suspend fun deleteBySongId(songId: Long)

    /**
     * 响应式整表轻量查询(列表状态符号用,issue 00007)。
     *
     * Room 会观察 song_loop 整表:分析落库(临时结果)、确认、取消确认、删除任一路径都会
     * 触发重新发射,列表因此自动刷新;调用方只需把这次发射映射成一张 map。
     */
    @Query("SELECT songId, confirmed FROM song_loop")
    fun getAllStatusFlow(): Flow<List<SongLoopStatus>>

    /** 备份/恢复:整表读取与写入(仅 BackupRestoreManager 使用)。 */
    @Query("SELECT * FROM song_loop")
    suspend fun getAll(): List<SongLoop>

    @Upsert
    suspend fun upsertAll(items: List<SongLoop>)

    @Query("DELETE FROM song_loop")
    suspend fun deleteAll()
}
