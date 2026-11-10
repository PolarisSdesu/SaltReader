package moe.gbxin.saltreader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt
import moe.gbxin.saltreader.R
import moe.gbxin.saltreader.data.BookFormat
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.LibraryFilter
import moe.gbxin.saltreader.data.LibraryLayout
import moe.gbxin.saltreader.data.LibraryMetaField
import moe.gbxin.saltreader.data.LibrarySort
import moe.gbxin.saltreader.util.formatBytes
import moe.gbxin.saltreader.util.formatRelativeTime

/**
 * Localized labels for books and library metadata. The data model stays language neutral, so all
 * user-facing text is resolved here from string resources.
 */

@Composable
fun bookFormatLabel(book: ComicBook): String = when (book.format) {
    BookFormat.EPUB -> stringResource(R.string.common_format_epub)
    BookFormat.MOBI -> stringResource(R.string.common_format_mobi)
    BookFormat.ARCHIVE -> stringResource(R.string.common_format_archive)
    BookFormat.PDF -> stringResource(R.string.common_format_pdf)
    BookFormat.FOLDER -> stringResource(R.string.common_format_folder)
}

@Composable
fun bookAuthorLabel(book: ComicBook): String =
    book.author?.takeIf { it.isNotBlank() } ?: stringResource(R.string.common_local_library)

@Composable
fun bookProgressLabel(book: ComicBook): String = when {
    book.finished -> stringResource(R.string.common_finished)
    book.reflowable && book.progressOffset > 0L ->
        stringResource(R.string.common_progress_percent, (book.progress * 100).roundToInt())
    book.currentPage <= 0 -> stringResource(R.string.common_not_started)
    else -> stringResource(R.string.common_read_to_page, book.currentPage + 1)
}

@Composable
fun libraryFilterLabel(filter: LibraryFilter): String = when (filter) {
    LibraryFilter.ALL -> stringResource(R.string.common_filter_all)
    LibraryFilter.READING -> stringResource(R.string.common_filter_reading)
    LibraryFilter.UNREAD -> stringResource(R.string.common_filter_unread)
}

@Composable
fun librarySortLabel(sort: LibrarySort): String = when (sort) {
    LibrarySort.RECENT -> stringResource(R.string.common_sort_recent)
    LibrarySort.ADDED -> stringResource(R.string.common_sort_added)
    LibrarySort.TITLE -> stringResource(R.string.common_sort_title)
    LibrarySort.PROGRESS -> stringResource(R.string.common_sort_progress)
}

@Composable
fun libraryLayoutLabel(layout: LibraryLayout): String = when (layout) {
    LibraryLayout.COMPACT -> stringResource(R.string.common_layout_compact)
    LibraryLayout.COMFORTABLE -> stringResource(R.string.common_layout_comfortable)
    LibraryLayout.COVER_ONLY -> stringResource(R.string.common_layout_cover_only)
    LibraryLayout.LIST -> stringResource(R.string.common_layout_list)
}

@Composable
fun metaFieldLabel(field: LibraryMetaField): String = when (field) {
    LibraryMetaField.FORMAT -> stringResource(R.string.common_meta_format)
    LibraryMetaField.SIZE -> stringResource(R.string.common_meta_size)
    LibraryMetaField.PAGES -> stringResource(R.string.common_meta_pages)
    LibraryMetaField.PROGRESS -> stringResource(R.string.common_meta_progress)
    LibraryMetaField.LAST_READ -> stringResource(R.string.common_meta_last_read)
}

@Composable
fun bookMetaLabel(book: ComicBook, fields: Set<LibraryMetaField>): String {
    if (fields.isEmpty()) return ""
    val parts = ArrayList<String>(fields.size)
    for (field in LibraryMetaField.entries) {
        if (field !in fields) continue
        when (field) {
            LibraryMetaField.FORMAT -> parts += bookFormatLabel(book)
            LibraryMetaField.SIZE -> parts += formatBytes(book.sizeBytes)
            LibraryMetaField.PAGES -> if (!book.reflowable) {
                parts += pluralStringResource(R.plurals.common_pages, book.pageCount, book.pageCount)
            }
            LibraryMetaField.PROGRESS -> parts += bookProgressLabel(book)
            LibraryMetaField.LAST_READ -> parts += if (book.lastReadAt == null) {
                stringResource(R.string.util_never_opened)
            } else {
                formatRelativeTime(book.lastReadAt)
            }
        }
    }
    return parts.joinToString(" · ")
}
