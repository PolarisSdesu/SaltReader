package moe.gbxin.saltreader.ui.reader

/** 原生阅读器的数据模型：Rust 输出解码、每页行数据、图片引用与行内不可见字符判定。 */

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Base64
import androidx.compose.foundation.layout.height
import java.util.concurrent.atomic.AtomicInteger
import moe.gbxin.saltreader.data.EbookChapter

internal data class NativeLine(
    val text: String,
    val sizeRatio: Float = 1f,
    val lineHeightRatio: Float = 1.8f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val mono: Boolean = false,
    val align: Int = 0,
    val leftIndentEm: Float = 0f,
    val tableCol: Int = 0,
    val tableCols: Int = 0,
    val tableRowStart: Boolean = false,
    val tableLastRow: Boolean = false,
    val tableRowEnd: Boolean = false,
    val imageId: Int = -1,
    val gapEm: Float = 0f,
)

/** 内联图片引用：按需从章节 HTML 里重新取出 data URI 解码，避免全书图片字节常驻。 */
internal data class ImageRef(val chapterIndex: Int, val dataUriIndex: Int)

internal val NATIVE_DATA_URI =
    Regex("data:image/([A-Za-z0-9.+-]+);base64,([A-Za-z0-9+/=\\r\\n]+)", RegexOption.IGNORE_CASE)

internal data class NativePageSet(
    val pages: List<List<NativeLine>>,
    val imageRefs: List<ImageRef> = emptyList(),
    val imageAspects: List<Float> = emptyList(),
    /** 图片原始宽度（物理 px）；0 表示不限（按版面宽度）。 */
    val imageWidths: List<Float> = emptyList(),
    val contentLeftPx: Float = 0f,
    val contentWidthPx: Float = 1f,
    val topPadPx: Float = 0f,
    val bottomPadPx: Float = 0f,
    val contentHeightPx: Float = 1f,
)

internal val nativeParserToken = AtomicInteger(0)

internal fun isInvisibleNativeChar(ch: Char): Boolean {
    if (ch.code in 0xE000..0xE016 || ch.code == 0x0014) return true
    if (ch.code < 0x20 || ch.code == 0x7F) return true
    if (ch.isWhitespace() || Character.isSpaceChar(ch)) return true
    if (ch == '\uFEFF' || ch in '\u200B'..'\u200F' || ch in '\u2060'..'\u2064') return true
    // 电子书常用的“不可见占位符”：韩文填充符、盲文空白、缺省字形等，渲染出来是空白。
    if (ch == '\u3164' || ch == '\u2800' || ch == '\u115F' || ch == '\u1160' ||
        ch == '\uFFA0' || ch == '\u180E' || ch == '\u2007' || ch == '\u2063'
    ) {
        return true
    }
    val type = Character.getType(ch)
    return type == Character.FORMAT.toInt() || type == Character.UNASSIGNED.toInt()
}

/** 只有不可见内容、没有块图片/表格的行：不应占用行高，也不参与切片。 */
internal fun isInvisibleNativeLine(line: NativeLine): Boolean {
    if (line.imageId >= 0) return false
    if (line.tableCols > 0) return false
    var position = 0
    while (position < line.text.length) {
        val ch = line.text[position]
        if (ch == '\u0004') {
            // 只含行内图片 token 的行没有可见正文，按空行处理（避免出现整行空白）。
            val tokenEnd = line.text.indexOf('\u0005', position)
            position = if (tokenEnd < 0) line.text.length else tokenEnd + 1
            continue
        }
        if (!isInvisibleNativeChar(ch)) return false
        position += 1
    }
    return true
}


internal fun imageInfo(bytes: ByteArray): Pair<Float, Float> = runCatching {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    val width = options.outWidth
    val height = options.outHeight
    if (width > 0 && height > 0) {
        (width.toFloat() / height.toFloat()) to width.toFloat()
    } else {
        1.5f to 0f
    }
}.getOrElse { 1.5f to 0f }

internal fun imageAspect(bytes: ByteArray): Float = runCatching {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    val width = options.outWidth
    val height = options.outHeight
    if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1.5f
}.getOrElse { 1.5f }

internal const val MAX_CACHED_IMAGES = 24
internal const val MAX_IMAGE_CACHE_BYTES = 48L * 1024 * 1024

