package com.example.musicplayer.data.enrich

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayer.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MusicBrainzClientTest {
    private val server = MockWebServer()
    private lateinit var db: AppDatabase
    private lateinit var http: CachedHttp
    private lateinit var mb: MusicBrainzClient

    @Before
    fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        http = CachedHttp(OkHttpClient(), db.enrichmentDao(), "MusicPlayer/test ( test@example.invalid )", emptyMap(), retryBaseMs = 10)
        mb = MusicBrainzClient(http, server.url("/ws/2").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun parsesReleaseAndServesRepeatsFromCache() = runBlocking {
        val tracks = listOf(Trk("One", 100_000), Trk("Two", 120_000))
        server.enqueue(MockResponse().setBody(mbReleaseJson("R1", "Album", "Band", "2004-05-01", "indie rock", tracks)))

        val first = mb.release("R1")!!
        val second = mb.release("R1")!!

        assertEquals(1, server.requestCount)
        assertEquals(first, second)
        assertEquals(1, http.cacheHits.get())
        val req = server.takeRequest()
        assertEquals("MusicPlayer/test ( test@example.invalid )", req.getHeader("User-Agent"))
        assertTrue(req.path!!.startsWith("/ws/2/release/R1?inc="))
        assertEquals(2004, first.year)
        assertEquals("Indie Rock", first.genre)
        assertEquals("https://coverartarchive.org/release/R1/front-500", first.artworkUrl)
        assertEquals(listOf("One", "Two"), first.tracks.map { it.title })
        assertEquals(120_000L, first.tracks[1].lengthMs)
    }

    @Test
    fun notFoundIsCachedButServerErrorsAreNot() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(mb.release("missing"))
        assertNull(mb.release("missing"))
        assertEquals(1, server.requestCount)

        // Throttled: waits (Retry-After) and retries; four 503s in a row give up without caching anything.
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1")) }
        try {
            mb.release("busy"); fail("expected TransientHttpException")
        } catch (_: TransientHttpException) {
        }
        assertEquals(5, server.requestCount)
    }

    @Test
    fun throttledRequestSucceedsOnRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setBody(mbReleaseJson("R3", "Album", "Band", "2001", null, listOf(Trk("One", 1_000)))))
        assertEquals("Album", mb.release("R3")!!.title)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun searchQueryIsQuotedAndEncoded() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"releases":[]}"""))
        mb.searchReleases("AC/DC", "Back in \"Black\"")
        val path = server.takeRequest().requestUrl!!
        assertEquals("release:\"Back in \\\"Black\\\"\" AND artist:\"AC/DC\"", path.queryParameter("query"))
    }

    @Test
    fun tiedGenresGiveNoGenre() = runBlocking {
        val json = org.json.JSONObject(mbReleaseJson("R2", "Album", "Band", "1969", null, listOf(Trk("One", 1_000))))
            .put("release-group", org.json.JSONObject().put("genres", org.json.JSONArray()
                .put(org.json.JSONObject().put("name", "ambient").put("count", 2))
                .put(org.json.JSONObject().put("name", "rock").put("count", 2))))
        server.enqueue(MockResponse().setBody(json.toString()))
        assertNull(mb.release("R2")!!.genre)
    }

    @Test
    fun itunesFallbackPicksTheMatchingAlbumAtHigherResolution() = runBlocking {
        val body = """{"results":[
            {"collectionName":"Abbey Road (Remastered)","artistName":"The Beatles","artworkUrl100":"https://x/100x100bb.jpg","primaryGenreName":"Rock","releaseDate":"1969-09-26T07:00:00Z"},
            {"collectionName":"Abbey Road Tribute","artistName":"Someone Else","artworkUrl100":"https://y/100x100bb.jpg","primaryGenreName":"Pop"}
        ]}"""
        val itunes = ItunesClient(FakeFetcher(listOf("/search" to HttpResult(200, body))), "https://itunes.example")
        assertEquals(
            ItunesAlbum("Abbey Road (Remastered)", "The Beatles", "https://x/600x600bb.jpg", "Rock", 1969),
            itunes.findAlbum("The Beatles", "Abbey Road"),
        )
        assertNull(itunes.findAlbum("Nobody", "Nothing Like It"))
    }

    @Test
    fun rateLimiterSpacesCalls() = runTest {
        val limiter = RateLimiter(1_000) { testScheduler.currentTime }
        repeat(3) { limiter.acquire() }
        assertTrue("took ${testScheduler.currentTime}", testScheduler.currentTime >= 2_000)
    }
}

@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2KeepsPlaylists() {
        helper.createDatabase("migration-test", 1).use { db ->
            db.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (1, 'Mix', 0)")
            db.execSQL("INSERT INTO playlist_songs (playlistId, songId, position) VALUES (1, 42, 0)")
        }
        helper.runMigrationsAndValidate("migration-test", 2, true).use { db ->
            db.query("SELECT songId FROM playlist_songs WHERE playlistId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(42L, c.getLong(0))
            }
            db.query("SELECT COUNT(*) FROM song_override").use { c -> assertTrue(c.moveToFirst()) }
        }
    }
}
