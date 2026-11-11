package moe.gbxin.saltreader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/**
 * 文字型 EPUB 的一个章节。
 *
 * @property title 章节标题：优先取 toc.ncx / nav.xhtml 的目录标签，其次正文里的标题标签；
 * @property html 已清洗的正文 HTML，可直接交给 WebView 渲染；
 * @property charCount 可见文本字符数（去掉标签、实体与空白字符；0 = 旧版缓存或纯图片页）；
 * @property imageOnly 该章只渲染图片（封面/彩页/插图页）；
 * @property showHeading 为 false 表示正文已经自带标题元素，阅读器不应再注入 h2 标题。
 */
@Serializable
data class EbookChapter(
    val title: String?,
    val html: String,
    val charCount: Int = 0,
    val imageOnly: Boolean = false,
    val showHeading: Boolean = true,
)

/**
 * EPUB spine 分类的纯函数部分（不触碰 Android / 文件系统），便于 JVM 单测。
 */
object EpubSpineClassifier {
    /** spine 中图片页占比达到该阈值即视为图片型 EPUB。 */
    const val IMAGE_RATIO = 0.8f

    /** 文字书书架进度换算：约 1500 字符一页。 */
    const val CHARS_PER_PAGE = 1500

    fun isImageBased(imagePages: Int, spinePages: Int): Boolean =
        imagePages > 0 && spinePages > 0 && imagePages.toFloat() / spinePages >= IMAGE_RATIO

    /** charCount = Σ(spine XHTML 未压缩大小) * 0.6，下限 1。 */
    fun estimateCharCount(spineXhtmlUncompressedBytes: Long): Long =
        max(1L, (spineXhtmlUncompressedBytes * 0.6).toLong())

    /** 只有书架进度估算用，真实位置由 progressOffset 决定。 */
    fun estimatePageCount(charCount: Long): Int =
        max(1, (charCount / CHARS_PER_PAGE).toInt())

    /**
     * spine 扫描的判定结果。
     *
     * - [kind]：ARCHIVE = 图片型，TEXT = 文字型；
     * - [pageCount]：仅图片型有意义（已按页数策略把未检查页计入）；文字型为 0，
     *   页数仍由 [estimatePageCount] 按 charCount 估算；
     * - [budgetExhausted]：判定时扫描预算是否已耗尽，原样透传便于调用方/测试区分来源。
     */
    data class ScanDecision(
        val kind: BookKind,
        val pageCount: Int,
        val budgetExhausted: Boolean,
    ) {
        val imageBased: Boolean get() = kind == BookKind.ARCHIVE
    }

    /**
     * 仅依据 XHTML 扫描结果做分类决策（纯函数，便于 JVM 单测）。
     *
     * 修复点：分母必须是"实际检查过的页数" [examinedPages]，而不是全部 spine 页数。原实现用
     * 全部 spine 页数做分母，扫描预算耗尽后未检查的页仍会累加分母，导致图片型 EPUB 的图片页
     * 比例被稀释、跌破 [IMAGE_RATIO] 而误判成文字型（随后走昂贵的整本文字提取）。
     *
     * - 预算未耗尽：直接按已检查页比例判定，图片型页数 = 已检查出的图片页数。
     * - 预算耗尽：仍按已检查比例保守判定；若判定为图片型，则把 [remainingPages]（来不及检查
     *   的页）计入页数，避免页数明显偏小。这些页没有图片映射，由调用方补齐占位映射。
     * - [remainingPages] 在预算未耗尽时忽略（那时只可能来自文字型的提前终止）。
     */
    fun decideByScan(
        imagePages: Int,
        examinedPages: Int,
        remainingPages: Int,
        budgetExhausted: Boolean,
    ): ScanDecision {
        val examined = examinedPages.coerceAtLeast(0)
        // 防御性裁剪：图片页不可能多于检查过的页。
        val images = imagePages.coerceIn(0, examined)
        if (examined == 0 || images == 0) {
            return ScanDecision(BookKind.TEXT, pageCount = 0, budgetExhausted = budgetExhausted)
        }
        if (images.toFloat() / examined < IMAGE_RATIO) {
            return ScanDecision(BookKind.TEXT, pageCount = 0, budgetExhausted = budgetExhausted)
        }
        val unchecked = if (budgetExhausted) remainingPages.coerceAtLeast(0).toLong() else 0L
        val pages = (images.toLong() + unchecked).coerceAtMost(Int.MAX_VALUE.toLong())
        return ScanDecision(BookKind.ARCHIVE, pageCount = pages.toInt(), budgetExhausted = budgetExhausted)
    }
}

/**
 * 文字章节的纯文本分析工具（不依赖 Android / 文件系统），便于 JVM 单测。
 *
 * 供章节的 [EbookChapter.charCount]、[EbookChapter.imageOnly] 与 [EbookChapter.showHeading]
 * 计算使用：这些值必须与阅读器的字符偏移进度一致，因此只统计"可见文本"。
 */
object EpubTextAnalyzer {
    /** 拼音类/英文类标题 class 词根；命中任一即认为第一个块级元素就是标题。 */
    private val TITLE_CLASS_TOKENS = listOf(
        "pius",
        "biaoti",
        "title",
        "chapter",
        "heading",
        "ctitle",
        "subtitle",
        "sectiontitle",
        "tco",
        "tbox",
    )

    /** 容器类标签：本身不是标题，可以继续向下找第一个有内容的块级元素。 */
    private val CONTAINER_TAGS = setOf(
        "html",
        "body",
        "main",
        "div",
        "section",
        "article",
        "header",
        "center",
        "figure",
    )

    private val TITLE_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val NON_VISIBLE_BLOCKS = Regex(
        "<(?:script\\b[^>]*>[\\s\\S]*?</script\\s*>" +
            "|style\\b[^>]*>[\\s\\S]*?</style\\s*>)",
        RegexOption.IGNORE_CASE,
    )
    private val COMMENT = Regex("<!--[\\s\\S]*?-->")
    private val TAG = Regex("<[^>]*>")
    private val IMG = Regex("<img\\b", RegexOption.IGNORE_CASE)
    private val OPEN_TAG = Regex("<([A-Za-z][A-Za-z0-9]*)\\b([^>]*)>")
    private val CLASS_ATTRIBUTE = Regex("""(?i)\bclass\s*=\s*("([^"]*)"|'([^']*)')""")
    private val NUMERIC_ENTITY = Regex("&#(?:([0-9]+)|[xX]([0-9a-fA-F]+));")

