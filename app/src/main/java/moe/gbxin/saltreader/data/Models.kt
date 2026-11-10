package moe.gbxin.saltreader.data

import kotlinx.serialization.Serializable

@Serializable
enum class BookKind { ARCHIVE, FOLDER, PDF }

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
 * The app never copies the book; only derived data (covers, flattened e-book pages) is cached.
 *
 * [treeUri] is the persisted Storage Access Framework tree URI and [documentPath] is the path
 * relative to that tree root ("" means the tree root itself, i.e. an image-folder book).
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
    /**
     * False while an e-book flattening cache (EPUB/MOBI -> CBZ) is still pending. Such books are
     * indexed instantly with an estimated [pageCount]; a background worker fills in the real page
     * count, and opening the book converts it on demand. Non-e-book books are always true.
     */
    val converted: Boolean = true,
) {
    val progress: Float
        get() = if (pageCount <= 0) 0f else ((currentPage + if (finished) 1 else 0).toFloat() / pageCount).coerceIn(0f, 1f)

    val displayAuthor: String
        get() = author?.takeIf { it.isNotBlank() } ?: "本地藏书"

    val pageLabel: String
        get() = when {
            !converted -> "待转换"
            finished -> "已读完"
            currentPage <= 0 -> "尚未开始"
            else -> "读到第 ${currentPage + 1} 页"
        }

    /** Human-readable container type, used by the book actions sheet. */
    val kindLabel: String
        get() = when (kind) {
            BookKind.ARCHIVE -> "压缩包"
            BookKind.PDF -> "PDF"
            BookKind.FOLDER -> "文件夹"
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
