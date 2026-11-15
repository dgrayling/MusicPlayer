package com.example.musicplayer.data.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanerTest {

    @Test
    fun stripsJunk() {
        val cases = mapOf(
            "Song Name (Official Music Video) [HD]" to "Song Name",
            "Song Name [320 kbps]" to "Song Name",
            "Song Name (Lyrics)" to "Song Name",
            "Song Name - www.mp3juices.cc" to "Song Name",
            "Song Name [MP3Juices]" to "Song Name",
            "Hello_World_Again" to "Hello World Again",
            "Song Name ()" to "Song Name",
            // Meaningful brackets survive.
            "Song Name (Live at Wembley)" to "Song Name (Live at Wembley)",
            "Song Name (Remastered 2009)" to "Song Name (Remastered 2009)",
        )
        cases.forEach { (raw, expected) -> assertEquals(raw, expected, TextCleaner.stripJunk(raw)) }
    }

    @Test
    fun leadingTrackNumbersOnlyWhenUnambiguous() {
        assertEquals("Come Together", TextCleaner.cleanTitle("01 - Come Together"))
        assertEquals("Come Together", TextCleaner.cleanTitle("1. Come Together"))
        assertEquals("Come Together", TextCleaner.cleanTitle("07 Come Together"))
        assertEquals("7 Rings", TextCleaner.cleanTitle("7 Rings"))
        assertEquals("99 Problems", TextCleaner.cleanTitle("99 Problems"))
        assertEquals("1979", TextCleaner.cleanTitle("1979"))
    }

    @Test
    fun removesArtistPrefixFromTitle() {
        assertEquals("Help!", TextCleaner.cleanTitle("The Beatles - Help!", artist = "The Beatles"))
    }

    @Test
    fun normalizesArtists() {
        assertEquals("The Beatles", TextCleaner.cleanArtist("Beatles, The"))
        assertEquals("Radiohead", TextCleaner.cleanArtist("radiohead"))
        assertEquals("AC/DC", TextCleaner.cleanArtist("AC/DC"))
        assertEquals("Drake feat. Rihanna", TextCleaner.cleanArtist("Drake ft Rihanna"))
    }

    @Test
    fun matchKeysUnifySpellings() {
        val k = TextCleaner.key("The Beatles")
        listOf("beatles", "Beatles, The", " THE BEATLES ", "The Beatles feat. Billy Preston").forEach {
            assertEquals(it, k, TextCleaner.key(it))
        }
        assertEquals(TextCleaner.key("Beyonce"), TextCleaner.key("Beyoncé"))
        assertEquals(TextCleaner.key("Simon and Garfunkel"), TextCleaner.key("Simon & Garfunkel"))
        assertNotEquals(TextCleaner.key("Oasis"), TextCleaner.key("Blur"))
    }

    @Test
    fun placeholders() {
        listOf(null, "", "<unknown>", "Unknown artist", "Track 01", "untitled").forEach { assertTrue("$it", TextCleaner.isPlaceholder(it)) }
        listOf("Joy Division", "Track Star").forEach { assertFalse(it, TextCleaner.isPlaceholder(it)) }
    }
}

class FilenameParserTest {

    @Test
    fun artistAlbumYearFolders() {
        val p = FilenameParser.parse("/storage/emulated/0/Music/Radiohead/OK Computer (1997)/03 - Subterranean Homesick Alien.mp3")
        assertEquals(ParsedPath("Subterranean Homesick Alien", "Radiohead", "OK Computer", 3, 1997), p)
    }

    @Test
    fun trackArtistTitleFileInGenericFolder() {
        val p = FilenameParser.parse("/storage/emulated/0/Download/01 - Daft Punk - One More Time [320kbps].mp3")
        assertEquals(ParsedPath("One More Time", "Daft Punk", null, 1, null), p)
    }

    @Test
    fun leadingDigitsInArtistAreNotATrackNumber() {
        val p = FilenameParser.parse("/storage/emulated/0/Download/50 Cent - In Da Club.mp3")
        assertEquals("50 Cent", p.artist)
        assertEquals("In Da Club", p.title)
        assertNull(p.trackNumber)
        assertEquals("7 Rings", FilenameParser.parse("/storage/emulated/0/Music/7 Rings.mp3").title)
    }

    @Test
    fun artistDashAlbumFolderWithDiscSubfolder() {
        val p = FilenameParser.parse("/storage/emulated/0/Music/Pink Floyd - The Wall/CD2/01 Hey You.flac")
        assertEquals(ParsedPath("Hey You", "Pink Floyd", "The Wall", 1, null), p)
    }
}
