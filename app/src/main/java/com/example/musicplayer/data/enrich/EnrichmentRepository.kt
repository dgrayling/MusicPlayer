package com.example.musicplayer.data.enrich

import com.example.musicplayer.data.db.AlbumResolutionEntity
import com.example.musicplayer.data.db.ArtistAliasEntity
import com.example.musicplayer.data.db.EnrichmentDao
import com.example.musicplayer.data.db.OnlineStatus
import com.example.musicplayer.data.db.OverrideSource
import com.example.musicplayer.data.db.RecordingCacheEntity
import com.example.musicplayer.data.db.ReleaseCacheEntity
import com.example.musicplayer.data.db.SongOverrideEntity
import com.example.musicplayer.data.db.SongSuggestionEntity
import com.example.musicplayer.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/** Progress of the current/last online run, for the Settings screen. */
data class EnrichmentRunStats(
    val running: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
    /** Songs resolved from the cache tiers (no new network request needed). */
    val fromCache: Int = 0,
    val networkLookups: Int = 0,
    val lastError: String? = null,
)

data class OnlinePassResult(val processed: Int, val remaining: Int)

/**
 * Orchestrates metadata enrichment. The core idea is that each fact is cached at the level where it
 * applies, so resolving one song fixes many:
 *  - `artist_alias`: raw artist spelling → canonical name (applied to every song by the local pass);
 *  - `album_resolution`: folder or artist+album → release (one search per album, not per track);
 *  - `release_cache`: release → tracklist/year/genre/art (shared by every track and duplicate folder);
 *  - `recording_cache`: artist+title → recording, for loose singles;
 *  - `http_cache` (in [CachedHttp]): raw responses, including "not found".
 */
