package com.example.musicplayer.data.dedupe

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest

/**
 * Gives each file a content id: files with identical bytes share one. Only files whose size matches another
 * file's are read; a file with a unique size (or that can't be read) gets an id nothing else has.
 */
object ContentHasher {

    data class Input(val key: Long, val size: Long)

    suspend fun contentIds(
        files: List<Input>,
        open: (Input) -> InputStream?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<Long, String> {
        val ids = HashMap<Long, String>(files.size)
        val toHash = ArrayList<Input>()
        for (group in files.groupBy { it.size }.values) {
            if (group.size == 1) ids[group[0].key] = unique(group[0]) else toHash += group
        }
        val buf = ByteArray(256 * 1024)
        toHash.forEachIndexed { i, f ->
            currentCoroutineContext().ensureActive()
            onProgress(i, toHash.size)
            ids[f.key] = try {
                open(f)?.use { stream ->
                    val md = MessageDigest.getInstance("SHA-256")
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                    "${f.size}:" + md.digest().joinToString("") { "%02x".format(it) }
                } ?: unique(f)
            } catch (_: Exception) {
                unique(f)
            }
        }
        onProgress(toHash.size, toHash.size)
        return ids
    }

    private fun unique(f: Input) = "u:${f.key}"
}
