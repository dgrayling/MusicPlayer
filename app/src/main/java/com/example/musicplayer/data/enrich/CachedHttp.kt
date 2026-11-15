package com.example.musicplayer.data.enrich

import com.example.musicplayer.data.db.EnrichmentDao
import com.example.musicplayer.data.db.HttpCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class HttpResult(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

/** Thrown for failures worth retrying later (offline, timeouts, 429/5xx). */
class TransientHttpException(message: String, cause: Throwable? = null) : IOException(message, cause)

fun interface HttpFetcher {
    suspend fun get(url: String): HttpResult
}

/** Spaces out calls so they're at least [intervalMs] apart (MusicBrainz allows ~1 request/second). */
class RateLimiter(private val intervalMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var last = 0L

    suspend fun acquire() = mutex.withLock {
        val wait = last + intervalMs - now()
        if (wait > 0) delay(wait)
        last = now()
    }
}

/**
 * GETs through the `http_cache` table: hits never touch the network. Successful responses are kept for
 * [okTtlMs], "not found" (404 / empty) for [negativeTtlMs], and transient failures are not cached.
 */
class CachedHttp(
    private val client: OkHttpClient,
    private val dao: EnrichmentDao,
    private val userAgent: String,
    private val limiters: Map<String, RateLimiter>,
    private val okTtlMs: Long = TimeUnit.DAYS.toMillis(90),
    private val negativeTtlMs: Long = TimeUnit.DAYS.toMillis(30),
    private val now: () -> Long = System::currentTimeMillis,
    /** First wait after a throttled (503/429) response; doubles per retry. */
    private val retryBaseMs: Long = 5_000,
) : HttpFetcher {
    val networkCalls = AtomicInteger()
    val cacheHits = AtomicInteger()

    override suspend fun get(url: String): HttpResult {
        dao.http(url, now())?.let {
            cacheHits.incrementAndGet()
            return HttpResult(it.status, it.body)
        }
        val host = url.substringAfter("://").substringBefore('/')
        var result: HttpResult
        var attempt = 0
        while (true) {
            limiters[host]?.acquire()
            networkCalls.incrementAndGet()
            val (r, retryAfterS) = fetch(url, host)
            result = r
            // MusicBrainz answers 503 when it's throttling (per client or globally); wait and try again
            // rather than abandoning the whole run.
            if ((r.status != 503 && r.status != 429) || attempt >= MAX_RETRIES) break
            // At least 5 s, 10 s, 20 s: during global throttling MusicBrainz sends "Retry-After: 0".
            delay(maxOf((retryAfterS ?: 0) * 1_000L, retryBaseMs shl attempt))
            attempt++
        }
        when {
            result.ok -> store(url, result, okTtlMs)
            result.status == 404 || result.status == 400 -> store(url, result, negativeTtlMs)
            else -> throw TransientHttpException("GET $host returned HTTP ${result.status}")
        }
        return result
    }

    private suspend fun fetch(url: String, host: String): Pair<HttpResult, Long?> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        try {
            client.newCall(request).execute().use {
                HttpResult(it.code, it.body?.string().orEmpty()) to it.header("Retry-After")?.trim()?.toLongOrNull()?.coerceIn(0, 60)
            }
        } catch (e: IOException) {
            throw TransientHttpException("GET $host failed: ${e.message}", e)
        }
    }

    private suspend fun store(url: String, r: HttpResult, ttl: Long) {
        val t = now()
        dao.putHttp(HttpCacheEntity(url, r.status, r.body, t, t + ttl))
    }

    companion object {
        private const val MAX_RETRIES = 3

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun defaultLimiters(): Map<String, RateLimiter> = mapOf(
            "musicbrainz.org" to RateLimiter(1_100),
            // The iTunes Search API allows roughly 20 calls a minute.
            "itunes.apple.com" to RateLimiter(3_100),
        )
    }
}