    /** 去掉脚本/样式/注释与全部标签后剩下的可见文本（实体已解码）。 */
    fun visibleText(html: String): String {
        var text = NON_VISIBLE_BLOCKS.replace(html, " ")
        text = COMMENT.replace(text, " ")
        text = TAG.replace(text, " ")
        return decodeEntities(text)
    }

    /** 可见字符数：标签、实体与空白都不计入（CJK 一个字算一个字符）。 */
    fun visibleTextLength(html: String): Int {
        val text = visibleText(html)
        var count = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) count++
            index += Character.charCount(codePoint)
        }
        return count
    }

    /** 纯图片页：没有任何可见文字，且至少有一个 img。 */
    fun isImageOnly(html: String): Boolean = visibleTextLength(html) == 0 && IMG.containsMatchIn(html)

    /**
     * 正文第一个有效元素是否已经是标题：
     * - 第一个非容器元素是 h1–h6；
     * - 或第一个 p/div 的 class 命中 [TITLE_CLASS_TOKENS]（如本样例书的 `pius1`）。
     *
     * 容器标签（div/section/…）不会终止查找，因为真实 EPUB 常用一个无 class 的 div 包住标题。
     */
    fun startsWithTitleElement(html: String): Boolean {
        for (match in OPEN_TAG.findAll(html)) {
            val tag = match.groupValues[1].lowercase()
            if (tag in TITLE_TAGS) return true
            val classValue = classAttribute(match.groupValues[2])
            if (classValue != null && hasTitleToken(classValue)) return true
            // 第一个段落不是标题样式：正文已经开始了。
            if (tag == "p") return false
            // 非容器的行内/表格元素出现在最前面，说明不是标题开头。
            if (tag !in CONTAINER_TAGS) return false
        }
        return false
    }

    /**
     * 兜底判断：正文第一段可见文字是否与章节标题重合（例如"简介""制作信息"这类用
     * 自定义 class 排版的标题段），避免阅读器再注入一个重复的 h2。
     */
    fun startsWithTitleText(html: String, title: String?): Boolean {
        val normalizedTitle = normalizeForComparison(title ?: return false)
        if (normalizedTitle.isEmpty()) return false
        val firstText = normalizeForComparison(firstTextRun(html))
        if (firstText.length < 2) return false
        return normalizedTitle.startsWith(firstText) || firstText.startsWith(normalizedTitle)
    }

    /** 去掉全部标签（用空格分隔，避免相邻文本粘连）。 */
    fun stripTags(text: String): String = TAG.replace(text, " ")

    /** 解码常见命名实体与数字实体；未知实体保持原样。 */
    fun decodeEntities(text: String): String {
        var result = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        result = NUMERIC_ENTITY.replace(result) { match ->
            val code = match.groupValues[1].toIntOrNull()
                ?: match.groupValues[2].toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
        }
        return result
    }

    private fun classAttribute(attributes: String): String? {
        val match = CLASS_ATTRIBUTE.find(attributes) ?: return null
        return if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
    }

    private fun hasTitleToken(classValue: String): Boolean {
        val lower = classValue.lowercase()
        return TITLE_CLASS_TOKENS.any { lower.contains(it) }
    }

    /** 第一个由标签分隔出的非空文本段（用于标题重复判断）。 */
    private fun firstTextRun(html: String): String {
        var cursor = 0
        for (match in TAG.findAll(html)) {
            if (match.range.first > cursor) {
                val candidate = decodeEntities(html.substring(cursor, match.range.first)).trim()
                if (candidate.isNotEmpty()) return candidate
            }
            cursor = match.range.last + 1
        }
        return if (cursor < html.length) decodeEntities(html.substring(cursor)).trim() else ""
    }

    /** 比较用归一化：去掉空白（含全角空格），标题/正文里同样的字才算重合。 */
    private fun normalizeForComparison(text: String): String = buildString(text.length) {
        for (char in text) {
            if (!char.isWhitespace() && char != '\u3000' && char != '\u00A0') append(char)
        }
    }
}

/**
 * toc.ncx / EPUB3 nav.xhtml 目录的纯解析（不依赖 Android / 文件系统），便于 JVM 单测。
 * 只负责从 XML/HTML 里取出 href 与标签文本，路径解析仍由 [EpubIndexer] 完成。
 */
object EpubTocParser {
    /** 一个目录项：[href] 是目录文件内的相对路径，[title] 是已去标签的标签文本。 */
    data class TocTarget(val href: String, val title: String)

    private val NCX_NAV_POINT = Regex(
        "<navPoint\\b[^>]*>[\\s\\S]*?<navLabel\\b[^>]*>[\\s\\S]*?<text\\b[^>]*>([\\s\\S]*?)</text>" +
            "[\\s\\S]*?<content\\b[^>]*?\\bsrc\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
        RegexOption.IGNORE_CASE,
    )
    private val NAV_TOC_BLOCK = Regex(
        "<nav\\b[^>]*(?:epub:)?type\\s*=\\s*(?:\"toc\"|'toc')[^>]*>([\\s\\S]*?)</nav\\s*>",
        RegexOption.IGNORE_CASE,
    )
    private val ANCHOR = Regex(
        "<a\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')[^>]*>([\\s\\S]*?)</a\\s*>",
        RegexOption.IGNORE_CASE,
    )
    private val WHITESPACE = Regex("\\s+")

    /** 解析 toc.ncx 的 navMap；嵌套 navPoint 也会被逐个取出。 */
    fun ncxTargets(xml: String): List<TocTarget> = NCX_NAV_POINT.findAll(xml).mapNotNull { match ->
        val href = match.groupValues[2].ifEmpty { match.groupValues[3] }
        if (href.isBlank()) return@mapNotNull null
        TocTarget(href, cleanLabel(match.groupValues[1]))
    }.toList()

    /**
     * 解析 EPUB3 nav.xhtml 里 `epub:type="toc"` 的目录；没有该 nav 时退化为全文档的 a 标签。
     */
    fun navTargets(xml: String): List<TocTarget> {
        val tocBlocks = NAV_TOC_BLOCK.findAll(xml).map { it.groupValues[1] }.toList()
        val blocks = if (tocBlocks.isNotEmpty()) tocBlocks else listOf(xml)
        return blocks.flatMap { block ->
            ANCHOR.findAll(block).mapNotNull { match ->
                val href = match.groupValues[1].ifEmpty { match.groupValues[2] }
                if (href.isBlank()) return@mapNotNull null
                TocTarget(href, cleanLabel(match.groupValues[3]))
            }.toList()
        }
    }

