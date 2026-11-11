package moe.gbxin.saltreader.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the in-repo remote ZIP reader.
 *
 * [RemoteZipReader] only needs a [FileChannel] to parse the EOCD/central directory;
 * the ParcelFileDescriptor is optional (it is only used/closed for SAF). The unit
 * test therefore exercises the real parser against a temporary ZIP file without any
 * Android runtime.
 */
class RemoteZipReaderTest {

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("remote-zip-test", ".zip")
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun <T> withReader(file: File, block: (RemoteZipReader) -> T): T {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            return RemoteZipReader(null, channel).use(block)
        }
    }

    @Test
    fun `central directory exposes entry names methods and sizes`() {
        val text = "hello".toByteArray()
        val file = zip("META-INF/container.xml" to text, "images/1.jpg" to ByteArray(10) { it.toByte() })
        try {
            withReader(file) { reader ->
                val entries = reader.entries()
                assertEquals(listOf("META-INF/container.xml", "images/1.jpg"), entries.map { it.name })
                val container = entries.first()
                assertEquals(8, container.method) // DEFLATE (ZipOutputStream default)
                assertEquals(text.size.toLong(), container.uncompressedSize)
                assertTrue(!container.encrypted)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `entry views expose zero-copy ranges matching the payload`() {
        val storedPayload = ByteArray(1000) { (it * 7).toByte() }
        val deflatedPayload = "page bytes".repeat(300).toByteArray()
        val file = File.createTempFile("remote-zip-view", ".zip")
        ZipOutputStream(file.outputStream()).use { out ->
            val stored = ZipEntry("stored/page.jpg").apply {
                method = ZipEntry.STORED
                size = storedPayload.size.toLong()
                compressedSize = storedPayload.size.toLong()
                crc = CRC32().apply { update(storedPayload) }.value
            }
            out.putNextEntry(stored)
            out.write(storedPayload)
            out.closeEntry()
            out.putNextEntry(ZipEntry("deflated/page.xhtml"))
            out.write(deflatedPayload)
            out.closeEntry()
        }
        try {
            withReader(file) { reader ->
                val storedEntry = assertNotNull(reader.entry("stored/page.jpg"))
                val stored = assertNotNull(reader.readEntryView(storedEntry, 1_000_000L))
                // STORED 直接返回合并缓冲区里的区间视图：offset 在本地头之后，不复制数据。
                assertTrue(stored.offset > 0)
                assertContentEquals(storedPayload, stored.toByteArray())

                val deflatedEntry = assertNotNull(reader.entry("deflated/page.xhtml"))
                val deflated = assertNotNull(reader.readEntryView(deflatedEntry, 1_000_000L))
                assertContentEquals(deflatedPayload, deflated.toByteArray())
                // 兼容 API 与视图 API 语义一致。
                assertContentEquals(deflatedPayload, assertNotNull(reader.readEntry(deflatedEntry, 1_000_000L)))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `readEntry inflates a deflated entry and rejects unknown or oversized reads`() {
        val payload = "chapter one".repeat(100).toByteArray()
        val file = zip("OEBPS/chapter1.xhtml" to payload)
        try {
            withReader(file) { reader ->
                assertContentEquals(payload, assertNotNull(reader.readEntry("OEBPS/chapter1.xhtml", 1_000_000L)))
                assertNull(reader.readEntry("missing.xhtml", 1_000_000L))
                assertNull(reader.readEntry("OEBPS/chapter1.xhtml", 10L))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `image entries are filtered by extension and sorted naturally`() {
        val bytes = byteArrayOf(1, 2, 3)
        val file = zip(
            "pages/10.jpg" to bytes,
            "pages/2.jpg" to bytes,
            "cover.png" to bytes,
            "notes.txt" to "ignored".toByteArray(),
            "pages/.__hidden.jpg" to bytes,
        )
        try {
            withReader(file) { reader ->
                assertEquals(
                    listOf("cover.png", "pages/2.jpg", "pages/10.jpg"),
                    reader.imageEntries().map { it.name },
                )
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `stored entries are read without inflating`() {
        val payload = "stored payload".toByteArray()
        val crc = java.util.zip.CRC32().apply { update(payload) }
        val file = File.createTempFile("remote-zip-stored", ".zip")
        ZipOutputStream(file.outputStream()).use { out ->
            val entry = ZipEntry("stored.bin").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                this.crc = crc.value
            }
            out.putNextEntry(entry)
            out.write(payload)
            out.closeEntry()
        }
        try {
            withReader(file) { reader ->
                val entry = reader.entries().single()
                assertEquals(0, entry.method) // STORED
                assertContentEquals(payload, assertNotNull(reader.readEntry("stored.bin", 1024L)))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `non zip data is rejected`() {
        val file = File.createTempFile("remote-zip-broken", ".zip")
        file.writeBytes(ByteArray(64) { 0x41 })
        try {
            val failure = runCatching {
                withReader(file) { reader -> reader.entries() }
            }
            assertTrue(failure.isFailure, "garbage bytes must not parse as a ZIP")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `entry index resolves names and reads through the entry overload`() {
        val payload = "indexed ".repeat(1000).toByteArray()
        val file = zip("a/one.txt" to payload, "a/two.txt" to ByteArray(0))
        try {
            withReader(file) { reader ->
                assertNull(reader.entry("missing.txt"))
                val entry = assertNotNull(reader.entry("a/one.txt"))
                assertEquals("a/one.txt", entry.name)
                // 索引与 entries() 返回同一条记录，缓存不会造成两份不一致的数据。
                assertEquals(reader.entries().first { it.name == entry.name }, entry)
                assertContentEquals(payload, assertNotNull(reader.readEntry(entry, 1_000_000L)))
                assertNull(reader.readEntry(entry, 10L))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `large deflated entry round trips through the preallocated inflate path`() {
        // 比 64KB 缓冲更大，确保直解路径要循环多次才填满预分配数组。
        val payload = ByteArray(300_000) { index -> (index * 31 % 251).toByte() }
        val file = zip("large/page.bin" to payload)
        try {
            withReader(file) { reader ->
                val entry = assertNotNull(reader.entry("large/page.bin"))
                assertContentEquals(payload, assertNotNull(reader.readEntry(entry, 1_000_000L)))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `wrong declared size falls back to the streaming inflate path`() {
        val payload = "fallback ".repeat(5000).toByteArray()
        val file = zip("wrong.txt" to payload)
        try {
            // 篡改 central directory 的 uncompressed size，直解校验必然失败，只能回退流式实现。
            patchCentralDirectoryUncompressedSize(file, payload.size - 10)
            withReader(file) { reader ->
                assertContentEquals(payload, assertNotNull(reader.readEntry("wrong.txt", 1_000_000L)))
            }
        } finally {
            file.delete()
        }
    }

    private fun patchCentralDirectoryUncompressedSize(file: File, value: Int) {
        val bytes = file.readBytes()
        for (index in 0..bytes.size - 46) {
            val isCentralHeader = bytes[index] == 0x50.toByte() &&
                bytes[index + 1] == 0x4B.toByte() &&
                bytes[index + 2] == 0x01.toByte() &&
                bytes[index + 3] == 0x02.toByte()
            if (!isCentralHeader) continue
            for (byteOffset in 0..3) {
                bytes[index + 24 + byteOffset] = ((value shr (8 * byteOffset)) and 0xFF).toByte()
            }
            break
        }
        file.writeBytes(bytes)
    }
}

/**
 * 采样纯逻辑单测：[PageImageSampling] 不碰 Android API，可直接在 JVM 上验证。
 * 长条漫是这次要修的主要回归：旧实现允许单张位图超过 64MB。
 */
class PageImageSamplingTest {

    private fun sampledWidth(width: Int, sample: Int): Long = ((width + sample - 1) / sample).toLong()
    private fun sampledHeight(height: Int, sample: Int): Long = ((height + sample - 1) / sample).toLong()

    private fun assertWithinCaps(
        width: Int,
        height: Int,
        targetWidth: Int,
        maxBytes: Long = 12L * 1024L * 1024L,
        maxSide: Int = 8192,
    ) {
        val sample = PageImageSampling.sampleSize(width, height, targetWidth, maxBytes, maxSide)
        val sampledWidth = sampledWidth(width, sample)
        val sampledHeight = sampledHeight(height, sample)
        assertTrue(sampledWidth * sampledHeight * 4L <= maxBytes, "decoded bitmap must fit in $maxBytes bytes")
        assertTrue(maxOf(sampledWidth, sampledHeight) <= maxSide, "decoded bitmap side must be <= $maxSide")
    }

    @Test
    fun `portrait page is downsampled to the target width`() {
        assertEquals(2, PageImageSampling.sampleSize(2000, 3000, 1080))
        assertWithinCaps(2000, 3000, 1080)
    }

    @Test
    fun `small page is not upscaled or downsampled`() {
        assertEquals(1, PageImageSampling.sampleSize(500, 400, 1080))
        assertEquals(1, PageImageSampling.sampleSize(1, 1, 1080))
    }

    @Test
    fun `tiny or invalid dimensions are treated as sample one`() {
        assertEquals(1, PageImageSampling.sampleSize(0, 100, 1080))
        assertEquals(1, PageImageSampling.sampleSize(-10, -10, 1080))
    }

    @Test
    fun `long strip is bounded by both the byte budget and the max side`() {
        // 800x40000 旧逻辑会解出约 128MB 位图（若 targetWidth 1080 时 sample=1），
        // 新逻辑必须同时压到 12MB 以内且长边 <= 8192。
        val sample = PageImageSampling.sampleSize(800, 40000, 1080)
        assertEquals(8, sample)
        assertWithinCaps(800, 40000, 1080)
    }

    @Test
    fun `very long thin strip keeps its long side within the limit`() {
        assertWithinCaps(100, 100_000, 1080)
        assertWithinCaps(50, 200_000, 480)
    }

    @Test
    fun `custom budgets are honoured`() {
        val sample = PageImageSampling.sampleSize(4000, 4000, 4000, maxBytes = 1024L * 1024L, maxSide = 1024)
        val width = sampledWidth(4000, sample)
        val height = sampledHeight(4000, sample)
        assertTrue(width * height * 4L <= 1024L * 1024L)
        assertTrue(maxOf(width, height) <= 1024L)
    }
}
