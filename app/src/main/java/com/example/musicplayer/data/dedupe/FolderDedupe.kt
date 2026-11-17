package com.example.musicplayer.data.dedupe

import com.example.musicplayer.data.FolderPaths

/** One music file as the duplicate finder sees it. [contentId] is equal for files with identical bytes. */
data class DedupeFile(val songId: Long, val path: String, val size: Long, val contentId: String)

enum class DuplicateKind {
    /** The folder holds exactly the same songs as the kept one. */
    IDENTICAL,

    /** Every song in the folder is also in the kept one, which has more. */
    SUBSET,
}

/**
 * A folder whose songs all exist in [keptIn], so removing it loses nothing.
 * [replacements] maps each removed song to its copy in the kept folder (used to repoint playlists).
 */
data class FolderRemoval(
    val folder: String,
    val keptIn: String,
    val kind: DuplicateKind,
    val songIds: List<Long>,
    val bytes: Long,
    val replacements: Map<Long, Long>,
)

/**
 * Finds folders that are identical to, or contained in, another folder. Folders are compared recursively
 * (subfolders included) by file content plus each file's path inside the folder, or by content alone when
 * [ignoreNames] is set. Folders nested in each other are never compared, and neither volume roots nor the
 * standard folders directly on them (Music, Download, …) are ever removed.
 *
 * Every removal is backed by a folder that is kept, or that is itself removed in favour of one that is kept,
 * so the plan as a whole never loses a song.
 */
object FolderDedupe {

    private data class Entry(val rel: String?, val contentId: String)

    fun plan(files: List<DedupeFile>, ignoreNames: Boolean = false): List<FolderRemoval> {
        val entries = HashMap<String, HashSet<Entry>>()
        val filesUnder = HashMap<String, MutableList<DedupeFile>>()
        val byContent = HashMap<String, MutableList<DedupeFile>>()
        for (f in files) {
            byContent.getOrPut(f.contentId) { ArrayList() } += f
            for (dir in ancestors(f.path)) {
                val rel = if (ignoreNames) null else f.path.substring(dir.length + 1)
                entries.getOrPut(dir) { HashSet() } += Entry(rel, f.contentId)
                filesUnder.getOrPut(dir) { ArrayList() } += f
            }
        }

        // Biggest folders first; among equals, the one kept is under Music/ if possible, then the shallowest,
        // then the first alphabetically.
        val order = entries.keys.sortedWith(
            compareBy<String>(
                { -entries.getValue(it).size },
                { FolderPaths.relativeDir(it)?.substringBefore('/') != "Music" },
                { it.count { c -> c == '/' } },
                { it },
            ),
        )
        val rank = order.withIndex().associate { (i, d) -> d to i }
        val removed = ArrayList<String>()
        val result = ArrayList<FolderRemoval>()
        fun insideRemoved(d: String) = removed.any { isWithin(d, it) }

        for (b in order) {
            if (isTopLevel(b) || insideRemoved(b)) continue
            val eb = entries.getValue(b)
            // Look for supersets only among folders holding b's rarest file.
            val probe = eb.minBy { byContent.getValue(it.contentId).size }
            val holders = byContent.getValue(probe.contentId)
            if (holders.size < 2) continue
            val candidates = HashSet<String>()
            for (h in holders) {
                if (ignoreNames) {
                    candidates += ancestors(h.path)
                } else if (h.path.endsWith("/" + probe.rel)) {
                    candidates += h.path.dropLast(probe.rel!!.length + 1)
                }
            }
            val best = candidates
                .filter { a ->
                    val ea = entries[a]
                    ea != null && rank.getValue(a) < rank.getValue(b) &&
                        !isWithin(a, b) && !isWithin(b, a) && !insideRemoved(a) && ea.containsAll(eb)
                }
                .minByOrNull { rank.getValue(it) }
                ?: continue

            val removedFiles = filesUnder.getValue(b)
            result += FolderRemoval(
                folder = b,
                keptIn = best,
                kind = if (eb.size == entries.getValue(best).size) DuplicateKind.IDENTICAL else DuplicateKind.SUBSET,
                songIds = removedFiles.map { it.songId },
                bytes = removedFiles.sumOf { it.size },
                replacements = replacements(b, best, removedFiles, filesUnder.getValue(best), ignoreNames),
            )
            removed += b
        }
        return result
    }

    private fun replacements(
        from: String,
        to: String,
        fromFiles: List<DedupeFile>,
        toFiles: List<DedupeFile>,
        ignoreNames: Boolean,
    ): Map<Long, Long> {
        val target = if (ignoreNames) {
            toFiles.associateBy { it.contentId }
        } else {
            toFiles.associateBy { it.path.substring(to.length + 1) + "\u0000" + it.contentId }
        }
        return fromFiles.mapNotNull { f ->
            val key = if (ignoreNames) f.contentId else f.path.substring(from.length + 1) + "\u0000" + f.contentId
            target[key]?.let { f.songId to it.songId }
        }.toMap()
    }

    /** Folders containing [path], deepest first, stopping below the storage volume root. */
    internal fun ancestors(path: String): List<String> {
        val stop = FolderPaths.volumeRoot(path) ?: ""
        val out = ArrayList<String>()
        var dir = path.substringBeforeLast('/', "")
        while (dir.length > stop.length && dir.startsWith(stop)) {
            out += dir
            dir = dir.substringBeforeLast('/', "")
        }
        return out
    }

    /** Standard folders such as Music or Download directly on a volume are kept even when their songs are elsewhere. */
    private fun isTopLevel(dir: String): Boolean = FolderPaths.relativeDir(dir)?.contains('/') == false

    fun isWithin(child: String, parent: String): Boolean = child == parent || child.startsWith("$parent/")
}
