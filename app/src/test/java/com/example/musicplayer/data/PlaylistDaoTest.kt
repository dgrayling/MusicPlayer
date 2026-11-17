package com.example.musicplayer.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.musicplayer.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: PlaylistRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = PlaylistRepository(db.playlistDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun songsAreAppendedInOrderWithoutDuplicates() = runTest {
        val id = repo.create("Mix", listOf(3L, 1L))
        repo.addSongs(id, listOf(1L, 2L, 2L))
        assertEquals(listOf(3L, 1L, 2L), repo.songIds(id).first())
        assertEquals(3, repo.playlists.first().single().songCount)
    }

    @Test
    fun replacingSongsKeepsPositionsAndDropsRepeats() = runTest {
        val a = repo.create("A", listOf(1L, 2L, 3L))
        val b = repo.create("B", listOf(2L, 5L)) // already has 5, the replacement for 2
        repo.replaceSongs(mapOf(2L to 5L))
        assertEquals(listOf(1L, 5L, 3L), repo.songIds(a).first())
        assertEquals(listOf(5L), repo.songIds(b).first())
    }

    @Test
    fun removeRenameAndDelete() = runTest {
        val id = repo.create("Old name", listOf(1L, 2L))
        repo.removeSong(id, 1L)
        assertEquals(listOf(2L), repo.songIds(id).first())

        repo.rename(id, "  New name ")
        assertEquals("New name", repo.playlistName(id).first())

        repo.delete(id)
        assertTrue(repo.playlists.first().isEmpty())
        assertTrue(repo.songIds(id).first().isEmpty())
    }
}
