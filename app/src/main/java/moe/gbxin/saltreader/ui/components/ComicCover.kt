package moe.gbxin.saltreader.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.gbxin.saltreader.R
import moe.gbxin.saltreader.data.ComicBook

/**
 * Process-wide cover cache: covers are derived cache files, so evicting them is always safe.
 * 容量随进程堆自适应：1/8 堆，最多 64MB；小屏设备不会因为固定 32MB 而频繁抖动，大堆设备也
 * 不会把封面长期霸占内存。
 */
private val COVER_CACHE_BYTES: Int = run {
    // 低内存设备（<=256MB 堆）更保守，避免封面把可用的页缓存/解码空间挤掉。
    val heap = Runtime.getRuntime().maxMemory()
    if (heap <= 256L * 1024L * 1024L) {
        (heap / 12).coerceAtMost(24L * 1024L * 1024L)
    } else {
        (heap / 8).coerceAtMost(48L * 1024L * 1024L)
    }.toInt()
}

/** Grid/list covers are small; decoding at ~2x the view size keeps them crisp without huge bitmaps. */
private const val DEFAULT_TARGET_WIDTH_PX = 640

private val coverCache = object : LruCache<String, Bitmap>(COVER_CACHE_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** 缓存 key 带文件 stat，封面重新生成后（长度/修改时间变化）不会命中旧图。 */
private fun coverCacheKey(file: File, targetWidthPx: Int): String =
    "${file.absolutePath}|${file.length()}:${file.lastModified()}|$targetWidthPx"

/**
 * Loads a cover through the process-wide [LruCache]. The fast path (cache hit) does only a single
 * synchronous `stat` during composition — deliberately, so a cover that was already decoded returns
 * on the first frame instead of flashing the fallback while an IO load spins up. Misses are decoded
 * on [Dispatchers.IO]; the file system is never touched synchronously beyond that stat.
 */
@Composable
fun rememberCoverBitmap(file: File, targetWidthPx: Int = DEFAULT_TARGET_WIDTH_PX): ImageBitmap? {
    // remember 只在 file/目标宽度变化时做一次 stat + 查表；命中即作为初始值同步返回，消除首帧闪烁。
    val cached = remember(file, targetWidthPx) { peekCoverBitmap(file, targetWidthPx) }
    val bitmap by produceState<ImageBitmap?>(initialValue = cached, file, targetWidthPx) {
        // produceState 的 initialValue 只在首次生效，key 变化时要显式写回当前 composition 的命中值；
        // 未命中才在 IO 线程解码。
        value = cached ?: withContext(Dispatchers.IO) {
            runCatching { loadCoverBitmap(file, targetWidthPx) }.getOrNull()?.asImageBitmap()
        }
    }
    return bitmap
}

/** 同步查缓存：只做 file.length()/lastModified() 的 stat，命中时零 IO 解码。 */
private fun peekCoverBitmap(file: File, targetWidthPx: Int): ImageBitmap? =
    coverCache.get(coverCacheKey(file, targetWidthPx))?.asImageBitmap()

private fun loadCoverBitmap(file: File, targetWidthPx: Int): Bitmap? {
    val key = coverCacheKey(file, targetWidthPx)
    coverCache.get(key)?.let { return it }
    if (!file.isFile) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, targetWidthPx)
        // Covers are JPEGs without alpha; 565 halves the cache footprint with no visible loss.
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)?.also { coverCache.put(key, it) }
}

private fun sampleSizeFor(sourceWidth: Int, targetWidthPx: Int): Int {
    if (targetWidthPx <= 0) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= targetWidthPx) sample *= 2
    return sample
}

@Composable
fun ComicCover(
    book: ComicBook,
    coverFile: File,
    modifier: Modifier = Modifier,
    radius: Int = 20,
    showTitleFallback: Boolean = true,
    /** 目标解码宽度（px）：调用方按实际显示尺寸传入，避免网格封面按 640px 解码浪费内存。 */
    targetWidthPx: Int = DEFAULT_TARGET_WIDTH_PX,
) {
    val bitmap = rememberCoverBitmap(coverFile, targetWidthPx)
    val shape = RoundedCornerShape(radius.dp)
    val accent = book.accent?.let { Color(it) } ?: MaterialTheme.colorScheme.primaryContainer
    // Contrast-aware fallback foreground: dynamic color primaries can be light in dark themes.
    val onAccent = if (accent.luminance() > 0.5f) Color(0xFF1A1C1E) else Color(0xFFF7EEDF)
    val backdrop = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.clip(shape).background(backdrop)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.settings_cover_content_description, book.title),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        listOf(accent, accent.copy(alpha = 0.85f), backdrop),
                    )
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (showTitleFallback) {
                    Text(
                        text = book.title.take(1),
                        color = onAccent,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 56.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 进程内存吃紧时裁剪封面缓存。封面是派生数据，随时可以重新解码；
 * 这里只裁到 1/4 容量而不是清空，避免用户切回书架时所有封面同时重解码造成卡顿。
 */
internal fun trimCoverCache() {
    coverCache.trimToSize(COVER_CACHE_BYTES / 4)
}
