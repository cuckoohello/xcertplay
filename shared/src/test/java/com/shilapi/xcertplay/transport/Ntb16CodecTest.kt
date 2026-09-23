package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Ntb16CodecTest {
    @Test
    fun parseCanReadBlockFromLargerBuffer() {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
        val block = Ntb16Codec.build(frame, 7)
        val buffer = ByteArray(11) + block + ByteArray(3)

        val parsed = Ntb16Codec.parse(buffer, 11, block.size)

        assertArrayEquals(frame, parsed.single())
    }

    @Test
    fun largestDatagramKeepsBlockLengthWithinU16() {
        val block = Ntb16Codec.build(ByteArray(65_507), 0x1234)

        assertEquals(65_535, block.size)
        assertEquals(0xff, block[8].toInt() and 0xff)
        assertEquals(0xff, block[9].toInt() and 0xff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun datagramThatWouldOverflowBlockLengthIsRejected() {
        Ntb16Codec.build(ByteArray(65_508), 0x1234)
    }

    @Test
    fun parsesCdcNcmNtbParametersAsLittleEndian() {
        val bytes = byteArrayOf(
            0x1c, 0x00,
            0x01, 0x00,
            0x00, 0x80.toByte(), 0x00, 0x00,
            0x04, 0x00,
            0x00, 0x00,
            0x04, 0x00,
            0x00, 0x00,
            0x00, 0x40, 0x00, 0x00,
            0x04, 0x00,
            0x00, 0x00,
            0x04, 0x00,
            0x10, 0x00,
        )

        val parameters = NcmUsbBridge.parseNtbParameters(bytes)

        assertEquals(28, parameters.length)
        assertEquals(true, parameters.supportsNtb16)
        assertEquals(32_768L, parameters.ntbInMaxSize)
        assertEquals(16_384L, parameters.ntbOutMaxSize)
        assertEquals(16, parameters.ntbOutMaxDatagrams)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortCdcNcmNtbParameters() {
        NcmUsbBridge.parseNtbParameters(ByteArray(27))
    }
}
