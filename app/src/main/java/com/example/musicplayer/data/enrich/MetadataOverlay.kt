package com.example.musicplayer.data.enrich

import com.example.musicplayer.data.db.SongOverrideEntity
import com.example.musicplayer.data.model.Song

/** Applies stored corrections on top of what MediaStore reports. Pure, so it's cheap to unit-test. */
object MetadataOverlay {

    /** A changed file (re-tagged, replaced) invalidates an automatic override until enrichment re-runs. */
    fun isActive(song: Song, o: SongOverrideEntity?): Boolean =
        o != null && (o.locked || o.fingerprint == song.fingerprint)

    fun apply(song: Song, o: SongOverrideEntity?): Song {
        if (o == null || !isActive(song, o)) return song
        return song.copy(
            title = o.title ?: song.title,
            artist = o.artist ?: song.artist,
            albumArtist = o.albumArtist ?: song.albumArtist,
            album = o.album ?: song.album,
            year = o.year ?: song.year,
            genre = o.genre ?: song.genre,
            trackNumber = o.trackNumber ?: song.trackNumber,
            remoteArtworkUrl = o.artworkUrl ?: song.remoteArtworkUrl,
        )
    }

    /**
     * Applies overrides, then re-points ids so corrected songs group together:
     *  - songs whose artists now share a match key share one artistId ("Beatles" + "The Beatles" → one artist);
     *  - songs matched to the same MusicBrainz release share one albumId (duplicate/split albums merge).
     * The lowest original id wins so ids stay stable across runs.
     */
    fun applyAll(raw: List<Song>, allOverrides: Map<Long, SongOverrideEntity>): List<Song> {
        val overrides = raw.mapNotNull { s -> allOverrides[s.id]?.takeIf { isActive(s, it) }?.let { s.id to it } }.toMap()
        val applied = raw.map { apply(it, overrides[it.id]) }

        val artistIdByKey = HashMap<String, Long>()
        for (s in applied) {
            val k = TextCleaner.key(s.artist)
            artistIdByKey[k] = minOf(artistIdByKey[k] ?: Long.MAX_VALUE, s.artistId)
        }
        val albumIdByRelease = HashMap<String, Long>()
        for (s in applied) {
            val release = overrides[s.id]?.mbReleaseId ?: continue
            albumIdByRelease[release] = minOf(albumIdByRelease[release] ?: Long.MAX_VALUE, s.albumId)
        }

        return applied.map { s ->
            val artistId = artistIdByKey.getValue(TextCleaner.key(s.artist))
            val albumId = overrides[s.id]?.mbReleaseId?.let(albumIdByRelease::get) ?: s.albumId
            if (artistId == s.artistId && albumId == s.albumId) s else s.copy(artistId = artistId, albumId = albumId)
        }
    }
}
