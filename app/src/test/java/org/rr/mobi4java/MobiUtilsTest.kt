package org.rr.mobi4java

import java.util.Arrays
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pure-JVM tests for [MobiUtils.removeRandomBytes]. */
class MobiUtilsTest {

    private val searchBytes =
        byteArrayOf(0x00, 0x14, 0x15, 0x19, 0x1c, 0x1d, 0x12, 0x13, 0x08, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)

    @Test
    fun `keeps all bytes when there is nothing to remove`() {
        val input = byteArrayOf(0x41, 0x42, 0x7f, -1, 0x14, 0x19)
        assertContentEquals(byteArrayOf(0x41, 0x42, 0x7f, -1, 0x14, 0x19), MobiUtils.removeRandomBytes(input))
    }

    @Test
    fun `removes the bytes which the legacy search dropped`() {
        val input = byteArrayOf(0x41, 0x00, 0x42, 0x13, 0x43, 0x00)
        assertContentEquals(byteArrayOf(0x41, 0x42, 0x43), MobiUtils.removeRandomBytes(input))
    }

    @Test
    fun `empty array stays empty`() {
        val input = ByteArray(0)
        val result = MobiUtils.removeRandomBytes(input)
        assertEquals(0, result.size)
        assertSame(input, result)
    }

    @Test
    fun `returns the same instance when no byte is removed`() {
        val input = byteArrayOf(0x0a, 0x0d, 0x20, 0x61, 0x14)
        assertSame(input, MobiUtils.removeRandomBytes(input))
    }

    @Test
    fun `removes everything when all bytes are filtered`() {
        val input = byteArrayOf(0x00, 0x13, 0x00, 0x13)
        val result = MobiUtils.removeRandomBytes(input)
        assertEquals(0, result.size)
        assertTrue(result !== input)
    }

    /**
     * The legacy implementation searched an unsorted array with [Arrays.binarySearch], so it did not drop every byte listed in that array.
     * This test locks the decision for every possible byte value against the legacy algorithm, so the lookup table cannot silently change
     * which bytes are removed.
     */
    @Test
    fun `single byte decision matches the legacy binary search for all 256 values`() {
        for (i in 0..255) {
            val value = i.toByte()
            val legacyRemoves = Arrays.binarySearch(searchBytes, value) >= 0
            val result = MobiUtils.removeRandomBytes(byteArrayOf(value))
            if (legacyRemoves) {
                assertEquals(0, result.size, "byte 0x%02x should be removed".format(i))
            } else {
                assertContentEquals(byteArrayOf(value), result, "byte 0x%02x should be kept".format(i))
            }
        }
    }
}
