package moe.gbxin.saltreader.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.min

/**
 * 轻量远程 ZIP 读取器。
 *
 * 通过 SAF `openFileDescriptor` 拿到的可 seek 文件描述符，只读取 EOCD（含 ZIP64）
 * 与 central directory，就能枚举条目、统计图片页数；需要小文件内容时再按条目名做
 * 局部 inflate。**绝不复制整包、也不解压整包**，因此几十本 CBZ/EPUB 的索引只需要
 * 读几个 KB~MB 的元数据。
 *
 * 支持范围与降级：
 * - STORED 与 DEFLATE（ZIP 方法 0/8）；
 * - ZIP64（EOCD locator / ZIP64 EOCD / 条目 extra field）；
 * - 加密条目视为不可读（image 统计会跳过，readEntry 返回 null）；
 * - provider 不支持 seek、文件损坏、条目超过安全上限时 [open] 返回 null，
 *   调用方回退到"流式 ZipInputStream 统计"的慢路径。
 */
internal class RemoteZipReader internal constructor(
    private val descriptor: ParcelFileDescriptor?,
    private val channel: FileChannel,
) : Closeable {
    private val lock = Any()
    private var cachedEntries: List<Entry>? = null
    private var cachedEntryIndex: HashMap<String, Entry>? = null

    /** central directory 中的一条记录（只保留读取所需字段）。 */
    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val encrypted: Boolean,
        /** central directory 记录的名字/extra 字节长度：合并读取时用来算数据偏移。 */
        val nameBytes: Int = 0,
        val extraBytes: Int = 0,
    )

    fun entries(): List<Entry> = synchronized(lock) {
        cachedEntries ?: parseCentralDirectory().also { cachedEntries = it }
    }

    /** 条目名→Entry 索引：与 [entries] 一样惰性构建并缓存，避免每次按名字线性扫描。 */
    private fun entryIndex(): HashMap<String, Entry> = synchronized(lock) {
        cachedEntryIndex ?: run {
            val list = entries()
            val index = HashMap<String, Entry>(list.size)
            for (entry in list) {
                // 同名条目保持与 firstOrNull 一致的"先出现者优先"语义。
                if (!index.containsKey(entry.name)) index[entry.name] = entry
            }
            cachedEntryIndex = index
            index
        }
    }

    /** 按名字取 central directory 条目；不存在返回 null。 */
    fun entry(name: String): Entry? = entryIndex()[name]

    /** 图片条目，按自然序；加密条目不算可读页。 */
    fun imageEntries(): List<Entry> = entries()
        .filter { !it.encrypted && PageIndexing.isComicImageName(it.name) }
        .sortedWith(compareBy(PageNameComparator) { it.name })

    /**
     * 按条目名读取并解压一个小文件（EPUB container.xml / OPF、CBZ 首图等）。
     * 超过 [maxBytes] 或加密/不支持的压缩算法时返回 null。
     */
    fun readEntry(name: String, maxBytes: Long): ByteArray? =
        entry(name)?.let { readEntry(it, maxBytes) }

    /**
     * 条目内容的零拷贝视图。ZIP 页面读取会把「本地头 + 名字/extra + 压缩数据」一次读进同一块
     * 缓冲区，数据只是其中一个区间；直接把这个区间交给解码器，避免再复制一份整页字节。
     */
    class EntryView internal constructor(
        val bytes: ByteArray,
        val offset: Int,
        val length: Int,
    ) {
        /** 需要独立数组的调用方（container.xml / OPF 等小文件）再复制一次。 */
        fun toByteArray(): ByteArray =
            if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length)
    }

    /**
     * 直接按已解析的 [Entry] 读取：页面源把条目列表整个缓存下来，
     * 每页不必再用名字查找，避免 O(条目数) 的重复扫描。
     * 超过 [maxBytes] 或加密/不支持的压缩算法时返回 null。
     */
    fun readEntry(entry: Entry, maxBytes: Long): ByteArray? =
        readEntryView(entry, maxBytes)?.toByteArray()

    /**
     * 按条目读取并返回字节视图：优先「一次 pread 读完整条记录」，
     * 比原来「头 30B + 数据」两次 pread 少一次系统调用（SAF/FUSE 上收益更明显），
     * STORED 条目还能零拷贝。本地头与中央目录长度不一致等异常包回退到两次 pread。
     */
    fun readEntryView(entry: Entry, maxBytes: Long): EntryView? {
        if (entry.encrypted) return null
        if (entry.uncompressedSize > maxBytes || entry.compressedSize > maxBytes) return null
        if (entry.compressedSize < 0L || entry.uncompressedSize < 0L) return null
        if (entry.compressedSize == 0L && entry.uncompressedSize > 0L) return null
        if (entry.uncompressedSize > Int.MAX_VALUE || entry.compressedSize > Int.MAX_VALUE) return null
        return readCombined(entry, maxBytes) ?: readTwoStep(entry, maxBytes)
    }

    /** 一次 pread 读「本地头 + 名字/extra + 压缩数据」，再按压缩方式解出视图。 */
    private fun readCombined(entry: Entry, maxBytes: Long): EntryView? {
        val nameBytes = entry.nameBytes
        val extraBytes = entry.extraBytes
        if (nameBytes <= 0 || nameBytes > MAX_LOCAL_NAME_BYTES) return null
        if (extraBytes < 0 || extraBytes > MAX_LOCAL_NAME_BYTES) return null
        if (entry.compressedSize > MAX_COMBINED_READ_BYTES) return null
        val total = LOCAL_HEADER_SIZE.toLong() + nameBytes + extraBytes + entry.compressedSize
        if (total <= LOCAL_HEADER_SIZE || total > MAX_COMBINED_READ_BYTES) return null

        val buffer = ByteArray(total.toInt())
        val wrapped = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        readFully(wrapped, entry.localHeaderOffset)
        if (wrapped.getInt(0) != LOCAL_HEADER_SIG) return null
        // 本地头长度字段必须与中央目录一致，否则「一次读」的偏移不可信，退回两次 pread。
        val localNameLength = wrapped.getShort(26).toInt() and 0xFFFF
        val localExtraLength = wrapped.getShort(28).toInt() and 0xFFFF
        if (localNameLength != nameBytes || localExtraLength != extraBytes) return null
        val dataOffset = LOCAL_HEADER_SIZE + localNameLength + localExtraLength
        if (dataOffset + entry.compressedSize != total) return null

        return when (entry.method) {
            // STORED：数据原样在缓冲区里，直接返回区间视图（零拷贝）。
            METHOD_STORED -> EntryView(buffer, dataOffset, entry.compressedSize.toInt())
            METHOD_DEFLATE -> {
                val inflated = inflateExact(
                    buffer,
                    dataOffset,
                    entry.compressedSize.toInt(),
                    entry.uncompressedSize.toInt(),
                ) ?: return null
                EntryView(inflated, 0, inflated.size)
            }
            else -> null
        }
    }

    /** 兼容路径：先读 30B 本地头拿真实长度，再按偏移读数据；处理本地头与中央目录不一致的包。 */
    private fun readTwoStep(entry: Entry, maxBytes: Long): EntryView? {
        val header = ByteBuffer.allocate(LOCAL_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        readFully(header, entry.localHeaderOffset)
        if (header.getInt(0) != LOCAL_HEADER_SIG) return null
        val localNameLength = header.getShort(26).toInt() and 0xFFFF
        val localExtraLength = header.getShort(28).toInt() and 0xFFFF
        val dataOffset = entry.localHeaderOffset + LOCAL_HEADER_SIZE + localNameLength + localExtraLength

        val compressed = ByteArray(entry.compressedSize.toInt())
        if (compressed.isNotEmpty()) {
            val buffer = ByteBuffer.wrap(compressed).order(ByteOrder.LITTLE_ENDIAN)
            readFully(buffer, dataOffset)
        }
        return when (entry.method) {
            METHOD_STORED -> compressed.takeIf { it.size.toLong() <= maxBytes }?.let { EntryView(it, 0, it.size) }
            METHOD_DEFLATE -> inflate(compressed, 0, compressed.size, entry.uncompressedSize.toInt(), maxBytes)
                ?.let { EntryView(it, 0, it.size) }
            else -> null
        }
    }

    override fun close() {
        synchronized(lock) {
            cachedEntries = null
            cachedEntryIndex = null
            runCatching { channel.close() }
            runCatching { descriptor?.close() }
        }
    }

    private fun parseCentralDirectory(): List<Entry> {
        val fileSize = channel.size()
        if (fileSize < EOCD_SIZE) throw IOException("文件太小，不是 ZIP：$fileSize")
        val tailLength = min(
            fileSize,
            (EOCD_SIZE + MAX_COMMENT_SIZE + ZIP64_LOCATOR_SIZE).toLong(),
        ).toInt()
        val tail = ByteBuffer.allocate(tailLength).order(ByteOrder.LITTLE_ENDIAN)
        readFully(tail, fileSize - tailLength)

        var eocdPosition = -1
        for (position in tailLength - EOCD_SIZE downTo 0) {
            if (tail.getInt(position) == EOCD_SIG) {
                eocdPosition = position
                break
            }
        }
        if (eocdPosition < 0) throw IOException("找不到 EOCD")

        var totalEntries = tail.getShort(eocdPosition + 10).toInt() and 0xFFFF
        var centralDirectorySize = tail.getInt(eocdPosition + 12).toLong() and 0xFFFFFFFFL
        var centralDirectoryOffset = tail.getInt(eocdPosition + 16).toLong() and 0xFFFFFFFFL

        if (totalEntries == 0xFFFF || centralDirectorySize == 0xFFFFFFFFL || centralDirectoryOffset == 0xFFFFFFFFL) {
            val locatorPosition = eocdPosition - ZIP64_LOCATOR_SIZE
            if (locatorPosition >= 0 && tail.getInt(locatorPosition) == ZIP64_LOCATOR_SIG) {
                val zip64Offset = tail.getLong(locatorPosition + 8)
                val zip64 = ByteBuffer.allocate(ZIP64_EOCD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                readFully(zip64, zip64Offset)
                if (zip64.getInt(0) == ZIP64_EOCD_SIG) {
                    totalEntries = zip64.getLong(32).toInt()
                    centralDirectorySize = zip64.getLong(40)
                    centralDirectoryOffset = zip64.getLong(48)
                }
            }
        }

        if (totalEntries > PageIndexing.MAX_ZIP_ENTRIES) throw IOException("条目过多：$totalEntries")
        if (centralDirectorySize <= 0L || centralDirectorySize > MAX_CENTRAL_DIRECTORY_BYTES) {
            throw IOException("中央目录过大：$centralDirectorySize")
        }
        if (centralDirectoryOffset < 0L || centralDirectoryOffset + centralDirectorySize > fileSize) {
            throw IOException("中央目录偏移越界")
        }

        val directory = ByteBuffer.allocate(centralDirectorySize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        readFully(directory, centralDirectoryOffset)
        val result = ArrayList<Entry>(min(totalEntries, 4096))
        var position = 0
        while (position + CENTRAL_HEADER_SIZE <= directory.limit() && result.size < totalEntries) {
            if (directory.getInt(position) != CENTRAL_HEADER_SIG) break
            val flags = directory.getShort(position + 8).toInt() and 0xFFFF
            val method = directory.getShort(position + 10).toInt() and 0xFFFF
            var compressedSize = directory.getInt(position + 20).toLong() and 0xFFFFFFFFL
            var uncompressedSize = directory.getInt(position + 24).toLong() and 0xFFFFFFFFL
            val nameLength = directory.getShort(position + 28).toInt() and 0xFFFF
            val extraLength = directory.getShort(position + 30).toInt() and 0xFFFF
            val commentLength = directory.getShort(position + 32).toInt() and 0xFFFF
            var localHeaderOffset = directory.getInt(position + 42).toLong() and 0xFFFFFFFFL

            val nameBytes = ByteArray(nameLength)
            val nameSlice = directory.duplicate().apply { position(position + CENTRAL_HEADER_SIZE) }
            nameSlice.get(nameBytes)
            val name = String(nameBytes, Charsets.UTF_8)

            if (compressedSize == 0xFFFFFFFFL || uncompressedSize == 0xFFFFFFFFL || localHeaderOffset == 0xFFFFFFFFL) {
                val extra = ByteArray(extraLength)
                if (extraLength > 0) {
                    val extraSlice = directory.duplicate().apply {
                        position(position + CENTRAL_HEADER_SIZE + nameLength)
                    }
                    extraSlice.get(extra)
                }
                var extraPosition = 0
                while (extraPosition + 4 <= extra.size) {
                    val headerId = u16(extra, extraPosition)
                    val fieldSize = u16(extra, extraPosition + 2)
                    if (headerId == ZIP64_EXTRA_ID) {
                        var cursor = extraPosition + 4
                        if (uncompressedSize == 0xFFFFFFFFL && cursor + 8 <= extra.size) {
                            uncompressedSize = u64(extra, cursor)
                            cursor += 8
                        }
                        if (compressedSize == 0xFFFFFFFFL && cursor + 8 <= extra.size) {
                            compressedSize = u64(extra, cursor)
                            cursor += 8
                        }
                        if (localHeaderOffset == 0xFFFFFFFFL && cursor + 8 <= extra.size) {
                            localHeaderOffset = u64(extra, cursor)
                        }
                    }
                    extraPosition += 4 + fieldSize
                }
            }

            result += Entry(
                name = name,
                method = method,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                localHeaderOffset = localHeaderOffset,
                encrypted = flags and FLAG_ENCRYPTED != 0,
                nameBytes = nameLength,
                extraBytes = extraLength,
            )
            position += CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
        }
        if (result.isEmpty()) throw IOException("中央目录为空")
        return result
    }

    private fun inflate(
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        expectedSize: Int,
        maxBytes: Long,
    ): ByteArray? {
        if (inputLength <= 0) return if (expectedSize == 0) ByteArray(0) else null
        // 优先路径：central directory 已给出解压后大小且合法时，直接 inflate 到预分配数组，
        // 省掉 InflaterInputStream + ByteArrayOutputStream + toByteArray 的整块额外拷贝。
        if (expectedSize > 0 && expectedSize.toLong() <= maxBytes) {
            inflateExact(input, inputOffset, inputLength, expectedSize)?.let { return it }
        }
        // 声明尺寸不可信（截断/多出数据/损坏）或超过 maxBytes 时，回退到原流式实现重新解压。
        return inflateStreaming(input, inputOffset, inputLength, expectedSize, maxBytes)
    }

    /**
     * 已知解压后大小：一次性 inflate 进 [expectedSize] 大小的数组（零额外拷贝）。
     * 任何尺寸不一致/数据异常都返回 null，由 [inflate] 回退到流式路径。
     */
    private fun inflateExact(
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        expectedSize: Int,
    ): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(input, inputOffset, inputLength)
            val output = ByteArray(expectedSize)
            var offset = 0
            while (!inflater.finished()) {
                // 已填满声明大小但流还没结束：尺寸声明偏小，交给流式路径判断。
                if (offset >= output.size) return null
                val read = inflater.inflate(output, offset, output.size - offset)
                if (read == 0) {
                    // 没有进展且未结束：输入被截断或需要字典，视为异常。
                    return null
                }
                offset += read
            }
            if (offset != expectedSize) return null
            output
        } catch (e: Exception) {
            null
        } finally {
            runCatching { inflater.end() }
        }
    }

    /** 原流式实现：不信任声明尺寸时的保底路径，同时负责 [maxBytes] 上限。 */
    private fun inflateStreaming(
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        expectedSize: Int,
        maxBytes: Long,
    ): ByteArray? {
        val inflater = Inflater(true)
        return try {
            InflaterInputStream(ByteArrayInputStream(input, inputOffset, inputLength), inflater, 64 * 1024).use { stream ->
                val initialSize = if (expectedSize in 1..maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
                    expectedSize
                } else {
                    16 * 1024
                }
                val output = ByteArrayOutputStream(initialSize)
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { inflater.end() }
        }
    }

    private fun readFully(buffer: ByteBuffer, position: Long): ByteBuffer {
        buffer.clear()
        var cursor = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, cursor)
            if (read < 0) throw IOException("读取越界：$cursor")
            cursor += read
        }
        buffer.flip()
        return buffer
    }

    companion object {
        private const val EOCD_SIG = 0x06054b50
        private const val ZIP64_EOCD_SIG = 0x06064b50
        private const val ZIP64_LOCATOR_SIG = 0x07064b50
        private const val CENTRAL_HEADER_SIG = 0x02014b50
        private const val LOCAL_HEADER_SIG = 0x04034b50

        private const val EOCD_SIZE = 22
        private const val MAX_COMMENT_SIZE = 0xFFFF
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val ZIP64_EOCD_SIZE = 56
        private const val CENTRAL_HEADER_SIZE = 46
        private const val LOCAL_HEADER_SIZE = 30

        private const val ZIP64_EXTRA_ID = 0x0001
        private const val FLAG_ENCRYPTED = 0x0001
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATE = 8

        private const val MAX_CENTRAL_DIRECTORY_BYTES = 32L * 1024L * 1024L

        /** 合并读取时名字/extra 字段上限（ZIP 规范为 16 位）。 */
        private const val MAX_LOCAL_NAME_BYTES = 0xFFFF

        /** 合并读取的整条记录上限；超过则回退两次 pread（正常页图远小于此值）。 */
        private const val MAX_COMBINED_READ_BYTES = 48L * 1024L * 1024L

        /** 打开失败（provider 不支持 seek/文件损坏/非 ZIP）返回 null，调用方回退慢路径。 */
        fun open(context: Context, uri: Uri): RemoteZipReader? {
            var descriptor: ParcelFileDescriptor? = null
            return try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                descriptor = pfd
                val stream = FileInputStream(pfd.fileDescriptor)
                val reader = RemoteZipReader(pfd, stream.channel)
                // 先解析一次，确保拿到可用的 central directory。
                reader.entries()
                reader
            } catch (t: Throwable) {
                runCatching { descriptor?.close() }
                null
            }
        }

        private fun u16(bytes: ByteArray, index: Int): Int =
            (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)

        private fun u64(bytes: ByteArray, index: Int): Long {
            var value = 0L
            for (i in 7 downTo 0) {
                value = (value shl 8) or (bytes[index + i].toLong() and 0xFF)
            }
            return value
        }
    }
}
