package moe.gbxin.saltreader.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the [ComicBook] wire format that is persisted in books.json.
 * These run without Robolectric or any Android framework class.
 */
class ComicBookSerializationTest {

    // Mirror the Json configuration used by LibraryRepository.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun safBook() = ComicBook(
        id = "saf-1",
        title = "SAF Book",
        author = "Author",
        kind = BookKind.ARCHIVE,
        treeUri = "content://com.android.externalstorage.documents/tree/primary%3AComics",
        documentPath = "Series/Vol 1/book.cbz",
        pageCount = 42,
        sizeBytes = 12_345L,
        addedAt = 1_700_000_000_000L,
        modifiedAt = 1_700_000_100_000L,
        currentPage = 3,
        direction = ReadingDirection.RTL,
        accent = 0xFF3366CC,
    )

    @Test
    fun `serialization round-trips SAF and text fields`() {
        val original = safBook()
        val restored = json.decodeFromString(ComicBook.serializer(), json.encodeToString(ComicBook.serializer(), original))

        assertEquals(original, restored)
        assertEquals(original.treeUri, restored.treeUri)
        assertEquals(original.documentPath, restored.documentPath)
        assertEquals(original.modifiedAt, restored.modifiedAt)
        assertEquals(original.currentPage, restored.currentPage)
        assertEquals(ReadingDirection.RTL, restored.direction)
        assertTrue(restored.treeUri.isNotBlank())
    }

    @Test
    fun `optional SAF and reflow fields take their defaults`() {
        val minimal = """
            {"id":"m","title":"Minimal","kind":"PDF","treeUri":"content://tree/1","pageCount":1,"addedAt":1}
        """.trimIndent()

        val book = json.decodeFromString(ComicBook.serializer(), minimal)

        assertEquals("", book.documentPath)
        assertNull(book.direction)
        assertEquals(0L, book.modifiedAt)
        assertEquals(0, book.currentPage)
        assertFalse(book.finished)
        assertFalse(book.reflowable)
        assertEquals(0L, book.progressOffset)
        assertEquals(0L, book.charCount)
        assertEquals("content://tree/1", book.treeUri)
    }

    @Test
    fun `reflowable text book progress fields round-trip`() {
        val original = ComicBook(
            id = "text-1",
            title = "Text EPUB",
            kind = BookKind.TEXT,
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ABooks",
            documentPath = "novel.epub",
            pageCount = 20,
            sizeBytes = 900_000L,
            addedAt = 1_700_000_000_000L,
            currentPage = 10,
            reflowable = true,
            progressOffset = 15_000L,
            charCount = 30_000L,
        )

        val restored = json.decodeFromString(ComicBook.serializer(), json.encodeToString(ComicBook.serializer(), original))

        assertEquals(original, restored)
        assertEquals(BookKind.TEXT, restored.kind)
        assertTrue(restored.reflowable)
        assertEquals(15_000L, restored.progressOffset)
        assertEquals(30_000L, restored.charCount)
        assertEquals(0.5f, restored.progress, 1e-6f)
    }

    @Test
    fun `JSON without a required treeUri is rejected as invalid data`() {
        val missingTreeUri = """
            {"id":"bad","title":"Bad","kind":"ARCHIVE","pageCount":1,"addedAt":1}
        """.trimIndent()

        assertFailsWith<SerializationException> {
            json.decodeFromString(ComicBook.serializer(), missingTreeUri)
        }
    }

    @Test
    fun `unknown future keys are ignored instead of failing the whole index`() {
        val withUnknown = """
            {"id":"x","title":"X","kind":"FOLDER","treeUri":"content://tree/2","pageCount":2,"addedAt":2,"futureField":true}
        """.trimIndent()

        val book = json.decodeFromString(ComicBook.serializer(), withUnknown)

        assertEquals("x", book.id)
        assertEquals(BookKind.FOLDER, book.kind)
        assertEquals(2, book.pageCount)
        assertEquals("", book.documentPath)
    }

}
