package moe.gbxin.saltreader.data

import android.content.ContentResolver
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 索引阶段的页数统计与资源安全阈值。
 *
 * 这些上限是「防止恶意/损坏文件拖垮应用」的硬约束，不只是性能优化：
 * - 单页文件读取上限，避免一张超大图直接 OOM；
 * - 解码总像素上限，超长条漫也只在缩略分辨率下解码；
 * - ZIP 条目数 / 解压体积上限，缓解压缩炸弹。
 */
internal object PageIndexing {
    /** 单页图片允许读取的最大字节数（解码前先判大小）。 */
    const val MAX_PAGE_BYTES: Long = 32L * 1024L * 1024L

    /** 解码后位图的总像素上限（约 16MP）。 */
    const val MAX_DECODE_PIXELS: Long = 16_000_000L

    /** 压缩包允许的最大条目数。 */
    const val MAX_ZIP_ENTRIES: Int = 100_000

    /** 压缩包允许声明的最大解压体积。 */
    const val MAX_ZIP_UNCOMPRESSED: Long = 8L * 1024L * 1024L * 1024L

    fun isComicImageName(name: String): Boolean {
        val base = name.substringAfterLast('/')
        if (base.isEmpty() || base.startsWith("._") || base.startsWith(".")) return false
        val ext = base.substringAfterLast('.', "").lowercase()
        return ext in imageExtensions
    }

    /**
     * 用 ZipInputStream 流式统计图片页数，不做整包复制。
     * 成功返回页数（可能为 0），文件损坏或超出安全上限返回 -1。
     */
    fun countZipPages(input: InputStream): Int {
        var entries = 0
        var images = 0
        var declaredUncompressed = 0L
        return try {
            ZipInputStream(BufferedInputStream(input, 64 * 1024)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ZIP_ENTRIES) return -1
                    if (!entry.isDirectory) {
                        if (entry.size > 0L) declaredUncompressed += entry.size
                        if (declaredUncompressed > MAX_ZIP_UNCOMPRESSED) return -1
                        if (isComicImageName(entry.name)) images++
                    }
                }
                images
            }
        } catch (e: IOException) {
            -1
        }
    }

    /** 用 ContentResolver 打开 SAF PDF 并统计页数；失败返回 0。 */
    fun pdfPageCount(resolver: ContentResolver, uri: Uri): Int {
        val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return 0
        var renderer: PdfRenderer? = null
        return try {
            renderer = PdfRenderer(descriptor)
            renderer.pageCount
        } catch (e: Exception) {
            0
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor.close() }
        }
    }
}
