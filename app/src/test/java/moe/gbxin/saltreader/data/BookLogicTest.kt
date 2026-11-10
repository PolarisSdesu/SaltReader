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
        converted: Boolean = true,
        author: String? = null,
    ) = ComicBook(
        id = id,
        title = "Book $id",
        author = author,
        kind = BookKind.ARCHIVE,
        treeUri = "content://com.android.externalstorage.documents/tree/primary%3AComics",
        documentPath = "book-$id.cbz",
        pageCount = pageCount,
        addedAt = 0L,
        currentPage = currentPage,
        finished = finished,
        converted = converted,
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
    fun `page label reports the pending conversion of an e-book`() {
        assertEquals("待转换", book(converted = false).pageLabel)
        // Conversion state wins over the reading state.
        assertEquals("待转换", book(converted = false, currentPage = 3).pageLabel)
        assertEquals("待转换", book(converted = false, finished = true).pageLabel)
    }

    @Test
    fun `page label reflects the reading state of a converted book`() {
        assertEquals("尚未开始", book(converted = true, currentPage = 0).pageLabel)
        assertEquals("读到第 3 页", book(converted = true, currentPage = 2).pageLabel)
        assertEquals("已读完", book(converted = true, finished = true).pageLabel)
    }

    @Test
    fun `display author falls back when blank or missing`() {
        assertEquals("本地藏书", book(author = null).displayAuthor)
        assertEquals("本地藏书", book(author = "  ").displayAuthor)
        assertEquals("Author", book(author = "Author").displayAuthor)
    }

    @Test
    fun `all indexed books are SAF references`() {
        assertTrue(book().treeUri.isNotBlank())
        assertEquals("book-b.cbz", book().documentPath)
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
