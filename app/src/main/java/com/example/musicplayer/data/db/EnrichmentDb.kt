package com.example.musicplayer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Where an override's values came from; higher wins when deciding whether to overwrite. */
object OverrideSource {
    const val LOCAL = "local"     // text clean-up / filename / folder inference
    const val ONLINE = "online"   // MusicBrainz (+ Cover Art Archive / iTunes)
    const val USER = "user"       // edited or accepted by the user
}

/** State of the online lookup for one song. */
object OnlineStatus {
    const val PENDING = "pending"
    const val MATCHED = "matched"
    const val SUGGESTED = "suggested"
    const val UNMATCHED = "unmatched"
    const val REJECTED = "rejected"
    const val ERROR = "error"
}

/**
 * The overlay: corrected values for one MediaStore song. Null fields mean "keep what MediaStore says".
 * The audio file itself is never modified.
 */
@Entity(tableName = "song_override")
data class SongOverrideEntity(
    @PrimaryKey val songId: Long,
    /** path|size|dateModified when this row was computed; a changed file is re-processed. */
    val fingerprint: String,
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val artworkUrl: String? = null,
    val mbRecordingId: String? = null,
    val mbReleaseId: String? = null,
    val source: String = OverrideSource.LOCAL,
    val confidence: Double = 0.0,
    /** Set when the user edited the song: nothing automatic may change this row again. */
    val locked: Boolean = false,
    val onlineStatus: String = OnlineStatus.PENDING,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0,
    val updatedAt: Long = 0,
)

/** An online match that wasn't confident enough to apply automatically; waits for the user. */
@Entity(tableName = "song_suggestion")
data class SongSuggestionEntity(
    @PrimaryKey val songId: Long,
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val year: Int?,
    val genre: String?,
    val trackNumber: Int?,
    val artworkUrl: String?,
    val mbRecordingId: String?,
    val mbReleaseId: String?,
    val confidence: Double,
    val createdAt: Long,
)

/**
 * Generalizing tier: a raw artist spelling (as a [com.example.musicplayer.data.enrich.TextCleaner.key])
 * → canonical name. One resolution fixes every song that uses that spelling.
 */
@Entity(tableName = "artist_alias")
data class ArtistAliasEntity(
    @PrimaryKey val rawKey: String,
    val name: String,
    val mbArtistId: String? = null,
    val source: String,
    val confidence: Double,
    val updatedAt: Long,
)

/**
 * Generalizing tier: an album group (by folder, or by artist+album key) → MusicBrainz release.
 * A null [mbReleaseId] is a cached "not found" so the group isn't searched again until it expires.
 */
@Entity(tableName = "album_resolution")
data class AlbumResolutionEntity(
    @PrimaryKey val groupKey: String,
    val mbReleaseId: String?,
    val confidence: Double,
    val source: String,
    val updatedAt: Long,
)

/** A fetched MusicBrainz release with its tracklist; serves every track and every duplicate folder of it. */
@Entity(tableName = "release_cache")
data class ReleaseCacheEntity(
    @PrimaryKey val mbReleaseId: String,
    val title: String,
    val artist: String,
    val mbArtistId: String?,
    val year: Int?,
    val genre: String?,
    val artworkUrl: String?,
    /** JSON array of {disc, position, title, artist, lengthMs, recordingId}. */
    val tracksJson: String,
    val fetchedAt: Long,
)

/** Per-song lookups for loose singles: (artist key, title key) → best recording; null id = not found. */
@Entity(tableName = "recording_cache")
data class RecordingCacheEntity(
    @PrimaryKey val queryKey: String,
    val mbRecordingId: String?,
    val mbReleaseId: String?,
    val confidence: Double,
    val fetchedAt: Long,
)

/** Raw HTTP responses keyed by URL, including negative (404) results. */
@Entity(tableName = "http_cache")
data class HttpCacheEntity(
    @PrimaryKey val url: String,
    val status: Int,
    val body: String,
    val fetchedAt: Long,
    val expiresAt: Long,
)

data class StatusCount(val onlineStatus: String, val count: Int)

