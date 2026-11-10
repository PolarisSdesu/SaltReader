package moe.gbxin.saltreader.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure-JVM tests for model-derived logic (progress, labels, import aggregation). */
class BookLogicTest {

    private fun book(
        id: String = "b",
        pageCount: Int = 10,
        currentPage: Int = 0,
        finished: Boolean = false,
        contentPath: String = "",
        treeUri: String? = null,
        author: String? = null,
    ) = ComicBook(
        id = id,
        title = "Book $id",
        author = author,
        kind = BookKind.ARCHIVE,
        contentPath = contentPath,
        treeUri = treeUri,
        pageCount = pageCount,
        addedAt = 0L,
        currentPage = currentPage,
        finished = finished,
    )

    @Test
    fun `progress is zero before the first page`() {
        assertEquals(0f, book(pageCount = 10, currentPage = 0).progress, 1e-6f)
    }

    @Test
    fun `progress is current page over page count`() {
        assertEquals(0.5f, book(pageCount = 10, currentPage = 5).progress, 1e-6f)
    }

    @Test
    fun `finished book reports full progress`() {
        assertEquals(1f, book(pageCount = 10, currentPage = 9, finished = true).progress, 1e-6f)
    }

    @Test
    fun `progress is clamped and safe for empty books`() {
        assertEquals(1f, book(pageCount = 10, currentPage = 99).progress, 1e-6f)
        assertEquals(0f, book(pageCount = 0, currentPage = 0).progress, 1e-6f)
    }

    @Test
    fun `saf and legacy copy flags are mutually driven by the source fields`() {
        assertTrue(book(treeUri = "content://tree/1").isSaf)
        assertFalse(book(treeUri = "content://tree/1").isLegacyCopy)

        assertTrue(book(contentPath = "library/content/a.cbz").isLegacyCopy)
        assertFalse(book(contentPath = "library/content/a.cbz").isSaf)

        val safAndLegacy = book(contentPath = "library/content/a.cbz", treeUri = "content://tree/1")
        assertTrue(safAndLegacy.isSaf)
        assertFalse(safAndLegacy.isLegacyCopy)
    }

    @Test
    fun `display author falls back when blank or missing`() {
        assertEquals("本地藏书", book(author = null).displayAuthor)
        assertEquals("本地藏书", book(author = "  ").displayAuthor)
        assertEquals("Author", book(author = "Author").displayAuthor)
    }

    @Test
    fun `import report aggregates imported books and failures`() {
        val report = ImportReport(
            imported = listOf(book(id = "a"), book(id = "b")),
            failures = listOf(ImportFailure("broken.cbz", "无法解析")),
        )

        assertEquals(2, report.count)
        assertTrue(report.hasFailures)
        assertEquals(1, report.failures.size)
    }

    @Test
    fun `empty import report has no failures`() {
        val report = ImportReport()

        assertEquals(0, report.count)
        assertFalse(report.hasFailures)
        assertTrue(report.imported.isEmpty())
        assertTrue(report.failures.isEmpty())
    }
}
