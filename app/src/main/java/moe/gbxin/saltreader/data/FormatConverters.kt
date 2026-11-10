package moe.gbxin.saltreader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.documentnode.epub4j.domain.Resource
import io.documentnode.epub4j.domain.Resources
import io.documentnode.epub4j.epub.EpubReader
import org.rr.mobi4java.MobiReader
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB / MOBI -> CBZ conversion at import time.
 *
 * The reader only deals with fixed page images, so e-books are flattened once
 * instead of adding a second rendering pipeline to the reading UI.
 */
object FormatConverters {
    fun toCbz(input: File, output: File, extension: String): Int {
        output.parentFile?.mkdirs()
        return when (extension) {
            "epub" -> epubToCbz(input, output)
            "mobi", "azw", "azw3" -> mobiToCbz(input, output)
            else -> 0
        }
    }

    private fun epubToCbz(input: File, output: File): Int {
        val book = FileInputStream(input).use { EpubReader().readEpub(it) }
        val spine = book.spine.spineReferences
        val images = mutableListOf<ByteArray>()
        val text = StringBuilder()
        for (reference in spine) {
            val resource = reference.resource ?: continue
            val html = runCatching { resource.data.toString(Charsets.UTF_8) }.getOrNull() ?: continue
            val sources = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1] }.toList()
            if (sources.isEmpty()) {
                text.append(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()).append('\n')
            } else {
                for (src in sources) {
                    val image = resolveImage(book.resources, resource.href, src) ?: continue
                    if (image.isNotEmpty()) images += image
                }
            }
        }
        val pages = if (images.size >= 2 && images.size >= spine.size / 2) {
            images
        } else {
            TextPageWriter.render(text.toString(), book.coverImage?.data, book.title)
        }
        return writeCbz(output, pages)
    }

    private fun mobiToCbz(input: File, output: File): Int {
        val document = MobiReader().read(input)
        val text = Html.fromHtml(document.textContent, Html.FROM_HTML_MODE_LEGACY).toString()
        val pages = TextPageWriter.render(text, document.cover, document.fullName)
        return writeCbz(output, pages)
    }

    private fun resolveImage(resources: Resources, baseHref: String, rawSrc: String): ByteArray? {
        val src = rawSrc.substringBefore('#').substringBefore('?')
        val baseDir = baseHref.substringBeforeLast('/', "")
        val combined = normalize(if (baseDir.isEmpty()) src else "$baseDir/$src")
        val resource: Resource? = resources.getByHref(combined)
            ?: resources.getByHref(src)
            ?: resources.all.firstOrNull { it.href.endsWith(src) || it.href.substringAfterLast('/') == src.substringAfterLast('/') }
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

    private fun writeCbz(output: File, pages: List<ByteArray>): Int {
        if (pages.isEmpty()) return 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            pages.forEachIndexed { index, bytes ->
                val ext = imageExtension(bytes)
                zip.putNextEntry(ZipEntry("%04d.%s".format(index + 1, ext)))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return pages.size
    }

    private fun imageExtension(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
        bytes.size > 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "gif"
        bytes.size > 12 && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() -> "webp"
        else -> "jpg"
    }
}

private object TextPageWriter {
    private const val WIDTH = 1080
    private const val HEIGHT = 1600
    private const val MARGIN = 90
    private const val CONTENT_WIDTH = WIDTH - MARGIN * 2
    private const val CONTENT_HEIGHT = HEIGHT - MARGIN * 2
    private const val TEXT_SIZE = 36f
    private const val LINE_SPACING = 12f

    fun render(text: String, cover: ByteArray?, title: String?): List<ByteArray> {
        val pages = mutableListOf<ByteArray>()
        cover?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                pages += bitmapToJpeg(bitmap)
                bitmap.recycle()
            }
        }
        val safeText = text.trim().ifBlank { title.orEmpty() }
        if (safeText.isNotBlank()) {
            pages += textToJpegPages(safeText)
        }
        return pages
    }

    private fun textToJpegPages(text: String): List<ByteArray> {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(26, 28, 30)
            textSize = TEXT_SIZE
            typeface = Typeface.DEFAULT
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, CONTENT_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(LINE_SPACING, 1f)
            .setIncludePad(false)
            .build()
        if (layout.lineCount == 0) return emptyList()
        val pages = mutableListOf<ByteArray>()
        var startLine = 0
        while (startLine < layout.lineCount) {
            var endLine = startLine
            while (endLine + 1 < layout.lineCount &&
                layout.getLineBottom(endLine + 1) - layout.getLineTop(startLine) <= CONTENT_HEIGHT
            ) {
                endLine++
            }
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(253, 252, 255))
            canvas.save()
            canvas.clipRect(MARGIN, MARGIN, WIDTH - MARGIN, HEIGHT - MARGIN - 24)
            canvas.translate(MARGIN.toFloat(), (MARGIN - layout.getLineTop(startLine)).toFloat())
            layout.draw(canvas)
            canvas.restore()
            val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(115, 119, 127)
                textSize = 24f
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText("${pages.size + 1}", (WIDTH - MARGIN).toFloat(), (HEIGHT - 42).toFloat(), pagePaint)
            pages += bitmapToJpeg(bitmap)
            bitmap.recycle()
            startLine = endLine + 1
        }
        return pages
    }

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
        return output.toByteArray()
    }
}