    private fun cleanLabel(raw: String): String =
        EpubTextAnalyzer.decodeEntities(EpubTextAnalyzer.stripTags(raw))
            .replace(WHITESPACE, " ")
            .trim()
}

/**
 * 文字型 EPUB 图片内联的纯策略（阈值 / 采样 / PNG 透明检测），不依赖 Android，便于 JVM 单测。
 * 真实的解码与重编码在 [EpubIndexer] 里用 BitmapFactory 完成。
 */
object EpubImagePolicy {
    /** 内联图片的目标长边：超过就等比缩小，兼顾清晰度与章节 HTML 体积。 */
    const val MAX_LONG_SIDE = 1440

    /** JPEG/WebP 超过该体积就重编码（即使尺寸不大，避免 base64 后膨胀）。 */
    const val DOWNSCALE_THRESHOLD_BYTES = 600 * 1024

    /** 单张图片内联后的上限（BASE64 前的原始字节）。 */
    const val MAX_INLINE_IMAGE_BYTES = 1536 * 1024

    /** 整本文字书图片内联总预算（BASE64 前的原始字节）。 */
    const val MAX_TOTAL_INLINE_BYTES = 8 * 1024 * 1024

    /** 单个图片条目的读取上限：允许先读入大图再降采样，避免直接丢弃彩色插页。 */
    const val MAX_SOURCE_IMAGE_BYTES = 8 * 1024 * 1024

    /** 带透明通道的 PNG 不重编码为 JPEG，仅在该体积内原样内联。 */
    const val PNG_ALPHA_MAX_BYTES = 1024 * 1024

    const val JPEG_QUALITY = 82

    data class Size(val width: Int, val height: Int)

    fun longSide(width: Int, height: Int): Int = max(width, height)

    /** 是否需要降采样+重编码：体积或长边任一超限。 */
    fun needsDownscale(byteSize: Int, width: Int, height: Int): Boolean =
        byteSize > DOWNSCALE_THRESHOLD_BYTES || longSide(width, height) > MAX_LONG_SIDE

    /**
     * 解码采样率（2 的幂）：保证解码后的长边不小于 [maxLongSide]，避免解码出超大位图，
     * 也避免采样过度导致画面糊掉。
     */
    fun sampleSizeFor(width: Int, height: Int, maxLongSide: Int = MAX_LONG_SIDE): Int {
        val long = longSide(width, height)
        if (long <= 0 || maxLongSide <= 0) return 1
        var sample = 1
        while (long / (sample * 2) >= maxLongSide) sample *= 2
        return sample
    }

    /** 等比缩到长边不超过 [maxLongSide]；只缩不放。 */
    fun fitWithin(width: Int, height: Int, maxLongSide: Int = MAX_LONG_SIDE): Size {
        val long = longSide(width, height)
        if (long <= 0 || long <= maxLongSide) return Size(max(1, width), max(1, height))
        val scale = maxLongSide.toDouble() / long.toDouble()
        return if (width >= height) {
            Size(maxLongSide, max(1, (height * scale).toInt()))
        } else {
            Size(max(1, (width * scale).toInt()), maxLongSide)
        }
    }

    /** 读取 PNG 头判断是否带透明通道（含 tRNS）；字节损坏时返回 false（按不透明处理）。 */
    fun pngHasAlpha(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size) return false
        for (index in PNG_SIGNATURE.indices) {
            if (bytes[index] != PNG_SIGNATURE[index]) return false
        }
        var position = PNG_SIGNATURE.size
        var colorType = -1
        while (position + CHUNK_HEADER_BYTES <= bytes.size) {
            val length = readIntBigEndian(bytes, position)
            if (length < 0 || position.toLong() + CHUNK_HEADER_BYTES + length > bytes.size.toLong()) break
            val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
            val dataOffset = position + CHUNK_HEADER_BYTES
            when (type) {
                "IHDR" -> if (length >= 10) colorType = bytes[dataOffset + 9].toInt() and 0xFF
                "tRNS" -> return true
                "IDAT" -> break
            }
            position = dataOffset + length + 4
        }
        return colorType == 4 || colorType == 6
    }

    private fun readIntBigEndian(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )
    private const val CHUNK_HEADER_BYTES = 8
}

/**
 * EPUB 封面条目解析的纯逻辑（不依赖 Android / 文件系统），便于 JVM 单测。
 *
 * 解析优先级（从高到低）：
 * 1. OPF `<meta name="cover" content="...">`：content 依次按 manifest id、href、文件名匹配；
 * 2. EPUB3 manifest 中 properties 含 `cover-image` 的条目；
 * 3. `<guide><reference type="cover" href="...">` 指向页面里唯一的本地图片（页面内容由调用方通过
 *    [resolve] 的 guideImage 回调读取，纯逻辑不碰文件系统）；
 * 4. manifest 图片条目中文件名含 "cover"（不区分大小写）；
 * 5. 第一个 manifest 图片条目。
 *
 * 任何异常输入（缺 metadata、畸形 OPF、路径不存在）都返回 null，绝不抛异常。
 */
internal object EpubCoverResolver {
    /** manifest 中一个可解析到 ZIP 条目的 item。 */
    data class Item(
        val id: String,
        val href: String,
        val entryName: String,
        val mediaType: String,
        val properties: String,
    ) {
        val isImage: Boolean
            get() = mediaType.lowercase().startsWith("image/") ||
                entryName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }

    /** OPF 中与封面解析有关的信息；[guideCoverEntry] 已相对 OPF 目录解析成 ZIP 条目名。 */
    data class Source(
        val items: List<Item>,
        val coverMeta: String?,
        val guideCoverEntry: String?,
    )

    /** 解析 OPF 文本，提取 manifest、`meta name="cover"` 与 guide 的封面引用。 */
    fun parse(opfText: String, opfDir: String, available: Set<String>): Source {
        val items = LinkedHashMap<String, Item>()
        for (itemTag in ITEM_TAG.findAll(opfText)) {
            val attributes = parseAttributes(itemTag.value)
            val id = attributes["id"] ?: continue
            val href = attributes["href"] ?: continue
            val entryName = resolveEntry(opfDir, href, available) ?: continue
            items[id] = Item(
                id = id,
                href = href,
                entryName = entryName,
                mediaType = attributes["media-type"] ?: "",
                properties = attributes["properties"] ?: "",
            )
        }
        return Source(
            items = items.values.toList(),
            coverMeta = findCoverMeta(opfText),
            guideCoverEntry = findGuideCoverEntry(opfText, opfDir, available),
        )
    }

