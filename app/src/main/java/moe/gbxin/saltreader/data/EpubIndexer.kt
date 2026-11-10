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
    private const val XHTML_SCAN_BUDGET = 1L * 1024L * 1024L
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
        var spinePages = 0
        var scanBudget = XHTML_SCAN_BUDGET
        // charCount 需要全书 spine XHTML 大小，分类扫描可能提前结束，所以先算好。
        val xhtmlBytes = opf.spineItems
            .asSequence()
            .filter { it.isXhtml }
            .sumOf { max(0L, it.uncompressedSize) }
        val imageEntries = ArrayList<String>()

        for (item in opf.spineItems) {
            spinePages++
            // 扫了足够多页仍几乎没有图片：按文字型提前结束，别为分类读完大半本书。
            if (spinePages >= XHTML_SCAN_EARLY_ABORT &&
                imagePages.toFloat() / spinePages < 0.2f
            ) {
                break
            }
            val mediaType = item.mediaType.lowercase()
            if (mediaType.startsWith("image/")) {
                if (item.entryName in available) {
                    imagePages++
                    imageEntries += item.entryName
                }
                continue
            }
            if (!item.isXhtml) continue
            if (scanBudget <= 0L) continue
            val limit = min(scanBudget, MAX_XHTML_SCAN_BYTES)
            val bytes = reader.readEntry(item.entryName, limit) ?: continue
            scanBudget -= bytes.size
            val image = singleImageEntry(bytes, dirOf(item.entryName), available)
            if (image != null) {
                imagePages++
                imageEntries += image
            }
        }

        if (EpubSpineClassifier.isImageBased(imagePages, spinePages) && imageEntries.isNotEmpty()) {
            return Classification(
                kind = BookKind.ARCHIVE,
                pageCount = imageEntries.size,
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
        html = SCRIPT_BLOCK.replace(html, "")
        html = STYLE_BLOCK.replace(html, "")
        html = LINK_TAG.replace(html, "")
        html = IFRAME_BLOCK.replace(html, "")
        html = OBJECT_BLOCK.replace(html, "")
        html = EMBED_TAG.replace(html, "")
        html = VIDEO_BLOCK.replace(html, "")
        html = AUDIO_BLOCK.replace(html, "")
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
    private val SCRIPT_BLOCK = Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE)
    private val STYLE_BLOCK = Regex("<style\\b[^>]*>[\\s\\S]*?</style\\s*>", RegexOption.IGNORE_CASE)
    private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val IFRAME_BLOCK = Regex("<iframe\\b[^>]*>[\\s\\S]*?</iframe\\s*>", RegexOption.IGNORE_CASE)
    private val OBJECT_BLOCK = Regex("<object\\b[^>]*>[\\s\\S]*?</object\\s*>", RegexOption.IGNORE_CASE)
    private val EMBED_TAG = Regex("<embed\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val VIDEO_BLOCK = Regex("<video\\b[^>]*>[\\s\\S]*?</video\\s*>", RegexOption.IGNORE_CASE)
    private val AUDIO_BLOCK = Regex("<audio\\b[^>]*>[\\s\\S]*?</audio\\s*>", RegexOption.IGNORE_CASE)
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
