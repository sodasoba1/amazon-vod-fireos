package com.scriptgod.fireos.avod.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@UnstableApi
class BifThumbnailProvider(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "BifThumbnailProvider"
        private const val MAX_RANGE_BYTES = 4L * 1024L * 1024L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var bifUrl: String = ""
    @Volatile private var bifIndex: BifIndex? = null
    @Volatile var onIndexLoaded: (() -> Unit)? = null
    private val stateLock = Any()
    private var loadGeneration = 0L
    private val thumbCache = LruCache<String, Bitmap>(10)

    fun clear() {
        synchronized(stateLock) {
            loadGeneration++
            bifUrl = ""
            bifIndex = null
            thumbCache.evictAll()
        }
    }

    /**
     * Loads only the header and index. Individual JPEGs are fetched on demand.
     * [durationMs] is used only when the file's timestamp multiplier is invalid.
     */
    fun loadIndex(url: String, durationMs: Long = 0L) {
        if (url.isBlank()) {
            clear()
            return
        }

        var alreadyLoaded = false
        val generation = synchronized(stateLock) {
            if (bifUrl == url && bifIndex != null) {
                alreadyLoaded = true
                -1L
            } else {
                loadGeneration++
                bifUrl = url
                bifIndex = null
                thumbCache.evictAll()
                loadGeneration
            }
        }
        if (alreadyLoaded) {
            scope.launch(Dispatchers.Main) { onIndexLoaded?.invoke() }
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val header = bifRangeGet(url, 0L, 63L) ?: return@launch
                if (header.size != 64) return@launch

                val countBuffer = java.nio.ByteBuffer.wrap(header)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                countBuffer.position(12)
                val imageCount = countBuffer.int.toLong() and 0xFFFFFFFFL
                if (imageCount !in 1L..100_000L) return@launch

                val indexStart = 64L
                val indexEnd = indexStart + ((imageCount + 1L) * 8L) - 1L
                val indexBytes = bifRangeGet(url, indexStart, indexEnd) ?: return@launch
                val parsed = BifIndexParser.parse(header, indexBytes, durationMs)
                    ?: run {
                        Log.w(TAG, "BIF index rejected: invalid header or index")
                        return@launch
                    }

                val accepted = withContext(Dispatchers.Main) {
                    synchronized(stateLock) {
                        if (generation == loadGeneration && bifUrl == url) {
                            bifIndex = parsed
                            true
                        } else false
                    }.also { if (it) onIndexLoaded?.invoke() }
                }
                if (accepted) {
                    Log.i(TAG, "BIF index loaded: " + parsed.frames.size +
                        " frames, multiplier=" + parsed.timestampMultiplierMs + "ms")
                }
            } catch (e: Exception) {
                Log.w(TAG, "BIF index load failed", e)
            }
        }
    }

    /**
     * Performs a strict byte-range request. A 200 response, mismatched
     * Content-Range, compressed representation, or short body is not usable.
     */
    private fun bifRangeGet(url: String, from: Long, to: Long): ByteArray? {
        if (from < 0L || to < from || to - from + 1L > MAX_RANGE_BYTES) return null
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=" + from + "-" + to)
                .header("Accept-Encoding", "identity")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code != 206) {
                    Log.w(TAG, "BIF range rejected: HTTP " + response.code)
                    return null
                }
                if (!response.header("Content-Encoding").isNullOrBlank() &&
                    !response.header("Content-Encoding").equals("identity", ignoreCase = true)
                ) return null

                val contentRange = response.header("Content-Range") ?: return null
                val match = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""").matchEntire(contentRange)
                    ?: return null
                if (match.groupValues[1].toLongOrNull() != from ||
                    match.groupValues[2].toLongOrNull() != to
                ) return null

                val bytes = response.body?.bytes() ?: return null
                if (bytes.size.toLong() != to - from + 1L) return null
                bytes
            }
        } catch (e: Exception) {
            Log.w(TAG, "BIF range request failed", e)
            null
        }
    }

    fun getThumbnailAt(posMs: Long, url: String, onBitmap: (Bitmap?) -> Unit) {
        val index = bifIndex
        if (index == null || url != bifUrl || index.frames.isEmpty()) {
            onBitmap(null)
            return
        }

        val frames = index.frames
        var low = 0
        var high = frames.lastIndex
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (frames[middle].timestampMs <= posMs) low = middle else high = middle - 1
        }
        val frameIndex = low
        val frame = frames[frameIndex]
        val cacheKey = url + "#" + frameIndex
        thumbCache.get(cacheKey)?.let {
            onBitmap(it)
            return
        }

        val byteCount = frame.endOffsetExclusive - frame.startOffset
        if (byteCount <= 0L || byteCount > MAX_RANGE_BYTES) {
            onBitmap(null)
            return
        }

        scope.launch(Dispatchers.IO) {
            val bitmap = try {
                val bytes = bifRangeGet(url, frame.startOffset, frame.endOffsetExclusive - 1L)
                if (bytes == null) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                Log.w(TAG, "BIF frame decode failed index=" + frameIndex, e)
                null
            }

            withContext(Dispatchers.Main) {
                if (bifUrl == url) {
                    if (bitmap != null) thumbCache.put(cacheKey, bitmap)
                    onBitmap(bitmap)
                }
            }
        }
    }

    fun hasBifEntries(): Boolean = bifIndex?.frames?.isNotEmpty() == true && bifUrl.isNotEmpty()
}
