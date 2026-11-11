package moe.gbxin.saltreader.data

import java.net.URLDecoder
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** 文字型 EPUB 的一个章节。html 是已清洗的正文片段，可直接交给 WebView 渲染。 */
@Serializable
data class EbookChapter(val title: String?, val html: String)

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
 * EPUB 直读：用 [RemoteZipReader] 解析 container.xml → OPF → manifest/spine，
 * 不转换、不落任何派生缓存。
 *
 * - 图片型：spine 条目的 media-type 是 image 类型，或 XHTML 中只有唯一可解析 `<img src>`；
 *   图片页占比 ≥ 80% 即按图片型处理，页序 = spine 顺序。
 * - 文字型：charCount 由 spine XHTML 未压缩大小估算，章节正文按需提取并清洗。
 */
internal object EpubIndexer {
    private const val MAX_CONTAINER_BYTES = 1L * 1024L * 1024L
    private const val MAX_OPF_BYTES = 16L * 1024L * 1024L
    private const val MAX_CHAPTER_BYTES = 8L * 1024L * 1024L
    private const val MAX_XHTML_SCAN_BYTES = 2L * 1024L * 1024L
    // 文字书有"24 页且图片比例 < 0.2 提前终止"，实际只有图片书才会用满预算，
    // 因此给到 32MB，避免图片书扫描中途断粮后分类比例失真。
    private const val XHTML_SCAN_BUDGET = 32L * 1024L * 1024L
    private const val XHTML_SCAN_EARLY_ABORT = 24
    private const val MAX_INLINE_IMAGE_BYTES = 2L * 1024L * 1024L
    private const val MAX_INLINE_TOTAL_BYTES = 4L * 1024L * 1024L
    private const val MAX_CHAPTER_OUTPUT_CHARS = 12 * 1024 * 1024
    private const val MAX_TITLE_CHARS = 200

    data class Classification(
        val kind: BookKind,
        val pageCount: Int,
        val charCount: Long,
        val imageEntries: List<String>,
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

    private data class Opf(val spineItems: List<SpineItem>)

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
        )
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
        val chapters = ArrayList<EbookChapter>()
        val inlineBudget = InlineBudget(MAX_INLINE_TOTAL_BYTES)
        for (item in opf.spineItems) {
            if (!item.isXhtml) continue
            val bytes = runCatching { reader.readEntry(item.entryName, MAX_CHAPTER_BYTES) }.getOrNull() ?: continue
            val html = runCatching {
                sanitizeChapter(bytes.toString(Charsets.UTF_8), dirOf(item.entryName), reader, available, inlineBudget)
            }.getOrNull() ?: continue
            if (html.isBlank()) continue
            chapters += EbookChapter(extractTitle(html), html)
        }
        return chapters
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

        val manifest = LinkedHashMap<String, SpineItem>()
        for (itemTag in ITEM_TAG.findAll(opfText)) {
            val attributes = parseAttributes(itemTag.value)
            val id = attributes["id"] ?: continue
            val href = attributes["href"] ?: continue
            val mediaType = attributes["media-type"] ?: ""
            val entryName = resolveEntry(opfDir, href, available) ?: continue
            val uncompressedSize = entriesByName[entryName]?.uncompressedSize ?: 0L
            manifest[id] = SpineItem(entryName, mediaType, uncompressedSize)
        }
        if (manifest.isEmpty()) return null

        val spineBlock = SPINE_BLOCK.find(opfText)?.groupValues?.get(1) ?: return null
        val spineItems = ArrayList<SpineItem>(manifest.size)
        for (itemRef in ITEMREF_TAG.findAll(spineBlock)) {
            val idref = parseAttributes(itemRef.value)["idref"] ?: continue
            manifest[idref]?.let(spineItems::add)
        }
        if (spineItems.isEmpty()) return null
        return Opf(spineItems)
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

    /** 本地图片转 data URI 内联；远程/超限/读取失败则移除该 img。 */
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
            reader.readEntry(entry, min(MAX_INLINE_IMAGE_BYTES, inlineBudget.remaining))
        }.getOrNull() ?: return@replace ""
        inlineBudget.remaining -= bytes.size
        val base64 = runCatching { Base64.getEncoder().encodeToString(bytes) }.getOrNull()
            ?: return@replace ""
        "<img src=\"data:${mimeTypeOf(entry)};base64,$base64\" alt=\"\"/>"
    }

    private fun extractTitle(html: String): String? {
        for (tag in TITLE_TAG_ORDER) {
            val match = Regex("(?is)<$tag\\b[^>]*>(.*?)</$tag>").find(html) ?: continue
            val title = decodeEntities(stripTags(match.groupValues[1])).trim()
            if (title.isNotBlank()) return title.take(MAX_TITLE_CHARS)
        }
        return null
    }

    private fun stripTags(text: String): String = HTML_TAG.replace(text, " ")

    private fun decodeEntities(text: String): String {
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

    private class InlineBudget(var remaining: Long)

    // ------------------------------------------------------------------
    // 路径与属性工具
    // ------------------------------------------------------------------

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

    /** XHTML 中"唯一可解析的本地图片"；0 张或多张都返回 null。 */
    private fun singleImageEntry(xhtml: ByteArray, baseDir: String, available: Set<String>): String? {
        val text = xhtml.toString(Charsets.UTF_8)
        var found: String? = null
        for (tag in IMG_TAG.findAll(text)) {
            val src = parseAttributes(tag.value)["src"] ?: continue
            val entry = resolveEntry(baseDir, src, available) ?: continue
            if (found != null) return null
            found = entry
        }
        return found
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
    private val XHTML_EXTENSIONS = setOf("xhtml", "html", "htm")
    private val TITLE_TAG_ORDER = listOf("h1", "h2", "title")

    private val FULL_PATH_REGEX =
        Regex("""full-path\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val ITEM_TAG = Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SPINE_BLOCK = Regex("<spine\\b[^>]*>([\\s\\S]*?)</spine>", RegexOption.IGNORE_CASE)
    private val ITEMREF_TAG = Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE_REGEX =
        Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")

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
    private val HTML_TAG = Regex("<[^>]+>")
    private val BODY_BLOCK = Regex("""<body\b[^>]*>([\s\S]*?)</body\s*>""", RegexOption.IGNORE_CASE)
    private val NUMERIC_ENTITY = Regex("&#(?:([0-9]+)|[xX]([0-9a-fA-F]+));")
}
