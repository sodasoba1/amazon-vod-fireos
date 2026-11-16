package com.scriptgod.fireos.avod.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BifIndexParserTest {

    @Test
    fun parsesValidIndexAndUsesTerminalOffsetForLastFrame() {
        val header = header(imageCount = 2, multiplier = 1_000)
        val index = index(
            Triple(0L, 88L, 0L),
            Triple(10L, 92L, 0L),
            Triple(0xFFFFFFFFL, 100L, 0L)
        )

        val parsed = BifIndexParser.parse(header, index, durationMs = 20_000L)

        assertNotNull(parsed)
        assertEquals(2, parsed!!.frames.size)
        assertEquals(10_000L, parsed.frames[1].timestampMs)
        assertEquals(92L, parsed.frames[0].endOffsetExclusive)
        assertEquals(8L, parsed.frames[1].endOffsetExclusive - parsed.frames[1].startOffset)
    }

    @Test
    fun zeroMultiplierUsesSpecificationDefaultOfOneSecond() {
        val parsed = BifIndexParser.parse(
            header(imageCount = 1, multiplier = 0),
            index(
                Triple(0L, 80L, 0L),
                Triple(0xFFFFFFFFL, 84L, 0L)
            )
        )

        assertNotNull(parsed)
        assertEquals(1_000.0, parsed!!.timestampMultiplierMs, 0.0)
    }

    @Test
    fun implausibleAmazonMultiplierUsesDurationDerivedInterval() {
        val parsed = BifIndexParser.parse(
            header(imageCount = 2, multiplier = 0xFFFFD8F0L),
            index(
                Triple(0L, 88L, 0L),
                Triple(1L, 92L, 0L),
                Triple(0xFFFFFFFFL, 100L, 0L)
            ),
            durationMs = 20_000L
        )

        assertNotNull(parsed)
        assertEquals(10_000.0, parsed!!.timestampMultiplierMs, 0.0)
        assertEquals(10_000L, parsed.frames[1].timestampMs)
    }

    @Test
    fun invalidMagicAndVersionAreRejected() {
        val badMagic = header(1, 1_000).also { it[0] = 0 }
        assertNull(BifIndexParser.parse(
            badMagic,
            index(Triple(0L, 80L, 0L), Triple(0xFFFFFFFFL, 84L, 0L))
        ))

        val badVersion = header(1, 1_000).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 1)
        }
        assertNull(BifIndexParser.parse(
            badVersion,
            index(Triple(0L, 80L, 0L), Triple(0xFFFFFFFFL, 84L, 0L))
        ))
    }

    @Test
    fun truncatedIndexAndMissingTerminatorAreRejected() {
        val header = header(1, 1_000)
        val valid = index(Triple(0L, 80L, 0L), Triple(0xFFFFFFFFL, 84L, 0L))
        assertNull(BifIndexParser.parse(header, valid.copyOf(valid.size - 1)))
        assertNull(BifIndexParser.parse(
            header,
            index(Triple(0L, 80L, 0L), Triple(1L, 84L, 0L))
        ))
    }

    private fun header(imageCount: Int, multiplier: Long): ByteArray {
        return ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0D, 0x0A, 0x1A, 0x0A))
            putInt(0)
            putInt(imageCount)
            putInt(multiplier.toInt())
        }.array()
    }

    private fun index(vararg entries: Triple<Long, Long, Long>): ByteArray {
        val buffer = ByteBuffer.allocate(entries.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        entries.forEach { (timestamp, offset, _) ->
            buffer.putInt(timestamp.toInt())
            buffer.putInt(offset.toInt())
        }
        return buffer.array()
    }
}
