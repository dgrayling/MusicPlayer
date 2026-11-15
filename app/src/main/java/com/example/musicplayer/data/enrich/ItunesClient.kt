package com.example.musicplayer.data.enrich

import org.json.JSONObject
import java.net.URLEncoder

data class ItunesAlbum(val title: String, val artist: String, val artworkUrl: String?, val genre: String?, val year: Int?)

/**
 * Fallback for cover art and genre when MusicBrainz / Cover Art Archive have none. No API key needed.
 * https://performance-partners.apple.com/search-api
 */
class ItunesClient(private val http: HttpFetcher, private val baseUrl: String = "https://itunes.apple.com") {

    /** Best album match for [artist] + [album], or null if nothing is close enough. */
    suspend fun findAlbum(artist: String, album: String): ItunesAlbum? {
        val term = URLEncoder.encode("$artist $album", "UTF-8")
        val r = http.get("$baseUrl/search?term=$term&media=music&entity=album&limit=10")
        if (!r.ok || r.body.isBlank()) return null
        val results = try { JSONObject(r.body).optJSONArray("results").objects() } catch (_: Exception) { return null }
        return results
            .map {
                ItunesAlbum(
                    title = it.optString("collectionName"),
                    artist = it.optString("artistName"),
                    // 100x100 is what the API returns; the CDN serves any size by rewriting the URL.
                    artworkUrl = it.optString("artworkUrl100").ifEmpty { null }?.replace("100x100bb", "600x600bb"),
                    genre = it.optString("primaryGenreName").ifEmpty { null },
                    year = MusicBrainzClient.year(it.optString("releaseDate")),
                )
            }
            .map { it to (Matcher.similarity(it.title, album) + Matcher.similarity(it.artist, artist)) / 2 }
            .filter { it.second >= Matcher.APPLY }
            .maxByOrNull { it.second }
            ?.first
    }
}
