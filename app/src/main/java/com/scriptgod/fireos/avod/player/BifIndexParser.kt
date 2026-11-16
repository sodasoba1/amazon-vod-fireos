package com.scriptgod.fireos.avod.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

internal data class BifFrame(
    val timestampMs: Long,
    val startOffset: Long,
    val endOffsetExclusive: Long
)

internal data class BifIndex(
    val frames: List<BifFrame>,
    val timestampMultiplierMs: Double
)

/**
 * Parser for Roku-compatible BIF index files. Amazon-hosted BIFs have also been
 * observed with an implausible unsigned multiplier (for example 0xFFFFD8F0).
 * In that case derive the interval from the known media duration, then fall
 * back to the common ten-second cadence if duration metadata is unavailable.
 */
internal object BifIndexParser {
    private val MAGIC = byteArrayOf(
        0x89.toByte(), 0x42, 0x49, 0x46, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private const val HEADER_SIZE = 64L
    private const val MAX_IMAGES = 100_000L
    private const val MAX_REASONABLE_MULTIPLIER_MS = 60_000L
    private const val FALLBACK_INTERVAL_MS = 10_000.0
    private const val END_TIMESTAMP = 0xFFFFFFFFL

    fun parse(headerBytes: ByteArray, indexBytes: ByteArray, durationMs: Long = 0L): BifIndex? {
        if (headerBytes.size != HEADER_SIZE.toInt()) return null
        if (!MAGIC.indices.all { headerBytes[it] == MAGIC[it] }) return null

        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        header.position(8)
        val version = readUnsignedInt(header)
        if (version != 0L) return null

        val imageCount = readUnsignedInt(header)
        if (imageCount !in 1L..MAX_IMAGES) return null
        val rawMultiplier = readUnsignedInt(header)
        val indexLength = (imageCount + 1L) * 8L
        if (indexLength > Int.MAX_VALUE || indexBytes.size.toLong() != indexLength) return null

        val indexBuffer = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN)
        val timestamps = LongArray(imageCount.toInt())
        val offsets = LongArray(imageCount.toInt())
        var previousTimestamp = -1L
        var previousOffset = -1L
        var endOffset = -1L

        for (i in 0..imageCount.toInt()) {
            if (indexBuffer.remaining() < 8) return null
            val timestamp = readUnsignedInt(indexBuffer)
            val offset = readUnsignedInt(indexBuffer)

            if (i == imageCount.toInt()) {
                if (timestamp != END_TIMESTAMP) return null
                endOffset = offset
            } else {
                if (timestamp == END_TIMESTAMP) return null
                if (timestamp < previousTimestamp || offset <= previousOffset) return null
                timestamps[i] = timestamp
                offsets[i] = offset
                previousTimestamp = timestamp
                previousOffset = offset
            }
        }

        val firstImageOffset = HEADER_SIZE + indexLength
        if (offsets.first() < firstImageOffset || endOffset <= offsets.last()) return null

        val multiplierMs = when {
            rawMultiplier == 0L -> 1_000.0
            rawMultiplier in 1L..MAX_REASONABLE_MULTIPLIER_MS -> rawMultiplier.toDouble()
            durationMs > 0L && timestamps.last() > 0L -> {
                val derived = durationMs.toDouble() / timestamps.last().toDouble()
                if (derived in 1.0..MAX_REASONABLE_MULTIPLIER_MS.toDouble()) derived
                else FALLBACK_INTERVAL_MS
            }
            else -> FALLBACK_INTERVAL_MS
        }

        val frames = ArrayList<BifFrame>(imageCount.toInt())
        for (i in offsets.indices) {
            val nextOffset = if (i + 1 < offsets.size) offsets[i + 1] else endOffset
            if (nextOffset <= offsets[i]) return null
            frames += BifFrame(
                timestampMs = (timestamps[i].toDouble() * multiplierMs).roundToLong(),
                startOffset = offsets[i],
                endOffsetExclusive = nextOffset
            )
        }

        return BifIndex(frames, multiplierMs)
    }

    private fun readUnsignedInt(buffer: ByteBuffer): Long =
        buffer.int.toLong() and 0xFFFFFFFFL
}
