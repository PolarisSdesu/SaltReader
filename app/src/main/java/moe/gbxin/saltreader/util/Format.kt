package moe.gbxin.saltreader.util

import android.text.format.DateUtils
import java.util.Locale

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

/**
 * Relative last-read label in the system locale, rendered by Android's [DateUtils]
 * (so "5 minutes ago" / "5 分钟前" / "5分前" come from the platform automatically).
 * Returns an empty string for a never-opened book; the localized "never opened"
 * copy lives in `strings_util.xml` and is used by the UI layer
 * (`ui/components/BookText.kt`), since this util is Context-free.
 */
fun formatRelativeTime(time: Long?): String =
    if (time == null) "" else DateUtils.getRelativeTimeSpanString(time).toString()
