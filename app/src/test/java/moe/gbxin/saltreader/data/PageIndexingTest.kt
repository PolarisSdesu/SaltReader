package moe.gbxin.saltreader.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the streaming ZIP indexing fallback used when a provider does
 * not support seek. [PageIndexing] only touches Android types in its SAF/PDF helpers,
 * which are not called here.
 */
class PageIndexingTest {

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `comic image names are filtered by extension and hidden files`() {
        assertTrue(PageIndexing.isComicImageName("001.jpg"))
        assertTrue(PageIndexing.isComicImageName("folder/cover.JPEG"))
        assertTrue(PageIndexing.isComicImageName("page.webp"))
        assertFalse(PageIndexing.isComicImageName("notes.txt"))
        assertFalse(PageIndexing.isComicImageName("book.cbz"))
        assertFalse(PageIndexing.isComicImageName("._hidden.jpg"))
        assertFalse(PageIndexing.isComicImageName(".hidden.jpg"))
        assertFalse(PageIndexing.isComicImageName(""))
    }

    @Test
    fun `indexZip counts image entries and captures the first image as cover`() {
        val firstImage = ByteArray(64) { 1 }
        val secondImage = ByteArray(32) { 2 }
        val bytes = zip(
            "notes.txt" to "ignored".toByteArray(),
            "pages/1.jpg" to firstImage,
            "pages/2.png" to secondImage,
        )

        val index = PageIndexing.indexZip(ByteArrayInputStream(bytes))

        assertEquals(2, index.pageCount)
        assertContentEquals(firstImage, assertNotNull(index.coverBytes))
    }

    @Test
    fun `indexZip ignores non image archives`() {
        val bytes = zip("a.txt" to "a".toByteArray(), "b.txt" to "b".toByteArray())

        val index = PageIndexing.indexZip(ByteArrayInputStream(bytes))

        assertEquals(0, index.pageCount)
        assertNull(index.coverBytes)
    }

    @Test
    fun `indexZip reports corrupted data as minus one`() {
        val broken = ByteArray(128) { 0x7F }

        val index = PageIndexing.indexZip(ByteArrayInputStream(broken))

        assertEquals(-1, index.pageCount)
        assertNull(index.coverBytes)
    }
}