    /**
     * 按优先级选出封面条目；[guideImage] 只在需要解析 guide 页面时调用，由调用方负责 I/O。
     * 返回的条目名保证存在于 ZIP 中（否则 parse 阶段就已丢弃）。
     */
    fun resolve(source: Source, guideImage: () -> String?): String? {
        coverFromMeta(source)?.let { return it }
        coverFromProperties(source)?.let { return it }
        val guideEntry = source.guideCoverEntry
        if (guideEntry != null) {
            // guide 直接指向图片时无需再解析 XHTML。
            if (isImageEntry(guideEntry)) return guideEntry
            guideImage()?.let { return it }
        }
        coverFromName(source)?.let { return it }
        return source.items.firstOrNull { it.isImage }?.entryName
    }

    /** XHTML 中"唯一可解析的本地图片"；0 张或多张都返回 null。 */
    fun singleImageEntry(xhtml: String, baseDir: String, available: Set<String>): String? {
        var found: String? = null
        for (tag in IMG_TAG.findAll(xhtml)) {
            val src = parseAttributes(tag.value)["src"] ?: continue
            val entry = resolveEntry(baseDir, src, available) ?: continue
            if (found != null) return null
            found = entry
        }
        return found
    }

    /** 条目扩展名是否为常见图片格式。 */
    fun isImageEntry(entryName: String): Boolean =
        entryName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    private fun coverFromMeta(source: Source): String? {
        val meta = source.coverMeta?.trim().orEmpty()
        if (meta.isEmpty()) return null
        val images = source.items.filter { it.isImage }
        if (images.isEmpty()) return null
        // a) manifest id 精确匹配（本样例书 meta content="cover.jpg" 正好等于 id）。
        images.firstOrNull { it.id == meta }?.let { return it.entryName }
        // b) 相对 OPF 的 href（含 URL 解码兜底）。
        val decoded = decodePath(meta)
        images.firstOrNull { it.href == meta || (decoded != null && it.href == decoded) }
            ?.let { return it.entryName }
        // c) 纯文件名匹配：meta content 有时只写 "cover.jpg" 而不是完整相对路径。
        val fileName = meta.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isNotEmpty()) {
            images.firstOrNull { item ->
                item.href.substringAfterLast('/').equals(fileName, ignoreCase = true) ||
                    item.entryName.substringAfterLast('/').equals(fileName, ignoreCase = true)
            }?.let { return it.entryName }
        }
        return null
    }

    private fun coverFromProperties(source: Source): String? =
        source.items.firstOrNull { item ->
            item.isImage && item.properties.split(WHITESPACE).any { it == "cover-image" }
        }?.entryName

    private fun coverFromName(source: Source): String? =
        source.items.firstOrNull { item ->
            item.isImage && item.entryName.substringAfterLast('/').lowercase().contains("cover")
        }?.entryName

    private fun findCoverMeta(opfText: String): String? {
        for (metaTag in META_TAG.findAll(opfText)) {
            val attributes = parseAttributes(metaTag.value)
            if (!attributes["name"].equals("cover", ignoreCase = true)) continue
            val content = attributes["content"]?.trim()
            if (!content.isNullOrBlank()) return content
        }
        return null
    }

    private fun findGuideCoverEntry(opfText: String, opfDir: String, available: Set<String>): String? {
        for (referenceTag in REFERENCE_TAG.findAll(opfText)) {
            val attributes = parseAttributes(referenceTag.value)
            if (!attributes["type"].equals("cover", ignoreCase = true)) continue
            val href = attributes["href"] ?: continue
            resolveEntry(opfDir, href, available)?.let { return it }
        }
        return null
    }

    private fun decodePath(value: String): String? =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()

    private val ITEM_TAG = Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val META_TAG = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val REFERENCE_TAG = Regex("<reference\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")
    private val IMAGE_EXTENSIONS = setOf(
        "jpg",
        "jpeg",
        "png",
        "gif",
        "webp",
        "bmp",
        "svg",
        "svgz",
        "avif",
        "ico",
    )
}

/**
 * EPUB 直读：用 [RemoteZipReader] 解析 container.xml → OPF → manifest/spine，
 * 不转换、不落任何派生的拍平缓存。
 *
 * - 图片型：spine 条目的 media-type 是 image 类型，或 XHTML 中只有唯一可解析 `<img src>`；
 *   图片页占比 ≥ 80% 即按图片型处理，页序 = spine 顺序。
 * - 文字型：charCount 由 spine XHTML 未压缩大小估算，章节正文按需提取并清洗。
 * - 章节标题优先取 toc.ncx / nav.xhtml 目录标签（按 spine 顺序向后继承），其次正文标题标签。
 */
internal object EpubIndexer {
    private const val MAX_CONTAINER_BYTES = 1L * 1024L * 1024L
    private const val MAX_OPF_BYTES = 16L * 1024L * 1024L
    private const val MAX_TOC_BYTES = 4L * 1024L * 1024L
    private const val MAX_CHAPTER_BYTES = 8L * 1024L * 1024L
    private const val MAX_XHTML_SCAN_BYTES = 2L * 1024L * 1024L
    // 文字书有"24 页且图片比例 < 0.2 提前终止"，实际只有图片书才会用满预算，
    // 因此给到 32MB，避免图片书扫描中途断粮后分类比例失真。
    private const val XHTML_SCAN_BUDGET = 32L * 1024L * 1024L
    private const val XHTML_SCAN_EARLY_ABORT = 24
    private val MAX_INLINE_IMAGE_SOURCE_BYTES = EpubImagePolicy.MAX_SOURCE_IMAGE_BYTES.toLong()
    private val MAX_INLINE_TOTAL_BYTES = EpubImagePolicy.MAX_TOTAL_INLINE_BYTES.toLong()
    private const val MAX_CHAPTER_OUTPUT_CHARS = 12 * 1024 * 1024
    private const val MAX_TITLE_CHARS = 200

    data class Classification(
        val kind: BookKind,
        val pageCount: Int,
        val charCount: Long,
        val imageEntries: List<String>,
        /** 文字型 EPUB 的封面条目（索引阶段直接复用已解析的 OPF，不必再读一遍）。 */
        val coverEntry: String? = null,
    )

    private data class SpineItem(
        val entryName: String,
        val mediaType: String,
        val uncompressedSize: Long,
    ) {
        val isXhtml: Boolean
            get() = mediaType.lowercase().startsWith("application/xhtml") ||
                entryName.substringAfterLast('.', "").lowercase() in XHTML_EXTENSIONS
    }

