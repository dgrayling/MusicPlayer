package com.example.musicplayer.data.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Shapes seen in a real phone library: YouTube downloads in playlist-style folders. */
@RunWith(RobolectricTestRunner::class)
class RealLibraryCasesTest {
    private val dl = "/storage/emulated/0/Download/music"

    @Test
    fun youtubeIdsAndJunkAreStripped() {
        val cases = mapOf(
            "Motorcade-mHJIp7bHIzc" to "Motorcade",
            "Uma Thurman-2VTxYQL2SbA" to "Uma Thurman",
            "Tropicalia (Mutations)-_L16J4Sk944" to "Tropicalia (Mutations)",
            "Do Ya Like (NEKXSTXZIS Remix)-5ymhZ-ODcJw" to "Do Ya Like (NEKXSTXZIS Remix)",
            "Hypercolour-hBaYYHAU_tg" to "Hypercolour",
            "EIGHTY-SIX [yq-Taa-wNzg]" to "EIGHTY-SIX",
            "Halo 3 Main Menu Music - HD 1080p-uXcWxw7UHME" to "Halo 3 Main Menu Music",
            "LOONA - Why Not _ Music Visualization🖤🎶-Qon_OfwtzQg" to "LOONA - Why Not",
            // Not ids: real words, symbols that belong to titles.
            "All I Want Is-Christmases" to "All I Want Is-Christmases",
            "AL℃-@" to "AL℃-@",
            "☆Chidonya" to "☆Chidonya",
        )
        cases.forEach { (raw, expected) -> assertEquals(raw, expected, TextCleaner.stripJunk(raw)) }
    }

    @Test
    fun artistsFromFileNamesOfYoutubeDownloads() {
        val s = song(1, "Halsey - Ghost-ao4o-XRU_KM", artist = "<unknown>", album = "oldFolder",
            path = "$dl/oldFolder/Halsey - Ghost-ao4o-XRU_KM.mp3")
        val fix = LocalCleanup.fix(s)
        assertEquals("Ghost", fix.title)
        assertEquals("Halsey", fix.artist)
    }

    @Test
    fun mixedArtistFolderIsAPlaylistNotAnAlbum() {
        val dir = "/storage/emulated/0/Download/mp3backup-20260401T115416Z-1-002/mp3backup/slow"
        val songs = listOf("Halsey - Ghost", "Grimes - REALiTi", "Beck - Tropicalia", "Some Untitled Thing").mapIndexed { i, t ->
            song(i.toLong(), t, artist = "<unknown>", album = "slow", path = "$dir/$t.mp3")
        }
        val playlists = LocalCleanup.playlistFolders(songs)
        assertEquals(setOf(dir), playlists)
        val fix = LocalCleanup.fix(songs[3], hints = LocalCleanup.FolderHints(isAlbum = false))
        assertEquals(LocalCleanup.UNKNOWN_ALBUM, fix.album)
        // The backup folder above is never taken as the artist.
        assertNull(fix.artist)
    }

    @Test
    fun realAlbumFolderIsKept() {
        val dir = "$dl/anime/Katanagatari Original Soundtrack Vol.2"
        val songs = listOf("Ootori", "Peacock blue eyes", "With best wishes").mapIndexed { i, t ->
            song(i.toLong(), t, artist = "Iwasaki Taku", album = "Katanagatari Original Soundtrack Vol.2", path = "$dir/$t.mp3")
        }
        assertTrue(LocalCleanup.playlistFolders(songs).isEmpty())
    }

    @Test
    fun backupAndDateFoldersAreGeneric() {
        listOf("mp3backup", "mp3backup-20260401T115416Z-1-002", "27processed", "feb2023", "oct", "2022july", "youtube")
            .forEach { assertTrue(it, FilenameParser.isGenericDir(it)) }
        listOf("Shadow of the Colossus", "Katanagatari Original Soundtrack Vol.1", "phonk")
            .forEach { assertTrue(it, !FilenameParser.isGenericDir(it)) }
    }

    @Test
    fun caselessScriptsAreLeftAlone() {
        assertEquals("abc澤野 弘之", TextCleaner.cleanArtist("abc澤野 弘之"))
        assertEquals("Halsey", TextCleaner.cleanArtist("HalseyVEVO"))
        assertEquals("Iwasaki Taku", TextCleaner.cleanArtist("Iwasaki Taku - Topic"))
    }

    @Test
    fun misdecodedTagsAreRecovered() {
        assertEquals("birth·gt·music", TextCleaner.recoverMisdecoded("扩牴梷杴띭畳楣"))
        // Real Japanese/Chinese text is never "recovered".
        listOf("澤野弘之", "TVアニメ「アルドノア・ゼロ」オリジナルサウンドトラック", "狂乱 Hey Kids!!", "Halsey").forEach {
            assertNull(it, TextCleaner.recoverMisdecoded(it))
        }
        val s = song(1, "扩牴梷杴띭畳楣", artist = "澤野弘之", album = "Aldnoah", path = "$dl/aldnoah/birth·gt·music.mp3")
        assertEquals("Birth·gt·music", LocalCleanup.fix(s).title) // all-lowercase titles get capitalized
    }

