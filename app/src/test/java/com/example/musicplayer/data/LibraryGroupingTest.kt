package com.example.musicplayer.data

import android.net.Uri
import com.example.musicplayer.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryGroupingTest {

    private fun song(
        id: Long,
        title: String,
        artist: String = "Artist",
        artistId: Long = 1,
        album: String = "Album",
        albumId: Long = 10,
        track: Int = 0,
        year: Int = 2000,
        path: String = "",
    ) = Song(id, title, artist, artistId, album, albumId, 1000L, track, year, 0L, Uri.parse("content://x/$id"), path)

    @Test
    fun albumSongsAreOrderedByTrackNumber() {
        val songs = listOf(song(1, "C", track = 3), song(2, "A", track = 1), song(3, "B", track = 2))
        val album = LibraryGrouping.albums(songs).single()
        assertEquals(listOf("A", "B", "C"), album.songs.map { it.title })
    }

    @Test
    fun albumWithMultipleArtistsIsVariousArtists() {
        val songs = listOf(
            song(1, "X", artist = "One", artistId = 1),
            song(2, "Y", artist = "Two", artistId = 2),
        )
        assertEquals(LibraryGrouping.VARIOUS_ARTISTS, LibraryGrouping.albums(songs).single().artist)
    }

    @Test
    fun sortingIgnoresLeadingThe() {
        val songs = listOf(song(1, "Zebra"), song(2, "The Apple"), song(3, "banana"))
        assertEquals(listOf("The Apple", "banana", "Zebra"), LibraryGrouping.sortSongs(songs).map { it.title })
    }

    @Test
    fun artistsCollectTheirAlbumsNewestFirst() {
        val songs = listOf(
            song(1, "a", album = "Old", albumId = 10, year = 1990),
            song(2, "b", album = "New", albumId = 11, year = 2020),
            song(3, "c", artist = "Other", artistId = 2, album = "Else", albumId = 12),
        )
        val albums = LibraryGrouping.albums(songs)
        val artists = LibraryGrouping.artists(songs, albums)
        assertEquals(listOf("Artist", "Other"), artists.map { it.name })
        assertEquals(listOf("New", "Old"), artists.first().albums.map { it.title })
        assertEquals(2, artists.first().songs.size)
    }

    @Test
    fun songsAreGroupedByFolderInFileNameOrder() {
        val songs = listOf(
            song(1, "Z", path = "/storage/emulated/0/Music/Rock/02 b.mp3"),
            song(2, "Y", path = "/storage/emulated/0/Music/Rock/01 a.mp3"),
            song(3, "X", path = "/storage/emulated/0/Download/c.mp3"),
            song(4, "W"), // no path: not in any folder
        )
        val folders = LibraryGrouping.folders(songs)
        assertEquals(listOf("Download", "Rock"), folders.map { it.name })
        assertEquals(listOf(2L, 1L), folders[1].songs.map { it.id })
        assertEquals("Internal storage/Music", folders[1].location)
    }

    @Test
    fun folderBrowsingStartsAtCommonRootAndNestsSubfolders() {
        val songs = listOf(
            song(1, "A", path = "/s/Music/Rock/Live/a.mp3"),
            song(2, "B", path = "/s/Music/Rock/b.mp3"),
            song(3, "C", path = "/s/Music/Jazz/c.mp3"),
        )
        val folders = LibraryGrouping.folders(songs)
        val root = LibraryGrouping.folderRoot(folders)
        assertEquals("/s/Music", root)
        val top = LibraryGrouping.folderContents(folders, root)
        assertEquals(listOf("Jazz", "Rock"), top.subfolders.map { it.name })
        assertEquals(listOf(2L, 1L), top.subfolders[1].songs.map { it.id }) // direct songs, then Live/
        val rock = LibraryGrouping.folderContents(folders, "/s/Music/Rock")
        assertEquals(listOf("Live"), rock.subfolders.map { it.name })
        assertEquals(listOf(2L), rock.songs.map { it.id })
    }
}