    private data class Opf(
        val spineItems: List<SpineItem>,
        /** spine 的 toc 属性或 manifest 里第一个 NCX 条目。 */
        val tocEntry: String?,
        /** manifest 里 properties 含 nav 的 EPUB3 导航文档。 */
        val navEntry: String?,
        /** 封面解析所需的 manifest / meta / guide 信息（与 OPF 同一次解析）。 */
        val coverSource: EpubCoverResolver.Source,
    )

    /** 图片型返回 ARCHIVE + 页数，文字型返回 TEXT + 估算字符数；解析失败返回 null。 */
    fun classify(reader: RemoteZipReader): Classification? {
        val opf = readOpf(reader) ?: return null
        if (opf.spineItems.isEmpty()) return null
        val available = reader.entries().mapTo(HashSet()) { it.name }

        var imagePages = 0
        // 分母只算"实际检查过的页"：预算耗尽后未检查的页不能拉低图片比例。
        var examinedPages = 0
        // 预算耗尽后仍未检查的 spine 页；保守判定为图片型时按页计入 pageCount。
        var remainingPages = 0
        var scanBudget = XHTML_SCAN_BUDGET
        var budgetExhausted = false
        // charCount 需要全书 spine XHTML 大小，分类扫描可能提前结束，所以先算好。
        val xhtmlBytes = opf.spineItems
            .asSequence()
            .filter { it.isXhtml }
            .sumOf { max(0L, it.uncompressedSize) }
        val imageEntries = ArrayList<String>()
        // 预算耗尽后未检查的 XHTML spine 条目名：图片条目无从得知，用条目名占位补齐映射。
        val remainingEntries = ArrayList<String>()

        for (item in opf.spineItems) {
            // 扫了足够多页仍几乎没有图片：按文字型提前结束，别为分类读完大半本书。
            if (examinedPages >= XHTML_SCAN_EARLY_ABORT &&
                imagePages.toFloat() / examinedPages < 0.2f
            ) {
                break
            }
            val mediaType = item.mediaType.lowercase()
            if (mediaType.startsWith("image/")) {
                // 直接是图片条目：看元数据就能判定，不消耗扫描预算。
                examinedPages++
                if (item.entryName in available) {
                    imagePages++
                    imageEntries += item.entryName
                }
                continue
            }
            if (!item.isXhtml) {
                // 非图片、非 XHTML 的 spine 页：看过元数据即可确定它不是图片页。
                examinedPages++
                continue
            }
            if (budgetExhausted || scanBudget <= 0L) {
                // 预算耗尽：该页不参与比例计算，只记为未检查页。
                budgetExhausted = true
                remainingPages++
                remainingEntries += item.entryName
                continue
            }
            val limit = min(scanBudget, MAX_XHTML_SCAN_BYTES)
            val bytes = reader.readEntry(item.entryName, limit)
            if (bytes == null) {
                // 读取失败视为已检查（确定拿不到图片），且不消耗预算，避免失败页空转。
                examinedPages++
                continue
            }
            scanBudget -= bytes.size
            examinedPages++
            val image = singleImageEntry(bytes, dirOf(item.entryName), available)
            if (image != null) {
                imagePages++
                imageEntries += image
            }
        }

        val decision = EpubSpineClassifier.decideByScan(
            imagePages = imagePages,
            examinedPages = examinedPages,
            remainingPages = remainingPages,
            budgetExhausted = budgetExhausted,
        )
        if (decision.imageBased && imageEntries.isNotEmpty()) {
            // 预算耗尽时未检查页也计入了 pageCount，但它们的图片条目无从得知。
            // 这里用这些页的 spine 条目名占位：名字在 ZIP 中真实存在，消费者按名过滤时不会丢页，
            // 从而保证 imageEntries.size == pageCount（imagePageEntries()/ComicPageSource 依赖
            // 该顺序，缺映射会让后续页整体错位）。读取时解不出图片只会退化成空白页。
            val padding = (decision.pageCount - imageEntries.size).coerceAtLeast(0)
            repeat(padding) { index ->
                imageEntries += remainingEntries.getOrNull(index) ?: remainingEntries.lastOrNull() ?: ""
            }
            return Classification(
                kind = BookKind.ARCHIVE,
                pageCount = decision.pageCount,
                charCount = 0L,
                imageEntries = imageEntries,
            )
        }
        val charCount = EpubSpineClassifier.estimateCharCount(xhtmlBytes)
        return Classification(
            kind = BookKind.TEXT,
            pageCount = EpubSpineClassifier.estimatePageCount(charCount),
            charCount = charCount,
            imageEntries = emptyList(),
            // 文字书书架封面：与分类同一次 OPF 解析，顺带解析封面条目，避免索引阶段再读一遍 OPF。
            coverEntry = runCatching { resolveCoverEntry(reader, opf, available) }.getOrNull(),
        )
    }

    /**
     * 解析 EPUB 封面图片条目（OPF → manifest / meta / guide）；失败或无封面返回 null。
     *
     * 用于既有书库补封面：文字书打开时若封面文件缺失，可直接复用当前已打开的 reader。
     */
    fun coverEntry(reader: RemoteZipReader): String? = runCatching {
        val opf = readOpf(reader) ?: return@runCatching null
        val available = reader.entries().mapTo(HashSet()) { it.name }
        resolveCoverEntry(reader, opf, available)
    }.getOrNull()

    /** 按 [EpubCoverResolver] 的优先级解析封面；guide 页面图片在这里读，纯逻辑不碰 I/O。 */
    private fun resolveCoverEntry(
        reader: RemoteZipReader,
        opf: Opf,
        available: Set<String>,
    ): String? {
        val guideEntry = opf.coverSource.guideCoverEntry
        return EpubCoverResolver.resolve(opf.coverSource) {
            if (guideEntry == null) {
                null
            } else {
                reader.readEntry(guideEntry, MAX_XHTML_SCAN_BYTES)?.let { bytes ->
                    EpubCoverResolver.singleImageEntry(
                        xhtml = bytes.toString(Charsets.UTF_8),
                        baseDir = dirOf(guideEntry),
                        available = available,
                    )
                }
            }
        }
    }

    /** 图片型 EPUB 的 spine 顺序图片条目；解析失败/文字型返回空列表。 */
    fun imagePageEntries(reader: RemoteZipReader): List<String> =
        runCatching { classify(reader) }
            .getOrNull()
            ?.takeIf { it.kind == BookKind.ARCHIVE }
            ?.imageEntries
            .orEmpty()

