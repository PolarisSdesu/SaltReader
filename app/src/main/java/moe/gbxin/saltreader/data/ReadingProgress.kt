package moe.gbxin.saltreader.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 阅读进度快照。
 *
 * 只有这几个字段会随翻页 / 滚动高频变化。把它们从 books.json（书库索引）里拆出来单独落盘，
 * 避免「翻一页就重新序列化整库」的写放大；载入时用这里的值覆盖 books.json 中的旧值。
 *
 * 该文件是派生数据：解析失败最多丢失阅读位置，不影响书库索引本身。
 */
@Serializable
internal data class ReadingProgress(
    val currentPage: Int = 0,
    val progressOffset: Long = 0L,
    val lastReadAt: Long? = null,
    val finished: Boolean = false,
) {
    /** 把进度覆盖到索引记录上；页数 / 字符数可能被重新索引改小，这里重新 clamp。 */
    fun applyTo(book: ComicBook): ComicBook = book.copy(
        currentPage = currentPage.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0)),
        progressOffset = progressOffset.coerceIn(0L, book.charCount.coerceAtLeast(0L)),
        lastReadAt = lastReadAt,
        finished = finished,
    )
}

@Serializable
internal data class ProgressSnapshot(val entries: Map<String, ReadingProgress> = emptyMap())

/**
 * progress.json 使用紧凑 JSON：高频写的文件不需要可读性，去掉缩进后体积约为原来的一半。
 */
internal val progressJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}
