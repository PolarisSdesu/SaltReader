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

    /** ZIP 流式索引结果；pageCount 为 -1 表示文件损坏或超出安全上限。 */
    data class ZipIndex(val pageCount: Int, val coverBytes: ByteArray?)

    fun isComicImageName(name: String): Boolean {
        val base = name.substringAfterLast('/')
        if (base.isEmpty() || base.startsWith("._") || base.startsWith(".")) return false
        val ext = base.substringAfterLast('.', "").lowercase()
        return ext in imageExtensions
    }

    /**
     * 用 ZipInputStream 流式统计图片页数，顺便抓取第一张图片用于生成封面：
     * 既不复制整包，也让索引阶段不必为封面再解析一次压缩包。
     *
     * 成功返回页数（可能为 0），文件损坏或超出安全上限返回 -1。
     */
    fun indexZip(input: InputStream): ZipIndex {
        var entries = 0
        var images = 0
        var declaredUncompressed = 0L
        var coverBytes: ByteArray? = null
        return try {
            val buffered = BufferedInputStream(input, 64 * 1024)
            // ZipInputStream quietly reports "no entries" for garbage instead of throwing, so a
            // corrupt archive would look like an empty one. Validate the local-file signature
            // up front and report -1 (corrupt) to the caller.
            buffered.mark(4)
            val signature = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = buffered.read(signature, read, 4 - read)
                if (n <= 0) break
                read += n
            }
            buffered.reset()
            val isZip = read == 4 &&
                signature[0] == 0x50.toByte() && signature[1] == 0x4B.toByte() && (
                    // local file header (PK\x03\x04), empty archive (PK\x05\x06) or spanned (PK\x07\x08)
                    (signature[2].toInt() == 0x03 && signature[3].toInt() == 0x04) ||
                        (signature[2].toInt() == 0x05 && signature[3].toInt() == 0x06) ||
                        (signature[2].toInt() == 0x07 && signature[3].toInt() == 0x08)
                    )
            if (!isZip) return ZipIndex(-1, null)
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ZIP_ENTRIES) return ZipIndex(-1, null)
                    if (!entry.isDirectory) {
                        if (entry.size > 0L) declaredUncompressed += entry.size
                        if (declaredUncompressed > MAX_ZIP_UNCOMPRESSED) return ZipIndex(-1, null)
                        if (isComicImageName(entry.name)) {
                            images++
                            if (coverBytes == null && (entry.size <= 0L || entry.size <= MAX_PAGE_BYTES)) {
                                coverBytes = readEntryBounded(zip, MAX_PAGE_BYTES)
                            }
                        }
                    }
                }
                ZipIndex(images, coverBytes)
            }
        } catch (e: IOException) {
            ZipIndex(-1, null)
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

    private fun readEntryBounded(input: InputStream, maxBytes: Long): ByteArray? {
        val output = java.io.ByteArrayOutputStream(64 * 1024)
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
}
