package moe.gbxin.saltreader.data

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for [EpubImagePolicy]: downscale decisions, inSampleSize math, target sizing and
 * PNG alpha detection. The actual BitmapFactory decode lives in the Android layer.
 */
class EpubImagePolicyTest {

    // --- needsDownscale ----------------------------------------------------------------------

    @Test
    fun `large byte size or oversized long side triggers downscale`() {
        assertTrue(EpubImagePolicy.needsDownscale(2 * 1024 * 1024, 2048, 1504))
        assertTrue(EpubImagePolicy.needsDownscale(700 * 1024, 800, 600))
        assertTrue(EpubImagePolicy.needsDownscale(100 * 1024, 1450, 100))
        assertFalse(EpubImagePolicy.needsDownscale(100 * 1024, 800, 600))
        assertFalse(EpubImagePolicy.needsDownscale(EpubImagePolicy.DOWNSCALE_THRESHOLD_BYTES, 1440, 100))
    }

    // --- sampleSizeFor -----------------------------------------------------------------------

    @Test
    fun `sampling keeps the decoded long side at least the target`() {
        // 2048 长边：采样 2 会掉到 1024，低于 1440，因此保持 1。
        assertEquals(1, EpubImagePolicy.sampleSizeFor(2048, 1504))
        assertEquals(1, EpubImagePolicy.sampleSizeFor(800, 600))
        assertEquals(2, EpubImagePolicy.sampleSizeFor(4000, 3000))
        assertEquals(2, EpubImagePolicy.sampleSizeFor(5000, 100))
        assertEquals(4, EpubImagePolicy.sampleSizeFor(8000, 6000))
    }

    @Test
    fun `sample size is always at least one even for invalid dimensions`() {
        assertEquals(1, EpubImagePolicy.sampleSizeFor(0, 0))
        assertEquals(1, EpubImagePolicy.sampleSizeFor(-5, 100))
    }

    // --- fitWithin ---------------------------------------------------------------------------

    @Test
    fun `fitWithin scales the long side down to the target and keeps the aspect ratio`() {
        assertEquals(EpubImagePolicy.Size(1440, 1057), EpubImagePolicy.fitWithin(2048, 1504))
        assertEquals(EpubImagePolicy.Size(720, 1440), EpubImagePolicy.fitWithin(1000, 2000))
        assertEquals(EpubImagePolicy.Size(1440, 1440), EpubImagePolicy.fitWithin(3000, 3000))
    }

    @Test
    fun `fitWithin never upscales small images`() {
        assertEquals(EpubImagePolicy.Size(800, 600), EpubImagePolicy.fitWithin(800, 600))
        assertEquals(EpubImagePolicy.Size(1440, 100), EpubImagePolicy.fitWithin(1440, 100))
    }

    // --- pngHasAlpha -------------------------------------------------------------------------

    @Test
    fun `RGBA and gray-alpha PNGs report an alpha channel`() {
        assertTrue(EpubImagePolicy.pngHasAlpha(png(colorType = 6, withTrns = false)))
        assertTrue(EpubImagePolicy.pngHasAlpha(png(colorType = 4, withTrns = false)))
    }

    @Test
    fun `RGB PNG reports alpha only when a tRNS chunk is present`() {
        assertFalse(EpubImagePolicy.pngHasAlpha(png(colorType = 2, withTrns = false)))
        assertTrue(EpubImagePolicy.pngHasAlpha(png(colorType = 2, withTrns = true)))
        assertFalse(EpubImagePolicy.pngHasAlpha(png(colorType = 0, withTrns = false)))
    }

    @Test
    fun `non-PNG and corrupt bytes never report alpha and never crash`() {
        assertFalse(EpubImagePolicy.pngHasAlpha(ByteArray(0)))
        assertFalse(EpubImagePolicy.pngHasAlpha(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)))
        assertFalse(
            EpubImagePolicy.pngHasAlpha(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x7F, 0x00),
            ),
        )
    }

    /** Minimal PNG: signature + IHDR + optional tRNS + IDAT + IEND (CRC 内容不参与检测)。 */
    private fun png(colorType: Int, withTrns: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13).also {
            it[8] = 8 // bit depth
            it[9] = colorType.toByte()
        }
        writeChunk(out, "IHDR", ihdr)
        if (withTrns) writeChunk(out, "tRNS", ByteArray(6))
        writeChunk(out, "IDAT", byteArrayOf(1, 2, 3))
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.writeInt(data.size)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.writeInt(0) // CRC 占位
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value ushr 24 and 0xFF)
        write(value ushr 16 and 0xFF)
        write(value ushr 8 and 0xFF)
        write(value and 0xFF)
    }
}
