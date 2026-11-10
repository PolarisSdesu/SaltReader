package moe.gbxin.saltreader.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure-JVM tests for model-derived logic (progress, labels, import aggregation). */
class BookLogicTest {

    private fun book(
        id: String = "b",
        kind: BookKind = BookKind.ARCHIVE,
        pageCount: Int = 10,
        currentPage: Int = 0,
        finished: Boolean = false,
        reflowable: Boolean = false,
        progressOffset: Long = 0L,
        charCount: Long = 0L,
        documentPath: String = "book-$id.cbz",
        author: String? = null,
    ) = ComicBook(
        id = id,
        title = "Book $id",
        author = author,
        kind = kind,
        treeUri = "content://com.android.externalstorage.documents/tree/primary%3AComics",
        documentPath = documentPath,
        pageCount = pageCount,
        addedAt = 0L,
        currentPage = currentPage,
        finished = finished,
        reflowable = reflowable,
        progressOffset = progressOffset,
        charCount = charCount,
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
    fun `page label reflects the reading state of an image book`() {
        assertEquals("尚未开始", book(currentPage = 0).pageLabel)
        assertEquals("读到第 3 页", book(currentPage = 2).pageLabel)
        assertEquals("已读完", book(finished = true).pageLabel)
    }

    @Test
    fun `reflowable EPUB page label is a percentage instead of a page number`() {
        val textBook = book(
            kind = BookKind.TEXT,
            reflowable = true,
            pageCount = 4,
            currentPage = 1,
            progressOffset = 250L,
            charCount = 1000L,
        )

        assertEquals("读至 25%", textBook.pageLabel)
        assertEquals(0.25f, textBook.progress, 1e-6f)
    }

    @Test
    fun `reflowable EPUB before any progress shows the normal start label`() {
        val started = book(kind = BookKind.TEXT, reflowable = true, pageCount = 4, currentPage = 0, progressOffset = 0L)

        assertEquals("尚未开始", started.pageLabel)
    }

    @Test
    fun `reflowable EPUB finished label wins over the percentage`() {
        val finished = book(
            kind = BookKind.TEXT,
            reflowable = true,
            pageCount = 4,
            currentPage = 3,
            finished = true,
            progressOffset = 990L,
            charCount = 1000L,
        )

        assertEquals("已读完", finished.pageLabel)
    }

    @Test
    fun `display author falls back when blank or missing`() {
        assertEquals("本地藏书", book(author = null).displayAuthor)
        assertEquals("本地藏书", book(author = "  ").displayAuthor)
        assertEquals("Author", book(author = "Author").displayAuthor)
    }

    @Test
    fun `all indexed books are SAF references and text books have their own label`() {
        assertTrue(book().treeUri.isNotBlank())
        assertEquals("book-b.cbz", book().documentPath)
        assertEquals("EPUB 电子书", book(kind = BookKind.TEXT, documentPath = "novel.epub").kindLabel)
        assertEquals("MOBI 电子书", book(kind = BookKind.TEXT, documentPath = "novel.mobi").kindLabel)
        assertEquals("压缩包", book(kind = BookKind.ARCHIVE, documentPath = "book.cbz").kindLabel)
        assertEquals("EPUB 电子书", book(kind = BookKind.ARCHIVE, documentPath = "images.epub").kindLabel)
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
