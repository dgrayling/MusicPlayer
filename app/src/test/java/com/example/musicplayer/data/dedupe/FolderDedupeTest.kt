package com.example.musicplayer.data.dedupe

import android.net.Uri
import com.example.musicplayer.data.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class FolderDedupeTest {
    private val root = "/storage/emulated/0"
    private var nextId = 1L

    /** Files given as relative path to content; equal content strings mean identical bytes. */
    private fun files(vararg pairs: Pair<String, String>) =
        pairs.map { (rel, content) -> DedupeFile(nextId++, "$root/$rel", content.length.toLong(), content) }

    private fun List<FolderRemoval>.summary() =
        map { "${it.folder.removePrefix("$root/")} -> ${it.keptIn.removePrefix("$root/")} ${it.kind}" }

    @Test
    fun identicalCopiesKeepTheShallowestOne() {
        val plan = FolderDedupe.plan(
            files(
                "Music/Album/01.mp3" to "a", "Music/Album/02.mp3" to "bb",
                "Download/old/Album/01.mp3" to "a", "Download/old/Album/02.mp3" to "bb",
            ),
        )
        // Download/old holds exactly Album, so it is the top-level duplicate that goes.
        assertEquals(listOf("Download/old -> Music IDENTICAL"), plan.summary())
    }

    @Test
    fun identicalCopiesPreferTheOneUnderMusic() {
        val plan = FolderDedupe.plan(
            files("Music/Rock/Album/1" to "a", "Music/Rock/Other/9" to "z", "Download/Album/1" to "a"),
        )
        assertEquals(listOf("Download/Album -> Music/Rock/Album IDENTICAL"), plan.summary())
    }

    @Test
    fun subsetIsMergedIntoSuperset() {
        val plan = FolderDedupe.plan(
            files(
                "Music/Full/01.mp3" to "a", "Music/Full/02.mp3" to "bb", "Music/Full/03.mp3" to "ccc",
                "Music/Part/01.mp3" to "a", "Music/Part/02.mp3" to "bb",
                "Music/Other/x.mp3" to "unique",
            ),
        )
        assertEquals(listOf("Music/Part -> Music/Full SUBSET"), plan.summary())
        assertEquals(2, plan.single().songIds.size)
        assertEquals(3L, plan.single().bytes)
    }

    @Test
    fun differentContentOrNamesAreNotDuplicates() {
        val plan = FolderDedupe.plan(
            files(
                "Music/A/01.mp3" to "a", "Music/A/02.mp3" to "bb",
                "Music/B/01.mp3" to "a", "Music/B/02.mp3" to "XX", // same name, different bytes
                "Music/C/renamed.mp3" to "a",
            ),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun ignoringNamesMatchesRenamedFiles() {
        val plan = FolderDedupe.plan(
            files("Music/A/01.mp3" to "a", "Music/A/02.mp3" to "bb", "Music/C/renamed.mp3" to "a"),
            ignoreNames = true,
        )
        assertEquals(listOf("Music/C -> Music/A SUBSET"), plan.summary())
        val r = plan.single()
        assertEquals(mapOf(r.songIds.single() to 1L), r.replacements)
    }

    @Test
    fun chainsCollapseIntoTheBiggestFolder() {
        val plan = FolderDedupe.plan(
            files(
                "Music/Big/1" to "a", "Music/Big/2" to "bb", "Music/Big/3" to "ccc",
                "Music/Mid/1" to "a", "Music/Mid/2" to "bb",
                "Music/Small/1" to "a",
            ),
        )
        assertEquals(listOf("Music/Mid -> Music/Big SUBSET", "Music/Small -> Music/Big SUBSET"), plan.summary())
    }

    @Test
    fun nestedFoldersAreNeverComparedAndVolumeRootsStay() {
        val plan = FolderDedupe.plan(files("Music/x.mp3" to "a", "Music/sub/x.mp3" to "a"))
        // Music/sub's file is also in Music itself, but a folder can't be merged into its own parent.
        assertTrue(plan.isEmpty())
        assertEquals(listOf("$root/Music/sub", "$root/Music"), FolderDedupe.ancestors("$root/Music/sub/x.mp3"))
    }

    @Test
    fun replacementsPointAtTheKeptCopies() {
        val f = files("Music/A/1" to "a", "Music/A/2" to "bb", "Music/B/1" to "a", "Music/B/2" to "bb")
        val r = FolderDedupe.plan(f).single()
        assertEquals("$root/Music/B", r.folder)
        assertEquals(mapOf(f[2].songId to f[0].songId, f[3].songId to f[1].songId), r.replacements)
    }

    @Test
    fun hasherOnlyReadsFilesWithMatchingSizes() = runTest {
        val content = mapOf(1L to "same", 2L to "same", 3L to "diff", 4L to "unique-size")
        val opened = mutableListOf<Long>()
        val ids = ContentHasher.contentIds(
            content.map { (k, v) -> ContentHasher.Input(k, v.length.toLong()) },
            open = { opened += it.key; ByteArrayInputStream(content.getValue(it.key).toByteArray()) },
        )
        assertEquals(ids[1], ids[2])
        assertTrue(ids[1] != ids[3])
        assertEquals(setOf(1L, 2L, 3L), opened.toSet())
    }

    @Test
    fun unreadableFilesNeverMatch() = runTest {
        val ids = ContentHasher.contentIds(
            listOf(ContentHasher.Input(1, 4), ContentHasher.Input(2, 4)),
            open = { null },
        )
        assertTrue(ids[1] != ids[2])
    }

    private fun song(id: Long, rel: String, size: Long) =
        Song(id, "t", "a", 0, "al", 0, 0, 0, 0, 0, Uri.EMPTY, path = "$root/$rel", size = size)

    @Test
    fun removalIsRecheckedAgainstTheCurrentLibrary() {
        val removal = FolderRemoval(
            folder = "$root/Music/B", keptIn = "$root/Music/A", kind = DuplicateKind.IDENTICAL,
            songIds = listOf(3), bytes = 5, replacements = mapOf(3L to 1L),
        )
        val ok = listOf(song(1, "Music/A/x.mp3", 5), song(3, "Music/B/x.mp3", 5))
        assertTrue(DuplicateFolderFinder.stillValid(removal, ok))
        // The kept copy was deleted meanwhile.
        assertFalse(DuplicateFolderFinder.stillValid(removal, listOf(song(3, "Music/B/x.mp3", 5))))
        // A new song appeared in the folder that would be removed.
        assertFalse(DuplicateFolderFinder.stillValid(removal, ok + song(9, "Music/B/new.mp3", 7)))
    }
}
