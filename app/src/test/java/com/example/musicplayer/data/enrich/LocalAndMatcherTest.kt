package com.example.musicplayer.data.enrich

import com.example.musicplayer.data.db.SongOverrideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalCleanupTest {

    @Test
    fun untaggedFileGetsEverythingFromItsPath() {
        // MediaStore's fallbacks for an untagged file: title = file name, album = folder name.
        val s = song(
            1, title = "03 - Subterranean Homesick Alien", artist = "<unknown>", album = "OK Computer (1997)",
            path = "/storage/emulated/0/Music/Radiohead/OK Computer (1997)/03 - Subterranean Homesick Alien.mp3",
        )
        val fix = LocalCleanup.fix(s)
        assertEquals("Subterranean Homesick Alien", fix.title)
        assertEquals("Radiohead", fix.artist)
        assertEquals("OK Computer", fix.album)
        assertEquals(1997, fix.year)
        assertEquals(3, fix.trackNumber)
        assertEquals(0.7, fix.confidence, 0.0)
    }

    @Test
    fun taggedFileOnlyLosesJunk() {
        val fix = LocalCleanup.fix(
            song(
                1, title = "Karma Police (Official Video)", artist = "Radiohead", album = "OK Computer", year = 1997, track = 6,
                path = "/storage/emulated/0/Music/Radiohead/OK Computer/06 Karma Police.mp3",
            ),
        )
        assertEquals("Karma Police", fix.title)
        assertNull(fix.artist)
        assertNull(fix.album)
        assertEquals(0.9, fix.confidence, 0.0)
    }

    @Test
    fun folderNamedLikeDownloadsIsNotAnAlbum() {
        val fix = LocalCleanup.fix(song(1, "Song", artist = "Someone", album = "Download", path = "/storage/emulated/0/Download/Song.mp3"))
        assertEquals(LocalCleanup.UNKNOWN_ALBUM, fix.album)
    }

    @Test
    fun aliasesAndLibraryWideSpellings() {
        val spellings = LocalCleanup.preferredSpellings(listOf("Beatles", "The Beatles", "The Beatles", "beatles", "Oasis"))
        assertEquals(mapOf("beatles" to "The Beatles"), spellings)
        val fix = LocalCleanup.fix(song(1, "Help!", artist = "beatles"), artistAlias = { spellings[it] })
        assertEquals("The Beatles", fix.artist)
    }
}

@RunWith(RobolectricTestRunner::class)
class MetadataOverlayTest {

    private fun override(s: com.example.musicplayer.data.model.Song, title: String? = null, artist: String? = null,
                         release: String? = null, locked: Boolean = false, fingerprint: String = s.fingerprint) =
        SongOverrideEntity(s.id, fingerprint, title = title, artist = artist, mbReleaseId = release, locked = locked)

    @Test
    fun overridesApplyOnlyToTheSameFile() {
        val s = song(1, "raw")
        assertEquals("fixed", MetadataOverlay.apply(s, override(s, title = "fixed")).title)
        assertEquals("raw", MetadataOverlay.apply(s, override(s, title = "fixed", fingerprint = "old")).title)
        assertEquals("mine", MetadataOverlay.apply(s, override(s, title = "mine", fingerprint = "old", locked = true)).title)
    }

    @Test
    fun correctedArtistsAndReleasesShareIds() {
        val a = song(1, "A", artist = "Beatles", artistId = 5, albumId = 50)
        val b = song(2, "B", artist = "The Beatles", artistId = 3, albumId = 30)
        val c = song(3, "C", artist = "Oasis", artistId = 9, albumId = 90)
        val out = MetadataOverlay.applyAll(
            listOf(a, b, c),
            mapOf(1L to override(a, artist = "The Beatles", release = "R"), 2L to override(b, release = "R")),
        ).associateBy { it.id }
        assertEquals(3L, out.getValue(1).artistId)
        assertEquals(3L, out.getValue(2).artistId)
        assertEquals(30L, out.getValue(1).albumId)
        assertEquals(30L, out.getValue(2).albumId)
        assertEquals(9L, out.getValue(3).artistId)
    }
}

@RunWith(RobolectricTestRunner::class)
class MatcherTest {
    private val tracks = (1..10).map { MbTrack(1, it, "Song Number $it", "Band", 180_000L + it * 7_000, "r$it") }
    private val release = MbRelease("R", "The Album", "Band", null, 2001, "Rock", null, tracks)

    @Test
    fun jaroWinklerSanity() {
        assertEquals(0.961, Matcher.jaroWinkler("martha", "marhta"), 0.001)
        assertEquals(1.0, Matcher.similarity("The Beatles", "beatles"), 0.0)
    }

    @Test
    fun correctAlbumScoresHigh() {
        val songs = tracks.map { t -> song(t.position.toLong(), t.title, "band", "The Album", durationMs = t.lengthMs!! + 1_000) }
        val m = Matcher.matchAlbum(songs, "band", "The Album", release)
        assertTrue("score ${m.score}", m.score >= Matcher.APPLY)
        assertEquals(10, m.assignments.size)
        m.assignments.forEach { assertEquals(it.songId.toInt(), it.track.position) }
    }

    @Test
    fun partialAlbumStillMatches() {
        val songs = tracks.take(6).map { t -> song(t.position.toLong(), t.title, "Band", "The Album", durationMs = t.lengthMs!!) }
        assertTrue(Matcher.matchAlbum(songs, "Band", "The Album", release).score >= Matcher.APPLY)
    }

    @Test
    fun garbageTitlesWithMatchingTrackNumbersAndLengthsAreAtLeastSuggested() {
        val songs = tracks.map { t -> song(t.position.toLong(), "Track ${t.position}", "Band", "The Album", durationMs = t.lengthMs!!, track = t.position) }
        val m = Matcher.matchAlbum(songs, "Band", "The Album", release)
        assertTrue("score ${m.score}", m.score >= Matcher.SUGGEST)
        assertEquals(10, m.assignments.size)
    }

    @Test
    fun wrongAlbumScoresLow() {
        val songs = (1..10).map { song(it.toLong(), "Different tune $it", "Other", "Other Album", durationMs = 400_000L + it * 13_000) }
        assertTrue(Matcher.matchAlbum(songs, "Other", "Other Album", release).score < Matcher.SUGGEST)
    }

    @Test
    fun recordingScore() {
        val rec = MbRecording("x", "Wonderwall", "Oasis", null, 258_000, emptyList(), 100)
        assertTrue(Matcher.matchRecording(song(1, "Wonderwall", "oasis", durationMs = 259_000), rec) >= Matcher.APPLY)
        assertTrue(Matcher.matchRecording(song(1, "Wonderwall", "oasis", durationMs = 420_000), rec) < Matcher.APPLY)
        assertTrue(Matcher.matchRecording(song(1, "Champagne Supernova", "oasis", durationMs = 258_000), rec) < Matcher.SUGGEST)
    }
}
