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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.gbxin.saltreader.data.ComicBook

/** Process-wide cover cache: covers are derived cache files, so evicting them is always safe. */
private const val COVER_CACHE_BYTES = 32 * 1024 * 1024

/** Grid/list covers are small; decoding at ~2x the view size keeps them crisp without huge bitmaps. */
private const val DEFAULT_TARGET_WIDTH_PX = 640

private val coverCache = object : LruCache<String, Bitmap>(COVER_CACHE_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/**
 * Loads a cover through the process-wide [LruCache]. Both the file stat and the decode happen on
 * [Dispatchers.IO]; nothing touches the file system during composition.
 */
@Composable
fun rememberCoverBitmap(file: File, targetWidthPx: Int = DEFAULT_TARGET_WIDTH_PX): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file, targetWidthPx) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadCoverBitmap(file, targetWidthPx) }.getOrNull()?.asImageBitmap()
        }
    }
    return bitmap
}

private fun loadCoverBitmap(file: File, targetWidthPx: Int): Bitmap? {
    val stamp = "${file.length()}:${file.lastModified()}"
    val key = "${file.absolutePath}|$stamp|$targetWidthPx"
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
) {
    val bitmap = rememberCoverBitmap(coverFile)
    val shape = RoundedCornerShape(radius.dp)
    val accent = book.accent?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    Box(modifier.clip(shape).background(Color(0xFF2A2723))) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "《${book.title}》封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        listOf(accent, accent.copy(alpha = 0.55f), Color(0xFF1E1B18)),
                    )
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (showTitleFallback) {
                    Text(
                        text = book.title.take(1),
                        color = Color(0xFFF7EEDF),
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
