package com.example.musicplayer.data.dedupe

import android.content.Context
import com.example.musicplayer.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads song files through MediaStore to find duplicate folders, and re-checks a plan before it's applied. */
class DuplicateFolderFinder(private val context: Context) {

    suspend fun find(
        songs: List<Song>,
        ignoreNames: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<FolderRemoval> = withContext(Dispatchers.IO) {
        val withPath = songs.filter { '/' in it.path }
        val byId = withPath.associateBy { it.id }
        val ids = ContentHasher.contentIds(
            files = withPath.map { ContentHasher.Input(it.id, it.size) },
            open = { context.contentResolver.openInputStream(byId.getValue(it.key).uri) },
            onProgress = onProgress,
        )
        FolderDedupe.plan(withPath.map { DedupeFile(it.id, it.path, it.size, ids.getValue(it.id)) }, ignoreNames)
    }

    companion object {
        /**
         * Whether [removal] still holds against the current library: none of the folder's songs are new, and every
         * song it maps to in the kept folder is still there with the same size.
         */
        fun stillValid(removal: FolderRemoval, current: List<Song>): Boolean {
            val byId = current.associateBy { it.id }
            val nowInFolder = current.filter { FolderDedupe.isWithin(it.path.substringBeforeLast('/'), removal.folder) }
            if (nowInFolder.any { it.id !in removal.replacements }) return false
            return removal.replacements.all { (from, to) ->
                val a = byId[from]
                val b = byId[to]
                a == null || (b != null && a.size == b.size && FolderDedupe.isWithin(b.path, removal.keptIn))
            }
        }
    }
}