@Dao
abstract class EnrichmentDao {
    // --- overrides ---
    @Query("SELECT * FROM song_override")
    abstract fun observeOverrides(): Flow<List<SongOverrideEntity>>

    @Query("SELECT * FROM song_override")
    abstract suspend fun allOverrides(): List<SongOverrideEntity>

    @Query("SELECT * FROM song_override WHERE songId = :songId")
    abstract suspend fun override(songId: Long): SongOverrideEntity?

    @Upsert
    abstract suspend fun upsertOverrides(rows: List<SongOverrideEntity>)

    @Query("DELETE FROM song_override WHERE songId IN (:songIds)")
    abstract suspend fun deleteOverrides(songIds: List<Long>)

    @Query("SELECT onlineStatus, COUNT(*) AS count FROM song_override GROUP BY onlineStatus")
    abstract fun observeStatusCounts(): Flow<List<StatusCount>>

    // --- suggestions ---
    @Query("SELECT * FROM song_suggestion ORDER BY mbReleaseId, trackNumber")
    abstract fun observeSuggestions(): Flow<List<SongSuggestionEntity>>

    @Query("SELECT * FROM song_suggestion WHERE songId IN (:songIds)")
    abstract suspend fun suggestions(songIds: List<Long>): List<SongSuggestionEntity>

    @Upsert
    abstract suspend fun upsertSuggestions(rows: List<SongSuggestionEntity>)

    @Query("DELETE FROM song_suggestion WHERE songId IN (:songIds)")
    abstract suspend fun deleteSuggestions(songIds: List<Long>)

    // --- generalizing tiers ---
    @Query("SELECT * FROM artist_alias")
    abstract suspend fun allAliases(): List<ArtistAliasEntity>

    @Query("SELECT * FROM artist_alias WHERE rawKey = :rawKey")
    abstract suspend fun alias(rawKey: String): ArtistAliasEntity?

    @Upsert
    abstract suspend fun upsertAliases(rows: List<ArtistAliasEntity>)

    @Query("SELECT * FROM album_resolution WHERE groupKey = :key")
    abstract suspend fun albumResolution(key: String): AlbumResolutionEntity?

    @Upsert
    abstract suspend fun upsertAlbumResolutions(rows: List<AlbumResolutionEntity>)

    @Query("SELECT * FROM release_cache WHERE mbReleaseId = :id")
    abstract suspend fun release(id: String): ReleaseCacheEntity?

    @Upsert
    abstract suspend fun upsertRelease(row: ReleaseCacheEntity)

    @Query("SELECT * FROM recording_cache WHERE queryKey = :key")
    abstract suspend fun recording(key: String): RecordingCacheEntity?

    @Upsert
    abstract suspend fun upsertRecording(row: RecordingCacheEntity)

    // --- http ---
    @Query("SELECT * FROM http_cache WHERE url = :url AND expiresAt > :now")
    abstract suspend fun http(url: String, now: Long): HttpCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putHttp(row: HttpCacheEntity)

    @Query("DELETE FROM http_cache WHERE expiresAt <= :now")
    abstract suspend fun pruneHttp(now: Long)

    // --- resets ---
    @Query("DELETE FROM song_override WHERE locked = 0")
    abstract suspend fun clearAutomaticOverrides()

    @Query("DELETE FROM song_suggestion")
    abstract suspend fun clearSuggestions()

    @Query("DELETE FROM artist_alias WHERE source != 'user'")
    abstract suspend fun clearAutomaticAliases()

    @Query("DELETE FROM album_resolution WHERE source != 'user'")
    abstract suspend fun clearAutomaticAlbumResolutions()

    @Query("DELETE FROM recording_cache")
    abstract suspend fun clearRecordings()

    /** Forget every automatic correction (user edits and user-taught aliases survive); caches stay warm. */
    @Transaction
    open suspend fun resetAutomatic() {
        clearAutomaticOverrides()
        clearSuggestions()
        clearAutomaticAliases()
        clearAutomaticAlbumResolutions()
        clearRecordings()
    }
}
