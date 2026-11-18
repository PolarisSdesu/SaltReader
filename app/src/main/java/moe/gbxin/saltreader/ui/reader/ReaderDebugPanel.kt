package moe.gbxin.saltreader.ui.reader

import android.os.Debug
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
    val frames: Int = 0,
    val jankFrames: Int = 0,
    val fpsHistory: List<Float> = emptyList(),
    val heapHistory: List<Float> = emptyList(),
)

private const val HISTORY = 60
private const val SAMPLE_INTERVAL_MS = 500L
private const val MB = 1024f * 1024f

/** 面板内的固定文案：EN / 中文各一份，由面板右下角的 EN/中 开关切换。 */
private data class DebugLabels(
    val perf: String,
    val layout: String,
    val record: String,
    val actions: String,
    val page: String,
    val frame: String,
    val java: String,
    val native: String,
    val pss: String,
    val gcCount: String,
    val gcTime: String,
    val blocking: String,
    val threads: String,
    val screen: String,
    val switchToNative: String,
    val switchToWebview: String,
    val nativeUnavailable: String,
    val status: String,
    val recording: String,
    val idle: String,
    val file: String,
    val start: String,
    val stop: String,
    val hint: String,
    val jank: String,
    val paged: String,
    val vertical: String,
    val pageCache: String,
    val clearCache: String,
    val runGc: String,
    val copyPath: String,
    val remoteDebug: String,
)

private val LABELS_EN = DebugLabels(
    perf = "PERF",
    layout = "LAYOUT",
    record = "RECORD",
    actions = "ACTIONS",
    page = "PAGE",
    frame = "FRAME",
    java = "JAVA",
    native = "NATIVE",
    pss = "PSS",
    gcCount = "GC COUNT",
    gcTime = "GC TIME",
    blocking = "blocking",
    threads = "THREADS",
    screen = "SCREEN",
    switchToNative = "SWITCH → NATIVE",
    switchToWebview = "SWITCH → WEBVIEW",
    nativeUnavailable = "NATIVE UNAVAILABLE",
    status = "STATUS",
    recording = "REC %d rows",
    idle = "IDLE",
    file = "FILE",
    start = "START RECORDING",
    stop = "STOP RECORDING",
    hint = "Android/data/<pkg>/files/debug/ · adb pull",
    jank = "JANK",
    paged = "paged",
    vertical = "vertical",
    pageCache = "PAGE CACHE",
    clearCache = "CLEAR PAGE CACHE",
    runGc = "RUN GC",
    copyPath = "COPY CSV PATH",
    remoteDebug = "WebView debugging: chrome://inspect",
)

private val LABELS_ZH = DebugLabels(
    perf = "性能",
    layout = "布局",
    record = "录制",
    actions = "操作",
    page = "页",
    frame = "帧耗时",
    java = "内存",
    native = "原生",
    pss = "总内存",
    gcCount = "GC 次数",
    gcTime = "GC 耗时",
    blocking = "阻塞",
    threads = "线程",
    screen = "屏幕",
    switchToNative = "切换 → 原生",
    switchToWebview = "切换 → 网页",
    nativeUnavailable = "原生阅读器不可用",
    status = "状态",
    recording = "录制中 · %d 行",
    idle = "未录制",
    file = "文件",
    start = "开始录制",
    stop = "停止录制",
    hint = "Android/data/<pkg>/files/debug/ · adb pull",
    jank = "卡顿",
    paged = "分页",
    vertical = "纵向",
    pageCache = "页缓存",
    clearCache = "清空页缓存",
    runGc = "触发 GC",
    copyPath = "复制 CSV 路径",
    remoteDebug = "WebView 调试：chrome://inspect",
)

private const val TAB_PERF = 0
private const val TAB_LAYOUT = 1
private const val TAB_RECORD = 2
private const val TAB_ACTIONS = 3
private val CONTENT_HEIGHT = 250.dp

/**
 * 帧率 + 内存采样。[enabled] 为 false 时不注册任何回调，正常阅读没有额外开销。
 */
