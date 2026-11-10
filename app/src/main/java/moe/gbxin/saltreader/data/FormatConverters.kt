package moe.gbxin.saltreader.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.documentnode.epub4j.domain.Book
import io.documentnode.epub4j.domain.Resource
import io.documentnode.epub4j.domain.Resources
import io.documentnode.epub4j.epub.EpubReader
import org.rr.mobi4java.MobiReader
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB / MOBI -> CBZ 的派生缓存转换（只在索引时执行一次）。
 *
 * 阅读器只认固定页图片，所以电子书在这里拍平；转换结果只是缓存，不复制用户原文件。
 * 转换过程全程流式写 Zip：图片页出现一页写一页，文本页排版一页写一页，
 * 不再把所有页的 JPEG ByteArray 同时驻留内存。
 */
object FormatConverters {
    private const val CHUNK_CHARS = 20_000
    private val STORED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun toCbz(input: File, output: File, extension: String): Int {
        val ext = extension.lowercase()
        if (ext !in setOf("epub", "mobi", "azw", "azw3")) return 0
        val target = output.absoluteFile
        val dir = target.parentFile ?: throw IOException("缓存路径无效：$output")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建缓存目录：$dir")
        // 唯一临时文件：prepare() 与后台 worker 可能同时转换同一本书。
        val tmp = File.createTempFile(target.name + ".", ".tmp", dir)
        val count = try {
            when (ext) {
                "epub" -> epubToCbz(input, tmp)
                else -> mobiToCbz(input, tmp)
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        if (count <= 0) {
            tmp.delete()
            return 0
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("无法写入缓存文件：$target")
        }
        return count
    }

    /** spine 页：html 与图片 src 列表只解析一次，图片/文本分支复用。 */
    private class SpinePage(val resource: Resource, val html: String, val imageSources: List<String>)

    private fun collectSpinePages(book: Book): List<SpinePage> {
        val pages = ArrayList<SpinePage>()
        for (reference in book.spine.spineReferences) {
            val resource = reference.resource ?: continue
            val html = runCatching { resource.data.toString(Charsets.UTF_8) }.getOrNull() ?: continue
            pages += SpinePage(resource, html, imageSources(html))
        }
        return pages
    }

    /** 只遍历 spine 一次收集信息，再根据图片占比决定拍图还是排版文字。 */
    private fun epubToCbz(input: File, output: File): Int {
        val book = FileInputStream(input).use { EpubReader().readEpub(it) }
        val spineCount = book.spine.spineReferences.size
        val spinePages = collectSpinePages(book)
        var imagePages = 0
        for (page in spinePages) {
            for (src in page.imageSources) {
                if (resolveImage(book.resources, page.resource.href, src) != null) imagePages++
            }
        }
        val useImages = imagePages >= 2 && imagePages >= spineCount / 2
        return ZipOutputStream(BufferedOutputStream(FileOutputStream(output), 64 * 1024)).use { zip ->
            if (useImages) writeEpubImages(zip, book, spinePages) else writeEpubText(zip, book, spinePages)
        }
    }

    private fun writeEpubImages(zip: ZipOutputStream, book: Book, spinePages: List<SpinePage>): Int {
        var pages = 0
        for (page in spinePages) {
            for (src in page.imageSources) {
                val image = resolveImage(book.resources, page.resource.href, src) ?: continue
                if (image.isEmpty()) continue
                pages++
                if (pages > PageIndexing.MAX_ZIP_ENTRIES) throw IOException("电子书页数超出上限")
                writeImageEntry(zip, pages, image, imageExtension(image))
            }
        }
        return pages
    }

    private fun writeEpubText(zip: ZipOutputStream, book: Book, spinePages: List<SpinePage>): Int {
        val writer = TextPageWriter(zip)
        try {
            writer.writeCover(book.coverImage?.data)
            val pending = StringBuilder()
            for (page in spinePages) {
                if (page.imageSources.isNotEmpty()) continue
                pending.append(Html.fromHtml(page.html, Html.FROM_HTML_MODE_LEGACY).toString()).append('\n')
                while (pending.length >= CHUNK_CHARS) {
                    val cut = findChunkCut(pending, CHUNK_CHARS)
                    writer.append(pending.substring(0, cut))
                    pending.delete(0, cut)
                }
            }
            writer.append(pending.toString())
            if (writer.pageCount == 0) writer.append(book.title.orEmpty())
            return writer.finish()
        } finally {
            writer.recycle()
        }
    }

    private fun mobiToCbz(input: File, output: File): Int {
        val document = MobiReader().read(input)
        val text = Html.fromHtml(document.textContent ?: "", Html.FROM_HTML_MODE_LEGACY).toString()
        return ZipOutputStream(BufferedOutputStream(FileOutputStream(output), 64 * 1024)).use { zip ->
            val writer = TextPageWriter(zip)
            try {
                writer.writeCover(document.cover)
                writer.append(text.ifBlank { document.fullName.orEmpty() })
                writer.finish()
            } finally {
                writer.recycle()
            }
        }
    }

    private fun imageSources(html: String): List<String> =
        Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()

    private fun resolveImage(resources: Resources, baseHref: String, rawSrc: String): ByteArray? {
        val src = rawSrc.substringBefore('#').substringBefore('?')
        val baseDir = baseHref.substringBeforeLast('/', "")
        val combined = normalize(if (baseDir.isEmpty()) src else "$baseDir/$src")
        val resource: Resource? = resources.getByHref(combined)
            ?: resources.getByHref(src)
            ?: resources.all.firstOrNull {
                it.href.endsWith(src) || it.href.substringAfterLast('/') == src.substringAfterLast('/')
            }
        return runCatching { resource?.data?.takeIf { it.isNotEmpty() } }.getOrNull()
    }

    private fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (part in path.replace('\\', '/').split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out += part
            }
        }
        return out.joinToString("/")
    }

