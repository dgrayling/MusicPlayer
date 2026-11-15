package com.example.musicplayer.data.enrich

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.musicplayer.data.db.AppDatabase
import com.example.musicplayer.data.db.EnrichmentDao
import com.example.musicplayer.data.db.OnlineStatus
import com.example.musicplayer.data.db.OverrideSource
import com.example.musicplayer.data.model.Song
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
class EnrichmentRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: EnrichmentDao

    private val abbeyRoad = listOf(
        "Come Together" to 259_000L, "Something" to 182_000L, "Maxwell's Silver Hammer" to 207_000L,
        "Oh! Darling" to 206_000L, "Octopus's Garden" to 170_000L, "I Want You (She's So Heavy)" to 467_000L,
        "Here Comes the Sun" to 185_000L, "Because" to 165_000L, "You Never Give Me Your Money" to 242_000L,
        "Sun King" to 146_000L, "Mean Mr. Mustard" to 66_000L, "Polythene Pam" to 72_000L,
    ).map { (t, l) -> Trk(t, l) }

    private val fetcher = FakeFetcher(
        listOf(
            "/release?query" to HttpResult(200, mbReleaseSearchJson(Triple("R-ABBEY", "Abbey Road", 12), artist = "The Beatles")),
            "/release/R-ABBEY" to HttpResult(200, mbReleaseJson("R-ABBEY", "Abbey Road", "The Beatles", "1969-09-26", "rock", abbeyRoad)),
            "/recording?query" to HttpResult(200, """{"recordings":[]}"""),
        ),
    )

    private lateinit var repo: EnrichmentRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.enrichmentDao()
        repo = EnrichmentRepository(dao, MusicBrainzClient(fetcher), itunes = null)
    }

    @After
    fun tearDown() = db.close()

    /** The album as a messy library holds it: lowercase artist, junk in titles, no years. */
    private fun folder(dir: String, firstId: Long, artist: String = "beatles") = abbeyRoad.mapIndexed { i, t ->
        song(
            id = firstId + i,
            title = if (i % 3 == 0) "${t.title} (Official Audio)" else t.title,
            artist = artist,
            album = "Abbey Road",
            path = "$dir/${t.title}.mp3",
            durationMs = t.lengthMs + 900,
        )
    }

    private suspend fun shown(songs: List<Song>): Map<Long, Song> =
        MetadataOverlay.applyAll(songs, repo.overrides.first()).associateBy { it.id }

    @Test
    fun oneAlbumLookupFixesEveryTrack() = runTest {
        val songs = folder("/storage/emulated/0/Music/beatles/Abbey Road", 1)
        repo.runLocalPass(songs)
        val result = repo.runOnlinePass(songs)

        assertEquals(OnlinePassResult(12, 0), result)
        assertEquals("1 search + 1 release fetch for 12 songs", 2, fetcher.calls.size)
        val out = shown(songs)
        songs.forEachIndexed { i, s ->
            val fixed = out.getValue(s.id)
            assertEquals(abbeyRoad[i].title, fixed.title)
            assertEquals("The Beatles", fixed.artist)
            assertEquals("Abbey Road", fixed.album)
            assertEquals(1969, fixed.year)
            assertEquals("Rock", fixed.genre)
            assertEquals(i + 1, fixed.trackNumber)
            assertEquals("https://coverartarchive.org/release/R-ABBEY/front-500", fixed.remoteArtworkUrl)
        }
        assertEquals(12, repo.statusCounts.first()[OnlineStatus.MATCHED])
    }

    @Test
    fun learnedFactsGeneralizeToOtherSongsAndCopies() = runTest {
        val album = folder("/storage/emulated/0/Music/beatles/Abbey Road", 1)
        // A loose single in the same spelling that MusicBrainz won't find.
        val single = song(
            100, title = "beatles - Rare Demo", artist = "<unknown>", album = "Download",
            path = "/storage/emulated/0/Download/beatles - Rare Demo.mp3",
        )
        repo.runLocalPass(album + single)
        repo.runOnlinePass(album + single)
        assertEquals("Beatles", shown(album + single).getValue(100).artist)

        // The album match taught "beatles" → "The Beatles"; the local pass now applies it everywhere.
        repo.runLocalPass(album + single)
        assertEquals("The Beatles", shown(album + single).getValue(100).artist)
        assertEquals(OverrideSource.ONLINE, dao.alias("beatles")?.source)

        // A second copy of the album in another folder resolves from the cache with no network at all.
        val callsBefore = fetcher.calls.size
        val copy = folder("/storage/emulated/0/Download/Abbey Road", 200, artist = "The Beatles")
        val library = album + single + copy
        repo.runLocalPass(library)
        repo.runOnlinePass(library)
        assertEquals(callsBefore, fetcher.calls.size)
        assertEquals("Here Comes the Sun", shown(library).getValue(206).title)
        assertEquals(12, repo.stats.value.fromCache)

        // Nothing is due any more: a re-run makes no requests.
        repo.runOnlinePass(library)
        assertEquals(callsBefore, fetcher.calls.size)
    }

    @Test
    fun userEditsAreNeverOverwritten() = runTest {
        val songs = folder("/storage/emulated/0/Music/beatles/Abbey Road", 1)
        repo.runLocalPass(songs)
        repo.editSong(songs[0], title = "My Title", artist = "", album = "", year = null, genre = "")
        repo.runLocalPass(songs)
        repo.runOnlinePass(songs)
        val row = dao.override(1)!!
        assertTrue(row.locked)
        assertEquals("My Title", row.title)
        assertEquals("My Title", shown(songs).getValue(1).title)
        // Its siblings were still matched.
        assertEquals(abbeyRoad[1].title, shown(songs).getValue(2).title)
    }

    @Test
    fun uncertainMatchesBecomeSuggestionsAndAcceptingTeachesTheFolder() = runTest {
        // Garbage titles: only track numbers and lengths line up, so it shouldn't auto-apply.
        val songs = abbeyRoad.mapIndexed { i, t ->
            song(300L + i, "Track ${i + 1}", "beatles", "Abbey Road", path = "/m/x/abbey road/${i + 1}.mp3",
                durationMs = t.lengthMs, track = i + 1)
        }
        repo.runLocalPass(songs)
        repo.runOnlinePass(songs)
        val sugs = repo.suggestions.first()
        assertEquals(12, sugs.size)
        assertEquals("Track 1", shown(songs).getValue(300).title)

        repo.acceptSuggestions(shown(songs).values.toList())
        assertEquals("Come Together", shown(songs).getValue(300).title)
        assertTrue(repo.suggestions.first().isEmpty())
        assertEquals(OverrideSource.USER, dao.albumResolution("dir:/m/x/abbey road|abbey road")?.source)
    }
}
