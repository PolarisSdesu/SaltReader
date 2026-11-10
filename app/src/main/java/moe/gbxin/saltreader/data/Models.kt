package moe.gbxin.saltreader.data

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

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
enum class ReaderBackground { WHITE, INK, CUSTOM }

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LibraryFilter(val label: String) { ALL("全部"), READING("在读"), UNREAD("未读") }

enum class LibrarySort(val label: String) { RECENT("最近阅读"), ADDED("最近加入"), TITLE("书名"), PROGRESS("阅读进度") }

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

    val displayAuthor: String
        get() = author?.takeIf { it.isNotBlank() } ?: "本地藏书"

    val pageLabel: String
        get() = when {
            finished -> "已读完"
            reflowable && progressOffset > 0L -> "读至 ${(progress * 100).roundToInt()}%"
            currentPage <= 0 -> "尚未开始"
            else -> "读到第 ${currentPage + 1} 页"
        }

    /** File extension of the referenced document, lowercased ("" for folder books). */
    val extension: String
        get() = documentPath.substringAfterLast('.', "").lowercase()

    /** Human-readable format label, used by the book cards and the actions sheet. */
    val kindLabel: String
        get() = when (kind) {
            BookKind.FOLDER -> "文件夹"
            BookKind.PDF -> "PDF"
            BookKind.TEXT -> when (extension) {
                "epub" -> "EPUB 电子书"
                "mobi", "azw", "azw3" -> "MOBI 电子书"
                else -> "电子书"
            }
            BookKind.ARCHIVE -> when (extension) {
                "epub" -> "EPUB 电子书"
                "mobi", "azw", "azw3" -> "MOBI 电子书"
                else -> "压缩包"
            }
        }
}

data class ImportFailure(val name: String, val reason: String)

data class ImportReport(
    val imported: List<ComicBook> = emptyList(),
    val failures: List<ImportFailure> = emptyList(),
) {
    val count: Int get() = imported.size
    val hasFailures: Boolean get() = failures.isNotEmpty()
}
