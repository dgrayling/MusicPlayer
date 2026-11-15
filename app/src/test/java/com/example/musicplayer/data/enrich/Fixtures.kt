package com.example.musicplayer.data.enrich

import android.net.Uri
import com.example.musicplayer.data.model.Song
import org.json.JSONArray
import org.json.JSONObject

/** Song as MediaStore would report it. Needs Robolectric (for Uri). */
fun song(
    id: Long,
    title: String,
    artist: String = "Artist",
    album: String = "Album",
    path: String = "/storage/emulated/0/Music/$artist/$album/$title.mp3",
    durationMs: Long = 200_000,
    track: Int = 0,
    year: Int = 0,
    artistId: Long = 1,
    albumId: Long = 10,
    size: Long = 1_000 + id,
) = Song(
    id = id, title = title, artist = artist, artistId = artistId, album = album, albumId = albumId,
    durationMs = durationMs, trackNumber = track, year = year, dateAdded = 0, uri = Uri.parse("content://media/$id"),
    path = path, size = size, dateModified = 1_700_000_000,
)

data class Trk(val title: String, val lengthMs: Long, val recordingId: String = "rec-" + title.lowercase().replace(' ', '-'))

private fun credit(name: String, id: String = "artist-" + name.lowercase().replace(' ', '-')) =
    JSONArray().put(JSONObject().put("name", name).put("joinphrase", "").put("artist", JSONObject().put("id", id).put("name", name)))

fun mbReleaseJson(id: String, title: String, artist: String, date: String, genre: String?, tracks: List<Trk>, frontArt: Boolean = true): String =
    JSONObject()
        .put("id", id).put("title", title).put("date", date)
        .put("artist-credit", credit(artist))
        .put("cover-art-archive", JSONObject().put("front", frontArt))
        .put("genres", JSONArray().apply { if (genre != null) put(JSONObject().put("name", genre).put("count", 5)) })
        .put(
            "media",
            JSONArray().put(
                JSONObject().put("position", 1).put(
                    "tracks",
                    JSONArray().apply {
                        tracks.forEachIndexed { i, t ->
                            put(
                                JSONObject().put("position", i + 1).put("title", t.title).put("length", t.lengthMs)
                                    .put("recording", JSONObject().put("id", t.recordingId).put("length", t.lengthMs)),
                            )
                        }
                    },
                ),
            ),
        )
        .toString()

fun mbReleaseSearchJson(vararg candidates: Triple<String, String, Int>, artist: String): String =
    JSONObject().put(
        "releases",
        JSONArray().apply {
            candidates.forEach { (id, title, count) ->
                put(JSONObject().put("id", id).put("title", title).put("score", 100).put("track-count", count).put("artist-credit", credit(artist)))
            }
        },
    ).toString()

fun mbRecordingSearchJson(id: String, title: String, artist: String, lengthMs: Long, releaseId: String, releaseTitle: String): String =
    JSONObject().put(
        "recordings",
        JSONArray().put(
            JSONObject().put("id", id).put("title", title).put("score", 100).put("length", lengthMs)
                .put("artist-credit", credit(artist))
                .put(
                    "releases",
                    JSONArray().put(
                        JSONObject().put("id", releaseId).put("title", releaseTitle).put("status", "Official").put("date", "1971")
                            .put("release-group", JSONObject().put("primary-type", "Album")),
                    ),
                ),
        ),
    ).toString()

/** Routes requests by URL substring and records every call. */
class FakeFetcher(private val routes: List<Pair<String, HttpResult>>) : HttpFetcher {
    val calls = mutableListOf<String>()
    override suspend fun get(url: String): HttpResult {
        calls += url
        return routes.firstOrNull { (k, _) -> k in url }?.second ?: HttpResult(404, "")
    }
}
