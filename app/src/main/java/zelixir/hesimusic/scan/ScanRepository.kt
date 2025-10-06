package zelixir.hesimusic.scan

import android.content.Context
import zelixir.hesimusic.scan.db.ScanDatabase
import zelixir.hesimusic.scan.db.SongEntity

class ScanRepository(context: Context) {
    private val db = ScanDatabase.getInstance(context)
    private val dao = db.songDao()

    suspend fun saveBatch(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        dao.insertSongs(songs)
    }

    suspend fun updateScanState(cursor: ByteArray?, completedAt: Long?) {
        dao.updateScanStateCursor(1, cursor, completedAt)
    }
}