@Composable
internal fun rememberReaderDebugStats(enabled: Boolean, refreshRate: Float): ReaderDebugStats {
    var stats by remember { mutableStateOf(ReaderDebugStats()) }
    LaunchedEffect(enabled, refreshRate) {
        if (!enabled) return@LaunchedEffect
        // 超过 1.5 个刷新周期算掉帧（60Hz 上约 25ms），和「慢帧」的直觉一致。
        val jankThresholdMs = 1500f / refreshRate.coerceAtLeast(1f)
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
            val windowFrames = recent.size
            val jankFrames = recent.count { it > jankThresholdMs }
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
                frames = windowFrames,
                jankFrames = jankFrames,
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
    mode: String,
    debugText: String,
    page: Int,
    pages: Int,
    ratio: Float,
    nativeAvailable: Boolean,
    onSwitchRenderer: () -> Unit,
    recording: Boolean,
    recordRows: Int,
    recordFileName: String,
    onToggleRecording: () -> Unit,
    refreshRate: Float,
    cacheStats: ReaderViewModel.PageCacheStats?,
    onClearPageCache: () -> Unit,
    onGc: () -> Unit,
    onCopyPath: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(TAB_PERF) }
    // 默认跟随系统语言，用户可以在面板里手动切换并保留到本次阅读结束。
    var chinese by rememberSaveable { mutableStateOf(Locale.getDefault().language == "zh") }
    val labels = if (chinese) LABELS_ZH else LABELS_EN
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
            .width(if (expanded) 276.dp else 180.dp)
            .plainClickable {
                if (SystemClock.elapsedRealtime() - dragEndedAt > 300) expanded = !expanded
            },
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "DEBUG PANEL",
                    color = colors.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                Text(
                    text = "${stats.fps.roundToInt()} fps",
                    color = colors.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            if (!expanded) return@Column
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = renderer + " · " + if (mode == "vertical") labels.vertical else labels.paged,
                    color = colors.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                LangToggle(chinese = chinese, onChange = { chinese = it })
            }
            SwitchRow(
                labels = labels,
                nativeActive = renderer == "native",
                enabled = nativeAvailable,
                onSwitch = onSwitchRenderer,
            )
            // 固定内容高度：切 tab 时面板不会跳动，放不下的内容自己滚动。
            Box(Modifier.fillMaxWidth().height(CONTENT_HEIGHT)) {
                when (tab) {
                    TAB_LAYOUT -> LayoutTab(debugText)
                    TAB_RECORD -> RecordTab(labels, recording, recordRows, recordFileName, onToggleRecording)
                    TAB_ACTIONS -> ActionsTab(
                        labels = labels,
                        cacheStats = cacheStats,
                        onClearPageCache = onClearPageCache,
                        onGc = onGc,
                        onCopyPath = onCopyPath,
                    )
                    else -> PerfTab(labels, stats, windowSize, density.density, refreshRate, page, pages, ratio)
                }
            }
            DebugTabs(labels, selected = tab, onSelect = { tab = it })
        }
    }
}

