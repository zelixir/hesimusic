package zelixir.hesimusic.scan.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_settings")
data class ScanSettingsEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo val folders_json: String?,  // JSON array of folder URIs
    @ColumnInfo val excludes_json: String?,  // JSON array of exclude URIs
    @ColumnInfo val skip_short: Boolean,
    @ColumnInfo val skip_amr_mid: Boolean,
    @ColumnInfo val skip_hidden: Boolean,
    @ColumnInfo val last_updated_at: Long
)
