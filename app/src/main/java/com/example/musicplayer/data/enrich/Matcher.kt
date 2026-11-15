package com.example.musicplayer.data.enrich

import com.example.musicplayer.data.model.Song
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Scores how well local songs match MusicBrainz data. All scores are 0..1. */
object Matcher {
    /** At or above: apply automatically. */
    const val APPLY = 0.85
    /** At or above (but below [APPLY]): offer as a suggestion. Below: treat as no match. */
    const val SUGGEST = 0.6

    /** Similarity of two strings after [TextCleaner.key] normalization (Jaro-Winkler, or token overlap if higher). */
    fun similarity(a: String?, b: String?): Double {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return 0.0
        val ka = TextCleaner.key(a)
        val kb = TextCleaner.key(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0.0
        if (ka == kb) return 1.0
        return max(jaroWinkler(ka, kb), tokenOverlap(ka, kb))
    }

    /** 1 within 3 s, falling linearly to 0 at 15 s apart. Null when either length is unknown. */
    fun durationScore(aMs: Long?, bMs: Long?): Double? {
        if (aMs == null || bMs == null || aMs <= 0 || bMs <= 0) return null
        val d = abs(aMs - bMs) / 1000.0
        return when {
            d <= 3 -> 1.0
            d >= 15 -> 0.0
            else -> 1 - (d - 3) / 12
        }
    }

    /** How well one song matches one track: title similarity, backed by duration when known. */
    fun trackScore(song: Song, title: String, lengthMs: Long?): Double {
        val t = similarity(song.title, title)
        val d = durationScore(song.durationMs, lengthMs) ?: return t
        return 0.6 * t + 0.4 * d
    }

    data class Assignment(val songId: Long, val track: MbTrack, val score: Double)
    data class AlbumMatch(val score: Double, val assignments: List<Assignment>)

    /**
     * Matches a folder/album group against a release's tracklist. Songs are assigned greedily to their
     * best track (by track number when it agrees, else by title + duration), each track used once.
     */
    fun matchAlbum(songs: List<Song>, artist: String, album: String, release: MbRelease): AlbumMatch {
        if (songs.isEmpty() || release.tracks.isEmpty()) return AlbumMatch(0.0, emptyList())
        val options = ArrayList<Assignment>()
        for (song in songs) {
            for (track in release.tracks) {
                var s = trackScore(song, track.title, track.lengthMs)
                // An agreeing track number is good evidence when titles are garbage (e.g. "Track 03").
                if (song.displayTrackNumber > 0 && song.displayTrackNumber == track.position) {
                    val disc = song.trackNumber / 1000
                    if (disc == 0 || disc == track.disc) s = min(1.0, s + 0.15)
                }
                options += Assignment(song.id, track, s)
            }
        }
        val usedSongs = HashSet<Long>()
        val usedTracks = HashSet<MbTrack>()
        val chosen = ArrayList<Assignment>()
        for (a in options.sortedByDescending { it.score }) {
            if (a.score < 0.5) break
            if (a.songId in usedSongs || a.track in usedTracks) continue
            usedSongs += a.songId
            usedTracks += a.track
            chosen += a
        }
        // Unassigned songs count as zero, so a release that explains few of the songs scores low.
        val avgTrack = chosen.sumOf { it.score } / songs.size
        val n = songs.size
        val total = release.tracks.size
        val countScore = if (n <= total) 0.6 + 0.4 * n / total else max(0.0, 0.6 - 0.2 * (n - total))
        val artistScore = if (TextCleaner.isPlaceholder(artist)) 0.5 else similarity(artist, release.artist)
        val albumScore = similarity(album, release.title)
        val score = 0.15 * albumScore + 0.15 * artistScore + 0.5 * avgTrack + 0.2 * countScore
        return AlbumMatch(score, chosen)
    }

    /** Confidence for one song inside an album match. */
    fun songConfidence(albumScore: Double, trackScore: Double): Double = albumScore * (0.7 + 0.3 * trackScore)

    /** How well a single song matches a recording search result. */
    fun matchRecording(song: Song, rec: MbRecording): Double {
        val t = similarity(song.title, rec.title)
        val a = if (TextCleaner.isPlaceholder(song.artist)) 0.5 else similarity(song.artist, rec.artist)
        val d = durationScore(song.durationMs, rec.lengthMs)
        val base = if (d == null) (0.6 * t + 0.4 * a) * 0.95 else 0.5 * t + 0.3 * a + 0.2 * d
        // Same artist and length but a different title is a different song, not a weak match.
        return if (t < 0.75) base * t / 0.75 else base
    }

    // --- string metrics ---

    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        val window = max(0, max(a.length, b.length) / 2 - 1)
        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val lo = max(0, i - window)
            val hi = min(b.length - 1, i + window)
            for (j in lo..hi) {
                if (!bMatched[j] && a[i] == b[j]) {
                    aMatched[i] = true; bMatched[j] = true; matches++; break
                }
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatched[i]) continue
            while (!bMatched[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3
        val prefix = a.zip(b).take(4).takeWhile { (x, y) -> x == y }.size
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    /** Fraction of the shorter string's words found in the longer one ("abbey road" vs "abbey road remastered"). */
    private fun tokenOverlap(a: String, b: String): Double {
        val ta = a.split(' ').toSet()
        val tb = b.split(' ').toSet()
        val shorter = if (ta.size <= tb.size) ta else tb
        val longer = if (shorter === ta) tb else ta
        if (shorter.isEmpty()) return 0.0
        // Penalize subset matches a little, more the more extra words there are.
        val overlap = shorter.count { it in longer }.toDouble() / shorter.size
        return overlap * (0.9 - 0.05 * min(4, longer.size - shorter.size).coerceAtLeast(0))
    }
}
