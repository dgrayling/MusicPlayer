package com.example.musicplayer.data

import com.example.musicplayer.data.db.PlaylistDao
import com.example.musicplayer.data.db.PlaylistEntity
import com.example.musicplayer.data.model.Playlist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlaylistRepository(private val dao: PlaylistDao) {

    val playlists: Flow<List<Playlist>> = dao.observePlaylists().map { rows ->
        rows.map { Playlist(it.id, it.name, it.songCount) }
    }

    fun playlistName(id: Long): Flow<String?> = dao.observePlaylist(id).map { it?.name }

    fun songIds(id: Long): Flow<List<Long>> = dao.observeSongIds(id)

    suspend fun create(name: String, songIds: List<Long> = emptyList()): Long {
        val id = dao.insertPlaylist(PlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
        if (songIds.isNotEmpty()) dao.addSongs(id, songIds)
        return id
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun addSongs(id: Long, songIds: List<Long>) = dao.addSongs(id, songIds)

    suspend fun removeSong(id: Long, songId: Long) = dao.removeSong(id, songId)
}
