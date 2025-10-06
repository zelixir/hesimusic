package zelixir.hesimusic.scan.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    @ColumnInfo val path: String,
    @ColumnInfo val cue_blob: String?,
    @ColumnInfo val title: String?,
    @ColumnInfo val artist: String?,
    @ColumnInfo val album: String?,
    @ColumnInfo val duration_ms: Long,
    @ColumnInfo val size_bytes: Long,
    @ColumnInfo val format: String?,
    @ColumnInfo val bitrate: Int?,
    @ColumnInfo val sample_rate: Int?,
    @ColumnInfo val channels: Int?,
    @ColumnInfo val tags_json: String?,
    @ColumnInfo val fingerprint: String?,
    @ColumnInfo val last_scanned_at: Long
)
