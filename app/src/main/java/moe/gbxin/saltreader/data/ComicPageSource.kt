package moe.gbxin.saltreader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 页面源接口（契约收敛）：
 * - [pageCount] 直接取索引阶段算好的 `book.pageCount`，source 不做页面枚举 I/O；
 * - [loadPage] 可能做阻塞 I/O（复制缓存、打开 zip/pdf），调用方在 IO 线程调用；
 *   内部也统一切到 Dispatchers.IO，避免误在主线程调用时 ANR；
 * - [close] 释放文件句柄/渲染器。
 */
interface ComicPageSource : Closeable {
    val pageCount: Int

    suspend fun loadPage(index: Int, targetWidth: Int): Bitmap?
}

/** 内部阻塞式读取接口：同一文件内的页面源互相复用真正干活的实现，避免二次调度。 */
private interface BlockingPageSource {
    fun loadBlocking(index: Int, targetWidth: Int): Bitmap?
}

/** 所有页面源的公共基类：惰性打开逻辑在子类中，loadPage 统一在 IO 线程执行。 */
private abstract class LazyPageSource(override val pageCount: Int) : ComicPageSource, BlockingPageSource {
    final override suspend fun loadPage(index: Int, targetWidth: Int): Bitmap? =
        withContext(Dispatchers.IO) { loadBlocking(index, targetWidth) }
}

/** 书元数据无效（既不是 SAF 也没有旧副本）时返回空源，保证 pageSource() 不抛异常。 */
private class EmptyPageSource(override val pageCount: Int) : ComicPageSource {
    override suspend fun loadPage(index: Int, targetWidth: Int): Bitmap? = null
    override fun close() = Unit
}

/** 页面源工厂：同步构造、零 I/O；具体打开动作推迟到第一次 loadPage。 */
internal object ComicPageSourceFactory {
    fun open(book: ComicBook, context: Context, cacheDir: File, ebookCacheDir: File): ComicPageSource {
        if (book.pageCount <= 0) return EmptyPageSource(book.pageCount)
        return when {
            book.isLegacyCopy -> LegacyCopyPageSource(book, context.filesDir)
            book.isSaf -> when {
                book.kind == BookKind.FOLDER -> SafFolderPageSource(book, context)
                book.kind == BookKind.PDF -> SafPdfPageSource(book, context)
                isEbookBook(book) -> EbookCachePageSource(book, context, ebookCacheDir)
                else -> SafArchivePageSource(book, context, cacheDir)
            }
            else -> EmptyPageSource(book.pageCount)
        }
    }
}

/** 外部 EPUB/MOBI 的派生缓存：`cacheDir/ebooks/<id>.cbz`，缺失时按需重建。 */
private class EbookCachePageSource(
    private val book: ComicBook,
    private val context: Context,
    private val ebookCacheDir: File,
) : LazyPageSource(book.pageCount) {
    private val lock = Any()
    private var delegate: FileArchivePageSource? = null
    private var closed = false

    override fun loadBlocking(index: Int, targetWidth: Int): Bitmap? {
        val source = ensureOpen() ?: return null
        return source.loadBlocking(index, targetWidth)
    }

    private fun ensureOpen(): FileArchivePageSource? = synchronized(lock) {
        if (closed) return null
        delegate?.let { return it }
        val cache = File(ebookCacheDir, "${book.id}.cbz")
        if (!cache.exists() || cache.length() == 0L) {
            if (!rebuildCache(cache)) return null
        }
        val source = FileArchivePageSource(cache, pageCount)
        delegate = source
        source
    }

    private fun rebuildCache(cache: File): Boolean {
        val ext = ebookExtensionOf(book) ?: return false
        val treeUri = book.treeUri ?: return false
        val tree = runCatching { SafTree(context, Uri.parse(treeUri)) }.getOrNull() ?: return false
        val entry = tree.find(book.documentPath) ?: return false
        if (entry.isDirectory) return false
        val tempDir = File(context.cacheDir, "tmp").apply { mkdirs() }
        val temp = runCatching { File.createTempFile("ebook-", ".$ext", tempDir) }.getOrNull() ?: return false
        cache.parentFile?.mkdirs()
        return try {
            context.contentResolver.openInputStream(entry.uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return false
            FormatConverters.toCbz(temp, cache, ext) > 0
        } catch (e: Exception) {
            false
        } finally {
            temp.delete()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            runCatching { delegate?.close() }
            delegate = null
        }
    }
}

/** SAF 压缩包：首次读取时把文档流式复制到缓存，再用 ZipFile 随机访问。 */
private class SafArchivePageSource(
    private val book: ComicBook,
    private val context: Context,
    private val cacheDir: File,
) : LazyPageSource(book.pageCount) {
    private val lock = Any()
    private var delegate: FileArchivePageSource? = null
    private var closed = false

    private fun ensureOpen(): FileArchivePageSource? = synchronized(lock) {
        if (closed) return null
        delegate?.let { return it }
        val treeUri = book.treeUri ?: return null
        val tree = runCatching { SafTree(context, Uri.parse(treeUri)) }.getOrNull() ?: return null
        val entry = tree.find(book.documentPath) ?: return null
        if (entry.isDirectory) return null
        val target = bookCacheFile(book, cacheDir)
        if (!target.exists() || target.length() == 0L) {
            if (!copyDocumentToCache(context, entry.uri, target)) return null
        }
        val source = FileArchivePageSource(target, pageCount)
        delegate = source
        source
    }

    override fun loadBlocking(index: Int, targetWidth: Int): Bitmap? {
        val source = ensureOpen() ?: return null
        return source.loadBlocking(index, targetWidth)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            runCatching { delegate?.close() }
            delegate = null
        }
    }
}

