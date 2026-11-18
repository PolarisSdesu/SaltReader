package moe.gbxin.saltreader.ui.reader

import android.os.Debug
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 调试浮层的采样数据。
 *
 * FPS 由 `withFrameNanos` 统计（只统计真正产生的帧，空闲时不会虚高）；内存每 500ms 采一次，
 * 与帧统计同一个窗口，方便对照「滚动时内存涨了多少」。
 */
internal data class ReaderDebugStats(
    val fps: Float = 0f,
    val frameMs: Float = 0f,
    val worstFrameMs: Float = 0f,
    val javaUsedMb: Float = 0f,
    val javaMaxMb: Float = 0f,
    val nativeMb: Float = 0f,
    val pssMb: Float = 0f,
    val fpsHistory: List<Float> = emptyList(),
    val heapHistory: List<Float> = emptyList(),
)

private const val HISTORY = 60
private const val SAMPLE_INTERVAL_MS = 500L
private const val MB = 1024f * 1024f

/**
 * 帧率 + 内存采样。[enabled] 为 false 时不注册任何回调，正常阅读没有额外开销。
 */
@Composable
internal fun rememberReaderDebugStats(enabled: Boolean): ReaderDebugStats {
    var stats by remember { mutableStateOf(ReaderDebugStats()) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        // 只统计「连续渲染」的帧间隔：面板自己每 500ms 触发的一次重绘间隔是 500ms，
        // 如果算进去，空闲时会显示 2 FPS。>100ms 的间隔视为空闲，不计入。
        val intervals = ArrayDeque<Float>()
        var framesSinceSample = 0
        var lastFrameNs = 0L
        launch {
            while (isActive) {
                withFrameNanos { now ->
                    if (lastFrameNs != 0L) {
                        val ms = (now - lastFrameNs) / 1_000_000f
                        if (ms > 0f && ms <= 100f) {
                            intervals.addLast(ms)
                            if (intervals.size > 120) intervals.removeFirst()
                            framesSinceSample++
                        }
                    }
                    lastFrameNs = now
                }
            }
        }
        val fpsHistory = ArrayDeque<Float>()
        val heapHistory = ArrayDeque<Float>()
        while (isActive) {
            delay(SAMPLE_INTERVAL_MS)
            // 最近 30 帧（60Hz 上约半秒）足够平滑，又不会把一分钟前的卡顿一直留在数字里。
            val recent = intervals.toList().takeLast(30)
            val active = framesSinceSample > 0 && recent.isNotEmpty()
            val avgMs = if (active) recent.average().toFloat() else 0f
            val fps = if (active) 1000f / avgMs else 0f
            framesSinceSample = 0
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
            val maxMb = runtime.maxMemory() / MB
            val nativeMb = Debug.getNativeHeapAllocatedSize() / MB
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            // 空闲窗口沿用上一次的帧率，曲线保持水平而不是掉到 0。
            val stableFps = if (active) fps else stats.fps
            fpsHistory.addLast(stableFps)
            if (fpsHistory.size > HISTORY) fpsHistory.removeFirst()
            heapHistory.addLast(usedMb)
            if (heapHistory.size > HISTORY) heapHistory.removeFirst()
            stats = ReaderDebugStats(
                fps = stableFps,
                frameMs = if (active) avgMs else stats.frameMs,
                worstFrameMs = if (active) recent.max() else stats.worstFrameMs,
                javaUsedMb = usedMb,
                javaMaxMb = maxMb,
                nativeMb = nativeMb,
                pssMb = info.totalPss / 1024f,
                fpsHistory = fpsHistory.toList(),
                heapHistory = heapHistory.toList(),
            )
        }
    }
    return stats
}

/**
 * 阅读器调试浮层：帧率、帧耗时、Java/原生堆、PSS，各带一条 30 秒的曲线。
 * 点一下折叠成一行，再点展开。只在 debugMode 打开时由 ReaderActivity 挂载。
 */
@Composable
internal fun ReaderDebugPanel(
    stats: ReaderDebugStats,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    // 可拖动：浮层默认在左上角，长按拖动后位置在本次进程内保留。
    var dragX by rememberSaveable { mutableFloatStateOf(0f) }
    var dragY by rememberSaveable { mutableFloatStateOf(0f) }
    val background = Color(0xE6000000)
    val text = Color(0xFFD8F3DC)
    val accent = Color(0xFF7BD88F)
    val heapAccent = Color(0xFF8ECAE6)
    Column(
        modifier
            .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    dragX += drag.x
                    dragY += drag.y
                }
            }
            .width(if (expanded) 236.dp else 116.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = "DEBUG  ${stats.fps.roundToInt()} FPS",
            color = accent,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        if (!expanded) return@Column
        Text(
            text = "frame ${oneDecimal(stats.frameMs)} ms · max ${oneDecimal(stats.worstFrameMs)}",
            color = text,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Sparkline(
            values = stats.fpsHistory,
            // 60Hz 屏幕满格；高刷设备自动按观测到的峰值缩放，免得曲线一直贴顶。
            maxValue = maxOf(60f, stats.fpsHistory.maxOrNull() ?: 0f),
            color = accent,
            modifier = Modifier.fillMaxWidth().height(24.dp).padding(vertical = 3.dp),
        )
        Text(
            text = "java ${oneDecimal(stats.javaUsedMb)} / ${stats.javaMaxMb.roundToInt()} MB",
            color = text,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Sparkline(
            values = stats.heapHistory,
            maxValue = stats.javaMaxMb.coerceAtLeast(1f),
            color = heapAccent,
            modifier = Modifier.fillMaxWidth().height(24.dp).padding(vertical = 3.dp),
        )
        Text(
            text = "native ${oneDecimal(stats.nativeMb)} MB · pss ${stats.pssMb.roundToInt()} MB",
            color = text,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

/**
 * 折线图。帧率满格按 60，内存满格按堆上限，这样两条线的斜率可以直接比较。
 */
@Composable
private fun Sparkline(
    values: List<Float>,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (values.size < 2 || maxValue <= 0f) return@Canvas
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val ratio = (value / maxValue).coerceIn(0f, 1f)
            val y = size.height - ratio * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
        // 基线，方便判断 60fps / 满堆的位置
        drawLine(
            color = color.copy(alpha = 0.25f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1f,
        )
    }
}

private fun oneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)