    @Test
    fun dashWrappedIdsAndStylizedDashes() {
        assertEquals("government_hooker (slowed)", TextCleaner.stripJunk("government_hooker (slowed) -L9uE9NoDhIw-"))
        assertEquals("CRUSH -Impossible Piano-", TextCleaner.stripJunk("CRUSH -Impossible Piano- -lFvfEOXYbeY-"))
        assertEquals("No differences -a0v-", TextCleaner.stripJunk("No differences -a0v-"))
        assertEquals("Song", TextCleaner.stripJunk("Song -"))
        // A leading number equal to the track tag is the track number.
        val s = song(1, "1 No differences -a0v-", artist = "Aimee Blackschleger", album = "Re:arrange", track = 1,
            path = "$dl/rearrange/1 No differences -a0v-.mp3")
        assertEquals("No differences -a0v-", LocalCleanup.fix(s).title)
    }

    @Test
    fun threePartNames() {
        assertEquals("STRLGHT" to "Clutch", FilenameParser.splitArtistTitle("MMV – STRLGHT - Clutch"))
        assertEquals("Resonance" to "Home (Piano Tutorial + MIDI)", FilenameParser.splitArtistTitle("Resonance - Home - Piano Tutorial + MIDI"))
        assertEquals("Halsey" to "Ghost", FilenameParser.splitArtistTitle("Halsey - Ghost"))
        assertNull(FilenameParser.splitArtistTitle("Love Live Noises"))
    }

    @Test
    fun unnumberedUntaggedFolderIsNotAnAlbum() {
        val dir = "$dl/Nonsense"
        val songs = listOf("Love Live Noises", "Theunknowdarkmartter").mapIndexed { i, t ->
            song(i.toLong(), t, artist = "<unknown>", album = "Nonsense", path = "$dir/$t.mp3")
        }
        assertEquals(setOf(dir), LocalCleanup.playlistFolders(songs))
        // An untagged but track-numbered rip is still an album.
        val rip = listOf("01 - Opening", "02 - Field").mapIndexed { i, t ->
            song(10L + i, t, artist = "<unknown>", album = "Some Album", path = "$dl/Some Album/$t.mp3")
        }
        assertTrue(LocalCleanup.playlistFolders(rip).isEmpty())
    }

    @Test
    fun swappedTitleAndArtistAcrossAFolder() {
        // Real case: Download/music/Jak/10.mp3 tagged title "JAK-X - Original Game Audio", artist "Track 10".
        val dir = "$dl/Jak"
        val tagged = listOf(10, 13, 2, 7).map { n ->
            // Some files have space-padded ID3v1 tags.
            val pad = if (n == 10) "   " else ""
            song(n.toLong(), "JAK-X - Original Game Audio$pad", artist = "Track $n$pad", album = "Jak", path = "$dir/$n.mp3")
        }
        val untagged = song(16, "16", artist = "<unknown>", album = "Jak", path = "$dir/16.mp3")
        val songs = tagged + untagged
        val hints = LocalCleanup.folderHints(songs)
        assertEquals(TextCleaner.key("JAK-X - Original Game Audio"), hints[dir]?.swappedTitle)

        val fix = LocalCleanup.fix(tagged[0], hints = LocalCleanup.hintsFor(tagged[0], hints))
        assertEquals("Track 10", fix.title)
        assertEquals(LocalCleanup.UNKNOWN_ARTIST, fix.artist) // not "JAK-X", not "Track 10"
        assertEquals("JAK-X - Original Game Audio", fix.album)
        assertEquals(10, fix.trackNumber)
        // The untagged song in the same folder joins the same album.
        assertEquals("JAK-X - Original Game Audio", LocalCleanup.fix(untagged, hints = LocalCleanup.hintsFor(untagged, hints)).album)
        assertEquals("Track 16", LocalCleanup.fix(untagged, hints = LocalCleanup.hintsFor(untagged, hints)).title)
        // A real numeric title is left alone.
        assertNull(LocalCleanup.fix(song(99, "1979", artist = "The Smashing Pumpkins", path = "$dl/sp/1979.mp3")).title)
    }

    @Test
    fun normalAlbumsAndArtistFoldersAreNotSwapped() {
        // One artist, different titles: normal.
        val album = (1..5).map { song(it.toLong(), "Song $it", artist = "Iwasaki Taku", album = "OST", path = "$dl/OST/$it.mp3") }
        // A compilation: different titles AND different artists: also normal.
        val comp = (1..5).map { song(10L + it, "Tune $it", artist = "Artist $it", album = "Comp", path = "$dl/Comp/$it.mp3") }
        assertTrue(LocalCleanup.folderHints(album + comp).values.none { it.swappedTitle != null })
    }
}
