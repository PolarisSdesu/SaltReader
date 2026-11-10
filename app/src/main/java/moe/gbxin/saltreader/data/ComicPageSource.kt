package moe.gbxin.saltreader.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.max

interface ComicPageSource : Closeable {
    val pageCount: Int
    val pageNames: List<String>
    suspend fun loadPage(index: Int, targetWidth: Int): Bitmap?
}

object ComicPageSourceFactory {
    fun open(book: ComicBook, root: File, cacheDir: File): ComicPageSource {
        val external = book.sourcePath?.let(::File)
        if (external != null) {
            val ext = external.extension.lowercase()
            return when {
                external.isDirectory -> FolderPageSource(external)
                ext == "pdf" -> PdfPageSource(external)
                ext in setOf("epub", "mobi", "azw", "azw3") -> {
                    val cache = File(cacheDir, "ebooks/${book.id}.cbz")
                    if (!cache.exists() || cache.length() == 0L) {
                        cache.parentFile?.mkdirs()
                        FormatConverters.toCbz(external, cache, ext)
                    }
                    ArchivePageSource(cache)
                }
                else -> ArchivePageSource(external)
            }
        }
        val content = File(root, book.contentPath)
        return when (book.kind) {
            BookKind.FOLDER -> FolderPageSource(content)
            BookKind.PDF -> PdfPageSource(content)
            BookKind.ARCHIVE -> ArchivePageSource(content)
        }
    }

    fun listPageNames(book: ComicBook, root: File): List<String> {
        val content = File(root, book.contentPath)
        return when (book.kind) {
            BookKind.FOLDER -> folderPages(content).map { it.name }
            BookKind.ARCHIVE -> zipPages(content)
            BookKind.PDF -> emptyList()
        }
    }

    fun externalPageCount(file: File): Int = when {
        file.isDirectory -> folderPages(file).size
        file.extension.lowercase() == "pdf" -> pdfPageCount(file)
        file.extension.lowercase() in setOf("epub", "mobi", "azw", "azw3") -> 0
        else -> zipPages(file).size
    }

    fun ebookExtension(file: File): Boolean =
        file.extension.lowercase() in setOf("epub", "mobi", "azw", "azw3")

    fun pdfPageCount(file: File): Int = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }
    }.getOrDefault(0)
}

internal val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "avif", "heic", "heif")

fun File.isComicImage(): Boolean {
    if (!isFile || isHidden) return false
    val ext = extension.lowercase()
    if (ext !in imageExtensions) return false
    return !name.startsWith("._")
}

private fun folderPages(dir: File): List<File> =
    dir.listFiles()?.filter { it.isComicImage() }
        ?.sortedWith(Comparator { a, b -> PageNameComparator.compare(a.name, b.name) })
        ?: emptyList()

private fun zipPages(file: File): List<String> = runCatching {
    ZipFile(file).use { zip ->
        zip.entries().toList()
            .filter { !it.isDirectory }
            .map { it.name }
            .filter { name ->
                val base = name.substringAfterLast('/')
                val ext = base.substringAfterLast('.', "").lowercase()
                !base.startsWith("__MACOSX") && !base.startsWith("._") && ext in imageExtensions
            }
            .sortedWith(PageNameComparator)
    }
}.getOrDefault(emptyList())

object PageNameComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val ai = a.lastIndexOf('/')
        val bi = b.lastIndexOf('/')
        val ad = if (ai >= 0) a.substring(0, ai) else ""
        val bd = if (bi >= 0) b.substring(0, bi) else ""
        if (ad != bd) return ad.compareTo(bd, ignoreCase = true)
        val an = a.substring(ai + 1)
        val bn = b.substring(bi + 1)
        var i = 0
        var j = 0
        while (i < an.length && j < bn.length) {
            val ca = an[i]
            val cb = bn[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ni = i
                while (ni < an.length && an[ni].isDigit()) ni++
                var nj = j
                while (nj < bn.length && bn[nj].isDigit()) nj++
                val na = an.substring(i, ni).trimStart('0').ifEmpty { "0" }.toLongOrNull()
                val nb = bn.substring(j, nj).trimStart('0').ifEmpty { "0" }.toLongOrNull()
                if (na != null && nb != null && na != nb) return na.compareTo(nb)
                i = ni
                j = nj
            } else {
                if (ca.lowercaseChar() != cb.lowercaseChar()) return ca.lowercaseChar().compareTo(cb.lowercaseChar())
                i++
                j++
            }
        }
        return (an.length - i).compareTo(bn.length - j).let { if (it != 0) it else a.compareTo(b) }
    }
}

private class ArchivePageSource(file: File) : ComicPageSource {
    private val zip = ZipFile(file)
    private val entries = zip.entries().toList()
        .filter { !it.isDirectory }
        .filter { entry ->
            val base = entry.name.substringAfterLast('/')
            val ext = base.substringAfterLast('.', "").lowercase()
            !entry.name.startsWith("__MACOSX") && !base.startsWith("._") && ext in imageExtensions
        }
        .sortedWith(compareBy(PageNameComparator) { it.name })

    override val pageNames: List<String> = entries.map { it.name }
    override val pageCount: Int get() = entries.size

    override suspend fun loadPage(index: Int, targetWidth: Int): Bitmap? {
        val entry = entries.getOrNull(index) ?: return null
        if (entry.size > 80L * 1024L * 1024L) return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        return decodePage(bytes, targetWidth)
    }

    override fun close() = zip.close()
}

private class FolderPageSource(dir: File) : ComicPageSource {
    private val pages = folderPages(dir)
    override val pageNames: List<String> = pages.map { it.name }
    override val pageCount: Int get() = pages.size

    override suspend fun loadPage(index: Int, targetWidth: Int): Bitmap? {
        val file = pages.getOrNull(index) ?: return null
        if (file.length() > 80L * 1024L * 1024L) return null
        return decodePage(file.readBytes(), targetWidth)
    }

    override fun close() = Unit
}

fun decodePage(bytes: ByteArray, targetWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val safeTarget = max(360, targetWidth)
    while (bounds.outWidth / (sample * 2) >= safeTarget) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inDither = true
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    val rotation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (rotation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        if (it != bitmap) bitmap.recycle()
    }
}

fun decodeCover(input: InputStream, maxWidth: Int = 480): Bitmap? {
    val bytes = input.use { it.readBytes() }
    val b = decodePage(bytes, maxWidth) ?: return null
    val maxSide = max(b.width, b.height)
    if (maxSide <= 1600) return b
    val scale = 1600f / maxSide
    return Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true).also {
        if (it != b) b.recycle()
    }
}


private class PdfPageSource(file: File) : ComicPageSource {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)

    override val pageCount: Int get() = renderer.pageCount
    override val pageNames: List<String> = (1..renderer.pageCount).map { "page_$it" }

    override suspend fun loadPage(index: Int, targetWidth: Int): Bitmap? = runCatching {
        renderer.openPage(index).use { page ->
            val scale = targetWidth.toFloat() / page.width
            val width = targetWidth.coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }.getOrNull()

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}