class EnrichmentRepository(
    private val dao: EnrichmentDao,
    private val mb: MusicBrainzClient,
    private val itunes: ItunesClient?,
    private val networkCalls: () -> Int = { 0 },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _stats = MutableStateFlow(EnrichmentRunStats())
    val stats: StateFlow<EnrichmentRunStats> = _stats.asStateFlow()

    val overrides: Flow<Map<Long, SongOverrideEntity>> = dao.observeOverrides().map { rows -> rows.associateBy { it.songId } }
    val suggestions: Flow<List<SongSuggestionEntity>> = dao.observeSuggestions()
    val statusCounts: Flow<Map<String, Int>> = dao.observeStatusCounts().map { c -> c.associate { it.onlineStatus to it.count } }

    // ------------------------------------------------------------------------------------------------
    // Local pass (no network): junk clean-up, filename/folder inference, library-wide spellings, aliases.
    // ------------------------------------------------------------------------------------------------

    /** Creates/updates the overlay row of every song from local information. Returns how many rows changed. */
    suspend fun runLocalPass(raw: List<Song>): Int = withContext(Dispatchers.Default) {
        if (raw.isEmpty()) return@withContext 0 // e.g. no permission yet: don't wipe anything
        val t = now()
        val existing = dao.allOverrides().associateBy { it.songId }

        // Learn one spelling per artist across the library, without overriding online or user aliases.
        val aliases = dao.allAliases().associateBy { it.rawKey }.toMutableMap()
        val t0 = System.nanoTime()
        val folderHints = LocalCleanup.folderHints(raw)
        val tPlaylists = System.nanoTime()
        val hints = { s: Song -> LocalCleanup.hintsFor(s, folderHints) }
        val learned = LocalCleanup.preferredSpellings(raw.mapNotNull { LocalCleanup.cleanedArtist(it, hints(it)) })
            .filter { (k, name) -> aliases[k].let { it == null || (it.source == OverrideSource.LOCAL && it.name != name) } }
            .map { (k, name) -> ArtistAliasEntity(k, name, null, OverrideSource.LOCAL, 0.7, t) }
        if (learned.isNotEmpty()) {
            dao.upsertAliases(learned)
            learned.forEach { aliases[it.rawKey] = it }
        }
        val aliasName = { key: String -> aliases[key]?.name }
        val albumSpellings = LocalCleanup.preferredSpellings(raw.mapNotNull { LocalCleanup.cleanedAlbum(it, hints(it)) })
        val albumSpelling = { k: String -> albumSpellings[k.substringAfter('|')] }

        val tSpellings = System.nanoTime()
        val changed = ArrayList<SongOverrideEntity>()
        for (s in raw) {
            val old = existing[s.id]
            if (old?.locked == true) continue
            val sameFile = old?.fingerprint == s.fingerprint
            // Online values are authoritative for this exact file; local guesses don't replace them.
            if (sameFile && old!!.source != OverrideSource.LOCAL) continue
            val fix = LocalCleanup.fix(s, aliasName, albumSpelling, hints(s))
            // New local information (better clean-up, a learned alias) is worth another online try.
            val valuesChanged = old == null || fix.title != old.title || fix.artist != old.artist || fix.album != old.album
            val retry = sameFile && valuesChanged &&
                (old!!.onlineStatus == OnlineStatus.UNMATCHED || old.onlineStatus == OnlineStatus.ERROR)
            val row = SongOverrideEntity(
                songId = s.id,
                fingerprint = s.fingerprint,
                title = fix.title,
                artist = fix.artist,
                album = fix.album,
                year = fix.year,
                trackNumber = fix.trackNumber,
                source = OverrideSource.LOCAL,
                confidence = fix.confidence,
                onlineStatus = if (sameFile && !retry) old!!.onlineStatus else OnlineStatus.PENDING,
                attempts = if (sameFile && !retry) old!!.attempts else 0,
                lastAttemptAt = if (sameFile && !retry) old!!.lastAttemptAt else 0,
                updatedAt = old?.updatedAt ?: t,
            )
            if (row != old) changed += row.copy(updatedAt = t)
        }
        val tFix = System.nanoTime()
        changed.chunked(500).forEach { dao.upsertOverrides(it) }
        android.util.Log.d(
            "Enrichment",
            "local pass ms: playlists=${(tPlaylists - t0) / 1_000_000} spellings=${(tSpellings - tPlaylists) / 1_000_000} " +
                "fix=${(tFix - tSpellings) / 1_000_000} write=${(System.nanoTime() - tFix) / 1_000_000}",
        )

        // Songs that left the library (but keep user edits, in case the file comes back).
        val present = raw.mapTo(HashSet()) { it.id }
        val gone = existing.values.filter { it.songId !in present && !it.locked }.map { it.songId }
        gone.chunked(500).forEach { dao.deleteOverrides(it); dao.deleteSuggestions(it) }
        changed.size
    }

    // ------------------------------------------------------------------------------------------------
    // Online pass: album-first resolution against MusicBrainz, then per-recording for the rest.
    // ------------------------------------------------------------------------------------------------

    /** Is any song waiting for (or due another) online lookup? */
    suspend fun hasDueWork(raw: List<Song>): Boolean {
        val rows = dao.allOverrides().associateBy { it.songId }
        val t = now()
        return raw.any { s -> rows[s.id]?.let { isDue(it, s, t) } == true }
    }

    /**
     * Looks up songs that are due, until [deadline] or [isStopped]. Throws [TransientHttpException] when the
     * network fails, after recording the failure so those songs back off.
     */
    suspend fun runOnlinePass(
        raw: List<Song>,
        deadline: Long = Long.MAX_VALUE,
        isStopped: () -> Boolean = { false },
    ): OnlinePassResult {
        val rows = dao.allOverrides().associateBy { it.songId }.toMutableMap()
        val t = now()
        val due = raw.filter { s -> rows[s.id]?.let { isDue(it, s, t) } == true }
            .map { MetadataOverlay.apply(it, rows[it.id]) }
        val startCalls = networkCalls()
        _stats.value = EnrichmentRunStats(running = true, total = due.size)
        var processed = 0
        try {
            val (groups, singles) = group(due)
            val ctx = PassContext(rows)
            for (g in groups) {
                if (isStopped() || now() > deadline) break
                processed += processGroup(g, ctx)
                _stats.update { it.copy(processed = processed, networkLookups = networkCalls() - startCalls) }
            }
            for (s in singles) {
                if (isStopped() || now() > deadline) break
                processSingle(s, ctx)
                processed++
                _stats.update { it.copy(processed = processed, networkLookups = networkCalls() - startCalls) }
            }
            _stats.update { it.copy(running = false, networkLookups = networkCalls() - startCalls) }
        } catch (e: Exception) {
            _stats.update { it.copy(running = false, lastError = e.message, networkLookups = networkCalls() - startCalls) }
            throw e
        }
        return OnlinePassResult(processed, due.size - processed)
    }

    private fun isDue(r: SongOverrideEntity, s: Song, t: Long): Boolean {
        if (r.locked || r.fingerprint != s.fingerprint) return false
        return when (r.onlineStatus) {
            OnlineStatus.PENDING -> true
            OnlineStatus.ERROR -> t >= r.lastAttemptAt + min(TimeUnit.HOURS.toMillis(1L shl min(r.attempts, 8)), RETRY_UNMATCHED_MS)
            OnlineStatus.UNMATCHED -> t >= r.lastAttemptAt + RETRY_UNMATCHED_MS
            else -> false
        }
    }

    private class PassContext(val rows: MutableMap<Long, SongOverrideEntity>)

    internal data class AlbumGroup(val dirKey: String, val artistKey: String, val artist: String, val album: String, val songs: List<Song>)

    /** Songs with a real album name are grouped by folder + album; everything else is looked up alone. */
    internal fun group(songs: List<Song>): Pair<List<AlbumGroup>, List<Song>> {
        val singles = ArrayList<Song>()
        val groups = ArrayList<AlbumGroup>()
        songs.groupBy { s ->
            if (TextCleaner.isPlaceholder(s.album) || s.album == LocalCleanup.UNKNOWN_ALBUM) null
            else s.path.substringBeforeLast('/', "") + "|" + TextCleaner.key(s.album)
        }.forEach { (key, members) ->
            if (key == null) { singles += members; return@forEach }
            // Majority artist (album artist when tagged): compilations still resolve as one release.
            val artist = members.map { it.albumArtist.ifBlank { it.artist } }
                .groupingBy { it }.eachCount().maxBy { it.value }.key
            groups += AlbumGroup("dir:$key", TextCleaner.key(artist), artist, members.first().album, members)
        }
        return groups to singles
    }

    private suspend fun processGroup(g: AlbumGroup, ctx: PassContext): Int {
        val aaKey = "aa:${g.artistKey}|${TextCleaner.key(g.album)}"
        try {
            val cached = (dao.albumResolution(g.dirKey) ?: dao.albumResolution(aaKey))
                ?.takeIf { it.source == OverrideSource.USER || now() - it.updatedAt < RETRY_UNMATCHED_MS }
            var fromCache = false
            val best: Pair<MbRelease, Matcher.AlbumMatch>? = if (cached != null) {
                fromCache = true
                cached.mbReleaseId?.let { loadRelease(it) }?.let { it to Matcher.matchAlbum(g.songs, g.artist, g.album, it) }
            } else {
                val candidates = mb.searchReleases(g.artist, g.album)
                    .filter { it.score >= 60 }
                    .take(5)
                    // Prefer editions that have room for every song we hold, closest in size first.
                    .sortedWith(compareBy({ it.trackCount < g.songs.size }, { abs(it.trackCount - g.songs.size) }, { -it.score }))
                    .take(3)
                candidates.mapNotNull { c -> loadRelease(c.id)?.let { it to Matcher.matchAlbum(g.songs, g.artist, g.album, it) } }
                    .maxByOrNull { it.second.score }
                    .also { found ->
                        val id = found?.takeIf { it.second.score >= Matcher.SUGGEST }?.first?.id
                        val conf = found?.second?.score ?: 0.0
                        dao.upsertAlbumResolutions(
                            listOf(g.dirKey, aaKey).map { AlbumResolutionEntity(it, id, conf, OverrideSource.ONLINE, now()) },
                        )
                    }
            }

            val (release, match) = best?.takeIf { it.second.score >= Matcher.SUGGEST } ?: run {
                g.songs.forEach { processSingle(it, ctx) }
                return g.songs.size
            }
            val bySong = match.assignments.associateBy { it.songId }
            val applied = ArrayList<SongOverrideEntity>()
            val suggested = ArrayList<SongSuggestionEntity>()
            val leftovers = ArrayList<Song>()
            for (s in g.songs) {
                val a = bySong[s.id] ?: run { leftovers += s; null } ?: continue
                val conf = Matcher.songConfidence(match.score, a.score)
                when {
                    conf >= Matcher.APPLY -> applied += matchedRow(ctx, s, release, a.track, null, conf)
                    conf >= Matcher.SUGGEST -> suggested += suggestion(s, release, a.track, null, conf)
                    else -> leftovers += s
                }
            }
            commit(ctx, applied, suggested)
            if (fromCache) _stats.update { it.copy(fromCache = it.fromCache + applied.size + suggested.size) }
            if (match.score >= Matcher.APPLY) learnAliases(g.songs.filter { it.id in bySong }, release, bySong)
            leftovers.forEach { processSingle(it, ctx) }
            return g.songs.size
        } catch (e: TransientHttpException) {
            markError(ctx, g.songs)
            throw e
        }
    }

    private suspend fun processSingle(s: Song, ctx: PassContext) {
        if (TextCleaner.isPlaceholder(s.title)) return markStatus(ctx, s, OnlineStatus.UNMATCHED)
        val artist = s.artist.takeUnless(TextCleaner::isPlaceholder)
        val queryKey = "rec:${artist?.let(TextCleaner::key).orEmpty()}|${TextCleaner.key(s.title)}"
        try {
            val cached = dao.recording(queryKey)?.takeIf { now() - it.fetchedAt < RETRY_UNMATCHED_MS }
            if (cached != null) {
                val release = cached.mbReleaseId?.let { loadRelease(it) }
                val track = release?.tracks?.firstOrNull { it.recordingId == cached.mbRecordingId }
                if (cached.mbRecordingId == null || track == null) return markStatus(ctx, s, OnlineStatus.UNMATCHED)
                _stats.update { it.copy(fromCache = it.fromCache + 1) }
                return decide(ctx, s, release, track, null, cached.confidence)
            }

            var best = mb.searchRecordings(artist, s.title)
                .map { it to Matcher.matchRecording(s, it) }
                .maxByOrNull { it.second }
            // Downloads are often named "Title - Artist"; if nothing fits, try the parts the other way round.
            if ((best == null || best.second < Matcher.SUGGEST) && artist != null) {
                val swapped = s.copy(title = artist, artist = s.title)
                val alt = mb.searchRecordings(s.title, artist)
                    .map { it to Matcher.matchRecording(swapped, it) }
                    .maxByOrNull { it.second }
                if (alt != null && alt.second > (best?.second ?: 0.0)) best = alt
            }
            if (best == null || best.second < Matcher.SUGGEST) {
                dao.upsertRecording(RecordingCacheEntity(queryKey, null, null, best?.second ?: 0.0, now()))
                return markStatus(ctx, s, OnlineStatus.UNMATCHED)
            }
            val (rec, score) = best
            val releaseRef = rec.releases.minWithOrNull(
                compareBy<MbRecordingRelease>(
                    { !it.official },
                    // Prefer the album the song claims to be on, then a studio album over compilations/live.
                    { if (TextCleaner.isPlaceholder(s.album)) 0.0 else -Matcher.similarity(s.album, it.title) },
                    { it.primaryType != "Album" },
                    { it.secondaryTypes.isNotEmpty() },
                    { it.year ?: Int.MAX_VALUE },
                ),
            )
            val release = releaseRef?.let { loadRelease(it.id) }
            val track = release?.tracks?.firstOrNull { it.recordingId == rec.id }
            dao.upsertRecording(RecordingCacheEntity(queryKey, rec.id, release?.id, score, now()))
            decide(ctx, s, release?.takeIf { track != null }, track, rec, score)
        } catch (e: TransientHttpException) {
            markError(ctx, listOf(s))
            throw e
        }
    }

    private suspend fun decide(ctx: PassContext, s: Song, release: MbRelease?, track: MbTrack?, rec: MbRecording?, conf: Double) =
        when {
            conf >= Matcher.APPLY -> commit(ctx, listOf(matchedRow(ctx, s, release, track, rec, conf)), emptyList())
            conf >= Matcher.SUGGEST -> commit(ctx, emptyList(), listOf(suggestion(s, release, track, rec, conf)))
            else -> markStatus(ctx, s, OnlineStatus.UNMATCHED)
        }

    private fun matchedRow(ctx: PassContext, s: Song, release: MbRelease?, track: MbTrack?, rec: MbRecording?, conf: Double): SongOverrideEntity {
        val old = ctx.rows.getValue(s.id)
        return old.copy(
            title = track?.title ?: rec?.title ?: old.title,
            artist = track?.artist ?: rec?.artist ?: old.artist,
            albumArtist = release?.artist ?: old.albumArtist,
            album = release?.title ?: old.album,
            year = release?.year ?: old.year,
            genre = release?.genre ?: old.genre,
            trackNumber = track?.encodedTrackNumber ?: old.trackNumber,
            artworkUrl = release?.artworkUrl ?: old.artworkUrl,
            mbRecordingId = track?.recordingId ?: rec?.id,
            mbReleaseId = release?.id,
            source = OverrideSource.ONLINE,
            confidence = conf,
            onlineStatus = OnlineStatus.MATCHED,
            attempts = old.attempts + 1,
            lastAttemptAt = now(),
            updatedAt = now(),
        )
    }

    private fun suggestion(s: Song, release: MbRelease?, track: MbTrack?, rec: MbRecording?, conf: Double) =
        SongSuggestionEntity(
            songId = s.id,
            title = track?.title ?: rec?.title,
            artist = track?.artist ?: rec?.artist,
            albumArtist = release?.artist,
            album = release?.title,
            year = release?.year,
            genre = release?.genre,
            trackNumber = track?.encodedTrackNumber,
            artworkUrl = release?.artworkUrl,
            mbRecordingId = track?.recordingId ?: rec?.id,
            mbReleaseId = release?.id,
            confidence = conf,
            createdAt = now(),
        )

    private suspend fun commit(ctx: PassContext, applied: List<SongOverrideEntity>, suggested: List<SongSuggestionEntity>) {
        val suggestedRows = suggested.map { sug ->
            ctx.rows.getValue(sug.songId).let {
                it.copy(onlineStatus = OnlineStatus.SUGGESTED, attempts = it.attempts + 1, lastAttemptAt = now())
            }
        }
        val rows = applied + suggestedRows
        if (rows.isNotEmpty()) dao.upsertOverrides(rows)
        if (suggested.isNotEmpty()) dao.upsertSuggestions(suggested)
        if (applied.isNotEmpty()) dao.deleteSuggestions(applied.map { it.songId })
        rows.forEach { ctx.rows[it.songId] = it }
    }

    private suspend fun markStatus(ctx: PassContext, s: Song, status: String) {
        val row = ctx.rows.getValue(s.id).let { it.copy(onlineStatus = status, attempts = it.attempts + 1, lastAttemptAt = now()) }
        dao.upsertOverrides(listOf(row))
        ctx.rows[s.id] = row
    }

    private suspend fun markError(ctx: PassContext, songs: List<Song>) {
        val rows = songs.mapNotNull { ctx.rows[it.id] }
            .filter { it.onlineStatus == OnlineStatus.PENDING || it.onlineStatus == OnlineStatus.ERROR || it.onlineStatus == OnlineStatus.UNMATCHED }
            .map { it.copy(onlineStatus = OnlineStatus.ERROR, attempts = it.attempts + 1, lastAttemptAt = now()) }
        dao.upsertOverrides(rows)
        rows.forEach { ctx.rows[it.songId] = it }
    }

    /**
     * A confident album match teaches how this library spells its artists: e.g. "beatles" → "The Beatles".
     * The local pass then applies that to every song with the same spelling, even ones never looked up.
     */
    private suspend fun learnAliases(songs: List<Song>, release: MbRelease, bySong: Map<Long, Matcher.Assignment>) {
        val t = now()
        val learned = songs.mapNotNull { s ->
            val canonical = bySong[s.id]?.track?.artist ?: return@mapNotNull null
            if (TextCleaner.isPlaceholder(s.artist) || s.artist == canonical) return@mapNotNull null
            if (canonical.equals("Various Artists", ignoreCase = true)) return@mapNotNull null
            // Only spelling differences, never a different artist (e.g. a label or uploader name).
            if (Matcher.similarity(s.artist, canonical) < 0.85) return@mapNotNull null
            ArtistAliasEntity(TextCleaner.key(s.artist), canonical, release.artistId, OverrideSource.ONLINE, 0.9, t)
        }.distinctBy { it.rawKey }
        val keep = learned.filter { dao.alias(it.rawKey)?.source != OverrideSource.USER }
        if (keep.isNotEmpty()) dao.upsertAliases(keep)
    }

    /** Release from cache, else MusicBrainz (+ iTunes for missing art/genre), cached for everyone after. */
    private suspend fun loadRelease(id: String): MbRelease? {
        dao.release(id)?.let { return it.toModel() }
        var release = mb.release(id) ?: return null
        if ((release.artworkUrl == null || release.genre == null) && itunes != null) {
            try {
                itunes.findAlbum(release.artist, release.title)?.let { alt ->
                    release = release.copy(artworkUrl = release.artworkUrl ?: alt.artworkUrl, genre = release.genre ?: alt.genre)
                }
            } catch (_: TransientHttpException) {
                // Art/genre are nice-to-have; don't fail the match over them.
            }
        }
        dao.upsertRelease(release.toEntity(now()))
        return release
    }

    // ------------------------------------------------------------------------------------------------
    // User decisions. These are stored at confidence 1.0 and feed the generalizing tiers.
    // ------------------------------------------------------------------------------------------------

    suspend fun acceptSuggestions(songs: List<Song>) {
        val sugs = dao.suggestions(songs.map { it.id }).associateBy { it.songId }
        val t = now()
        val rows = songs.mapNotNull { s ->
            val sug = sugs[s.id] ?: return@mapNotNull null
            val old = dao.override(s.id) ?: return@mapNotNull null
            old.copy(
                title = sug.title ?: old.title, artist = sug.artist ?: old.artist,
                albumArtist = sug.albumArtist ?: old.albumArtist, album = sug.album ?: old.album,
                year = sug.year ?: old.year, genre = sug.genre ?: old.genre,
                trackNumber = sug.trackNumber ?: old.trackNumber, artworkUrl = sug.artworkUrl ?: old.artworkUrl,
                mbRecordingId = sug.mbRecordingId, mbReleaseId = sug.mbReleaseId,
                source = OverrideSource.ONLINE, confidence = 1.0, onlineStatus = OnlineStatus.MATCHED, updatedAt = t,
            )
        }
        dao.upsertOverrides(rows)
        dao.deleteSuggestions(rows.map { it.songId })
        // Teach the album tier, so this folder (and other copies of the album) resolve without asking again.
        val albumRows = songs.filter { sugs[it.id]?.mbReleaseId != null }
            .groupBy { s -> s.path.substringBeforeLast('/', "") + "|" + TextCleaner.key(s.album) }
            .map { (key, group) -> AlbumResolutionEntity("dir:$key", sugs.getValue(group.first().id).mbReleaseId, 1.0, OverrideSource.USER, t) }
        if (albumRows.isNotEmpty()) dao.upsertAlbumResolutions(albumRows)
    }

    suspend fun rejectSuggestions(songIds: List<Long>) {
        val t = now()
        dao.deleteSuggestions(songIds)
        val rows = songIds.mapNotNull { dao.override(it) }.map { it.copy(onlineStatus = OnlineStatus.REJECTED, updatedAt = t) }
        dao.upsertOverrides(rows)
    }

    /** "Always call artist X → Y": applied to every song with that spelling on the next local pass. */
    suspend fun teachArtistAlias(from: String, to: String) {
        dao.upsertAliases(listOf(ArtistAliasEntity(TextCleaner.key(from), to.trim(), null, OverrideSource.USER, 1.0, now())))
    }

    /** User edit: locks the song so nothing automatic changes it again. Blank values mean "use the file's tag". */
    suspend fun editSong(song: Song, title: String, artist: String, album: String, year: Int?, genre: String) {
        val old = dao.override(song.id)
        val row = (old ?: SongOverrideEntity(song.id, song.fingerprint)).copy(
            fingerprint = song.fingerprint,
            title = title.trim().ifEmpty { null },
            artist = artist.trim().ifEmpty { null },
            album = album.trim().ifEmpty { null },
            year = year,
            genre = genre.trim().ifEmpty { null },
            source = OverrideSource.USER,
            confidence = 1.0,
            locked = true,
            updatedAt = now(),
        )
        dao.upsertOverrides(listOf(row))
        dao.deleteSuggestions(listOf(song.id))
    }

    /** Show exactly what the file's tags say, and keep it that way. */
    suspend fun revertToOriginal(song: Song) {
        dao.upsertOverrides(
            listOf(
                SongOverrideEntity(
                    song.id, song.fingerprint, source = OverrideSource.USER, confidence = 1.0, locked = true,
                    onlineStatus = OnlineStatus.REJECTED, updatedAt = now(),
                ),
            ),
        )
        dao.deleteSuggestions(listOf(song.id))
    }

    /** Forget everything about this song (including a lock) so the next run starts over. */
    suspend fun searchAgain(songId: Long) {
        dao.deleteOverrides(listOf(songId))
        dao.deleteSuggestions(listOf(songId))
    }

    suspend fun resetAutomatic() = dao.resetAutomatic()

    suspend fun pruneHttpCache() = dao.pruneHttp(now())

    companion object {
        val RETRY_UNMATCHED_MS: Long = TimeUnit.DAYS.toMillis(30)

        fun MbRelease.toEntity(t: Long) = ReleaseCacheEntity(
            mbReleaseId = id, title = title, artist = artist, mbArtistId = artistId, year = year, genre = genre,
            artworkUrl = artworkUrl,
            tracksJson = JSONArray().apply {
                tracks.forEach { tr ->
                    put(
                        JSONObject()
                            .put("disc", tr.disc).put("position", tr.position).put("title", tr.title)
                            .put("artist", tr.artist).put("lengthMs", tr.lengthMs ?: 0).put("recordingId", tr.recordingId ?: "")
                            .put("discCount", tr.discCount),
                    )
                }
            }.toString(),
            fetchedAt = t,
        )

        fun ReleaseCacheEntity.toModel() = MbRelease(
            id = mbReleaseId, title = title, artist = artist, artistId = mbArtistId, year = year, genre = genre,
            artworkUrl = artworkUrl,
            tracks = JSONArray(tracksJson).objects().map {
                MbTrack(
                    disc = it.optInt("disc", 1), position = it.optInt("position"), title = it.optString("title"),
                    artist = it.optString("artist"), lengthMs = it.optLong("lengthMs").takeIf { l -> l > 0 },
                    recordingId = it.optString("recordingId").ifEmpty { null }, discCount = it.optInt("discCount", 1),
                )
            },
        )
    }
}
