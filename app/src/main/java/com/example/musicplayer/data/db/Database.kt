package com.example.musicplayer.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: Long,
    val position: Int,
)

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val songCount: Int,
)

@Dao
abstract class PlaylistDao {
    @Query(
        """
        SELECT p.id, p.name, COUNT(ps.songId) AS songCount
        FROM playlists p LEFT JOIN playlist_songs ps ON ps.playlistId = p.id
        GROUP BY p.id
        ORDER BY p.name COLLATE NOCASE
        """,
    )
    abstract fun observePlaylists(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract fun observePlaylist(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position")
    abstract fun observeSongIds(playlistId: Long): Flow<List<Long>>

    @Insert
    abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :playlistId")
    abstract suspend fun maxPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSongs(songs: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    abstract suspend fun removeSong(playlistId: Long, songId: Long)

    @Query("UPDATE OR IGNORE playlist_songs SET songId = :to WHERE songId = :from")
    abstract suspend fun repointSong(from: Long, to: Long)

    @Query("DELETE FROM playlist_songs WHERE songId = :songId")
    abstract suspend fun removeSongEverywhere(songId: Long)

    /** Points every playlist entry for a key song at its value song; drops entries that would then repeat. */
    @Transaction
    open suspend fun replaceSongs(replacements: Map<Long, Long>) {
        for ((from, to) in replacements) {
            repointSong(from, to)
            removeSongEverywhere(from) // left only where the playlist already had `to`
        }
    }

    /** Appends songs to the end of the playlist, skipping any already in it. */
    @Transaction
    open suspend fun addSongs(playlistId: Long, songIds: List<Long>) {
        var position = maxPosition(playlistId)
        insertSongs(songIds.distinct().map { PlaylistSongEntity(playlistId, it, ++position) })
    }
}

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        SongOverrideEntity::class,
        SongSuggestionEntity::class,
        ArtistAliasEntity::class,
        AlbumResolutionEntity::class,
        ReleaseCacheEntity::class,
        RecordingCacheEntity::class,
        HttpCacheEntity::class,
        TrackTrimEntity::class,
    ],
    version = 3,
    // v2 adds the metadata-enrichment tables and v3 the track start/end marks, so Room can generate both migrations.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun enrichmentDao(): EnrichmentDao
    abstract fun trimDao(): TrimDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "music.db").build()
    }
}
