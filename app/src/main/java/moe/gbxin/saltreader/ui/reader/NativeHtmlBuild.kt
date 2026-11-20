package moe.gbxin.saltreader.ui.reader

/** 章节 HTML -> Rust 排版输入：去除重型负载、抽取行内图片、拼装 JNI 参数。 */

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Html
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import moe.gbxin.saltreader.data.EbookChapter
import moe.gbxin.saltreader.data.NativeTextLayout

/** 章节 HTML → 纯文本（Rust 排版只吃纯文本）。 */
internal fun chapterPlainText(chapter: EbookChapter): String {
    if (chapter.imageOnly) return ""
    val text = runCatching {
        Html.fromHtml(chapter.html, Html.FROM_HTML_MODE_LEGACY).toString()
    }.getOrElse { chapter.html.replace(Regex("<[^>]+>"), "") }
    return text
        .replace('\u00A0', ' ')
        .lines()
        .map { it.trimEnd() }
        .joinToString("\n")
        .trim('\n', ' ')
}

internal fun buildNativePageSet(
    chapters: List<EbookChapter>,
    width: Float,
    height: Float,
    fontSizePx: Float,
    lineHeightFactor: Float,
    typeface: Typeface,
    contentLeftPx: Float = 0f,
    contentWidthPx: Float = 1f,
    topPadPx: Float = 0f,
    bottomPadPx: Float = 0f,
    contentHeightPx: Float = 1f,
    imageFullWidth: Boolean = false,
    fullPageWidth: Float = 1f,
    imageMaxHeightRatio: Float = 0.92f,
    imagePlaceholder: String = "［图片］",
    imageDensity: Float = 1f,
    monoAdvanceEm: Float = 0.6f,
): NativePageSet? {
    if (!NativeTextLayout.isAvailable) return null
    val token = nativeParserToken.incrementAndGet()
    val text = buildString {
        for ((chapterPosition, chapter) in chapters.withIndex()) {
            val body = chapterPlainText(chapter).trim()
            if (body.isBlank()) continue
            val title = chapter.title?.trim().orEmpty()
            if (title.isNotEmpty() && chapter.showHeading) {
                append(title)
                append('\n')
            }
            append(body)
            append("\n\n")
        }
    }.trim()
    if (text.isBlank()) return NativePageSet(emptyList())

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSizePx
        this.typeface = typeface
    }
    // 等宽字宽：行内代码/代码块用 Typeface.MONOSPACE 绘制，断行必须用同一字宽，
    // 否则 Rust 会按偏宽的估值提前折行，Kotlin 再把短行两端对齐 → 出现夸张空隙。
    val measuredMonoEm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSizePx
        this.typeface = Typeface.MONOSPACE
    }.let { monoPaint ->
        (monoPaint.measureText("0000") / 4f / fontSizePx).takeIf { it.isFinite() && it > 0.2f }
            ?: 0.6f
    }
    // 字宽表：整本书去重后的码点各测一次，Rust 用它做精确断行。
    val codePoints = ArrayList<Int>(1024)
    val seen = HashSet<Int>(2048)
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (seen.add(codePoint)) codePoints.add(codePoint)
        index += Character.charCount(codePoint)
    }
    val advances = FloatArray(codePoints.size) { i ->
        val codePoint = codePoints[i]
        val measured = paint.measureText(String(Character.toChars(codePoint)))
        // 自定义/系统字体缺少 CJK 字形时，measureText 可能给出 0 或极小值，
        // 会直接导致 Rust 认为一行能塞下整段文字。宽字符给一个全角下限。
        val isWide = codePoint in 0x2E80..0x9FFF || codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFF00..0xFF60 || codePoint in 0x20000..0x2FFFD
        when {
            !measured.isFinite() || measured <= 0f ->
                if (isWide) fontSizePx else fontSizePx * 0.55f
            // CJK 字形回退到系统字体时，实测宽度可能比真实绘制窄，统一按全角宽度排版。
            isWide -> maxOf(measured, fontSizePx)
            else -> measured
        }
    }

    // 把章节 HTML 交给 Rust 解析（含书籍自带 CSS 子集）；标题缺失时补一个 h2。
    // 只记录图片所在章节与序号；解码时按需从章节 HTML 取 data URI，避免全书图片字节常驻。
    val imageRefs = ArrayList<ImageRef>()
    val imageAspects = ArrayList<Float>()
    val imageWidths = ArrayList<Float>()
    // 必须连同 src="..." 引号整体替换，否则会生成畸形属性：src="data-salt-img="0""。
    val dataUri = Regex(
        "src=\"data:image/([A-Za-z0-9.+-]+);base64,([A-Za-z0-9+/=\\r\\n]+)\"",
        RegexOption.IGNORE_CASE,
    )
    var perChapterImageBase = 0
    val htmlChapters = ArrayList<String>(chapters.size)
    for ((chapterPosition, chapter) in chapters.withIndex()) {
        val imagesBeforeChapter = imageRefs.size
        perChapterImageBase = imagesBeforeChapter
        // image-only（彩页）章节也要参与：它们的正文就是插图，跳过会丢掉整章插画。
        val withImages = if (chapter.html.contains("data:image", ignoreCase = true)) {
            // 保留原 <img> 的 class/style（脚注/封面/插图类名决定内联还是块级），
            // 只把 data URI 换成 data-salt-img 占位。
            val imgTag = Regex("(?is)<img\\b[^>]*>")
            chapter.html.replace(imgTag) { match ->
                val tag = match.value
                val dataMatch = dataUri.find(tag)
                if (dataMatch == null) {
                    tag
                } else {
                    val payload = dataMatch.groupValues[2]
                    val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
                    if (bytes == null) {
                        tag
                    } else {
                        val id = imageRefs.size
                        imageRefs.add(ImageRef(chapterPosition, id - perChapterImageBase))
                        val info = imageInfo(bytes)
                        imageAspects.add(info.first)
                        // WebView 的 max-width: 图片原始 CSS 宽度 × density；0 表示不限。
                        imageWidths.add(info.second * imageDensity)
                        tag.replaceRange(dataMatch.range, "data-salt-img=\"$id\"")
                    }
                }
            }
        } else {
            chapter.html
        }
        val body = withImages.stripHeavyPayloads()
        if (body.isBlank()) continue
        val title = chapter.title?.trim().orEmpty()
        val addHeading = !chapter.imageOnly && title.isNotEmpty() && chapter.showHeading &&
            !body.contains(title, ignoreCase = true)
        htmlChapters += if (addHeading) {
            // 与 WebView 自动章节标题一致：使用 #book h2.chapter-heading 的紧凑间距。
            "<h2 class=\"chapter-heading\">${title.escapeHtml()}</h2>$body"
        } else {
            body
        }
    }
    if (htmlChapters.isEmpty()) {
        return NativePageSet(emptyList())
    }