/** SAF 图片文件夹：惰性列举直接子级图片（自然序），按 index 打开输入流。 */
private class SafFolderPageSource(
    private val book: ComicBook,
    private val context: Context,
) : LazyPageSource(book.pageCount) {
    private val lock = Any()
    private var pages: List<Uri>? = null
    private var closed = false

    private fun ensurePages(): List<Uri>? = synchronized(lock) {
        if (closed) return null
        pages?.let { return it }
        val treeUri = book.treeUri ?: return null
        val tree = runCatching { SafTree(context, Uri.parse(treeUri)) }.getOrNull() ?: return null
        val folder = tree.find(book.documentPath) ?: return null
        if (!folder.isDirectory) return null
        val children = tree.children(folder.documentId, folder.documentPath) ?: return null
        val list = children
            .filter { !it.isDirectory && PageIndexing.isComicImageName(it.name) }
            .sortedWith(compareBy(PageNameComparator) { it.name })
            .map { it.uri }
        pages = list
        list
    }

    override fun loadBlocking(index: Int, targetWidth: Int): Bitmap? {
        val list = ensurePages() ?: return null
        val uri = list.getOrNull(index) ?: return null
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { readBounded(it) }
        }.getOrNull() ?: return null
        return decodePage(bytes, targetWidth)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            pages = null
        }
    }
}

/** SAF PDF：惰性 openFileDescriptor → PdfRenderer，关闭时释放 fd。 */
private class SafPdfPageSource(
    private val book: ComicBook,
    private val context: Context,
) : LazyPageSource(book.pageCount) {
    private val lock = Any()
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var closed = false

    private fun ensureOpen(): PdfRenderer? = synchronized(lock) {
        if (closed) return null
        renderer?.let { return it }
        val treeUri = book.treeUri ?: return null
        val tree = runCatching { SafTree(context, Uri.parse(treeUri)) }.getOrNull() ?: return null
        val entry = tree.find(book.documentPath) ?: return null
        if (entry.isDirectory) return null
        val fd = runCatching { context.contentResolver.openFileDescriptor(entry.uri, "r") }.getOrNull() ?: return null
        val pdf = try {
            PdfRenderer(fd)
        } catch (t: Exception) {
            runCatching { fd.close() }
            return null
        }
        descriptor = fd
        renderer = pdf
        pdf
    }

    override fun loadBlocking(index: Int, targetWidth: Int): Bitmap? {
        val pdf = ensureOpen() ?: return null
        return renderPdfPage(pdf, index, targetWidth)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            renderer = null
            descriptor = null
        }
    }
}

/** 旧版本应用内副本（只读兼容）：内容位于 filesDir/library/content。 */
private class LegacyCopyPageSource(
    book: ComicBook,
    filesDir: File,
) : ComicPageSource {
    override val pageCount: Int = book.pageCount
    private val delegate: ComicPageSource = run {
        val file = File(File(filesDir, "library"), book.contentPath)
        when (book.kind) {
            BookKind.FOLDER -> FileFolderPageSource(file, pageCount)
            BookKind.PDF -> FilePdfPageSource(file, pageCount)
            BookKind.ARCHIVE -> FileArchivePageSource(file, pageCount)
        }
    }

    override suspend fun loadPage(index: Int, targetWidth: Int): Bitmap? = delegate.loadPage(index, targetWidth)

    override fun close() = delegate.close()
}