    /** 按 spine 顺序提取并清洗文字章节；失败返回空列表。 */
    fun loadChapters(reader: RemoteZipReader): List<EbookChapter> {
        val opf = runCatching { readOpf(reader) }.getOrNull() ?: return emptyList()
        val available = runCatching { reader.entries().mapTo(HashSet()) { it.name } }.getOrNull()
            ?: return emptyList()
        val tocTitles = runCatching { loadTocTitles(reader, opf, available) }.getOrDefault(emptyMap())
        val chapters = ArrayList<EbookChapter>()
        val inlineBudget = InlineBudget(MAX_INLINE_TOTAL_BYTES)
        // 目录标签按 spine 顺序"向后继承"：真实 EPUB 常把章节标题页（插图页）作为 navPoint，
        // 正文 XHTML 本身没有对应目录项；正文页沿用最近一个目录标签，避免标题丢失。
        var pendingTitle: String? = null
        for (item in opf.spineItems) {
            if (!item.isXhtml) continue
            val bytes = runCatching { reader.readEntry(item.entryName, MAX_CHAPTER_BYTES) }.getOrNull()
                ?: continue
            val raw = bytes.toString(Charsets.UTF_8)
            val html = runCatching {
                sanitizeChapter(raw, dirOf(item.entryName), reader, available, inlineBudget)
            }.getOrNull() ?: continue
            if (html.isBlank()) continue
            val imageOnly = EpubTextAnalyzer.isImageOnly(html)
            val exactTitle = tocTitles[item.entryName]
            if (exactTitle != null) pendingTitle = exactTitle
            val title = when {
                exactTitle != null -> exactTitle
                // 纯图片页没有可见正文，不需要标题（阅读器也不会画标题）。
                imageOnly -> null
                pendingTitle != null -> pendingTitle
                // 兜底：章节文档头部 <title> 或正文 h1/h2。
                else -> extractTitle(raw) ?: extractTitle(html)
            }
            val charCount = if (imageOnly) 0 else EpubTextAnalyzer.visibleTextLength(html)
            val showHeading = !imageOnly &&
                !EpubTextAnalyzer.startsWithTitleElement(html) &&
                !EpubTextAnalyzer.startsWithTitleText(html, title)
            chapters += EbookChapter(
                title = title,
                html = html,
                charCount = charCount,
                imageOnly = imageOnly,
                showHeading = showHeading,
            )
        }
        return chapters
    }

    // ------------------------------------------------------------------
    // 目录（toc.ncx / EPUB3 nav.xhtml）
    // ------------------------------------------------------------------

    /** 目录项解析为 "章节条目名 -> 标题"；条目名一定真实存在于 ZIP 中。 */
    private fun loadTocTitles(
        reader: RemoteZipReader,
        opf: Opf,
        available: Set<String>,
    ): Map<String, String> {
        val titles = LinkedHashMap<String, String>()
        opf.tocEntry?.let { tocEntry ->
            val xml = runCatching { reader.readEntry(tocEntry, MAX_TOC_BYTES) }
                .getOrNull()
                ?.toString(Charsets.UTF_8)
            if (xml != null) {
                val baseDir = dirOf(tocEntry)
                for (target in EpubTocParser.ncxTargets(xml)) {
                    val title = normalizeTocTitle(target.title) ?: continue
                    val entry = resolveEntry(baseDir, target.href, available) ?: continue
                    titles.putIfAbsent(entry, title)
                }
            }
        }
        // 没有可用的 NCX 时才回退到 EPUB3 nav.xhtml。
        if (titles.isEmpty()) {
            opf.navEntry?.let { navEntry ->
                val xml = runCatching { reader.readEntry(navEntry, MAX_TOC_BYTES) }
                    .getOrNull()
                    ?.toString(Charsets.UTF_8)
                if (xml != null) {
                    val baseDir = dirOf(navEntry)
                    for (target in EpubTocParser.navTargets(xml)) {
                        val title = normalizeTocTitle(target.title) ?: continue
                        val entry = resolveEntry(baseDir, target.href, available) ?: continue
                        titles.putIfAbsent(entry, title)
                    }
                }
            }
        }
        return titles
    }

    private fun normalizeTocTitle(raw: String): String? {
        val title = EpubTextAnalyzer.decodeEntities(EpubTextAnalyzer.stripTags(raw)).trim()
        return title.takeIf { it.isNotBlank() }?.take(MAX_TITLE_CHARS)
    }

    // ------------------------------------------------------------------
    // OPF / container 解析
    // ------------------------------------------------------------------

    private fun readOpf(reader: RemoteZipReader): Opf? {
        val container = reader.readEntry(CONTAINER_PATH, MAX_CONTAINER_BYTES) ?: return null
        val rootPath = findRootPath(container) ?: return null
        val entriesByName = reader.entries().associateBy { it.name }
        val available = entriesByName.keys
        val opfEntry = resolveEntry("", rootPath, available) ?: return null
        val opfBytes = reader.readEntry(opfEntry, MAX_OPF_BYTES) ?: return null
        val opfText = opfBytes.toString(Charsets.UTF_8)
        val opfDir = dirOf(opfEntry)

        // manifest / 封面元数据只解析一次：封面解析复用同一份 Opf，不重复扫 OPF 文本。
        val coverSource = EpubCoverResolver.parse(opfText, opfDir, available)
        val manifest = LinkedHashMap<String, SpineItem>()
        var navEntry: String? = null
        for (item in coverSource.items) {
            val uncompressedSize = entriesByName[item.entryName]?.uncompressedSize ?: 0L
            manifest[item.id] = SpineItem(item.entryName, item.mediaType, uncompressedSize)
            if (navEntry == null) {
                if (item.properties.split(WHITESPACE).any { it == "nav" }) navEntry = item.entryName
            }
        }
        if (manifest.isEmpty()) return null

        // spine 的 toc 属性指向 NCX；部分 EPUB 不写 toc，就找 manifest 里第一个 NCX。
        val tocId = SPINE_OPEN.find(opfText)?.let { parseAttributes(it.value)["toc"] }
        val tocEntry = tocId?.let { manifest[it]?.entryName }
            ?: manifest.values.firstOrNull {
                it.mediaType.equals(NCX_MEDIA_TYPE, ignoreCase = true)
            }?.entryName

        val spineBlock = SPINE_BLOCK.find(opfText)?.groupValues?.get(1) ?: return null
        val spineItems = ArrayList<SpineItem>(manifest.size)
        for (itemRef in ITEMREF_TAG.findAll(spineBlock)) {
            val idref = parseAttributes(itemRef.value)["idref"] ?: continue
            manifest[idref]?.let(spineItems::add)
        }
        if (spineItems.isEmpty()) return null
        return Opf(
            spineItems = spineItems,
            tocEntry = tocEntry,
            navEntry = navEntry,
            coverSource = coverSource,
        )
    }