@Composable
private fun PerfTab(
    labels: DebugLabels,
    stats: ReaderDebugStats,
    windowSize: IntSize,
    densityValue: Float,
    refreshRate: Float,
    page: Int,
    pages: Int,
    ratio: Float,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val percent = (ratio.coerceIn(0f, 1f) * 100f).roundToInt()
        DebugRow(labels.page, "$page / $pages · $percent%")
        DebugRow(labels.frame, "${oneDecimal(stats.frameMs)} ms · max ${oneDecimal(stats.worstFrameMs)}")
        val jankPercent = if (stats.frames > 0) stats.jankFrames * 100f / stats.frames else 0f
        DebugRow(
            labels.jank,
            "${stats.jankFrames} / ${stats.frames} · ${oneDecimal(jankPercent)}%",
        )
        Sparkline(
            values = stats.fpsHistory,
            // 60Hz 屏幕满格；高刷设备自动按观测到的峰值缩放，免得曲线一直贴顶。
            maxValue = maxOf(60f, stats.fpsHistory.maxOrNull() ?: 0f),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().height(72.dp).padding(vertical = 4.dp),
        )
        val heapPercent = if (stats.javaMaxMb > 0f) {
            (stats.javaUsedMb / stats.javaMaxMb * 100f).roundToInt()
        } else {
            0
        }
        DebugRow(
            labels.java,
            "${oneDecimal(stats.javaUsedMb)} / ${stats.javaMaxMb.roundToInt()} MB · $heapPercent%",
        )
        Sparkline(
            values = stats.heapHistory,
            maxValue = stats.javaMaxMb.coerceAtLeast(1f),
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.fillMaxWidth().height(72.dp).padding(vertical = 4.dp),
        )
        DebugRow(labels.native, "${oneDecimal(stats.nativeMb)} MB")
        DebugRow(labels.pss, "${stats.pssMb.roundToInt()} MB")
        DebugRow(labels.gcCount, "${stats.gcCount} · ${labels.blocking} ${stats.blockingGcCount}")
        DebugRow(labels.gcTime, "${stats.gcTimeMs} ms")
        DebugRow(labels.threads, "${stats.threads}")
        DebugRow(
            labels.screen,
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
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    )
}

/**
 * 录制 tab：把每次采样写进 CSV。文件落在应用外部私有目录，`adb pull` 即可取出。
 */
@Composable
private fun RecordTab(
    labels: DebugLabels,
    recording: Boolean,
    rows: Int,
    fileName: String,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DebugRow(labels.status, if (recording) labels.recording.format(rows) else labels.idle)
        DebugRow(labels.file, fileName.ifBlank { "—" })
        Text(
            text = if (recording) labels.stop else labels.start,
            color = colors.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp)
                .clickable { onToggle() },
        )
        Text(
            text = labels.hint,
            color = colors.onSurfaceVariant,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

/** 渲染器切换：原本是阅读器右上角的浮动按钮，现在收进面板。 */
@Composable
private fun SwitchRow(
    labels: DebugLabels,
    nativeActive: Boolean,
    enabled: Boolean,
    onSwitch: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val label = if (nativeActive) labels.switchToWebview else labels.switchToNative
    Text(
        text = if (enabled) label else labels.nativeUnavailable,
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
private fun DebugTabs(labels: DebugLabels, selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        listOf(labels.perf, labels.layout, labels.record, labels.actions).forEachIndexed { index, label ->
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

/** 操作 tab：清缓存 / GC / 复制 CSV 路径，并显示页缓存占用。 */
@Composable
private fun ActionsTab(
    labels: DebugLabels,
    cacheStats: ReaderViewModel.PageCacheStats?,
    onClearPageCache: () -> Unit,
    onGc: () -> Unit,
    onCopyPath: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (cacheStats != null) {
            DebugRow(
                labels.pageCache,
                "${cacheStats.entries} · ${cacheStats.bytes / (1024f * 1024f)} MB · " +
                    "hit ${(cacheStats.hitRate * 100f).roundToInt()}%",
            )
        }
        ActionRow(labels.clearCache, colors.primary, onClearPageCache)
        ActionRow(labels.runGc, colors.primary, onGc)
        ActionRow(labels.copyPath, colors.primary, onCopyPath)
        Text(
            text = labels.remoteDebug,
            color = colors.onSurfaceVariant,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ActionRow(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable { onClick() },
    )
}

/** 无 ripple 的点击：调试面板是常驻浮层，水波纹会一直闪，干扰读数。 */
@Composable
private fun Modifier.plainClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

/** 面板语言的 EN / 中 开关。 */
@Composable
private fun LangToggle(chinese: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        LangOption("EN", active = !chinese) { onChange(false) }
        Text(
            text = "|",
            color = colors.outlineVariant,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        LangOption("中", active = chinese) { onChange(true) }
    }
}

@Composable
private fun LangOption(text: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = text,
        color = if (active) colors.primary else colors.onSurfaceVariant,
        fontSize = 10.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 2.dp),
    )
}

private fun oneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)
