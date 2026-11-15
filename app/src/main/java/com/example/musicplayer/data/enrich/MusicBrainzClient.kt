package com.example.musicplayer.data.enrich

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class MbTrack(
    val disc: Int,
    val position: Int,
    val title: String,
    val artist: String,
    val lengthMs: Long?,
    val recordingId: String?,
    val discCount: Int = 1,
) {
    /** MediaStore-style track number: disc * 1000 + track for multi-disc releases. */
    val encodedTrackNumber: Int get() = if (discCount > 1) disc * 1000 + position else position
}

data class MbRelease(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val year: Int?,
    val genre: String?,
    /** Cover Art Archive front image when it has one; may be filled from iTunes otherwise. */
    val artworkUrl: String?,
    val tracks: List<MbTrack>,
)

data class MbReleaseCandidate(val id: String, val title: String, val artist: String, val trackCount: Int, val score: Int)

data class MbRecordingRelease(
    val id: String,
    val title: String,
    val year: Int?,
    val official: Boolean,
    val primaryType: String?,
    val secondaryTypes: List<String>,
)

data class MbRecording(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val lengthMs: Long?,
    val releases: List<MbRecordingRelease>,
    val score: Int,
)

/** The MusicBrainz web service calls enrichment needs. See https://musicbrainz.org/doc/MusicBrainz_API */
class MusicBrainzClient(
    private val http: HttpFetcher,
    private val baseUrl: String = "https://musicbrainz.org/ws/2",
) {
    suspend fun searchReleases(artist: String?, album: String, limit: Int = 10): List<MbReleaseCandidate> {
        val query = buildString {
            append("release:").append(quote(album))
            if (!artist.isNullOrBlank() && !TextCleaner.isPlaceholder(artist)) append(" AND artist:").append(quote(artist))
        }
        val json = getJson("$baseUrl/release?query=${enc(query)}&fmt=json&limit=$limit") ?: return emptyList()
        return json.optJSONArray("releases").objects().map { r ->
            MbReleaseCandidate(
                id = r.getString("id"),
                title = r.optString("title"),
                artist = artistCredit(r.optJSONArray("artist-credit")),
                trackCount = r.optInt("track-count"),
                score = r.optInt("score"),
            )
        }
    }

    suspend fun searchRecordings(artist: String?, title: String, limit: Int = 10): List<MbRecording> {
        val query = buildString {
            append("recording:").append(quote(title))
            if (!artist.isNullOrBlank() && !TextCleaner.isPlaceholder(artist)) append(" AND artist:").append(quote(artist))
        }
        val json = getJson("$baseUrl/recording?query=${enc(query)}&fmt=json&limit=$limit") ?: return emptyList()
        return json.optJSONArray("recordings").objects().map { r ->
            val credit = r.optJSONArray("artist-credit")
            MbRecording(
                id = r.getString("id"),
                title = r.optString("title"),
                artist = artistCredit(credit),
                artistId = firstArtistId(credit),
                lengthMs = r.optLong("length").takeIf { it > 0 },
                releases = r.optJSONArray("releases").objects().map { rel ->
                    val group = rel.optJSONObject("release-group")
                    MbRecordingRelease(
                        id = rel.getString("id"),
                        title = rel.optString("title"),
                        year = year(rel.optString("date")),
                        official = rel.optString("status").equals("Official", ignoreCase = true),
                        primaryType = group?.optString("primary-type")?.ifEmpty { null },
                        secondaryTypes = group?.optJSONArray("secondary-types").strings(),
                    )
                },
                score = r.optInt("score"),
            )
        }
    }

    suspend fun release(id: String): MbRelease? {
        val json = getJson("$baseUrl/release/$id?inc=recordings+artist-credits+genres+release-groups&fmt=json")
            ?: return null
        val media = json.optJSONArray("media").objects()
        val discCount = media.size
        val tracks = media.flatMap { m ->
            val disc = m.optInt("position", 1)
            m.optJSONArray("tracks").objects().map { t ->
                val rec = t.optJSONObject("recording")
                MbTrack(
                    disc = disc,
                    position = t.optInt("position"),
                    title = t.optString("title"),
                    artist = artistCredit(t.optJSONArray("artist-credit")).ifEmpty { artistCredit(json.optJSONArray("artist-credit")) },
                    lengthMs = (t.optLong("length").takeIf { it > 0 } ?: rec?.optLong("length"))?.takeIf { it > 0 },
                    recordingId = rec?.optString("id")?.ifEmpty { null },
                    discCount = discCount,
                )
            }
        }
        val credit = json.optJSONArray("artist-credit")
        return MbRelease(
            id = json.getString("id"),
            title = json.optString("title"),
            artist = artistCredit(credit),
            artistId = firstArtistId(credit),
            year = year(json.optString("date")) ?: year(json.optJSONObject("release-group")?.optString("first-release-date")),
            genre = topGenre(json) ?: json.optJSONObject("release-group")?.let(::topGenre),
            artworkUrl = if (json.optJSONObject("cover-art-archive")?.optBoolean("front") == true) {
                "https://coverartarchive.org/release/${json.getString("id")}/front-500"
            } else null,
            tracks = tracks,
        )
    }

    private suspend fun getJson(url: String): JSONObject? {
        val r = http.get(url)
        if (!r.ok || r.body.isBlank()) return null
        return try { JSONObject(r.body) } catch (_: Exception) { null }
    }

    companion object {
        fun artistCredit(credit: JSONArray?): String =
            credit.objects().joinToString("") { it.optString("name") + it.optString("joinphrase") }.trim()

        private fun firstArtistId(credit: JSONArray?): String? =
            credit.objects().firstOrNull()?.optJSONObject("artist")?.optString("id")?.ifEmpty { null }

        /**
         * The most-voted genre, but only with a clear winner: votes are sparse and ties are common (Abbey Road's
         * top genres are all tied at 2 votes), and a wrong genre is worse than none, which lets iTunes fill it.
         * Free-form tags ("british", "1969") aren't genres, so they're ignored.
         */
        private fun topGenre(obj: JSONObject): String? {
            val ranked = obj.optJSONArray("genres").objects()
                .filter { it.optString("name").isNotEmpty() }
                .sortedByDescending { it.optInt("count") }
            val top = ranked.firstOrNull() ?: return null
            if (ranked.size > 1 && ranked[1].optInt("count") >= top.optInt("count")) return null
            return top.optString("name").split(' ', '-').joinToString(" ") { w -> w.replaceFirstChar { it.titlecaseChar() } }
        }

        fun year(date: String?): Int? = date?.take(4)?.toIntOrNull()?.takeIf { it in 1800..2200 }

        /** Lucene phrase query: quote, escaping only what matters inside quotes. */
        fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifEmpty { null } }
