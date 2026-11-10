package moe.gbxin.saltreader.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the EPUB/MOBI spine classification and size-based estimates.
 * [EpubSpineClassifier] has no Android/filesystem dependency by design.
 */
class EpubSpineClassifierTest {

    // --- image vs reflowable classification -------------------------------------------------

    @Test
    fun `image ratio at or above 80 percent is an image based EPUB`() {
        assertTrue(EpubSpineClassifier.isImageBased(imagePages = 5, spinePages = 5))
        assertTrue(EpubSpineClassifier.isImageBased(imagePages = 9, spinePages = 10))
        assertTrue(EpubSpineClassifier.isImageBased(imagePages = 4, spinePages = 5)) // exactly 0.8
        assertTrue(EpubSpineClassifier.isImageBased(imagePages = 80, spinePages = 100))
    }

    @Test
    fun `image ratio below 80 percent is a reflowable EPUB`() {
        assertFalse(EpubSpineClassifier.isImageBased(imagePages = 3, spinePages = 4)) // 0.75
        assertFalse(EpubSpineClassifier.isImageBased(imagePages = 7, spinePages = 10))
        assertFalse(EpubSpineClassifier.isImageBased(imagePages = 1, spinePages = 2))
    }

    @Test
    fun `empty spine or no images is never image based`() {
        assertFalse(EpubSpineClassifier.isImageBased(imagePages = 0, spinePages = 10))
        assertFalse(EpubSpineClassifier.isImageBased(imagePages = 5, spinePages = 0))
        assertFalse(EpubSpineClassifier.isImageBased(imagePages = 0, spinePages = 0))
    }

    // --- charCount estimation (EPUB spine bytes and MOBI/AZW3 file bytes) -------------------

    @Test
    fun `char count is 60 percent of the uncompressed content size`() {
        assertEquals(60L, EpubSpineClassifier.estimateCharCount(100L))
        assertEquals(600L, EpubSpineClassifier.estimateCharCount(1_000L))
        assertEquals(600_000L, EpubSpineClassifier.estimateCharCount(1_000_000L))
    }

    @Test
    fun `char count never drops below one`() {
        assertEquals(1L, EpubSpineClassifier.estimateCharCount(0L))
        assertEquals(1L, EpubSpineClassifier.estimateCharCount(1L)) // 0.6 -> 0 -> clamped to 1
        assertEquals(1L, EpubSpineClassifier.estimateCharCount(2L)) // 1.2 -> 1
    }

    @Test
    fun `MOBI file size is estimated with the same 60 percent rule`() {
        // A 2 MB MOBI/AZW3 is estimated as 1.2M characters -> 800 estimated pages.
        val charCount = EpubSpineClassifier.estimateCharCount(2L * 1024L * 1024L)
        assertEquals(1_258_291L, charCount)
        assertEquals(838, EpubSpineClassifier.estimatePageCount(charCount))
    }

    // --- pageCount estimation ---------------------------------------------------------------

    @Test
    fun `page count is charCount divided by 1500 rounded down with a minimum of one`() {
        assertEquals(1, EpubSpineClassifier.estimatePageCount(0L))
        assertEquals(1, EpubSpineClassifier.estimatePageCount(1L))
        assertEquals(1, EpubSpineClassifier.estimatePageCount(1_499L))
        assertEquals(1, EpubSpineClassifier.estimatePageCount(1_500L))
        assertEquals(1, EpubSpineClassifier.estimatePageCount(1_501L))
        assertEquals(2, EpubSpineClassifier.estimatePageCount(3_000L))
        assertEquals(3, EpubSpineClassifier.estimatePageCount(4_500L))
        assertEquals(20, EpubSpineClassifier.estimatePageCount(30_000L))
    }

    // --- char offset -> shelf page/progress conversion --------------------------------------
    // NOTE: the production conversion currently lives inline in
    // LibraryRepository.updateTextProgress/updateTextStats (data layer). This helper mirrors
    // that documented formula so the contract stays covered; if it is later extracted into
    // EpubSpineClassifier, this test should call the production function instead.
    private fun pageForOffset(offset: Long, charCount: Long, pageCount: Int): Int =
        ((offset.toDouble() / charCount) * pageCount).toInt().coerceIn(0, maxOf(0, pageCount - 1))

    @Test
    fun `char offset maps to the estimated shelf page and percentage`() {
        val charCount = 30_000L
        val pageCount = EpubSpineClassifier.estimatePageCount(charCount) // 20 pages
        assertEquals(20, pageCount)

        assertEquals(0, pageForOffset(0L, charCount, pageCount))
        assertEquals(10, pageForOffset(charCount / 2, charCount, pageCount))
        assertEquals(19, pageForOffset(charCount - 1, charCount, pageCount))

        val book = ComicBook(
            id = "text",
            title = "Text Book",
            kind = BookKind.TEXT,
            treeUri = "content://tree/1",
            documentPath = "novel.epub",
            pageCount = pageCount,
            addedAt = 0L,
            currentPage = pageForOffset(charCount / 2, charCount, pageCount),
            reflowable = true,
            progressOffset = charCount / 2,
            charCount = charCount,
        )
        assertEquals("读至 50%", book.pageLabel)
    }
}
