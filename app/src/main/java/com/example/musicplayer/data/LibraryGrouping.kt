package com.example.musicplayer.data

import com.example.musicplayer.data.model.Album
import com.example.musicplayer.data.model.Artist
import com.example.musicplayer.data.model.Folder
import com.example.musicplayer.data.model.FolderContents
import com.example.musicplayer.data.model.Song

/** Pure functions that derive albums/artists from the flat song list. */
object LibraryGrouping {

    fun sortKey(text: String): String {
        val lower = text.trim().lowercase()
        return if (lower.startsWith("the ")) lower.removePrefix("the ") else lower
    }

    fun sortSongs(songs: List<Song>): List<Song> =
        songs.sortedWith(compareBy({ sortKey(it.title) }, { it.id }))

    fun albums(songs: List<Song>): List<Album> =
        songs.groupBy { it.albumId }
            .map { (albumId, albumSongs) ->
                val ordered = albumSongs.sortedWith(compareBy({ it.trackNumber }, { sortKey(it.title) }))
                val artists = albumSongs.map { it.artist }.distinct()
                Album(
                    id = albumId,
                    title = ordered.first().album,
                    artist = artists.singleOrNull() ?: VARIOUS_ARTISTS,
                    artistId = ordered.first().artistId,
                    year = albumSongs.maxOf { it.year },
                    songs = ordered,
                )
            }
            .sortedWith(compareBy({ sortKey(it.title) }, { it.id }))

    fun artists(songs: List<Song>, albums: List<Album>): List<Artist> {
        val albumsById = albums.associateBy { it.id }
        return songs.groupBy { it.artistId }
            .map { (artistId, artistSongs) ->
                val artistAlbums = artistSongs.map { it.albumId }.distinct()
                    .mapNotNull { albumsById[it] }
                    .sortedWith(compareByDescending<Album> { it.year }.thenBy { sortKey(it.title) })
                Artist(
                    id = artistId,
                    name = artistSongs.first().artist,
                    albums = artistAlbums,
                    songs = sortSongs(artistSongs),
                )
            }
            .sortedWith(compareBy({ sortKey(it.name) }, { it.id }))
    }

    /** Groups songs by the directory they live in; songs keep file-name order, like a file browser. */
    fun folders(songs: List<Song>): List<Folder> =
        songs.filter { '/' in it.path }
            .groupBy { it.path.substringBeforeLast('/') }
            .map { (dir, dirSongs) ->
                Folder(dir, dirSongs.sortedWith(compareBy({ it.path.substringAfterLast('/').lowercase() }, { it.id })))
            }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.path }))

    /** Deepest directory containing every music folder: where folder browsing starts. */
    fun folderRoot(folders: List<Folder>): String {
        if (folders.isEmpty()) return ""
        var common = folders.first().path.split('/')
        for (f in folders) {
            val parts = f.path.split('/')
            common = common.zip(parts).takeWhile { (a, b) -> a == b }.map { it.first }
        }
        return common.joinToString("/")
    }

    /**
     * What's inside [path]: its immediate subfolders (each carrying all songs beneath it, in path order)
     * and the songs stored directly in it.
     */
    fun folderContents(folders: List<Folder>, path: String): FolderContents {
        val prefix = "$path/"
        val subfolders = folders
            .filter { it.path.startsWith(prefix) }
            .groupBy { prefix + it.path.removePrefix(prefix).substringBefore('/') }
            .map { (child, nested) -> Folder(child, nested.sortedBy { it.path.lowercase() }.flatMap { it.songs }) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.path }))
        val direct = folders.firstOrNull { it.path == path }?.songs.orEmpty()
        return FolderContents(Folder(path, direct + subfolders.flatMap { it.songs }), subfolders, direct)
    }

    const val VARIOUS_ARTISTS = "Various artists"
}
