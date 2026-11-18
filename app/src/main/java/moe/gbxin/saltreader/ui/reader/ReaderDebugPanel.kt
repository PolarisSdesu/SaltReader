package moe.gbxin.saltreader.ui.reader

import android.os.Debug
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 调试浮层的采样数据。
 *
 * FPS 由 `withFrameNanos` 统计（只统计真正连续产生的帧，空闲时不会虚高）；内存每 500ms 采一次，
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
    val gcCount: Int = 0,
    val blockingGcCount: Int = 0,
    val gcTimeMs: Int = 0,
    val threads: Int = 0,
    val fpsHistory: List<Float> = emptyList(),
    val heapHistory: List<Float> = emptyList(),
)

private const val HISTORY = 60
private const val SAMPLE_INTERVAL_MS = 500L
private const val MB = 1024f * 1024f
private const val TAB_PERF = 0
private const val TAB_LAYOUT = 1

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
                gcCount = Debug.getRuntimeStat("art.gc.gc-count")?.toIntOrNull() ?: stats.gcCount,
                blockingGcCount = Debug.getRuntimeStat("art.gc.blocking-gc-count")
                    ?.toIntOrNull() ?: stats.blockingGcCount,
                gcTimeMs = Debug.getRuntimeStat("art.gc.gc-time")?.toIntOrNull() ?: stats.gcTimeMs,
                threads = Thread.activeCount(),
                fpsHistory = fpsHistory.toList(),
                heapHistory = heapHistory.toList(),
            )
        }
    }
    return stats
}

/**
 * 阅读器调试浮层：底部两个 tab。
 *
 * - PERF：帧率、帧耗时、Java/原生堆、PSS、GC，各自带曲线；
 * - LAYOUT：当前渲染器的布局信息（WebView 的视口/版心/段落行高，或原生阅读器的分页摘要）。
 *
 * 点一下折叠成一行，拖动可移动位置。只在 debugMode 打开时由 ReaderActivity 挂载。
 */
@Composable
internal fun ReaderDebugPanel(
    stats: ReaderDebugStats,
    renderer: String,
    debugText: String,
    page: Int,
    pages: Int,
    ratio: Float,
    nativeAvailable: Boolean,
    onSwitchRenderer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(TAB_PERF) }
    // 位置是相对窗口左上角的绝对偏移（不含任何外层 padding），这样边界可以直接用窗口尺寸算。
    val density = LocalDensity.current
    val initialX = with(density) { 8.dp.roundToPx() }
    // 默认放在顶栏下方，避免一上来就盖住返回按钮；位置可以拖。
    val initialY = WindowInsets.safeDrawing.getTop(density) + with(density) { 72.dp.roundToPx() }
    var dragX by rememberSaveable { mutableFloatStateOf(initialX.toFloat()) }
    var dragY by rememberSaveable { mutableFloatStateOf(initialY.toFloat()) }
    val windowSize = LocalWindowInfo.current.containerSize
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    // 拖动结束后短暂忽略点击，否则「拖完手指抬起」会被当成一次展开/折叠。
    var dragEndedAt by remember { mutableLongStateOf(0L) }
    // 折叠/展开会改变面板尺寸，位置要重新收进屏幕内。
    LaunchedEffect(panelSize, windowSize) {
        val maxX = (windowSize.width - panelSize.width).coerceAtLeast(0).toFloat()
        val maxY = (windowSize.height - panelSize.height).coerceAtLeast(0).toFloat()
        dragX = dragX.coerceIn(0f, maxX)
        dragY = dragY.coerceIn(0f, maxY)
    }
    val context = LocalContext.current
    val refreshRate = remember(context) {
        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.refreshRate ?: 60f
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
            .onSizeChanged { panelSize = it }
            .pointerInput(windowSize, panelSize) {
                detectDragGestures(
                    onDragEnd = { dragEndedAt = SystemClock.elapsedRealtime() },
                    onDragCancel = { dragEndedAt = SystemClock.elapsedRealtime() },
                ) { change, drag ->
                    change.consume()
                    val maxX = (windowSize.width - panelSize.width).coerceAtLeast(0).toFloat()
                    val maxY = (windowSize.height - panelSize.height).coerceAtLeast(0).toFloat()
                    dragX = (dragX + drag.x).coerceIn(0f, maxX)
                    dragY = (dragY + drag.y).coerceIn(0f, maxY)
                }
            }
            .width(if (expanded) 276.dp else 132.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        onClick = {
            if (SystemClock.elapsedRealtime() - dragEndedAt > 300) expanded = !expanded
        },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "DEBUG",
                    color = colors.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "${stats.fps.roundToInt()} fps",
                    color = colors.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (!expanded) return@Column
            Text(
                text = renderer,
                color = colors.onSurfaceVariant,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            SwitchRow(
                nativeActive = renderer.startsWith("native"),
                enabled = nativeAvailable,
                onSwitch = onSwitchRenderer,
            )
            when (tab) {
                TAB_LAYOUT -> LayoutTab(debugText)
                else -> PerfTab(stats, windowSize, density.density, refreshRate, page, pages, ratio)
            }
            DebugTabs(selected = tab, onSelect = { tab = it })
        }
    }
}

