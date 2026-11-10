package moe.gbxin.saltreader.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
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
}
