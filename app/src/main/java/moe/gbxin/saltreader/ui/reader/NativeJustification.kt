package moe.gbxin.saltreader.ui.reader

/** 两端对齐：按行缓存测量结果（簇宽度 / 可拉伸边界），绘制时逐簇对齐。 */

import android.graphics.Paint
import android.util.LruCache
import androidx.compose.foundation.Canvas

internal class JustifiedLayout(
    val source: String,
    val clusters: Array<String>,
    val widths: FloatArray,
    val boundaries: BooleanArray,
    val measured: Float,
    val gaps: Int,
    /** 每个字都是可拉伸边界（纯 CJK 行）：可以用 letterSpacing 一次画完整行。 */
    val uniform: Boolean,
)

/** 行文本 + 画笔特征 → 布局。key 碰撞时用 `source === text` 复核，不会错绘。 */
internal val justifiedLayouts = object : LruCache<Long, JustifiedLayout>(512) {
    override fun sizeOf(key: Long, value: JustifiedLayout): Int = 1
}

internal fun paintSignature(paint: Paint): Int {
    var h = System.identityHashCode(paint)
    h = 31 * h + paint.textSize.toBits()
    h = 31 * h + (paint.typeface?.hashCode() ?: 0)
    h = 31 * h + paint.letterSpacing.toBits()
    h = 31 * h + paint.textScaleX.toBits()
    h = 31 * h + paint.textSkewX.toBits()
    return h
}

internal fun justifiedLayout(text: String, paint: Paint): JustifiedLayout {
    val key = (text.hashCode().toLong() shl 32) xor paintSignature(paint).toLong()
    justifiedLayouts.get(key)?.let { if (it.source === text) return it }

    val clusters = ArrayList<String>(text.length)
    val widths = ArrayList<Float>(text.length)
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val count = Character.charCount(codePoint)
        val cluster = text.substring(index, index + count)
        clusters.add(cluster)
        widths.add(paint.measureText(cluster))
        index += count
    }
    val measured = widths.sum()
    val boundaries = BooleanArray(clusters.size)
    var gaps = 0
    for (position in 0 until clusters.size - 1) {
        val previous = clusters[position]
        val next = clusters[position + 1]
        if (TextJustification.isExpansionBoundary(
                previous.codePointBefore(previous.length),
                next.codePointAt(0),
            )
        ) {
            boundaries[position] = true
            gaps++
        }
    }
    val layout = JustifiedLayout(
        source = text,
        clusters = clusters.toTypedArray(),
        widths = FloatArray(widths.size) { widths[it] },
        boundaries = boundaries,
        measured = measured,
        gaps = gaps,
        uniform = gaps == clusters.size - 1 && clusters.size >= 2,
    )
    justifiedLayouts.put(key, layout)
    return layout
}

internal fun drawJustifiedLine(
    canvas: android.graphics.Canvas,
    text: String,
    x: Float,
    baseline: Float,
    targetWidth: Float,
    paint: Paint,
) {
    val layout = justifiedLayout(text, paint)
    val measured = layout.measured
    if (measured <= 0f || targetWidth <= measured + 0.5f || layout.clusters.size < 2) {
        canvas.drawText(text, x, baseline, paint)
        return
    }
    if (layout.gaps == 0) {
        canvas.drawText(text, x, baseline, paint)
        return
    }
    val extra = (targetWidth - measured) / layout.gaps
    // 纯 CJK 行每个字后都要加同样的间距：用 Paint.letterSpacing 一次画完整行，
    // 把每帧几十次 drawText 降到一次（拉丁/含空格的行仍走逐簇绘制）。
    if (layout.uniform) {
        val original = paint.letterSpacing
        paint.letterSpacing = extra / paint.textSize
        canvas.drawText(text, x, baseline, paint)
        paint.letterSpacing = original
        return
    }
    var cursor = x
    layout.clusters.forEachIndexed { position, cluster ->
        canvas.drawText(cluster, cursor, baseline, paint)
        cursor += layout.widths[position]
        if (layout.boundaries[position]) cursor += extra
    }
}

/** 解析 Rust 返回的样式前缀；兼容无前缀的纯文本行。 */
