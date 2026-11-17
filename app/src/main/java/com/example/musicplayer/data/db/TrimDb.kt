package com.example.musicplayer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Where a song should start and stop playing. The user's marks win over what silence detection found;
 * either may be missing.
 */
@Entity(tableName = "track_trim")
data class TrackTrimEntity(
    @PrimaryKey val songId: Long,
    /** Start mark set by the user, in ms from the beginning of the file. */
    val startMs: Long? = null,
    /** End mark set by the user, in ms from the beginning of the file. */
    val endMs: Long? = null,
    /** File version (Song.fingerprint) the detection below was run on; "" if it hasn't run. */
    val analyzedFingerprint: String = "",
    /** Where sound begins, when there's leading silence worth skipping. */
    val soundStartMs: Long? = null,
    /** Where sound ends, when there's trailing silence worth skipping. */
    val soundEndMs: Long? = null,
)

@Dao
abstract class TrimDao {
    @Query("SELECT * FROM track_trim WHERE songId = :songId")
    abstract suspend fun get(songId: Long): TrackTrimEntity?

    @Query("SELECT * FROM track_trim WHERE songId = :songId")
    abstract fun observe(songId: Long): Flow<TrackTrimEntity?>

    @Upsert
    abstract suspend fun upsert(row: TrackTrimEntity)
}
