package com.example.musicplayer.data

import com.example.musicplayer.data.model.Folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderPathsTest {

    @Test
    fun volumeRootHandlesInternalStorageAndSdCards() {
        assertEquals("/storage/emulated/0", FolderPaths.volumeRoot("/storage/emulated/0/Music/a.mp3"))
        assertEquals("/storage/1A2B-3C4D", FolderPaths.volumeRoot("/storage/1A2B-3C4D/Music"))
        assertNull(FolderPaths.volumeRoot("/sdcard/Music"))
    }

    @Test
    fun onlyStandardAudioFoldersAcceptMoves() {
        assertTrue(FolderPaths.canMoveInto("/storage/emulated/0/Music"))
        assertTrue(FolderPaths.canMoveInto("/storage/emulated/0/Download/anime/ost"))
        assertFalse(FolderPaths.canMoveInto("/storage/emulated/0"))
        assertFalse(FolderPaths.canMoveInto("/storage/emulated/0/DCIM/x"))
        assertEquals("Music/Rock", FolderPaths.relativeDir("/storage/emulated/0/Music/Rock"))
    }

    @Test
    fun destinationTreeIncludesAncestorsStandardFoldersAndCreatedOnes() {
        val tree = FolderPaths.destinationTree(
            listOf(Folder("/storage/emulated/0/Download/anime/ost", emptyList())),
            extra = listOf("/storage/emulated/0/Music/New"),
        )
        assertEquals(
            listOf("/storage/emulated/0/Download", "/storage/emulated/0/Music"),
            FolderPaths.children(tree, "/storage/emulated/0"),
        )
        assertEquals(listOf("/storage/emulated/0/Download/anime"), FolderPaths.children(tree, "/storage/emulated/0/Download"))
        assertEquals(listOf("/storage/emulated/0/Music/New"), FolderPaths.children(tree, "/storage/emulated/0/Music"))
    }

    @Test
    fun folderNamesRejectSeparatorsAndBlank() {
        assertTrue(FolderPaths.isValidFolderName("Chill mix"))
        assertFalse(FolderPaths.isValidFolderName("a/b"))
        assertFalse(FolderPaths.isValidFolderName("  "))
        assertFalse(FolderPaths.isValidFolderName(".."))
    }
}