android.util.Log.i(
        "SaltReaderNative",
        "images extracted=${imageRefs.size}; " +
            "sample=${
                htmlChapters.firstOrNull()?.let { html ->
                    Regex("(?is)<img[^>]{0,160}>").find(html)?.value ?: "(no img)"
                }
            }",
    )

    return runCatching {
        val startedAt = System.nanoTime()
        val raw = NativeTextLayout.paginateHtml(
            htmlChapters.toTypedArray(),
            width,
            height,
            fontSizePx,
            lineHeightFactor,
            codePoints.toIntArray(),
            advances,
            imageAspects.toFloatArray(),
            imageWidths.toFloatArray(),
            imageFullWidth,
            fullPageWidth,
            imageMaxHeightRatio,
            imagePlaceholder,
            measuredMonoEm,
        )
        val pages = if (raw.isNullOrEmpty()) {
            emptyList()
        } else {
            raw.split('\u0000').map { page -> page.split('\n').map(::decodeNativeLine) }
        }
        android.util.Log.i(
            "SaltReaderNative",
            "paginate ${htmlChapters.size} chapters -> ${pages.size} pages in " +
                "${(System.nanoTime() - startedAt) / 1_000_000}ms (token=$token)",
        )
        NativePageSet(
            pages, imageRefs.toList(), imageAspects.toList(), imageWidths.toList(),
            contentLeftPx, contentWidthPx, topPadPx, bottomPadPx, contentHeightPx,
        )
    }.getOrElse {
        android.util.Log.w("SaltReaderNative", "native paginate failed (token=$token)", it)
        null
    }
}

/**
 * 两端对齐：按 Blink 的可拉伸边界（空格之后、CJK 两侧）拉开间距，
 * 最后一行由 Rust 标记为左对齐，不参与拉伸。
 *
 * 这段逻辑在 Canvas 的 draw lambda 里，每次重绘都会跑一遍；一页 20 行 × 25 字意味着每帧
 * 上千次 `measureText` + `substring`。这里把「切簇 + 量宽 + 数边界」的结果按行缓存，
 * 绘制端仍然逐簇 `drawText`，渲染结果与逐帧重算完全一致，只是不再重复测量。
 */
