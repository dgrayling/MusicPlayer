package com.example.musicplayer.data

import com.example.musicplayer.data.model.Folder

/** Path rules for moving audio files around shared storage. */
object FolderPaths {

    /** Top-level folders Android lets an app put audio files into (scoped storage). */
    val AUDIO_ROOTS = listOf(
        "Music", "Download", "Podcasts", "Audiobooks", "Recordings", "Ringtones", "Alarms", "Notifications",
    )

    /** Storage volume a path lives on: /storage/emulated/0 for internal storage, /storage/XXXX-XXXX for SD cards. */
    fun volumeRoot(path: String): String? {
        val parts = path.split('/')
        // ["", "storage", "emulated", "0", ...] or ["", "storage", "ABCD-1234", ...]
        if (parts.size < 3 || parts[0] != "" || parts[1] != "storage") return null
        return if (parts[2] == "emulated") {
            if (parts.size < 4) null else parts.take(4).joinToString("/")
        } else {
            parts.take(3).joinToString("/")
        }
    }

    /** Directory relative to its volume, e.g. "Music/Rock", or null if it isn't on a storage volume. */
    fun relativeDir(dir: String): String? {
        val root = volumeRoot(dir) ?: return null
        return dir.removePrefix(root).trim('/')
    }

    /** Whether audio files may be moved into [dir]. */
    fun canMoveInto(dir: String): Boolean {
        val rel = relativeDir(dir) ?: return false
        return rel.isNotEmpty() && rel.substringBefore('/') in AUDIO_ROOTS
    }

    fun parent(dir: String): String = dir.substringBeforeLast('/', "")

    /**
     * Every directory worth offering as a move destination: each music folder and its ancestors up to the
     * volume root, plus each volume's Music and Download folders and any [extra] folders the user created.
     */
    fun destinationTree(folders: List<Folder>, extra: Collection<String> = emptyList()): Set<String> {
        val dirs = HashSet<String>()
        for (path in folders.map { it.path } + extra) {
            val root = volumeRoot(path) ?: continue
            var dir = path
            while (dir.length >= root.length && dirs.add(dir)) dir = parent(dir)
            dirs += "$root/Music"
            dirs += "$root/Download"
        }
        return dirs
    }

    fun children(tree: Set<String>, dir: String): List<String> =
        tree.filter { parent(it) == dir }.sortedBy { it.substringAfterLast('/').lowercase() }

    /** A new folder name is a single path segment without characters Android rejects. */
    fun isValidFolderName(name: String): Boolean {
        val n = name.trim()
        return n.isNotEmpty() && n != "." && n != ".." && n.none { it in "/\\:*?\"<>|" }
    }
}
