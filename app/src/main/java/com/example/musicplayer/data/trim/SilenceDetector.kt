package com.example.musicplayer.data.trim

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlin.math.sqrt

/** Where audible sound begins and ends in a file, in ms; null where there's no silence worth skipping. */
data class SoundBounds(val startMs: Long?, val endMs: Long?)

/**
 * Finds leading and trailing silence by decoding only the start and the end of a file: the first
 * [HEAD_SCAN_US] for where sound begins and the last [TAIL_SCAN_US] for where it ends.
 */
object SilenceDetector {
    /** RMS level, relative to full scale, below which a window counts as silent. */
    const val THRESHOLD_DB = -50.0
    private const val HEAD_SCAN_US = 60_000_000L
    private const val TAIL_SCAN_US = 45_000_000L

    /** Shorter gaps aren't worth a seek (and may be part of the song). */
    private const val MIN_LEADING_MS = 300L
    private const val MIN_TRAILING_MS = 1_000L
    /** Room left before the first sound and after the last, so attacks and reverb tails aren't clipped. */
    private const val START_PAD_MS = 50L
    private const val END_PAD_MS = 300L
    /** If trimming would leave less than this, the "song" is probably mostly quiet: leave it alone. */
    private const val MIN_REMAINING_MS = 5_000L

    /** Decodes the file's start and end; throws if it can't be read. */
    suspend fun detect(context: Context, uri: Uri): SoundBounds = withContext(Dispatchers.IO) {
        val head = scan(context, uri, fromUs = 0, untilUs = HEAD_SCAN_US, stopAtFirstSound = true)
        val tail = if (head.durationUs > 0) {
            scan(context, uri, fromUs = (head.durationUs - TAIL_SCAN_US).coerceAtLeast(0), untilUs = Long.MAX_VALUE)
        } else {
            null
        }
        bounds(head.firstLoudUs, tail?.lastLoudEndUs, head.durationUs)
    }

    /** Turns raw scan results into the bounds worth acting on. */
    internal fun bounds(firstLoudUs: Long?, lastLoudEndUs: Long?, durationUs: Long): SoundBounds {
        val durationMs = durationUs / 1000
        val start = firstLoudUs
            ?.let { (it / 1000 - START_PAD_MS).coerceAtLeast(0) }
            ?.takeIf { it >= MIN_LEADING_MS }
        val end = lastLoudEndUs
            ?.takeIf { durationMs > 0 }
            ?.let { it / 1000 + END_PAD_MS }
            ?.takeIf { durationMs - it >= MIN_TRAILING_MS }
        if (durationMs > 0 && (end ?: durationMs) - (start ?: 0) < MIN_REMAINING_MS) return SoundBounds(null, null)
        return SoundBounds(start, end)
    }

    private class ScanResult(val firstLoudUs: Long?, val lastLoudEndUs: Long?, val durationUs: Long)

    private suspend fun scan(
        context: Context,
        uri: Uri,
        fromUs: Long,
        untilUs: Long,
        stopAtFirstSound: Boolean = false,
    ): ScanResult {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track in $uri")
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            extractor.selectTrack(track)
            if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var scanner: LoudnessScanner? = null
            var samples = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false

            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(inIndex)!!, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > untilUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        val s = scanner ?: LoudnessScanner(sampleRate, channels).also { scanner = it }
                        val bytes = decoder.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        bytes.position(info.offset)
                        bytes.limit(info.offset + info.size)
                        val count = if (floatPcm) info.size / 4 else info.size / 2
                        if (samples.size < count) samples = FloatArray(count)
                        if (floatPcm) {
                            bytes.asFloatBuffer().get(samples, 0, count)
                        } else {
                            val shorts = bytes.asShortBuffer()
                            for (i in 0 until count) samples[i] = shorts.get(i) / 32768f
                        }
                        s.feed(samples, count, info.presentationTimeUs)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (stopAtFirstSound && scanner?.firstLoudUs != null) break
                }
            }
            scanner?.finish()
            return ScanResult(scanner?.firstLoudUs, scanner?.lastLoudEndUs, durationUs)
        } finally {
            try {
                codec?.stop()
            } catch (_: IllegalStateException) {
            }
            codec?.release()
            extractor.release()
        }
    }
}

/**
 * Splits interleaved PCM (samples in -1..1) into short windows and records when the first and last
 * windows louder than [thresholdDb] RMS occur.
 */
class LoudnessScanner(
    private val sampleRate: Int,
    private val channels: Int,
    thresholdDb: Double = SilenceDetector.THRESHOLD_DB,
    windowMs: Int = 20,
) {
    private val threshold = 10.0.pow(thresholdDb / 20)
    private val windowFrames = (sampleRate * windowMs / 1000).coerceAtLeast(1)
    private var sumSquares = 0.0
    private var frames = 0
    private var windowStartUs = 0L

    /** Start of the first loud window, in µs. */
    var firstLoudUs: Long? = null
        private set

    /** End of the last loud window seen so far, in µs. */
    var lastLoudEndUs: Long? = null
        private set

    /** Feeds [count] interleaved samples whose first frame plays at [startUs]. */
    fun feed(samples: FloatArray, count: Int, startUs: Long) {
        val frameCount = count / channels
        for (f in 0 until frameCount) {
            if (frames == 0) windowStartUs = startUs + f * 1_000_000L / sampleRate
            var sum = 0.0
            val base = f * channels
            for (c in 0 until channels) {
                val v = samples[base + c].toDouble()
                sum += v * v
            }
            sumSquares += sum / channels
            if (++frames == windowFrames) closeWindow()
        }
    }

    /** Closes the last, partial window. */
    fun finish() {
        if (frames > 0) closeWindow()
    }

    private fun closeWindow() {
        if (sqrt(sumSquares / frames) >= threshold) {
            if (firstLoudUs == null) firstLoudUs = windowStartUs
            lastLoudEndUs = windowStartUs + frames * 1_000_000L / sampleRate
        }
        sumSquares = 0.0
        frames = 0
    }
}