    private fun findRootPath(containerXml: ByteArray): String? {
        val text = containerXml.toString(Charsets.UTF_8)
        val match = FULL_PATH_REGEX.find(text) ?: return null
        val doubleQuoted = match.groupValues[2]
        val singleQuoted = match.groupValues[3]
        return (if (doubleQuoted.isNotEmpty()) doubleQuoted else singleQuoted).takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------
    // 章节清洗
    // ------------------------------------------------------------------

    private fun sanitizeChapter(
        raw: String,
        baseDir: String,
        reader: RemoteZipReader,
        available: Set<String>,
        inlineBudget: InlineBudget,
    ): String {
        var html = raw
        // 6 个块级标签合并成一次 alternation 替换，link/embed 两个自闭合标签合并成一次：
        // 原来 8 遍全串 Regex.replace 各自生成一个新 String，合并后只遍历 2 遍。
        html = BLOCK_TAGS.replace(html, "")
        html = SELF_CLOSING_TAGS.replace(html, "")
        html = EVENT_ATTRIBUTE.replace(html, "")
        // 只保留 body 正文，避免把 html/head 嵌套进阅读器模板。
        BODY_BLOCK.find(html)?.let { match -> html = match.groupValues[1] }
        html = inlineImages(html, baseDir, reader, available, inlineBudget)
        // 远程资源：去掉 http(s)/协议相对 url 的属性与 CSS url()
        html = REMOTE_ATTRIBUTE.replace(html, "")
        html = SRCSET_ATTRIBUTE.replace(html, "")
        html = REMOTE_CSS_URL.replace(html, "none")
        html = XML_DECLARATION.replace(html, "")
        html = DOCTYPE.replace(html, "")
        if (html.length > MAX_CHAPTER_OUTPUT_CHARS) {
            html = IMG_TAG.replace(html, "")
            if (html.length > MAX_CHAPTER_OUTPUT_CHARS) html = html.take(MAX_CHAPTER_OUTPUT_CHARS)
        }
        return html.trim()
    }

    /**
     * 本地图片转 data URI 内联；远程/读取失败则移除该 img。
     *
     * 大图先降采样再以 JPEG 重编码（见 [prepareInlineImage]），既保证彩色插页能进入正文，
     * 又把章节 HTML 体积/内存控制在预算内。
     */
    private fun inlineImages(
        html: String,
        baseDir: String,
        reader: RemoteZipReader,
        available: Set<String>,
        inlineBudget: InlineBudget,
    ): String = IMG_TAG.replace(html) { match ->
        val src = parseAttributes(match.value)["src"] ?: return@replace ""
        if (isRemote(src) || src.startsWith("data:", ignoreCase = true)) return@replace ""
        val entry = resolveEntry(baseDir, src, available) ?: return@replace ""
        if (inlineBudget.remaining <= 0L) return@replace ""
        val bytes = runCatching {
            reader.readEntry(entry, MAX_INLINE_IMAGE_SOURCE_BYTES)
        }.getOrNull() ?: return@replace ""
        val prepared = prepareInlineImage(entry, bytes) ?: return@replace ""
        if (prepared.data.size > inlineBudget.remaining) return@replace ""
        val base64 = runCatching { Base64.getEncoder().encodeToString(prepared.data) }.getOrNull()
            ?: return@replace ""
        inlineBudget.remaining -= prepared.data.size
        "<img src=\"data:${prepared.mime};base64,$base64\" alt=\"\"/>"
    }

    /** 内联后的图片字节与 MIME。 */
    private data class InlineImage(val data: ByteArray, val mime: String)

    /**
     * 决定一张图片如何内联（全程在 IO 线程执行）：
     * - 动图 / 矢量图不重编码，超限直接丢弃；
     * - 带透明通道的 PNG 原样保留（<=1MB），否则丢弃——重编码成 JPEG 会丢透明；
     * - JPEG/WebP 及不透明 PNG：体积 >600KB 或长边 >1440 时先 inSampleSize 解码、再缩到长边
     *   1440、JPEG q82 重编码；解码失败则原样保留（在大小上限内）。
     */
    private fun prepareInlineImage(entry: String, bytes: ByteArray): InlineImage? {
        val original = InlineImage(bytes, mimeTypeOf(entry))
        val ext = entry.substringAfterLast('.', "").lowercase()
        if (ext in VECTOR_OR_ANIMATED_EXTENSIONS) {
            return original.takeIf { bytes.size <= EpubImagePolicy.MAX_INLINE_IMAGE_BYTES }
        }
        if (ext == "png" && EpubImagePolicy.pngHasAlpha(bytes)) {
            return original.takeIf { bytes.size <= EpubImagePolicy.PNG_ALPHA_MAX_BYTES }
        }
        // 只读图片头拿尺寸：解码前先限制位图大小，损坏文件直接回退原字节。
        val bounds = decodeBitmapBounds(bytes)
            ?: return original.takeIf { bytes.size <= EpubImagePolicy.MAX_INLINE_IMAGE_BYTES }
        if (!EpubImagePolicy.needsDownscale(bytes.size, bounds.width, bounds.height)) {
            return original.takeIf { bytes.size <= EpubImagePolicy.MAX_INLINE_IMAGE_BYTES }
        }
        return downscaleToJpeg(bytes, bounds)
            ?: original.takeIf { bytes.size <= EpubImagePolicy.MAX_INLINE_IMAGE_BYTES }
    }

    /** 解码用 inJustDecodeBounds 只取宽高；损坏字节返回 null，绝不向调用方抛异常。 */
    private fun decodeBitmapBounds(bytes: ByteArray): EpubImagePolicy.Size? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            EpubImagePolicy.Size(options.outWidth, options.outHeight)
        } else {
            null
        }
    }.getOrNull()

    /** 降采样解码 → 精确缩放到目标长边 → JPEG q82 重编码；位图统一在 finally 回收。 */
    private fun downscaleToJpeg(bytes: ByteArray, bounds: EpubImagePolicy.Size): InlineImage? {
        val target = EpubImagePolicy.fitWithin(bounds.width, bounds.height)
        val options = BitmapFactory.Options().apply {
            inSampleSize = EpubImagePolicy.sampleSizeFor(bounds.width, bounds.height)
        }
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull() ?: return null
        val scaled: Bitmap = if (decoded.width == target.width && decoded.height == target.height) {
            decoded
        } else {
            runCatching {
                Bitmap.createScaledBitmap(decoded, target.width, target.height, true)
            }.getOrNull() ?: run {
                decoded.recycle()
                return null
            }
        }
        return try {
            val output = ByteArrayOutputStream()
            val compressed = runCatching {
                scaled.compress(Bitmap.CompressFormat.JPEG, EpubImagePolicy.JPEG_QUALITY, output)
            }.getOrDefault(false)
            if (!compressed) {
                null
            } else {
                val data = output.toByteArray()
                data.takeIf { it.size <= EpubImagePolicy.MAX_INLINE_IMAGE_BYTES }
                    ?.let { InlineImage(it, "image/jpeg") }
            }
        } finally {
            if (scaled !== decoded && !scaled.isRecycled) scaled.recycle()
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun extractTitle(html: String): String? {
        for (tag in TITLE_TAG_ORDER) {
            val match = Regex("(?is)<$tag\\b[^>]*>(.*?)</$tag>").find(html) ?: continue
            val title = EpubTextAnalyzer.decodeEntities(EpubTextAnalyzer.stripTags(match.groupValues[1])).trim()
            if (title.isNotBlank()) return title.take(MAX_TITLE_CHARS)
        }
        return null
    }

    private class InlineBudget(var remaining: Long)

    private fun mimeTypeOf(entryName: String): String = when (entryName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }

    private const val CONTAINER_PATH = "META-INF/container.xml"
    private const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"
    private val XHTML_EXTENSIONS = setOf("xhtml", "html", "htm")

    /** 动图/矢量图不重编码：重编码会丢帧或破坏矢量语义。 */
    private val VECTOR_OR_ANIMATED_EXTENSIONS = setOf("gif", "svg", "svgz", "bmp", "ico", "avif")
    private val TITLE_TAG_ORDER = listOf("h1", "h2", "title")
    private val WHITESPACE = Regex("\\s+")

    private val FULL_PATH_REGEX =
        Regex("""full-path\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val SPINE_OPEN = Regex("<spine\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SPINE_BLOCK = Regex("<spine\\b[^>]*>([\\s\\S]*?)</spine>", RegexOption.IGNORE_CASE)
    private val ITEMREF_TAG = Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE)

    private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    /**
     * 6 个需要整块删除的标签（script/style/iframe/object/video/audio）合并成一次替换。
     * 每个分支的结束标签与自身对应，内容用 [\s\S]*? 非贪婪跨行匹配；IGNORE_CASE 覆盖大小写。
     */
    private val BLOCK_TAGS = Regex(
        "<(?:script\\b[^>]*>[\\s\\S]*?</script\\s*>" +
            "|style\\b[^>]*>[\\s\\S]*?</style\\s*>" +
            "|iframe\\b[^>]*>[\\s\\S]*?</iframe\\s*>" +
            "|object\\b[^>]*>[\\s\\S]*?</object\\s*>" +
            "|video\\b[^>]*>[\\s\\S]*?</video\\s*>" +
            "|audio\\b[^>]*>[\\s\\S]*?</audio\\s*>)",
        RegexOption.IGNORE_CASE,
    )

    /** link/embed 两个自闭合标签合并成一次替换。 */
    private val SELF_CLOSING_TAGS = Regex("<(?:link|embed)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val EVENT_ATTRIBUTE = Regex("""(?i)\s+on[a-z]+\s*=\s*("[^"]*"|'[^']*')""")
    private val REMOTE_ATTRIBUTE =
        Regex("""(?i)\s+(?:src|href|poster)\s*=\s*("(?:https?:)?//[^"]*"|'(?:https?:)?//[^']*')""")
    private val SRCSET_ATTRIBUTE = Regex("""(?i)\s+srcset\s*=\s*("[^"]*"|'[^']*')""")
    private val REMOTE_CSS_URL = Regex("""(?i)url\(\s*(['"]?)(?:https?:)?//[^)]*\)""")
    private val XML_DECLARATION = Regex("(?is)<\\?xml.*?\\?>")
    private val DOCTYPE = Regex("(?is)<!DOCTYPE.*?>")
    private val BODY_BLOCK = Regex("""<body\b[^>]*>([\s\S]*?)</body\s*>""", RegexOption.IGNORE_CASE)
}

// ---------------------------------------------------------------------------
// 文件级纯工具：EpubIndexer 与 EpubCoverResolver 共用（路径在 ZIP 条目名空间内解析）
// ---------------------------------------------------------------------------

private fun dirOf(entryName: String): String = entryName.substringBeforeLast('/', "")

private fun resolveEntry(baseDir: String, href: String, available: Set<String>): String? {
    val clean = href.substringBefore('#').substringBefore('?').trim().replace('\\', '/')
    if (clean.isEmpty() || isRemote(clean) || clean.startsWith("data:", ignoreCase = true)) return null
    val combined = if (baseDir.isEmpty()) clean else "$baseDir/$clean"
    val normalized = normalizePath(combined)
    if (normalized in available) return normalized
    if (normalized.startsWith("../")) return null
    val decoded = runCatching { URLDecoder.decode(normalized, "UTF-8") }.getOrNull()
    if (decoded != null && decoded in available) return decoded
    return null
}

private fun normalizePath(path: String): String {
    val out = ArrayList<String>()
    for (part in path.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex) else out += ".."
            else -> out += part
        }
    }
    return out.joinToString("/")
}

private fun isRemote(value: String): Boolean {
    val lower = value.trim().lowercase()
    return lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("//")
}

private fun parseAttributes(tag: String): Map<String, String> {
    val attributes = HashMap<String, String>(8)
    for (match in ATTRIBUTE_REGEX.findAll(tag)) {
        val name = match.groupValues[1].lowercase()
        val quoted = match.groupValues[2]
        val value = if (quoted.startsWith("\"")) match.groupValues[3] else match.groupValues[4]
        attributes[name] = value
    }
    return attributes
}

/** XHTML 字节版：图片型 EPUB 分类扫描用（章节/封面解析也复用同一套纯逻辑）。 */
private fun singleImageEntry(xhtml: ByteArray, baseDir: String, available: Set<String>): String? =
    EpubCoverResolver.singleImageEntry(xhtml.toString(Charsets.UTF_8), baseDir, available)

private val ATTRIBUTE_REGEX =
    Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
