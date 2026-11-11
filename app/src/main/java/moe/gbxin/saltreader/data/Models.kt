package moe.gbxin.saltreader.data

import kotlinx.serialization.Serializable

@Serializable
enum class BookKind {
    /** CBZ/ZIP archive (also image-based EPUB, read directly from its own ZIP). */
    ARCHIVE,
    /** Folder of images. */
    FOLDER,
    /** PDF rendered through PdfRenderer. */
    PDF,
    /** Reflowable text book (EPUB/MOBI/AZW3), rendered by the native text reader. */
    TEXT,
}

@Serializable
enum class ReaderMode { PAGED, VERTICAL }

@Serializable
enum class ReadingDirection { LTR, RTL }

@Serializable
enum class ReaderBackground {
    /** Follow the system theme: pure white in light mode, pure black in dark mode. */
    AUTO,
    WHITE,
    INK,
    CUSTOM,
}

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LibraryFilter { ALL, READING, UNREAD }

enum class LibrarySort { RECENT, ADDED, TITLE, PROGRESS }

/**
 * A book is a reference to a document inside one of the user's SAF folder trees.
 * The app never copies the book; only derived data is cached.
 *
 * Image books (CBZ/ZIP/PDF/image folders/image-based EPUB) go through the image pager.
 * Reflowable EPUBs ([reflowable] = true) are rendered by the text reader and keep their reading
 * position as a character offset in [progressOffset]; [pageCount] is an estimate used only for
 * shelf progress display.
 */
@Serializable
data class ComicBook(
    val id: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val kind: BookKind,
    /** SAF tree URI, e.g. content://com.android.externalstorage.documents/tree/primary%3AComics */
    val treeUri: String,
    /** Document path relative to the tree root. "" = the tree root itself. */
    val documentPath: String = "",
    /**
     * 索引阶段解析出的 document URI（可空，旧索引没有这个字段）。
     * 打开书 / 页面源时先用它，省掉重新逐级枚举 SAF 目录；失效时回退到 [documentPath]。
     */
    val documentUri: String? = null,
    val pageCount: Int,
    val sizeBytes: Long = 0L,
    val addedAt: Long,
    val lastReadAt: Long? = null,
    val currentPage: Int = 0,
    val finished: Boolean = false,
    val accent: Long? = null,
    val direction: ReadingDirection? = null,
    /** Document lastModified used for incremental refresh; 0 when the provider does not report it. */
    val modifiedAt: Long = 0L,
    /** True for reflowable text books rendered by the text reader. */
    val reflowable: Boolean = false,
    /** Character offset of the reading position inside the reflowable text. */
    val progressOffset: Long = 0L,
    /** Estimated character count of a reflowable book (from spine XHTML sizes, no parsing). */
    val charCount: Long = 0L,
) {
    val progress: Float
        get() = if (pageCount <= 0) 0f else ((currentPage + if (finished) 1 else 0).toFloat() / pageCount).coerceIn(0f, 1f)

    /** File extension of the referenced document, lowercased ("" for folder books). */
    val extension: String
        get() = documentPath.substringAfterLast('.', "").lowercase()

    /** Format independent of the UI language; labels are resolved in the UI layer. */
    val format: BookFormat
        get() = when (kind) {
            BookKind.FOLDER -> BookFormat.FOLDER
            BookKind.PDF -> BookFormat.PDF
            BookKind.TEXT -> if (extension in mobiExtensions) BookFormat.MOBI else BookFormat.EPUB
            BookKind.ARCHIVE -> when (extension) {
                "epub" -> BookFormat.EPUB
                in mobiExtensions -> BookFormat.MOBI
                else -> BookFormat.ARCHIVE
            }
        }
}

/** UI-facing book format; labels live in string resources. */
enum class BookFormat { EPUB, MOBI, ARCHIVE, PDF, FOLDER }

private val mobiExtensions = setOf("mobi", "azw", "azw3")

/** Optional metadata pieces shown under a book card; freely combinable in the layout panel. */
enum class LibraryMetaField { FORMAT, SIZE, PAGES, PROGRESS, LAST_READ }

data class ImportFailure(val name: String, val reason: String)

data class ImportReport(
    val imported: List<ComicBook> = emptyList(),
    val failures: List<ImportFailure> = emptyList(),
) {
    val count: Int get() = imported.size
    val hasFailures: Boolean get() = failures.isNotEmpty()
}
