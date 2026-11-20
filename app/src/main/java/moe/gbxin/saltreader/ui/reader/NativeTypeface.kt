package moe.gbxin.saltreader.ui.reader

/** 原生阅读器的字体解析（系统字体 / 用户字体文件）与页面字符索引换算。 */

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.roundToInt
import moe.gbxin.saltreader.data.AppSettings
import moe.gbxin.saltreader.data.ReaderFontFamily
import moe.gbxin.saltreader.data.ReaderFontRef
import moe.gbxin.saltreader.data.ReaderFontSource
import moe.gbxin.saltreader.data.ReaderFonts

internal fun nativeTypeface(context: Context, settings: AppSettings): Typeface {
    val customRef = settings.readerCustomFont
    val customFile = if (settings.readerFontFamily == ReaderFontFamily.CUSTOM) {
        customFontFile(context, customRef)
    } else {
        null
    }
    if (customFile != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val fallbackRefs = listOfNotNull(
            settings.readerFontFallback1,
            settings.readerFontFallback2,
            settings.readerFontFallback3,
        )
        val fallbackFiles = fallbackRefs.mapNotNull { customFontFile(context, it) }
        val systemFallback = fallbackRefs.firstOrNull { it.source == ReaderFontSource.SYSTEM }?.key
        return runCatching {
            val builder = Typeface.CustomFallbackBuilder(fontFamilyOf(customFile))
            fallbackFiles.forEach { builder.addCustomFallback(fontFamilyOf(it)) }
            if (fallbackFiles.isEmpty() && !systemFallback.isNullOrBlank()) {
                builder.setSystemFallback(systemFallback)
            }
            builder.build()
        }.getOrElse { Typeface.createFromFile(customFile) }
    }
    return when (settings.readerFontFamily) {
        ReaderFontFamily.SONG -> Typeface.SERIF
        ReaderFontFamily.HEI -> Typeface.SANS_SERIF
        ReaderFontFamily.CUSTOM -> {
            if (customRef?.source == ReaderFontSource.SYSTEM) {
                runCatching { Typeface.create(customRef.key, Typeface.NORMAL) }.getOrElse { Typeface.SANS_SERIF }
            } else {
                Typeface.SANS_SERIF
            }
        }
    }
}

internal fun customFontFile(context: Context, ref: ReaderFontRef?): java.io.File? {
    if (ref == null || ref.key.isBlank() || ref.source != ReaderFontSource.DOCUMENT) return null
    return ReaderFonts.ensureCached(context, ref)
}

// Callers already gate this on Build.VERSION_CODES.Q; the annotation makes that contract
// explicit because lint cannot see the guard across the function boundary.
@RequiresApi(Build.VERSION_CODES.Q)
internal fun fontFamilyOf(file: java.io.File): android.graphics.fonts.FontFamily =
    android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(file).build()).build()

internal fun nativeChapterIndex(offset: Long, prefix: LongArray): Int {
    var low = 0
    var high = prefix.size - 1
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (prefix[mid] <= offset) low = mid else high = mid - 1
    }
    return (low - 1).coerceIn(0, (prefix.size - 2).coerceAtLeast(0))
}

internal fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).roundToInt(),
    (red * 255).roundToInt(),
    (green * 255).roundToInt(),
    (blue * 255).roundToInt(),
)
