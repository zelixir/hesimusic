package com.zjr.hesimusic.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.zjr.hesimusic.data.model.SongLoop
import kotlinx.coroutines.flow.Flow

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
}