    private fun findChunkCut(text: StringBuilder, target: Int): Int {
        val floor = target * 3 / 5
        for (index in target downTo floor) {
            if (text[index - 1] == '\n') return index
        }
        return target
    }

    /** 已经是压缩格式的页图直接 STORED 写入，避免二次压缩；其余用默认 DEFLATE。 */
    private fun writeImageEntry(zip: ZipOutputStream, index: Int, bytes: ByteArray, extension: String) {
        val ext = extension.lowercase()
        val entry = ZipEntry("%04d.%s".format(index, ext))
        if (ext in STORED_IMAGE_EXTENSIONS && bytes.isNotEmpty()) {
            val crc = CRC32().apply { update(bytes) }
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = crc.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun imageExtension(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
        bytes.size > 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "gif"
        bytes.size > 12 && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() -> "webp"
        else -> "jpg"
    }
}

/**
 * 文字排版写入器：复用一张页面位图和一个 StaticLayout 尺寸，
 * 按块排版、逐页压缩进 Zip，页数连续。
 */
private class TextPageWriter(private val zip: ZipOutputStream) {
    private var pages = 0
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val paint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(26, 28, 30)
        textSize = TEXT_SIZE
        typeface = Typeface.DEFAULT
    }
    private val pageNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(115, 119, 127)
        textSize = 24f
        textAlign = Paint.Align.RIGHT
    }

    val pageCount: Int get() = pages

    fun writeCover(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        val cover = decodePage(bytes, WIDTH) ?: return
        try {
            writeBitmap(cover)
        } finally {
            cover.recycle()
        }
    }

    fun append(text: String) {
        if (text.isBlank()) return
        renderChunk(text)
    }

    fun finish(): Int {
        recycle()
        return pages
    }

    fun recycle() {
        runCatching { bitmap?.recycle() }
        bitmap = null
        canvas = null
    }

    private fun renderChunk(text: String) {
        if (text.isBlank()) return
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, CONTENT_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(LINE_SPACING, 1f)
            .setIncludePad(false)
            .build()
        if (layout.lineCount == 0) return
        var startLine = 0
        while (startLine < layout.lineCount) {
            var endLine = startLine
            while (endLine + 1 < layout.lineCount &&
                layout.getLineBottom(endLine + 1) - layout.getLineTop(startLine) <= CONTENT_HEIGHT
            ) {
                endLine++
            }
            val page = ensurePageBitmap()
            val target = canvas ?: return
            target.drawColor(Color.rgb(253, 252, 255))
            target.save()
            target.clipRect(MARGIN, MARGIN, WIDTH - MARGIN, HEIGHT - MARGIN - 24)
            target.translate(MARGIN.toFloat(), (MARGIN - layout.getLineTop(startLine)).toFloat())
            layout.draw(target)
            target.restore()
            target.drawText("${pages + 1}", (WIDTH - MARGIN).toFloat(), (HEIGHT - 42).toFloat(), pageNumberPaint)
            writeBitmap(page)
            startLine = endLine + 1
        }
    }

    private fun ensurePageBitmap(): Bitmap {
        bitmap?.let { return it }
        val created = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap = created
        canvas = Canvas(created)
        return created
    }

    private fun writeBitmap(bitmap: Bitmap) {
        val buffer = ByteArrayOutputStream(256 * 1024)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, buffer)
        pages++
        if (pages > PageIndexing.MAX_ZIP_ENTRIES) throw IOException("电子书页数超出上限")
        val bytes = buffer.toByteArray()
        val entry = ZipEntry("%04d.jpg".format(pages))
        val crc = CRC32().apply { update(bytes) }
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = crc.value
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1600
        const val MARGIN = 90
        const val CONTENT_WIDTH = WIDTH - MARGIN * 2
        const val CONTENT_HEIGHT = HEIGHT - MARGIN * 2
        const val TEXT_SIZE = 36f
        const val LINE_SPACING = 12f
    }
}
