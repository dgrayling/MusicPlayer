package com.example.musicplayer.data.trim

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.musicplayer.data.SettingsStore
import com.example.musicplayer.data.db.AppDatabase
import com.example.musicplayer.data.model.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.sin

class LoudnessScannerTest {
    private val rate = 1000 // 1 frame per ms keeps the arithmetic readable

    /** Mono PCM: silent, then a loud tone from [fromMs] until [toMs], then silent up to [totalMs]. */
    private fun pcm(totalMs: Int, fromMs: Int, toMs: Int, level: Float = 0.5f) =
        FloatArray(totalMs) { i -> if (i in fromMs until toMs) level * sin(i * 0.7f) else 0f }

    @Test
    fun findsFirstAndLastLoudWindows() {
        val scanner = LoudnessScanner(rate, channels = 1, windowMs = 20)
        val samples = pcm(totalMs = 5000, fromMs = 1200, toMs = 4000)
        scanner.feed(samples, samples.size, startUs = 0)
        scanner.finish()
        assertEquals(1_200_000L, scanner.firstLoudUs)
        assertEquals(4_000_000L, scanner.lastLoudEndUs)
    }

    @Test
    fun usesBufferTimestampsAndIgnoresQuietNoise() {
        val scanner = LoudnessScanner(rate, channels = 2, windowMs = 20)
        // Hiss at about -60 dB stays "silent"; split across two buffers, the second starting at 10 s.
        val hiss = FloatArray(2000) { 0.001f }
        scanner.feed(hiss, hiss.size, startUs = 9_000_000)
        val loud = FloatArray(2000) { if (it >= 1000) 0.3f else 0.001f }
        scanner.feed(loud, loud.size, startUs = 10_000_000)
        scanner.finish()
        assertEquals(10_500_000L, scanner.firstLoudUs)
        assertEquals(11_000_000L, scanner.lastLoudEndUs)
    }

    @Test
    fun silenceOnly() {
        val scanner = LoudnessScanner(rate, channels = 1)
        val samples = FloatArray(3000)
        scanner.feed(samples, samples.size, 0)
        scanner.finish()
        assertNull(scanner.firstLoudUs)
        assertNull(scanner.lastLoudEndUs)
    }
}

class SoundBoundsTest {
    @Test
    fun padsAndKeepsMeaningfulSilence() {
        val b = SilenceDetector.bounds(firstLoudUs = 2_000_000, lastLoudEndUs = 170_000_000, durationUs = 180_000_000)
        assertEquals(SoundBounds(1_950, 170_300), b)
    }

    @Test
    fun ignoresTinyGaps() {
        val b = SilenceDetector.bounds(firstLoudUs = 200_000, lastLoudEndUs = 179_500_000, durationUs = 180_000_000)
        assertEquals(SoundBounds(null, null), b)
    }

    @Test
    fun leavesMostlySilentFilesAlone() {
        val b = SilenceDetector.bounds(firstLoudUs = 10_000_000, lastLoudEndUs = 12_000_000, durationUs = 60_000_000)
        assertEquals(SoundBounds(null, null), b)
    }

    @Test
    fun noEndWithoutDuration() {
        val b = SilenceDetector.bounds(firstLoudUs = 3_000_000, lastLoudEndUs = 50_000_000, durationUs = -1)
        assertEquals(SoundBounds(2_950, null), b)
    }
}

class TrimMarksTest {
    @Test
    fun userMarksWinOverDetection() {
        val marks = TrimMarks(userStartMs = 5_000, detectedStartMs = 1_000, detectedEndMs = 100_000)
        assertEquals(TrackTrim(5_000, 100_000), marks.effective)
    }

    @Test
    fun detectionOnlyWhenEnabled() {
        val marks = TrimMarks(userEndMs = 90_000, detectedStartMs = 1_000, detectedEndMs = 100_000, skipSilence = false)
        assertEquals(TrackTrim(0, 90_000), marks.effective)
    }

    @Test
    fun endBeforeStartIsIgnored() {
        assertEquals(TrackTrim(50_000, null), TrimMarks(userStartMs = 50_000, detectedEndMs = 40_000).effective)
    }
}

@RunWith(RobolectricTestRunner::class)
class TrimRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private var detections = 0
    private lateinit var repo: TrimRepository

    private val song = Song(
        id = 7, title = "Song", artist = "A", artistId = 1, album = "B", albumId = 1, durationMs = 200_000,
        trackNumber = 1, year = 0, dateAdded = 0, uri = Uri.parse("content://media/7"), path = "/m/s.mp3", size = 10,
    )

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsStore(context)
        settings.setSkipSilence(true)
        repo = TrimRepository(context, db.trimDao(), settings) {
            detections++
            SoundBounds(2_000, 190_000)
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun detectsOncePerFileVersion() = runTest {
        assertEquals(TrackTrim(2_000, 190_000), repo.trimFor(song))
        assertEquals(TrackTrim(2_000, 190_000), repo.trimFor(song))
        assertEquals(1, detections)
        repo.trimFor(song.copy(dateModified = 99)) // file changed
        assertEquals(2, detections)
    }

    @Test
    fun marksPersistAndOverrideDetection() = runTest {
        repo.setStart(song.id, 10_000)
        assertEquals(TrackTrim(10_000, 190_000), repo.trimFor(song))
        repo.setEnd(song.id, 5_000) // before the start: the start mark gives way
        assertEquals(TrackTrim(2_000, 5_000), repo.trimFor(song))
        repo.setMarks(song.id, null, null)
        assertEquals(TrackTrim(2_000, 190_000), repo.trimFor(song))
    }

    @Test
    fun turningDetectionOffKeepsUserMarks() = runTest {
        repo.setEnd(song.id, 150_000)
        settings.setSkipSilence(false)
        assertEquals(TrackTrim(0, 150_000), repo.trimFor(song))
        assertEquals(0, detections)
        assertEquals(150_000L, repo.observe(song.id).first().userEndMs)
    }
}