/** 本地压缩包（旧副本/电子书派生缓存）：惰性打开 ZipFile。 */
private class FileArchivePageSource(
    private val file: File,
    override val pageCount: Int,
) : LazyPageSource(pageCount) {
    private val lock = Any()
    private var zip: ZipFile? = null
    private var entries: List<ZipEntry>? = null
    private var closed = false

    private fun ensureOpen(): Pair<ZipFile, List<ZipEntry>>? = synchronized(lock) {
        if (closed) return null
        val openedZip = zip
        val openedEntries = entries
        if (openedZip != null && openedEntries != null) return openedZip to openedEntries
        val newZip = try {
            ZipFile(file)
        } catch (t: Exception) {
            return null
        }
        val newEntries = try {
            newZip.imageEntries()
        } catch (t: Exception) {
            runCatching { newZip.close() }
            return null
        }
        zip = newZip
        entries = newEntries
        newZip to newEntries
    }

    override fun loadBlocking(index: Int, targetWidth: Int): Bitmap? {
        val (zipFile, pageEntries) = ensureOpen() ?: return null
        val entry = pageEntries.getOrNull(index) ?: return null
        if (entry.size > PageIndexing.MAX_PAGE_BYTES) return null
        val bytes = try {
            zipFile.getInputStream(entry).use { readBounded(it) }
        } catch (t: Exception) {
            null
        } ?: return null
        return decodePage(bytes, targetWidth)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            runCatching { zip?.close() }
            zip = null
            entries = null
        }
    }
}

/** 本地图片文件夹（旧副本）。 */
private class FileFolderPageSource(
    private val dir: File,
    override val pageCount: Int,
) : LazyPageSource(pageCount) {
    private val lock = Any()
    private var pages: List<File>? = null
    private var closed = false

    private fun ensurePages(): List<File>? = synchronized(lock) {
        if (closed) return null
        pages?.let { return it }
        val list = dir.listFiles()
            ?.filter { it.isComicImage() }
            ?.sortedWith(Comparator { a, b -> PageNameComparator.compare(a.name, b.name) })
            ?: return null
        pages = list
        list
    }

    override fun loadBlocking(index: Int, targetWidth: Int): Bitmap? {
        val list = ensurePages() ?: return null
        val file = list.getOrNull(index) ?: return null
        if (file.length() > PageIndexing.MAX_PAGE_BYTES) return null
        val bytes = runCatching { file.inputStream().use { readBounded(it) } }.getOrNull() ?: return null
        return decodePage(bytes, targetWidth)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            pages = null
        }
    }
}

/** 本地 PDF（旧副本）。 */
private class FilePdfPageSource(
    private val file: File,
    override val pageCount: Int,
) : LazyPageSource(pageCount) {
    private val lock = Any()
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var closed = false

    private fun ensureOpen(): PdfRenderer? = synchronized(lock) {
        if (closed) return null
        renderer?.let { return it }
        val fd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Exception) {
            return null
        }
        val pdf = try {
            PdfRenderer(fd)
        } catch (t: Exception) {
            runCatching { fd.close() }
            return null
        }
        descriptor = fd
        renderer = pdf
        pdf
    }

    override fun loadBlocking(index: Int, targetWidth: Int): Bitmap? {
        val pdf = ensureOpen() ?: return null
        return renderPdfPage(pdf, index, targetWidth)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            renderer = null
            descriptor = null
        }
    }
}

// ---------------------------------------------------------------------------
// 共享工具
// ---------------------------------------------------------------------------

internal fun bookCacheFile(book: ComicBook, cacheDir: File): File {
    val rawExt = book.documentPath?.substringAfterLast('.', "").orEmpty().lowercase()
    val ext = rawExt.filter { it.isLetterOrDigit() }.ifBlank { "zip" }
    return File(cacheDir, "${book.id}_${book.modifiedAt}_${book.sizeBytes}.$ext")
}

internal val ebookExtensions: Set<String> = setOf("epub", "mobi", "azw", "azw3")

