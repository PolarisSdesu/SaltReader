package moe.gbxin.saltreader.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `serialization round-trips SAF fields`() {
        val original = safBook()
        val restored = json.decodeFromString(ComicBook.serializer(), json.encodeToString(ComicBook.serializer(), original))

        assertEquals(original, restored)
        assertEquals(original.treeUri, restored.treeUri)
        assertEquals(original.documentPath, restored.documentPath)
        assertEquals(original.modifiedAt, restored.modifiedAt)
        assertEquals(original.currentPage, restored.currentPage)
        assertEquals(ReadingDirection.RTL, restored.direction)
        assertTrue(restored.isSaf)
        assertFalse(restored.isLegacyCopy)
    }

    @Test
    fun `SAF fields default to empty or null for minimal JSON`() {
        val minimal = """
            {"id":"m","title":"Minimal","kind":"PDF","pageCount":1,"addedAt":1}
        """.trimIndent()

        val book = json.decodeFromString(ComicBook.serializer(), minimal)

        assertEquals("", book.contentPath)
        assertNull(book.treeUri)
        assertNull(book.documentPath)
        assertNull(book.direction)
        assertEquals(0L, book.modifiedAt)
        assertEquals(0, book.currentPage)
        assertFalse(book.finished)
        assertFalse(book.isSaf)
        assertFalse(book.isLegacyCopy)
    }

    @Test
    fun `legacy JSON without treeUri still parses and keeps the old copy readable`() {
        // Written by an older app version: has contentPath + now-removed sourcePath/sourceUri.
        val legacy = """
            {
              "id": "old-1",
              "title": "Old Book",
              "kind": "ARCHIVE",
              "contentPath": "library/content/old-1.cbz",
              "pageCount": 12,
              "addedAt": 1600000000000,
              "sourcePath": "/storage/emulated/0/Comics/old-1.cbz",
              "sourceUri": "content://media/external/file/123"
            }
        """.trimIndent()

        val book = json.decodeFromString(ComicBook.serializer(), legacy)

        assertEquals("library/content/old-1.cbz", book.contentPath)
        assertNull(book.treeUri)
        assertTrue(book.isLegacyCopy)
        assertFalse(book.isSaf)
    }

    @Test
    fun `unknown future keys are ignored instead of failing the whole index`() {
        val withUnknown = """
            {"id":"x","title":"X","kind":"FOLDER","pageCount":2,"addedAt":2,"futureField":true}
        """.trimIndent()

        val book = json.decodeFromString(ComicBook.serializer(), withUnknown)

        assertEquals("x", book.id)
        assertEquals(BookKind.FOLDER, book.kind)
        assertEquals(2, book.pageCount)
    }
}
