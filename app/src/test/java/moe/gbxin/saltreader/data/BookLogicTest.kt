package moe.gbxin.saltreader.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure-JVM tests for model-derived logic (format inference, progress, import aggregation). */
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

    // --- progress ---------------------------------------------------------------------------

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

    // --- format inference (UI labels are resolved in the UI layer) ---------------------------

    @Test
    fun `text books are EPUB unless the extension is MOBI`() {
        assertEquals(BookFormat.EPUB, book(kind = BookKind.TEXT, documentPath = "novel.epub").format)
        assertEquals(BookFormat.MOBI, book(kind = BookKind.TEXT, documentPath = "novel.mobi").format)
        assertEquals(BookFormat.MOBI, book(kind = BookKind.TEXT, documentPath = "novel.azw").format)
        assertEquals(BookFormat.MOBI, book(kind = BookKind.TEXT, documentPath = "novel.azw3").format)
    }

    @Test
    fun `archives are EPUB for epub files and MOBI for mobi files`() {
        assertEquals(BookFormat.EPUB, book(kind = BookKind.ARCHIVE, documentPath = "images.epub").format)
        assertEquals(BookFormat.MOBI, book(kind = BookKind.ARCHIVE, documentPath = "book.mobi").format)
        assertEquals(BookFormat.ARCHIVE, book(kind = BookKind.ARCHIVE, documentPath = "book.cbz").format)
        assertEquals(BookFormat.ARCHIVE, book(kind = BookKind.ARCHIVE, documentPath = "book.zip").format)
    }

    @Test
    fun `folder and PDF kinds map directly and ignore the extension`() {
        assertEquals(BookFormat.FOLDER, book(kind = BookKind.FOLDER, documentPath = "").format)
        assertEquals(BookFormat.FOLDER, book(kind = BookKind.FOLDER, documentPath = "some/dir").format)
        assertEquals(BookFormat.PDF, book(kind = BookKind.PDF, documentPath = "comic.pdf").format)
        assertEquals(BookFormat.PDF, book(kind = BookKind.PDF, documentPath = "weird.cbz").format)
    }

    @Test
    fun `all format enum values are reachable from the model`() {
        val formats = setOf(
            book(kind = BookKind.TEXT, documentPath = "a.epub").format,
            book(kind = BookKind.TEXT, documentPath = "a.mobi").format,
            book(kind = BookKind.ARCHIVE, documentPath = "a.cbz").format,
            book(kind = BookKind.PDF, documentPath = "a.pdf").format,
            book(kind = BookKind.FOLDER).format,
        )
        assertEquals(BookFormat.entries.toSet(), formats)
    }

    @Test
    fun `metadata field enum keeps the five combinable fields`() {
        assertEquals(
            setOf(
                LibraryMetaField.FORMAT,
                LibraryMetaField.SIZE,
                LibraryMetaField.PAGES,
                LibraryMetaField.PROGRESS,
                LibraryMetaField.LAST_READ,
            ),
            LibraryMetaField.entries.toSet(),
        )
    }

    @Test
    fun `all indexed books are SAF references`() {
        assertTrue(book().treeUri.isNotBlank())
        assertEquals("book-b.cbz", book().documentPath)
    }

    // --- import report ----------------------------------------------------------------------

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