internal fun ebookExtensionOf(book: ComicBook): String? {
    if (!book.isSaf) return null
    val ext = book.documentPath?.substringAfterLast('.', "").orEmpty().lowercase()
    return ext.takeIf { it in ebookExtensions }
}

internal fun isEbookBook(book: ComicBook): Boolean = ebookExtensionOf(book) != null

internal fun ZipFile.imageEntries(): List<ZipEntry> {
    val all = entries().toList()
    if (all.size > PageIndexing.MAX_ZIP_ENTRIES) throw IOException("压缩包条目过多")
    return all
        .filter { !it.isDirectory && PageIndexing.isComicImageName(it.name) }
        .sortedWith(compareBy(PageNameComparator) { it.name })
}

/** 把 SAF 文档流式复制到本地缓存：先写 .tmp，成功后 rename。 */
private fun copyDocumentToCache(context: Context, uri: Uri, target: File): Boolean = try {
    writeFileAtomically(target) { out ->
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.copyTo(out, 64 * 1024)
        } ?: throw IOException("无法读取文件")
    }
    true
} catch (t: Exception) {
    false
}

/** 有上限的流式读取；超过 [maxBytes] 返回 null（视为损坏/超限文件）。 */
internal fun readBounded(
    input: InputStream,
    maxBytes: Long = PageIndexing.MAX_PAGE_BYTES,
): ByteArray? {
    val output = ByteArrayOutputStream(64 * 1024)
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

/**
 * 解码一页图片：
 * - inSampleSize 同时约束目标宽度与总像素（约 16MP），超长条漫也不会 OOM；
 * - EXIF 只在 JPEG 上解析（其他格式解析既无意义又可能误判）。
 */
internal fun decodePage(bytes: ByteArray, targetWidth: Int): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null

    val safeTarget = targetWidth.coerceIn(240, 4096)
    var sample = 1
    while (sample < 4096) {
        val next = sample * 2
        if (width / next < safeTarget) break
        sample = next
    }
    while (sample < 4096) {
        val pixels = (width.toLong() / sample) * (height.toLong() / sample)
        if (pixels <= PageIndexing.MAX_DECODE_PIXELS) break
        sample *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    if (!isJpeg(bytes)) return bitmap

    val degrees = runCatching {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (degrees == 0f) return bitmap
    return runCatching {
        val matrix = Matrix().apply { postRotate(degrees) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrElse { bitmap }.also { rotated ->
        if (rotated !== bitmap) bitmap.recycle()
    }
}

/** 渲染 PDF 页：宽高双重 clamp，避免超长页/超大页 OOM。 */
private fun renderPdfPage(renderer: PdfRenderer, index: Int, targetWidth: Int): Bitmap? {
    if (index < 0 || index >= renderer.pageCount) return null
    var opened: PdfRenderer.Page? = null
    return try {
        val page = renderer.openPage(index)
        opened = page
        val pageWidth = page.width.coerceAtLeast(1)
        val pageHeight = page.height.coerceAtLeast(1)
        val target = targetWidth.coerceIn(240, 4096)
        val scale = target.toDouble() / pageWidth
        var width = (pageWidth * scale).toInt().coerceAtLeast(1)
        var height = (pageHeight * scale).toInt().coerceAtLeast(1)

        val maxSide = 8192
        if (width > maxSide || height > maxSide) {
            val shrink = min(maxSide.toDouble() / width, maxSide.toDouble() / height)
            width = (width * shrink).toInt().coerceAtLeast(1)
            height = (height * shrink).toInt().coerceAtLeast(1)
        }
        val pixels = width.toLong() * height.toLong()
        if (pixels > PageIndexing.MAX_DECODE_PIXELS) {
            val shrink = sqrt(PageIndexing.MAX_DECODE_PIXELS.toDouble() / pixels)
            width = (width * shrink).toInt().coerceAtLeast(1)
            height = (height * shrink).toInt().coerceAtLeast(1)
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap
    } catch (t: Exception) {
        null
    } finally {
        runCatching { opened?.close() }
    }
}

internal val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "avif", "heic", "heif")

fun File.isComicImage(): Boolean {
    if (!isFile || isHidden) return false
    if (!PageIndexing.isComicImageName(name)) return false
    return !name.startsWith("._")
}

/**
 * 自然排序：目录优先按目录名比较，文件名中的数字按数值比较
 * （`2.jpg` 排在 `10.jpg` 前面）。测试也依赖这一行为，修改前请同步 test。
 */
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