@Composable
private fun PerfTab(
    stats: ReaderDebugStats,
    windowSize: IntSize,
    densityValue: Float,
    refreshRate: Float,
    page: Int,
    pages: Int,
    ratio: Float,
) {
    Column {
        val percent = (ratio.coerceIn(0f, 1f) * 100f).roundToInt()
        DebugRow("页 PAGE", "$page / $pages · $percent%")
        DebugRow("帧耗时 FRAME", "${oneDecimal(stats.frameMs)} ms · max ${oneDecimal(stats.worstFrameMs)}")
        Sparkline(
            values = stats.fpsHistory,
            // 60Hz 屏幕满格；高刷设备自动按观测到的峰值缩放，免得曲线一直贴顶。
            maxValue = maxOf(60f, stats.fpsHistory.maxOrNull() ?: 0f),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().height(26.dp).padding(vertical = 4.dp),
        )
        val heapPercent = if (stats.javaMaxMb > 0f) {
            (stats.javaUsedMb / stats.javaMaxMb * 100f).roundToInt()
        } else {
            0
        }
        DebugRow(
            "内存 JAVA",
            "${oneDecimal(stats.javaUsedMb)} / ${stats.javaMaxMb.roundToInt()} MB · $heapPercent%",
        )
        Sparkline(
            values = stats.heapHistory,
            maxValue = stats.javaMaxMb.coerceAtLeast(1f),
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.fillMaxWidth().height(26.dp).padding(vertical = 4.dp),
        )
        DebugRow("原生 NATIVE", "${oneDecimal(stats.nativeMb)} MB")
        DebugRow("总内存 PSS", "${stats.pssMb.roundToInt()} MB")
        DebugRow("GC 次数", "${stats.gcCount} · 阻塞 ${stats.blockingGcCount}")
        DebugRow("GC 耗时", "${stats.gcTimeMs} ms")
        DebugRow("线程 THREADS", "${stats.threads}")
        DebugRow(
            "屏幕 SCREEN",
            "${windowSize.width}×${windowSize.height} · ${oneDecimal(densityValue)}x · " +
                "${refreshRate.roundToInt()}Hz",
        )
    }
}

@Composable
private fun LayoutTab(debugText: String) {
    Text(
        text = debugText.ifBlank { "no layout info yet" },
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    )
}

/** 渲染器切换：原本是阅读器右上角的浮动按钮，现在收进面板。 */
@Composable
private fun SwitchRow(nativeActive: Boolean, enabled: Boolean, onSwitch: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = if (nativeActive) "切换 SWITCH → 网页 WEBVIEW" else "切换 SWITCH → 原生 NATIVE"
    Text(
        text = if (enabled) label else "原生阅读器不可用 NATIVE UNAVAILABLE",
        color = when {
            !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
            else -> colors.primary
        },
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp)
            .clickable(enabled = enabled) { onSwitch() },
    )
}

/** 底部 tab：选中项用主色 + 下划线，未选中用弱化的 onSurfaceVariant。 */
@Composable
private fun DebugTabs(selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        listOf("性能 PERF", "布局 LAYOUT").forEachIndexed { index, label ->
            val active = index == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(index) }
                    .padding(top = 6.dp, bottom = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    color = if (active) colors.primary else colors.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace,
                )
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .fillMaxWidth(0.5f)
                        .height(2.dp)
                        .background(
                            color = if (active) colors.primary else Color.Transparent,
                            shape = RoundedCornerShape(1.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

/** 折线图，基线在底部；帧率满格按 60，内存满格按堆上限。 */
@Composable
private fun Sparkline(
    values: List<Float>,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        drawLine(
            color = color.copy(alpha = 0.18f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1f,
        )
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
    }
}

private fun oneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)