/** 一页里出现的图片 id（块级图片 + 行内 \u0004 token），用于预渲染。 */
internal fun pageImageIds(pageSet: NativePageSet, pageIndex: Int): List<Int> {
    val page = pageSet.pages.getOrNull(pageIndex) ?: return emptyList()
    val ids = LinkedHashSet<Int>()
    for (line in page) {
        if (line.imageId >= 0) ids.add(line.imageId)
        var position = line.text.indexOf('\u0004')
        while (position >= 0) {
            val end = line.text.indexOf('\u0005', position)
            if (end < 0) break
            line.text.substring(position + 1, end).toIntOrNull()?.let { ids.add(it) }
            position = line.text.indexOf('\u0004', end + 1)
        }
    }
    return ids.toList()
}

internal fun resolveImageBytes(ref: ImageRef, chapters: List<EbookChapter>): ByteArray? {
    val html = chapters.getOrNull(ref.chapterIndex)?.html ?: return null
    val match = NATIVE_DATA_URI.findAll(html).elementAtOrNull(ref.dataUriIndex) ?: return null
    return runCatching { Base64.decode(match.groupValues[2], Base64.DEFAULT) }.getOrNull()
}

internal fun decodeInlineBitmap(
    ref: ImageRef,
    chapters: List<EbookChapter>,
    targetWidth: Int,
): Bitmap? = runCatching {
    val bytes = resolveImageBytes(ref, chapters) ?: return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    if (width <= 0) return@runCatching null
    var sample = 1
    while (width / (sample * 2) >= targetWidth.coerceAtLeast(1)) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}.getOrNull()

internal fun decodeNativeLine(raw: String): NativeLine {
    if (raw.length >= 8 && raw[0] == '\u0003') {
        return NativeLine(
            text = "",
            imageId = raw.substring(1, 6).toIntOrNull() ?: -1,
            gapEm = raw.substring(6, 8).toFloatOrNull()?.div(10f) ?: 0f,
        )
    }
    if (raw.length >= 18 && raw[0] == '\u0002') {
        val col = raw.substring(1, 3).toIntOrNull() ?: 0
        val cols = raw.substring(3, 5).toIntOrNull() ?: 0
        val flags = raw[5].digitToIntOrNull() ?: 0
        val inner = decodeNativeLine(raw.substring(6))
        return inner.copy(
            tableCol = col,
            tableCols = cols,
            tableRowStart = flags and 1 != 0,
            tableLastRow = flags and 2 != 0,
            tableRowEnd = flags and 4 != 0,
        )
    }
    if (raw.length < 12 || raw[0] != '\u0001') return NativeLine(raw)
    return NativeLine(
        text = raw.substring(12),
        sizeRatio = raw.substring(1, 3).toFloatOrNull()?.div(10f) ?: 1f,
        lineHeightRatio = raw.substring(3, 5).toFloatOrNull()?.div(10f) ?: 1.8f,
        bold = raw[5] == '1' || raw[5] == '3' || raw[5] == '5' || raw[5] == '7',
        italic = raw[5] == '2' || raw[5] == '3' || raw[5] == '6' || raw[5] == '7',
        mono = raw[5] == '4' || raw[5] == '5' || raw[5] == '6' || raw[5] == '7',
        align = raw.substring(6, 8).toIntOrNull() ?: 0,
        leftIndentEm = raw.substring(8, 10).toFloatOrNull()?.div(10f) ?: 0f,
        gapEm = raw.substring(10, 12).toFloatOrNull()?.div(10f) ?: 0f,
    )
}

/**
 * 原生排版只需要文字结构：把 base64 内联图片、内联 SVG、超长 data URI 去掉，
 * 避免把整本图片书的负载复制进 Rust（否则内存会被放大到 OOM）。
 */
internal fun String.stripHeavyPayloads(): String {
    if (!contains("data:") && !contains("<svg", ignoreCase = true) && length < 2_000_000) {
        return this
    }
    var result = this
    if (result.contains("<svg", ignoreCase = true)) {
        result = result.replace(Regex("(?is)<svg.*?</svg>"), "［图片］")
    }
    if (result.contains("data:")) {
        result = result.replace(Regex("(?is)data:[^\"'\\s>]{64,}"), "data:image")
    }
    if (result.contains("<img", ignoreCase = true)) {
        result = result.replace(Regex("(?is)<img(?![^>]*data-salt-img)[^>]*>"), "［图片］")
    }
    return result
}

internal fun String.escapeHtml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/**
 * 解析原生渲染用的 Typeface：自定义字体（字体目录文件或系统字体）+ 回退字体链。
 * API 29+ 用 [Typeface.CustomFallbackBuilder] 把回退字体串成真正的 fallback chain；
 * 更低版本退回主字体（Android 仍会做系统兜底）。
 */
