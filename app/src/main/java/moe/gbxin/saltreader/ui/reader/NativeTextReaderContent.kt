package moe.gbxin.saltreader.ui.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.DashPathEffect
import android.graphics.Rect
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.text.Html
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicInteger
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import moe.gbxin.saltreader.data.ReaderPageInfo
import moe.gbxin.saltreader.data.ReaderMode
import moe.gbxin.saltreader.data.ReadingDirection
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.gbxin.saltreader.R
import moe.gbxin.saltreader.data.AppSettings
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.EbookChapter
import moe.gbxin.saltreader.data.NativeTextLayout
import moe.gbxin.saltreader.data.ReaderBackground
import moe.gbxin.saltreader.data.ReaderFontFamily
import moe.gbxin.saltreader.data.ReaderFontRef
import moe.gbxin.saltreader.data.ReaderFontSource
import moe.gbxin.saltreader.data.ReaderFonts
import moe.gbxin.saltreader.ui.components.BatteryIndicator
import moe.gbxin.saltreader.ui.components.CustomColorPickerDialog
import moe.gbxin.saltreader.ui.components.SaltLoading
import moe.gbxin.saltreader.ui.settings.ReaderFontSettingsActivity
import moe.gbxin.saltreader.ui.settings.ReaderSettingsActivity
import moe.gbxin.saltreader.ui.theme.pageBackgroundColor
import moe.gbxin.saltreader.ui.theme.readerAccentColor
import moe.gbxin.saltreader.ui.theme.readerForegroundColor

/** Rust 排版后的单行：文本 + 行内样式（字号/行高倍率、粗斜、对齐、左缩进）。 */
internal data class NativeLine(
    val text: String,
    val sizeRatio: Float = 1f,
    val lineHeightRatio: Float = 1.8f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val mono: Boolean = false,
    val align: Int = 0,
    val leftIndentEm: Float = 0f,
    val tableCol: Int = 0,
    val tableCols: Int = 0,
    val tableRowStart: Boolean = false,
    val tableLastRow: Boolean = false,
    val tableRowEnd: Boolean = false,
    val imageId: Int = -1,
    val gapEm: Float = 0f,
)

/** 内联图片引用：按需从章节 HTML 里重新取出 data URI 解码，避免全书图片字节常驻。 */
private data class ImageRef(val chapterIndex: Int, val dataUriIndex: Int)

private val NATIVE_DATA_URI =
    Regex("data:image/([A-Za-z0-9.+-]+);base64,([A-Za-z0-9+/=\\r\\n]+)", RegexOption.IGNORE_CASE)

private data class NativePageSet(
    val pages: List<List<NativeLine>>,
    val imageRefs: List<ImageRef> = emptyList(),
    val imageAspects: List<Float> = emptyList(),
    val contentLeftPx: Float = 0f,
    val contentWidthPx: Float = 1f,
    val topPadPx: Float = 0f,
    val bottomPadPx: Float = 0f,
    val contentHeightPx: Float = 1f,
)

private val nativeParserToken = AtomicInteger(0)

/**
 * 原生文字阅读器（实验性）：章节文本 → Rust(`libtext_reader.so`) 断行分页 → Compose Canvas
 * 逐行 drawText。完全绕开 WebView 的 HTML/CSS/多栏布局。
 *
 * 顶栏/底栏复用 App 原生阅读器 chrome（目录/主题/字号/进度/设置），功能分步补齐：
 * 当前支持横向翻页、页内点按/滑动翻页、按页进度条、目录、纸张颜色、字号/行距/页边距与设置入口。
 */
@Composable
internal fun NativeTextReaderContent(
    vm: ReaderViewModel,
    book: ComicBook,
    chapters: List<EbookChapter>,
    settings: AppSettings,
    onBack: () -> Unit,
    volumePageEvents: Flow<Int>?,
    modifier: Modifier = Modifier,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    initialRatioOverride: Float? = null,
    onDebugSwitch: ((Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val readerBackground = pageBackgroundColor(
        settings.readerBackground,
        settings.customBackgroundColor,
        androidx.compose.foundation.isSystemInDarkTheme(),
        MaterialTheme.colorScheme.surface,
    )
    val readerTextColor = if (readerBackground == Color.Black) {
        Color.White
    } else {
        readerForegroundColor(readerBackground)
    }
    val accent = readerAccentColor(settings.readerBackground, settings.customBackgroundColor)
    val chromeColors = rememberReaderChromeColors(settings, readerBackground, readerTextColor)
    val chromeBarColor = chromeColors.bar
    val chromeContentColor = chromeColors.content
    val pageIndicatorColor = chromeColors.indicator

    // 与 WebView 阅读器一致：进度按"可见字符数"计。
    val chapterChars = remember(chapters) {
        LongArray(chapters.size) { index ->
            val chapter = chapters[index]
            when {
                chapter.charCount > 0 -> chapter.charCount.toLong()
                chapter.imageOnly -> 0L
                else -> chapter.html.length.toLong()
            }.coerceAtLeast(1L)
        }
    }
    val chapterPrefix = remember(chapterChars) {
        val prefix = LongArray(chapterChars.size + 1)
        for (i in chapterChars.indices) prefix[i + 1] = prefix[i] + chapterChars[i]
        prefix
    }
    val totalChars = chapterPrefix.lastOrNull()?.coerceAtLeast(1L) ?: 1L
    // 仓库只有在知道整书字符数后才会接受 updateTextProgress；WebView 阅读器会调用，
    // 原生阅读器此前漏掉了，导致阅读进度永远写不进去（重开书回到开头）。
    LaunchedEffect(book.id, totalChars) {
        if (totalChars > 1L) vm.updateTextStats(totalChars)
    }

    val fontSizePx = with(density) { settings.readerFontSizeSp.sp.toPx() }
    val lineHeightPx = fontSizePx * settings.readerLineHeight
    val marginPx = with(density) { settings.readerPageMarginDp.dp.toPx() }
    // 页面固定避让状态栏/顶栏与底部导航/底栏；chrome 收起时不重排页面。
    // 关键：沉浸式切换会改变 WindowInsets，这里只取首次组合时的值并缓存，
    // 否则每次显隐顶/底栏都会改变版心高度、触发重新分页，造成页面上下"弹跳"。
    // 与 WebView 阅读器的版心完全一致：safeDrawing 四边 + 上 20dp / 下 96dp 留白、
    // 左右 30dp 页边距（--reader-vertical-pad-top/bottom、--reader-page-margin-x）。
    // 与 WebView 阅读器同样的做法：取「见过的最大」系统栏 inset，chrome 显隐不改变版心。
    // 首次组合时系统栏可能还在隐藏状态（inset=0），所以用 SideEffect 持续记录最大值。
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    val liveTop = safeInsets.calculateTopPadding()
    val liveBottom = safeInsets.calculateBottomPadding()
    val liveLeft = safeInsets.calculateLeftPadding(LocalLayoutDirection.current)
    val liveRight = safeInsets.calculateRightPadding(LocalLayoutDirection.current)
    var safeTopDp by remember { mutableFloatStateOf(liveTop.value) }
    var safeBottomDp by remember { mutableFloatStateOf(liveBottom.value) }
    var safeLeftDp by remember { mutableFloatStateOf(liveLeft.value) }
    var safeRightDp by remember { mutableFloatStateOf(liveRight.value) }
    SideEffect {
        if (liveTop.value > safeTopDp) safeTopDp = liveTop.value
        if (liveBottom.value > safeBottomDp) safeBottomDp = liveBottom.value
        if (liveLeft.value > safeLeftDp) safeLeftDp = liveLeft.value
        if (liveRight.value > safeRightDp) safeRightDp = liveRight.value
    }
    // 纵向模式图片始终全宽（不受开关影响），避免设置状态导致宽度不满。
    val imageFullWidthEffective = settings.readerImageFullWidth || settings.readerMode == ReaderMode.VERTICAL

    // 与 WebView 的上下留白公式一致：
    // - 分页模式：PAGE_PAD_TOP/BOTTOM = max(28, safe + 8)dp；
    // - 纵向模式：`--reader-vertical-pad-top/bottom` = max(20, safe+8) / max(96, safe+8)dp。
    val verticalPads = settings.readerMode == ReaderMode.VERTICAL
    // 实测 WebView 纵向模式的顶部留白为 40dp（其 SAFE_TOP 取值比本进程大），这里取同样的下限。
    val pagePadTopDp = if (verticalPads) maxOf(40f, safeTopDp + 8f) else maxOf(28f, safeTopDp + 8f)
    val pagePadBottomDp = if (verticalPads) maxOf(96f, safeBottomDp + 8f) else maxOf(28f, safeBottomDp + 8f)
    val pageMarginLeftDp = maxOf(settings.readerPageMarginDp.toFloat(), safeLeftDp + 8f)
    val pageMarginRightDp = maxOf(settings.readerPageMarginDp.toFloat(), safeRightDp + 8f)
    val topPadPx = with(density) { pagePadTopDp.dp.toPx() }
    val bottomPadPx = with(density) { pagePadBottomDp.dp.toPx() }
    val leftPadPx = with(density) { pageMarginLeftDp.dp.toPx() }
    val rightPadPx = with(density) { pageMarginRightDp.dp.toPx() }
    // 字体解析可能涉及读取/复制用户字体文件，放到排版的 IO 协程里做。
    var typefaceState by remember { mutableStateOf<Typeface?>(null) }

    var pageArea by remember { mutableStateOf(IntSize.Zero) }
    var pageIndex by remember(book.id) { mutableIntStateOf(-1) }
    var initialised by remember(book.id) { mutableStateOf(false) }
    val savedProgress = remember(book.id) { maxOf(book.progressOffset, vm.latestProgressOffset()) }

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var activePanel by rememberSaveable { mutableStateOf(ReaderChromePanel.NONE) }
    var showCustomBackground by rememberSaveable { mutableStateOf(false) }

    var anchorRatio by remember(book.id) { mutableFloatStateOf(-1f) }
    val pageSet by produceState<NativePageSet?>(
        initialValue = null,
        chapters,
        settings.readerFontSizeSp,
        settings.readerLineHeight,
        settings.readerPageMarginDp,
        settings.readerFontFamily,
        settings.readerCustomFont,
        settings.readerFontFallback1,
        settings.readerFontFallback2,
        settings.readerFontFallback3,
        settings.readerImageFullWidth,
        settings.readerMode,
        pageArea,
        topPadPx,
        bottomPadPx,
        leftPadPx,
        rightPadPx,
    ) {
        if (!NativeTextLayout.isAvailable) return@produceState
        val area = pageArea
        if (area.width <= 0 || area.height <= 0) return@produceState
        val width = (area.width - leftPadPx - rightPadPx).coerceAtLeast(32f)
        val height = (area.height - topPadPx - bottomPadPx).coerceAtLeast(64f)
        val layoutHeight = height
        value = withContext(Dispatchers.IO) {
            val typeface = nativeTypeface(context, settings)
            typefaceState = typeface
            val built0 = buildNativePageSet(
                chapters,
                width,
                layoutHeight,
                fontSizePx,
                settings.readerLineHeight,
                typeface,
                contentLeftPx = leftPadPx,
                contentWidthPx = width,
                topPadPx = topPadPx,
                bottomPadPx = bottomPadPx,
                contentHeightPx = height,
                imageFullWidth = imageFullWidthEffective,
                fullPageWidth = (leftPadPx + width + rightPadPx),
                // 纵向模式没有分页：全宽图片不被页高封顶（允许 2×版心），保证宽度真正 100%。
                imageMaxHeightRatio = if (settings.readerMode == ReaderMode.VERTICAL) 10f else 0.92f,
                imagePlaceholder = context.getString(R.string.reader_image_placeholder),
            )
            // 纵向：直接使用 Rust 分页结果（保证内容完整），页面按真实内容高度连续堆叠。
            val built = built0
            if (built != null && settings.debugMode) {
                if (settings.readerMode == ReaderMode.VERTICAL) {
                    for ((pageIndex, page) in built.pages.withIndex()) {
                        val first = page.firstOrNull()?.text?.replace('\u0001', ' ')?.take(16)
                        val last = page.lastOrNull()?.text?.replace('\u0001', ' ')?.take(16)
                        android.util.Log.i(
                            "SaltReaderPerf",
                            "page-summary idx=$pageIndex lines=${page.size} first=\"$first\" last=\"$last\"",
                        )
                    }
                }
            }
            if (built != null && settings.debugMode) {
                // 诊断：统计每块中图片/表格行，定位“视觉空白但占高”的行。
                for ((chunkIndex, page) in built.pages.withIndex()) {
                    val special = page.mapIndexedNotNull { lineIndex, line ->
                        val hasText = if (line.text.isEmpty()) false else run {
                            var visible = false
                            var position = 0
                            while (position < line.text.length) {
                                val ch = line.text[position]
                                if (ch == '\u0004') {
                                    val end = line.text.indexOf('\u0005', position)
                                    position = if (end < 0) line.text.length else end + 1
                                    continue
                                }
                                if (!isInvisibleNativeChar(ch)) { visible = true; break }
                                position += 1
                            }
                            visible
                        }
                        if (hasText) return@mapIndexedNotNull null
                        val kind = when {
                            line.imageId >= 0 -> "img id=${line.imageId} aspect=${built.imageAspects.getOrNull(line.imageId)}"
                            line.tableCols > 0 -> "table col=${line.tableCol}/${line.tableCols} row=${line.tableRowStart}/${line.tableLastRow}"
                            else -> "text len=${line.text.length}"
                        }
                        "chunk$chunkIndex line$lineIndex $kind gap=${line.gapEm} h=${line.lineHeightRatio} size=${line.sizeRatio}"
                    }
                    if (special.isNotEmpty()) {
                        for (entry in special.take(4)) android.util.Log.i("SaltReaderPerf", "special-line $entry")
                    }
                }
            }
            if (built != null && built.pages.isNotEmpty()) {
                // 分页完成的同一帧内决定页码：首次按保存进度，重排（字号/行距/页边距）按比例恢复。
                val savedRatio = (savedProgress.toFloat() / totalChars.toFloat()).coerceIn(0f, 1f)
                val ratio = when {
                    !initialised -> initialRatioOverride?.coerceIn(0f, 1f) ?: savedRatio
                    // 重排可能产生多套分页结果；没有锚点时回退到保存比例，禁止用 0 覆盖已恢复的页码。
                    anchorRatio >= 0f -> anchorRatio
                    savedProgress > 0L -> savedRatio
                    else -> 0f
                }
                pageIndex = (ratio * (built.pages.size - 1)).roundToInt()
                    .coerceIn(0, built.pages.size - 1)
                initialised = true
            }
            built
        }
    }

    val totalPages = pageSet?.pages?.size ?: 0
    val safePageIndex = pageIndex.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    LaunchedEffect(pageIndex, totalPages, initialised) {
        if (initialised && totalPages > 1 && pageIndex in 0 until totalPages) {
            anchorRatio = pageIndex.toFloat() / (totalPages - 1)
        }
    }
    // 图片缓存：预渲染当前页 ±5 页，当前页优先；超出窗口的旧图会被淘汰。
    val imageCache = remember(book.id, settings.readerMode, settings.readerImageFullWidth) {
        mutableStateMapOf<Int, Bitmap>()
    }
    LaunchedEffect(pageSet, safePageIndex, settings.readerMode, settings.readerImageFullWidth) {
        val set = pageSet ?: return@LaunchedEffect
        if (set.pages.isEmpty()) return@LaunchedEffect
        val window = ((safePageIndex - 3)..(safePageIndex + 3)).filter { it in set.pages.indices }
        val idsByPage = window.associateWith { pageImageIds(set, it) }
        val ordered = listOf(safePageIndex) + window.filter { it != safePageIndex }
        val targetWidth = (set.contentWidthPx * 1.2f).roundToInt().coerceAtLeast(240)
        for (page in ordered) {
            if (!currentCoroutineContext().isActive) break
            for (id in idsByPage[page].orEmpty()) {
                if (imageCache.containsKey(id)) continue
                val decoded = withContext(Dispatchers.IO) {
                    set.imageRefs.getOrNull(id)?.let { ref -> decodeInlineBitmap(ref, chapters, targetWidth) }
                }
                if (decoded != null) imageCache[id] = decoded
                // 字节预算：先按当前窗口保留，再淘汰远端图片，避免图片书 OOM。
                var totalBytes = imageCache.values.sumOf { it.allocationByteCount.toLong() }
                if (totalBytes > MAX_IMAGE_CACHE_BYTES) {
                    val needed = window.flatMap { idsByPage[it].orEmpty() }.toSet()
                    for ((cacheId, cacheBitmap) in imageCache.toList()) {
                        if (totalBytes <= MAX_IMAGE_CACHE_BYTES) break
                        if (cacheId in needed) continue
                        totalBytes -= cacheBitmap.allocationByteCount
                        imageCache.remove(cacheId)
                    }
                }
            }
            if (imageCache.size > MAX_CACHED_IMAGES) {
                val keep = window.flatMap { idsByPage[it].orEmpty() }.toSet()
                imageCache.keys.filter { it !in keep }.take(4).forEach { imageCache.remove(it) }
            }
        }
        // 剔除不在预取窗口内的图片，控制内存。
        val keep = window.flatMap { idsByPage[it].orEmpty() }.toSet()
        imageCache.keys.filter { it !in keep }.forEach { imageCache.remove(it) }
    }

    val pageRatio = when {
        totalPages > 1 -> safePageIndex.toFloat() / (totalPages - 1)
        totalPages == 1 -> 1f
        else -> 0f
    }
    // 纵向模式共享的列表状态：进度条/角标需要读取连续滚动位置。
    val verticalListState = rememberLazyListState()
    // 分页模式用平台 Pager：拖动/fling/吸附与系统滚动同源（与 WebView 的手感一致）。
    val pagerState = rememberPagerState { totalPages.coerceAtLeast(1) }
    // pageIndex -> Pager：点击/音量键/进度条跳转。
    LaunchedEffect(pageIndex, initialised, totalPages) {
        if (initialised && totalPages > 0) {
            val target = pageIndex.coerceIn(0, totalPages - 1)
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        }
    }
    // Pager -> pageIndex：用户滑动后同步页码/进度。
    LaunchedEffect(pagerState, totalPages) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { current ->
                if (totalPages > 0 && current != pageIndex) pageIndex = current
            }
    }

    // 纵向模式没有“页”的概念：用连续滚动比例显示进度。
    val verticalFraction = if (settings.readerMode == ReaderMode.VERTICAL && totalPages > 0) {
        // 与 WebView 的 scrollTop/(scrollHeight-clientHeight) 对齐：末页起始即 100%。
        val layoutInfo = verticalListState.layoutInfo
        val firstIndex = verticalListState.firstVisibleItemIndex
        val firstItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == firstIndex }
        val viewportStart = layoutInfo.viewportStartOffset
        val itemSize = firstItem?.size?.coerceAtLeast(1) ?: 1
        val consumed = if (firstItem != null) {
            (viewportStart - firstItem.offset).toFloat().coerceIn(0f, itemSize.toFloat())
        } else {
            verticalListState.firstVisibleItemScrollOffset.toFloat()
        }
        val position = firstIndex + consumed / itemSize
        (position / (totalPages - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
    } else {
        pageRatio
    }
    val isVerticalMode = settings.readerMode == ReaderMode.VERTICAL

    // 纵向模式的进度持久化与“恢复上次位置”：LazyColumn 必须显式滚到保存的页，
    // 否则首帧 firstVisibleItemIndex=0 会把恢复的页码覆盖掉。
    var verticalRestored by remember(book.id) { mutableStateOf(false) }
    var lastSentOffset by remember(book.id) { mutableLongStateOf(-1L) }
    var lastSentAt by remember(book.id) { mutableLongStateOf(0L) }
    // 退出时 pageSet 已被释放，totalPages=0；这里持续缓存“最后已知阅读比例”，用于可靠的进度写回。
    var lastKnownRatio by remember(book.id) { mutableFloatStateOf(0f) }

    fun nativeProgressRatio(): Float =
        if (isVerticalMode) verticalFraction else pageRatio

    /** 原生阅读器此前只读进度不写回：这里按 WebView 的节流策略写回全书偏移。 */
    fun persistNativeProgress(force: Boolean) {
        if (totalChars <= 1L) return
        if (isVerticalMode && !verticalRestored) return
        val ratio = if (pageSet != null && totalPages > 0) {
            nativeProgressRatio()
        } else {
            lastKnownRatio
        }
        val offset = (ratio.toDouble() * totalChars.toDouble()).toLong()
            .coerceIn(0L, (totalChars - 1L).coerceAtLeast(0L))
        // 退出时可能还有第二个（已释放的）阅读器实例在写进度：未初始化/已释放时不要用 0 覆盖。
        if (offset == 0L && (pageSet == null || !initialised || pageIndex > 0)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && kotlin.math.abs(offset - lastSentOffset) < 200L &&
            now - lastSentAt < 1_500L
        ) {
            return
        }
        lastSentOffset = offset
        lastSentAt = now
        vm.updateTextProgress(offset)
    }

    LaunchedEffect(isVerticalMode, initialised, totalPages, pageSet) {
        if (!isVerticalMode || !initialised || totalPages <= 0 || verticalRestored) return@LaunchedEffect
        val target = pageIndex.coerceIn(0, totalPages - 1)
        if (target > 0) {
            verticalListState.scrollToItem(target)
        }
        verticalRestored = true
        persistNativeProgress(force = true)
    }
    LaunchedEffect(initialised, pageRatio, verticalFraction, verticalRestored, pageSet) {
        if (!initialised || pageSet == null) return@LaunchedEffect
        if (isVerticalMode && !verticalRestored) return@LaunchedEffect
        lastKnownRatio = nativeProgressRatio()
        persistNativeProgress(force = false)
    }

    val chapterIndex = remember(pageIndex, pageSet, chapterPrefix, totalChars) {
        if (pageSet == null || pageIndex < 0 || totalPages <= 0) {
            0
        } else {
            val offset = (pageRatio * totalChars.toFloat()).toLong()
                .coerceIn(0L, (totalChars - 1L).coerceAtLeast(0L))
            nativeChapterIndex(offset, chapterPrefix)
                .coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        }
    }
    val chapterTitle = chapters.getOrNull(chapterIndex)?.title.orEmpty()

    val direction = book.direction ?: settings.readingDirection
    val rtl = direction == ReadingDirection.RTL
    // 横向滑动由 Pager 处理（平台 fling + 吸附）。
    val swipeScope = rememberCoroutineScope()

    // 文本选择（长按选中所在段落，可复制到系统剪贴板）。
    var selectionPage by remember { mutableIntStateOf(-1) }
    var selectionStartLine by remember { mutableIntStateOf(-1) }
    var selectionEndLine by remember { mutableIntStateOf(-1) }
    var selectionAnchorLine by remember { mutableIntStateOf(-1) }
    var selectionAnchorChar by remember { mutableIntStateOf(0) }
    var selectionFocusLine by remember { mutableIntStateOf(-1) }
    var selectionFocusChar by remember { mutableIntStateOf(0) }

    // 纵向模式的“外部跳转”目标（进度条/目录/音量键），避免滚动回写与目标互相打断。
    var verticalJumpTarget by remember { mutableIntStateOf(-1) }
    fun requestVerticalJump(target: Int) {
        if (settings.readerMode == ReaderMode.VERTICAL && target >= 0) {
            verticalJumpTarget = target
        }
    }

    fun turnPage(delta: Int) {
        if (totalPages <= 0) return
        if (settings.readerMode == ReaderMode.VERTICAL) {
            // 纵向模式没有“页”，点按翻页映射为滚动到相邻切片，避免页码闪跳。
            val target = (safePageIndex + delta).coerceIn(0, totalPages - 1)
            pageIndex = target
            requestVerticalJump(target)
            return
        }
        pageIndex = (pageIndex + delta).coerceIn(0, totalPages - 1)
    }

    // 与 WebView 阅读器一致的沉浸式系统栏：chrome 显示时露出，隐藏时全屏。
    val view = LocalView.current
    val activityWindow = remember(view) { (view.context as? Activity)?.window }
    val insetsController = remember(view) {
        activityWindow?.let { WindowCompat.getInsetsController(it, view) }
    }
    DisposableEffect(insetsController) {
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(insetsController, chromeVisible) {
        if (insetsController == null) return@LaunchedEffect
        if (chromeVisible) {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    SideEffect {
        val systemBarBackground = if (chromeVisible) chromeBarColor else readerBackground
        insetsController?.isAppearanceLightStatusBars = systemBarBackground.luminance() > 0.5f
        insetsController?.isAppearanceLightNavigationBars = systemBarBackground.luminance() > 0.5f
    }
    DisposableEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) {
            activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                persistNativeProgress(force = true)
                vm.flushProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            persistNativeProgress(force = true)
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.flushProgress()
        }
    }
    LaunchedEffect(volumePageEvents) {
        // 音量键：与 WebView 的 turnFast 相同，110ms ease-out 快滑。
        volumePageEvents?.collectLatest { delta ->
            if (totalPages <= 1) return@collectLatest
            if (settings.readerMode == ReaderMode.VERTICAL) {
                pageIndex = (pageIndex + delta).coerceIn(0, totalPages - 1)
                requestVerticalJump(pageIndex)
                return@collectLatest
            }
            // 音量键：与 WebView 的 turnFast 一样，110ms 快速滑动。
            val target = (pageIndex + delta).coerceIn(0, totalPages - 1)
            pagerState.animateScrollToPage(
                target,
                animationSpec = tween(
                    durationMillis = 110,
                    easing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f),
                ),
            )
            pageIndex = pagerState.currentPage
        }
    }

    val activeTypeface = typefaceState ?: Typeface.SANS_SERIF
    val paintCache = remember(activeTypeface, fontSizePx, readerTextColor) { HashMap<Long, Paint>() }
    fun paintFor(line: NativeLine): Paint {
        val key = (line.sizeRatio * 1000).toLong() * 1000 +
            (if (line.bold) 1L else 0L) * 100 +
            (if (line.italic) 1L else 0L) * 10 +
            (if (line.mono) 1L else 0L)
        return paintCache.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fontSizePx * line.sizeRatio
                typeface = if (line.mono) Typeface.MONOSPACE else activeTypeface
                isFakeBoldText = line.bold
                textSkewX = if (line.italic) -0.25f else 0f
                color = readerTextColor.toArgbCompat()
            }
        }
    }
    val richPaintCache = remember(activeTypeface, fontSizePx, readerTextColor) { HashMap<Long, Paint>() }
    fun richPaintFor(
        line: NativeLine,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
        strike: Boolean,
        mono: Boolean,
    ): Paint {
        val key = (line.sizeRatio * 1000).toLong() * 100000 +
            (if (bold) 1L else 0L) * 10000 +
            (if (italic) 1L else 0L) * 1000 +
            (if (underline) 1L else 0L) * 100 +
            (if (strike) 1L else 0L) * 10 +
            (if (mono) 1L else 0L)
        return richPaintCache.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fontSizePx * line.sizeRatio
                typeface = if (mono) Typeface.MONOSPACE else activeTypeface
                isFakeBoldText = bold || line.bold
                textSkewX = if (italic || line.italic) -0.25f else 0f
                isUnderlineText = underline
                isStrikeThruText = strike
                color = readerTextColor.toArgbCompat()
            }
        }
    }

    val paint = remember(activeTypeface, fontSizePx, readerTextColor) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
            typeface = activeTypeface
            color = readerTextColor.toArgbCompat()
        }
    }

    /** 当前页每行的顶部/高度，用于长按命中测试。 */
    fun lineTops(page: List<NativeLine>): List<Float> {
        val start = (pageSet?.topPadPx ?: topPadPx)
        val result = ArrayList<Float>(page.size)
        var y = start
        for (line in page) {
            if (line.gapEm > 0f) y += line.gapEm * fontSizePx
            result.add(y)
            y += fontSizePx * line.lineHeightRatio * line.sizeRatio
        }
        return result
    }

    fun paragraphRangeAt(page: List<NativeLine>, y: Float): Pair<Int, Int>? {
        if (page.isEmpty()) return null
        val tops = lineTops(page)
        var index = tops.indexOfLast { it <= y }
        if (index < 0) index = 0
        index = index.coerceAtMost(page.size - 1)
        val isBlock = { line: NativeLine ->
            line.imageId >= 0 || line.tableCols > 0 || line.align == 1 || line.mono ||
                line.sizeRatio > 1.02f
        }
        var startLine = index
        var endLine = index
        if (!isBlock(page[index])) {
            while (startLine > 0) {
                val line = page[startLine]
                if (isBlock(line)) {
                    // 不要把上一行的块级内容（标题/图片/表格）纳入选区。
                    startLine += 1
                    break
                }
                if (line.gapEm > 0f || line.text.startsWith("　")) break
                startLine -= 1
            }
            while (endLine + 1 < page.size) {
                val next = page[endLine + 1]
                if (isBlock(next) || next.gapEm > 0f || next.text.startsWith("　")) break
                endLine += 1
            }
        }
        return startLine to endLine
    }

    /** 行内纯文本（剔除样式标记与图片 token）。 */
    fun plainLineText(line: NativeLine): String {
        val builder = StringBuilder()
        var position = 0
        while (position < line.text.length) {
            val ch = line.text[position]
            if (ch == '\u0004') {
                val end = line.text.indexOf('\u0005', position)
                position = if (end < 0) line.text.length else end + 1
                continue
            }
            if (ch.code in 0x0006..0x0013) {
                position += 1
                continue
            }
            builder.append(ch)
            position += 1
        }
        return builder.toString()
    }

    /** 触摸点 -> (行, 字符偏移)，按绘制字号逐步测量。 */
    fun hitTestChar(page: List<NativeLine>, x: Float, y: Float): Pair<Int, Int>? {
        if (page.isEmpty()) return null
        val tops = lineTops(page)
        var index = tops.indexOfLast { it <= y }
        if (index < 0) index = 0
        index = index.coerceAtMost(page.size - 1)
        val line = page[index]
        val startX = (pageSet?.contentLeftPx ?: leftPadPx) + line.leftIndentEm * fontSizePx
        val paint = paintFor(line)
        val plain = plainLineText(line)
        var cursor = startX
        var char = 0
        while (char < plain.length) {
            val width = paint.measureText(plain.substring(char, char + 1))
            if (x < cursor + width / 2f) break
            cursor += width
            char += 1
        }
        return index to char
    }

    fun applySelectionLines() {
        if (selectionAnchorLine < 0 || selectionFocusLine < 0) return
        val anchor = selectionAnchorLine to selectionAnchorChar
        val focus = selectionFocusLine to selectionFocusChar
        val first = if (anchor.first < focus.first ||
            (anchor.first == focus.first && anchor.second <= focus.second)
        ) anchor else focus
        val second = if (first === anchor) focus else anchor
        selectionStartLine = first.first
        selectionEndLine = second.first
    }

    fun clearSelection() {
        selectionPage = -1
        selectionStartLine = -1
        selectionEndLine = -1
        selectionAnchorLine = -1
        selectionAnchorChar = 0
        selectionFocusLine = -1
        selectionFocusChar = 0
    }

    fun copySelection(page: List<NativeLine>) {
        if (selectionPage < 0 || selectionAnchorLine < 0 || selectionFocusLine < 0) return
        val a = selectionAnchorLine to selectionAnchorChar
        val b = selectionFocusLine to selectionFocusChar
        val first = if (a.first < b.first || (a.first == b.first && a.second <= b.second)) a else b
        val second = if (first === a) b else a
        val builder = StringBuilder()
        for (index in first.first..second.first) {
            val line = page.getOrNull(index) ?: continue
            val plain = plainLineText(line)
            val from = if (index == first.first) first.second.coerceIn(0, plain.length) else 0
            val to = if (index == second.first) second.second.coerceIn(0, plain.length) else plain.length
            if (to > from) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(plain.substring(from, to))
            }
        }
        if (builder.isEmpty()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Salt Reader", builder.toString()))
        clearSelection()
    }

    /**
     * 一页实际内容高度：与绘制端的 y 推进完全一致（行高 + 段前距 + 图片实际高度 + 表格行），
     * 纵向模式按它堆叠页面，避免页尾留白，也避免图片页高度不足导致下一页文字重叠。
     */
    fun pageContentHeightPx(page: List<NativeLine>): Float {
        val geometry = pageSet
        val contentWidth = geometry?.contentWidthPx ?: (pageArea.width - leftPadPx - rightPadPx).coerceAtLeast(1f)
        val contentHeight = geometry?.contentHeightPx ?: (pageArea.height - topPadPx - bottomPadPx).coerceAtLeast(1f)
        val leftPad = geometry?.contentLeftPx ?: leftPadPx
        val fullWidth = (leftPad * 2f + contentWidth).coerceAtLeast(contentWidth)
        val imageHeightRatio = if (settings.readerMode == ReaderMode.VERTICAL) 10f else 0.92f
        var y = 0f
        for (line in page) {
            y += line.gapEm * fontSizePx
            val lineBox = fontSizePx * line.lineHeightRatio * line.sizeRatio
            when {
                line.imageId >= 0 -> {
                    val aspect = (geometry?.imageAspects?.getOrNull(line.imageId) ?: 1.5f)
                        .coerceAtLeast(0.05f)
                    if (settings.readerMode == ReaderMode.VERTICAL && imageFullWidthEffective) {
                        y += pageArea.width.toFloat() / aspect
                    } else {
                        val base = if (imageFullWidthEffective) fullWidth else contentWidth
                        y += minOf(base / aspect, contentHeight * imageHeightRatio)
                    }
                }

                line.tableCols > 0 -> {
                    // 同一视觉行的多个单元格只推进一次（最后一列代表该行结束）。
                    if (line.tableCol == line.tableCols - 1) y += lineBox
                }

                else -> y += lineBox
            }
        }
        return y.coerceAtLeast(fontSizePx)
    }

    fun handleReaderLongPress(offset: Offset) {
        if (settings.readerMode == ReaderMode.VERTICAL) return
        val page = pageSet?.pages?.getOrNull(safePageIndex) ?: return
        val range = paragraphRangeAt(page, offset.y) ?: return
        selectionPage = safePageIndex
        val endPlain = page.getOrNull(range.second)?.let { plainLineText(it).length } ?: 0
        selectionAnchorLine = range.first
        selectionAnchorChar = 0
        selectionFocusLine = range.second
        selectionFocusChar = endPlain
        applySelectionLines()
    }

    fun handleReaderTap(offset: Offset, width: Float) {
        if (selectionPage >= 0) {
            val page = pageSet?.pages?.getOrNull(safePageIndex)
            if (page != null) {
                // 点已选中的段落 = 取消选择；点其他段落 = 扩展/切换选区。
                val tappedRange = paragraphRangeAt(page, offset.y)
                val startLine = minOf(selectionAnchorLine, selectionFocusLine)
                val endLine = maxOf(selectionAnchorLine, selectionFocusLine)
                if (tappedRange != null &&
                    tappedRange.first >= startLine && tappedRange.second <= endLine
                ) {
                    clearSelection()
                    return
                }
                val hit = hitTestChar(page, offset.x, offset.y)
                if (hit != null) {
                    selectionFocusLine = hit.first
                    selectionFocusChar = hit.second
                    applySelectionLines()
                }
            }
            return
        }
        if (activePanel != ReaderChromePanel.NONE) {
            activePanel = ReaderChromePanel.NONE
            return
        }
        when {
            offset.x < width / 3f -> turnPage(if (rtl) 1 else -1)
            offset.x > width * 2f / 3f -> turnPage(if (rtl) -1 else 1)
            else -> chromeVisible = !chromeVisible
        }
    }

    fun jumpToChapter(index: Int) {
        if (totalPages <= 0) return
        val safe = index.coerceIn(0, chapters.size - 1)
        val ratio = (chapterPrefix[safe].toFloat() / totalChars.toFloat()).coerceIn(0f, 1f)
        pageIndex = (ratio * (totalPages - 1)).roundToInt().coerceIn(0, totalPages - 1)
        requestVerticalJump(pageIndex)
    }

    Box(modifier = modifier.fillMaxSize().background(readerBackground)) {
        // 页面：Rust 已断行，Canvas 逐行绘制；点按左右翻页、中间收起/展开 chrome。
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    // chrome/系统栏显隐是浮层，不改变这个 Box 尺寸；真正变化（旋转/分屏/折叠）才重排。
                    if (pageArea.width == 0 || pageArea.height == 0 ||
                        kotlin.math.abs(pageArea.width - size.width) > 1 ||
                        kotlin.math.abs(pageArea.height - size.height) > 1
                    ) {
                        pageArea = size
                    }
                }
                .then(
                    if (settings.readerMode == ReaderMode.VERTICAL) {
                        Modifier.pointerInput(totalPages, activePanel, pageSet) {
                            detectTapGestures(
                                onLongPress = { offset -> handleReaderLongPress(offset) },
                                onTap = { offset -> handleReaderTap(offset, size.width.toFloat()) },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
                val drawPage: @Composable (List<NativeLine>, Float, Float, Boolean, Int) -> Unit = { pageLines, offsetX, topInset, showDebug, chunkIndex ->
                        Canvas(modifier.fillMaxSize().graphicsLayer { translationX = offsetX }) {
                        if (settings.debugMode && chunkIndex >= 0 && offsetX == 0f) {
                            android.util.Log.i(
                                "SaltReaderPerf",
                                "canvas-size chunk=$chunkIndex sizeH=${size.height} sizeW=${size.width} " +
                                    "expect=${pageContentHeightPx(pageLines).roundToInt()} " +
                                    "topInset=$topInset",
                            )
                        }
                        drawIntoCanvas { canvas ->
                            val layout = pageSet
                            var yTop = if (topInset.isNaN()) (layout?.topPadPx ?: topPadPx) else topInset
                            val contentLeft = layout?.contentLeftPx ?: leftPadPx
                            val contentWidth = layout?.contentWidthPx ?: (size.width - leftPadPx - rightPadPx).coerceAtLeast(1f)
                            val geometryHeight = layout?.contentHeightPx ?: (pageArea.height - topPadPx - bottomPadPx)
                            val debugTop = yTop
                            pageLines.forEachIndexed { lineIndex, line ->
                                val paint = paintFor(line)
                                val fontMetrics = paint.fontMetrics
                                val lineBox = fontSizePx * line.lineHeightRatio * line.sizeRatio
                                val debugGapPx = line.gapEm * fontSizePx
                                val debugGapTop = yTop
                                if (line.gapEm > 0f) yTop += line.gapEm * fontSizePx
                                if (selectionPage == safePageIndex &&
                                    pageLines === pageSet?.pages?.getOrNull(safePageIndex) &&
                                    lineIndex in selectionStartLine..selectionEndLine
                                ) {
                                    drawRect(
                                        color = accent.copy(alpha = 0.20f),
                                        topLeft = Offset(contentLeft, yTop),
                                        size = Size(contentWidth, lineBox),
                                    )
                                }
                                val baseline = yTop + (lineBox - (fontMetrics.descent - fontMetrics.ascent)) / 2f -
                                    fontMetrics.ascent
                                if (line.text.any { it == '\u0004' || it in '\u0006'..'\u0013' }) {
                                // 行内 run：样式标记（粗/斜/下划线/删除线/等宽）+ 行内小图。
                                paint.textAlign = Paint.Align.LEFT
                                val richIndent = line.leftIndentEm * fontSizePx
                                // 行内样式行的对齐：先量出整行宽度，再按居中/右对齐决定起点（两端对齐暂按左对齐）。
                                var richSlots = 0
                                val richWidth = run {
                                    var widthSum = 0f
                                    var tokenIndex = 0
                                    val measurePaint = paintFor(line)
                                    while (tokenIndex < line.text.length) {
                                        val tokenChar = line.text[tokenIndex]
                                        when {
                                            tokenChar == '\u0004' -> {
                                                val tokenEnd = line.text.indexOf('\u0005', tokenIndex)
                                                if (tokenEnd < 0) {
                                                    tokenIndex = line.text.length
                                                    continue
                                                }
                                                val tokenId = line.text.substring(tokenIndex + 1, tokenEnd)
                                                    .toIntOrNull() ?: 0
                                                val tokenAspect = (pageSet?.imageAspects?.getOrNull(tokenId) ?: 1f)
                                                    .coerceAtLeast(0.05f)
                                                val em = fontSizePx * line.sizeRatio
                                                widthSum += em * 1.15f * tokenAspect + em * 0.2f
                                                richSlots += 1
                                                tokenIndex = tokenEnd + 1
                                            }

                                            tokenChar.code in 0x0006..0x0013 -> tokenIndex += 1

                                            else -> {
                                                widthSum += measurePaint.measureText(tokenChar.toString())
                                                richSlots += 1
                                                tokenIndex += 1
                                            }
                                        }
                                    }
                                    widthSum
                                }
                                var cursorX = when (line.align) {
                                    1 -> contentLeft + (contentWidth - richWidth).coerceAtLeast(0f) / 2f
                                    2 -> contentLeft + contentWidth - richWidth - richIndent
                                    else -> contentLeft + richIndent
                                }
                                if (line.align == 2) cursorX = cursorX.coerceAtLeast(contentLeft)
                                // 两端对齐：把剩余宽度均摊到每个字形/行内图片上（最后一行 Rust 已改为左对齐）。
                                val justifyExtra = if (line.align == 3 && richSlots > 0) {
                                    ((contentWidth - richIndent - richWidth).coerceAtLeast(0f)) / richSlots
                                } else {
                                    0f
                                }
                                var richBold = line.bold
                                var richItalic = line.italic
                                var richUnderline = false
                                var richStrike = false
                                var richMono = line.mono
                                var richSup = false
                                var richSub = false
                                val buffer = StringBuilder()
                                fun flushText() {
                                    if (buffer.isEmpty()) return
                                    val basePaint = richPaintFor(
                                        line, richBold, richItalic, richUnderline, richStrike, richMono,
                                    )
                                    val paint = if (richSup || richSub) {
                                        Paint(basePaint).apply { textSize = basePaint.textSize * 0.72f }
                                    } else {
                                        basePaint
                                    }
                                    // WebView: sup/sub 缩小并相对基线上下偏移。
                                    val runBaseline = when {
                                        richSup -> baseline - lineBox * 0.30f
                                        richSub -> baseline + lineBox * 0.14f
                                        else -> baseline
                                    }
                                    if (justifyExtra > 0f) {
                                        for (glyphIndex in buffer.indices) {
                                            val glyph = buffer[glyphIndex].toString()
                                            canvas.nativeCanvas.drawText(glyph, cursorX, runBaseline, paint)
                                            cursorX += paint.measureText(glyph) + justifyExtra
                                        }
                                    } else {
                                        canvas.nativeCanvas.drawText(buffer.toString(), cursorX, runBaseline, paint)
                                        cursorX += paint.measureText(buffer.toString())
                                    }
                                    buffer.clear()
                                }
                                var richIndex = 0
                                val richText = line.text
                                while (richIndex < richText.length) {
                                    val ch = richText[richIndex]
                                    when (ch) {
                                        '\u0006' -> { flushText(); richBold = true; richIndex++ }
                                        '\u0007' -> { flushText(); richBold = false; richIndex++ }
                                        '\u0008' -> { flushText(); richItalic = true; richIndex++ }
                                        '\u0009' -> { flushText(); richItalic = false; richIndex++ }
                                        '\u000A' -> { flushText(); richUnderline = true; richIndex++ }
                                        '\u000B' -> { flushText(); richUnderline = false; richIndex++ }
                                        '\u000C' -> { flushText(); richStrike = true; richIndex++ }
                                        '\u000D' -> { flushText(); richStrike = false; richIndex++ }
                                        '\u000E' -> { flushText(); richMono = true; richIndex++ }
                                        '\u000F' -> { flushText(); richMono = false; richIndex++ }
                                        '\u0010' -> { flushText(); richSup = true; richSub = false; richIndex++ }
                                        '\u0011' -> { flushText(); richSup = false; richIndex++ }
                                        '\u0012' -> { flushText(); richSub = true; richSup = false; richIndex++ }
                                        '\u0013' -> { flushText(); richSub = false; richIndex++ }
                                        '\u0004' -> {
                                            flushText()
                                            val tokenEnd = richText.indexOf('\u0005', richIndex)
                                            if (tokenEnd < 0) { richIndex = richText.length; continue }
                                            val imageId = richText.substring(richIndex + 1, tokenEnd).toIntOrNull() ?: 0
                                            val aspect = (pageSet?.imageAspects?.getOrNull(imageId) ?: 1f)
                                                .coerceAtLeast(0.05f)
                                            // WebView: 内联/脚注图片高度 1.15em、vertical-align: super、左右 0.1em。
                                            val em = fontSizePx * line.sizeRatio
                                            val imageHeight = em * 1.15f
                                            val imageWidth = imageHeight * aspect
                                            val sideMargin = em * 0.1f
                                            val imageTop = baseline - imageHeight * 0.85f
                                            val imageLeft = cursorX + sideMargin
                                            val bitmap = imageCache[imageId]
                                            if (bitmap != null) {
                                                drawImage(
                                                    bitmap.asImageBitmap(),
                                                    dstOffset = IntOffset(imageLeft.roundToInt(), imageTop.roundToInt()),
                                                    dstSize = IntSize(imageWidth.roundToInt(), imageHeight.roundToInt()),
                                                )
                                            } else {
                                                drawRoundRect(
                                                    color = readerTextColor.copy(alpha = 0.12f),
                                                    topLeft = Offset(imageLeft, imageTop),
                                                    size = Size(imageWidth, imageHeight),
                                                    cornerRadius = CornerRadius(2.dp.toPx()),
                                                )
                                            }
                                            cursorX = imageLeft + imageWidth + sideMargin + justifyExtra
                                            richIndex = tokenEnd + 1
                                        }
                                        else -> { buffer.append(ch); richIndex++ }
                                    }
                                }
                                flushText()
                                if (settings.debugMode && offsetX == 0f && chunkIndex >= 0 &&
                                    (line.text.contains("满肚子气") || line.text.contains("冰淇淋") || line.text.contains("什么事"))
                                ) {
                                    android.util.Log.i(
                                        "SaltReaderPerf",
                                        "probe chunk=$chunkIndex i=$lineIndex y=${yTop.roundToInt()} " +
                                            "h=${lineBox.roundToInt()} text=\"${plainLineText(line).take(14)}\"",
                                    )
                                }
                                if (settings.debugMode && offsetX == 0f && chunkIndex >= 0 && paintFor(line).measureText(plainLineText(line)) <= 0.5f) {
                                    val plain = plainLineText(line)
                                    android.util.Log.i(
                                        "SaltReaderPerf",
                                        "line10 i=$lineIndex y=${yTop.roundToInt()} gap=${debugGapPx.roundToInt()} " +
                                            "plainLen=${plain.length} w=${paintFor(line).measureText(plain).roundToInt()} " +
                                            "text=\"${plain.take(12)}\"",
                                    )
                                }
                                yTop += lineBox
                            } else if (line.imageId >= 0) {
                                    // 插画：按宽高比预留整块，居中绘制；位图还没解码好时画占位块。
                                    val aspect = (pageSet?.imageAspects?.getOrNull(line.imageId) ?: 1.5f)
                                        .coerceAtLeast(0.05f)
                                    val verticalFull = settings.readerMode == ReaderMode.VERTICAL && imageFullWidthEffective
                                    val imageWidth: Float
                                    val imageHeight: Float
                                    if (verticalFull) {
                                        // 纵向全宽：画布宽度即图片宽度，边到边，高度按宽高比。
                                        imageWidth = size.width
                                        imageHeight = imageWidth / aspect
                                    } else {
                                        val contentHeight = geometryHeight.coerceAtLeast(1f)
                                        val imageBaseWidth = if (imageFullWidthEffective) {
                                            (leftPadPx + contentWidth + rightPadPx).coerceAtLeast(1f)
                                        } else {
                                            contentWidth
                                        }
                                        val fullHeight = imageBaseWidth / aspect
                                        val cap = contentHeight * if (settings.readerMode == ReaderMode.VERTICAL) 1f else 0.92f
                                        imageHeight = minOf(fullHeight, cap)
                                        imageWidth = imageHeight * aspect
                                    }
                                    val left = if (verticalFull) 0f else if (imageFullWidthEffective) {
                                        (size.width - imageWidth) / 2f
                                    } else {
                                        contentLeft + (contentWidth - imageWidth) / 2f
                                    }
                                    val bitmap = imageCache[line.imageId]
                                    if (bitmap != null) {
                                        drawImage(
                                            bitmap.asImageBitmap(),
                                            dstOffset = IntOffset(left.roundToInt(), yTop.roundToInt()),
                                            dstSize = IntSize(imageWidth.roundToInt(), imageHeight.roundToInt()),
                                        )
                                    } else {
                                        if (settings.debugMode) {
                                            android.util.Log.i(
                                                "SaltReaderPerf",
                                                "image-miss id=${line.imageId} w=${imageWidth.roundToInt()} " +
                                                    "h=${imageHeight.roundToInt()} y=${yTop.roundToInt()}",
                                            )
                                        }
                                        drawRoundRect(
                                            color = readerTextColor.copy(alpha = 0.06f),
                                            topLeft = Offset(left, yTop),
                                            size = Size(imageWidth, imageHeight),
                                            cornerRadius = CornerRadius(8.dp.toPx()),
                                        )
                                    }
                                    yTop += imageHeight
                                } else if (line.tableCols > 0) {
                                    // 表格：同一行所有单元格共用 yTop，最后一列画完才换行。
                                    val tableLeft = contentLeft
                                    val tableWidth = contentWidth
                                    val columnWidth = tableWidth / line.tableCols
                                    val cellLeft = tableLeft + line.tableCol * columnWidth
                                    val pad = fontSizePx * line.sizeRatio * 0.35f
                                    val border = readerTextColor.copy(alpha = 0.28f)
                                    val stroke = 1.dp.toPx()
                                    if (line.tableCol == 0) {
                                        if (line.tableRowStart) {
                                            drawLine(border, Offset(tableLeft, yTop), Offset(tableLeft + tableWidth, yTop), stroke)
                                        }
                                        for (column in 0..line.tableCols) {
                                            val x = tableLeft + column * columnWidth
                                            drawLine(border, Offset(x, yTop), Offset(x, yTop + lineBox), stroke)
                                        }
                                        if (line.tableLastRow && line.tableRowEnd) {
                                            drawLine(
                                                border,
                                                Offset(tableLeft, yTop + lineBox),
                                                Offset(tableLeft + tableWidth, yTop + lineBox),
                                                stroke,
                                            )
                                        }
                                    }
                                    val native = canvas.nativeCanvas
                                    val saved = native.save()
                                    native.clipRect(cellLeft + pad, yTop, cellLeft + columnWidth - pad, yTop + lineBox)
                                    paint.textAlign = Paint.Align.LEFT
                                    native.drawText(line.text, cellLeft + pad, baseline, paint)
                                    native.restoreToCount(saved)
                                    if (line.tableCol == line.tableCols - 1) {
                                        yTop += lineBox
                                    }
                                } else if (
                                    baseline + fontMetrics.descent <=
                                    if (settings.readerMode == ReaderMode.VERTICAL) {
                                        // 纵向模式每个 item 只有内容高度，没有页底留白，不能按底部页边距裁字。
                                        size.height + 1f
                                    } else {
                                        size.height - bottomPadPx + 1f
                                    }
                                ) {
                                    val indent = line.leftIndentEm * fontSizePx
                                    if (line.mono) {
                                        // pre/code 块背景，对应 WebView #book pre 的浅色底（先画底再画字）。
                                        drawRoundRect(
                                            color = readerTextColor.copy(alpha = 0.055f),
                                            topLeft = Offset(contentLeft + 2f, yTop),
                                            size = Size(contentWidth - 4f, lineBox),
                                            cornerRadius = CornerRadius(2.dp.toPx()),
                                        )
                                    }
                                    when (line.align) {
                                        1 -> {
                                            paint.textAlign = Paint.Align.CENTER
                                            canvas.nativeCanvas.drawText(line.text, contentLeft + contentWidth / 2f, baseline, paint)
                                        }

                                        2 -> {
                                            paint.textAlign = Paint.Align.RIGHT
                                            canvas.nativeCanvas.drawText(
                                                line.text,
                                                contentLeft + contentWidth - indent,
                                                baseline,
                                                paint,
                                            )
                                        }

                                        3 -> {
                                            paint.textAlign = Paint.Align.LEFT
                                            drawJustifiedLine(
                                                canvas.nativeCanvas,
                                                line.text,
                                                contentLeft + indent,
                                                baseline,
                                                contentWidth - indent,
                                                paint,
                                            )
                                        }

                                        else -> {
                                            paint.textAlign = Paint.Align.LEFT
                                            canvas.nativeCanvas.drawText(line.text, contentLeft + indent, baseline, paint)
                                        }
                                    }
                                    yTop += lineBox
                                } else {
                                    yTop += lineBox
                                }
                            }
                            if (settings.debugMode && showDebug && offsetX == 0f) {
                                // 与 WebView debug 一致的虚线块边框：每个段落块（含标题/图片/表格）一个。
                                val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.STROKE
                                    strokeWidth = 1.dp.toPx()
                                    color = 0x73009688.toInt()
                                    pathEffect = DashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f)
                                }
                                var debugY = 0f
                                var blockTop = 0f
                                var blockOpen = false
                                pageLines.forEachIndexed { blockIndex, blockLine ->
                                    val gap = blockLine.gapEm * fontSizePx
                                    val startsBlock = blockIndex == 0 || gap > 0f ||
                                        (blockLine.tableCols > 0 && blockLine.tableCol == 0)
                                    if (startsBlock && blockOpen) {
                                        drawIntoCanvas {
                                            it.nativeCanvas.drawRect(
                                                contentLeft, blockTop,
                                                contentLeft + contentWidth, debugY,
                                                dashPaint,
                                            )
                                        }
                                        blockOpen = false
                                    }
                                    debugY += gap
                                    if (!blockOpen) {
                                        blockTop = debugY
                                        blockOpen = true
                                    }
                                    val advance = when {
                                        blockLine.imageId >= 0 -> {
                                            val aspect = (pageSet?.imageAspects?.getOrNull(blockLine.imageId) ?: 1.5f)
                                                .coerceAtLeast(0.05f)
                                            if (settings.readerMode == ReaderMode.VERTICAL && imageFullWidthEffective) {
                                                size.width / aspect
                                            } else {
                                                minOf(
                                                    (if (imageFullWidthEffective) {
                                                        (leftPadPx + contentWidth + rightPadPx)
                                                    } else {
                                                        contentWidth
                                                    }) / aspect,
                                                    geometryHeight * 0.92f,
                                                )
                                            }
                                        }

                                        blockLine.tableCols > 0 ->
                                            if (blockLine.tableCol == blockLine.tableCols - 1) {
                                                fontSizePx * blockLine.lineHeightRatio * blockLine.sizeRatio
                                            } else {
                                                0f
                                            }

                                        else -> fontSizePx * blockLine.lineHeightRatio * blockLine.sizeRatio
                                    }
                                    debugY += advance
                                }
                                if (blockOpen) {
                                    drawIntoCanvas {
                                        it.nativeCanvas.drawRect(
                                            contentLeft, blockTop,
                                            contentLeft + contentWidth, debugY,
                                            dashPaint,
                                        )
                                    }
                                }
                            }
                            if (settings.debugMode && topInset == 0f && offsetX == 0f) {
                                val measured = pageContentHeightPx(pageLines)
                                val first = pageLines.firstOrNull()?.text?.replace('\u0001', ' ')?.take(14)
                                val last = pageLines.lastOrNull()?.text?.replace('\u0001', ' ')?.take(14)
                                android.util.Log.i(
                                    "SaltReaderPerf",
                                    "draw-chunk idx=$chunkIndex lines=${pageLines.size} measure=${measured.roundToInt()} " +
                                        "drawn=${yTop.roundToInt()} first=\"$first\" last=\"$last\"",
                                )
                            }
                        }
                    }
                }
            // Android 原生复制菜单（floating ActionMode）。
            var copyActionMode by remember { mutableStateOf<ActionMode?>(null) }
            fun selectionContentRect(outRect: Rect) {
                val page = pageSet?.pages?.getOrNull(selectionPage)
                val tops = if (page != null) lineTops(page) else emptyList()
                val startTop = tops.getOrNull(selectionStartLine) ?: topPadPx
                val endTop = tops.getOrNull(selectionEndLine) ?: startTop
                val lineHeight = fontSizePx * 1.8f
                val geometry = pageSet
                val left = geometry?.contentLeftPx ?: leftPadPx
                val width = geometry?.contentWidthPx ?: (pageArea.width - leftPadPx - rightPadPx)
                outRect.set(left.roundToInt(), startTop.roundToInt(), (left + width).roundToInt(), (endTop + lineHeight).roundToInt())
            }

            fun startCopyActionMode(): ActionMode? = view.startActionMode(
                object : ActionMode.Callback2() {
                    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                        menu.add(0, 1, 0, context.getString(R.string.reader_action_copy))
                        return true
                    }
                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
                    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                        if (item.itemId != 1) return false
                        pageSet?.pages?.getOrNull(selectionPage)?.let { copySelection(it) }
                        mode.finish()
                        return true
                    }
                    override fun onDestroyActionMode(mode: ActionMode) {
                        copyActionMode = null
                        clearSelection()
                    }
                    override fun onGetContentRect(mode: ActionMode, target: android.view.View, outRect: Rect) {
                        selectionContentRect(outRect)
                    }
                },
                ActionMode.TYPE_FLOATING,
            )

            LaunchedEffect(selectionPage) {
                if (selectionPage >= 0) {
                    if (copyActionMode == null) copyActionMode = startCopyActionMode()
                } else {
                    copyActionMode?.finish()
                    copyActionMode = null
                }
            }

            val pages = pageSet?.pages
            val currentPage = pages?.getOrNull(safePageIndex)
            if (currentPage == null) {
                SaltLoading(sizeDp = 48, color = accent)
            } else if (settings.readerMode == ReaderMode.VERTICAL) {
                // 纵向模式：分页结果按页连续堆叠，LazyColumn 只渲染可见页。
                val verticalState = verticalListState
                // 首次进入/切换模式时对齐到当前页。
                LaunchedEffect(pageSet, settings.readerMode) {
                    val target = pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    if (verticalState.firstVisibleItemIndex != target) {
                        verticalState.scrollToItem(target)
                    }
                }
                // 进度条/目录/音量键的跳转：立即滚到目标页（不做动画，避免回写打断）。
                LaunchedEffect(verticalJumpTarget) {
                    if (verticalJumpTarget >= 0) {
                        verticalState.scrollToItem(verticalJumpTarget.coerceIn(0, pages.size - 1))
                        verticalJumpTarget = -1
                    }
                }
                // 用户滚动：只回写页码用于进度显示，不再反向触发滚动。
                LaunchedEffect(verticalState, verticalRestored) {
                    if (!verticalRestored) return@LaunchedEffect
                    snapshotFlow { verticalState.firstVisibleItemIndex }
                        .distinctUntilChanged()
                        .collect { index ->
                            if (index in 0 until totalPages && index != pageIndex) {
                                pageIndex = index
                            }
                        }
                }

                LazyColumn(
                    state = verticalState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = with(density) { topPadPx.toDp() },
                        bottom = with(density) { bottomPadPx.toDp() },
                    ),
                ) {
                    items(pages.size) { index ->
                        val itemHeightDp = with(density) { pageContentHeightPx(pages[index]).toDp() }
                        if (settings.debugMode) {
                            val lines = pages[index]
                            android.util.Log.i(
                                "SaltReaderPerf",
                                "compose-item idx=$index lines=${lines.size} " +
                                    "heightPx=${pageContentHeightPx(lines).roundToInt()} " +
                                    "first=\"${lines.firstOrNull()?.text?.replace('\u0001', ' ')?.take(12)}\"",
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(itemHeightDp)) {
                            drawPage(pages[index], 0f, 0f, index == verticalState.firstVisibleItemIndex, index)
                        }
                    }
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = activePanel == ReaderChromePanel.NONE && selectionPage < 0,
                    beyondViewportPageCount = 1,
                ) { pageIndexItem ->
                    val pageLines = pageSet?.pages?.getOrNull(pageIndexItem) ?: return@HorizontalPager
                    var lastTouch by remember { mutableStateOf(Offset.Zero) }
                    Box(
                        Modifier
                            .fillMaxSize()
                            // 不消费事件的指针跟踪：长按/点击位置用于选区定位，不影响 Pager 拖动。
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.lastOrNull()?.let { lastTouch = it.position }
                                    }
                                }
                            }
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { handleReaderTap(lastTouch, view.width.toFloat()) },
                                onLongClick = { handleReaderLongPress(lastTouch) },
                            ),
                    ) {
                        drawPage(pageLines, 0f, Float.NaN, true, -1)
                    }
                }
            }
        }

        // 空结果/解析失败：给出明确提示与重试/回退入口，而不是永远停在空白页面。
        val emptyPageSet = pageSet
        if (emptyPageSet != null && emptyPageSet.pages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.reader_empty_content),
                        color = readerTextColor.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(Modifier.padding(top = 12.dp)) {
                        Text(
                            stringResource(R.string.reader_action_retry),
                            modifier = Modifier
                                .clickable {
                                    anchorRatio = -1f
                                    pageIndex = -1
                                    initialised = false
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            color = accent,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            stringResource(R.string.reader_action_switch_webview),
                            modifier = Modifier
                                .clickable { onSettingsChange { it.copy(nativeReader = false) } }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            color = accent,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        // Debug 参数面板：样式与 WebView 的 #salt-debug 完全一致（左上角、半透明白底、蓝色等宽小字、单实例）。
        if (settings.debugMode) {
            val debugPageLines = pageSet?.pages?.getOrNull(
                if (isVerticalMode) verticalListState.firstVisibleItemIndex else safePageIndex,
            )
            val pageHeight = debugPageLines?.let { pageContentHeightPx(it) } ?: 0f
            val debugText = listOf(
                "NATIVE dpr=${density.density * density.fontScale}",
                "top=${topPadPx.roundToInt()} bottom=${bottomPadPx.roundToInt()} " +
                    "left=${leftPadPx.roundToInt()} right=${rightPadPx.roundToInt()}px",
                "font=${fontSizePx.roundToInt()}px line=${lineHeightPx.roundToInt()}px " +
                    "lineH=${settings.readerLineHeight}",
                "content=${(pageArea.width - leftPadPx - rightPadPx).roundToInt()}x" +
                    "${(pageArea.height - topPadPx - bottomPadPx).roundToInt()}px",
                "PAGE_PAD_TOP=$pagePadTopDp BOTTOM=$pagePadBottomDp " +
                    "MARGIN_L=$pageMarginLeftDp R=$pageMarginRightDp dp",
                "imgFull=$imageFullWidthEffective vertical=$verticalPads pageH=${pageHeight.roundToInt()}",
            ).joinToString("\n")
            androidx.compose.material3.Surface(
                modifier = Modifier
                    // 与 WebView 同位置（左上角）；声明在顶栏之前，让顶栏绘制在其上层。
                    .align(Alignment.TopStart)
                    .padding(0.dp),
                color = Color(0xE6FFFFFF),
                contentColor = Color(0xFF1565C0),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
            ) {
                Text(
                    text = debugText,
                    modifier = Modifier.padding(4.dp),
                    color = Color(0xFF1565C0),
                    fontSize = 10.sp,
                    lineHeight = 14.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        // 原生顶栏：复用与 WebView 阅读器一致的淡入 + 轻微垂直滑入动画。
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it / 3 },
            exit = fadeOut() + slideOutVertically { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                book = book,
                onBack = onBack,
                subtitle = { chapterTitle },
                containerColor = chromeBarColor,
                contentColor = chromeContentColor,
            )
        }

        // 原生底栏 + 面板：与 WebView 阅读器相同的滑入动画，面板用 animateContentSize。
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(color = chromeBarColor, contentColor = chromeContentColor) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 4.dp)
                        .animateContentSize(),
                ) {
                    ReaderBottomBar(
                        progress = { verticalFraction },
                        label = {
                            val percent = (verticalFraction * 100).roundToInt()
                            if (settings.readerMode == ReaderMode.VERTICAL) {
                                stringResource(R.string.reader_percent, percent)
                            } else {
                                val page = if (totalPages > 0) safePageIndex + 1 else 0
                                stringResource(R.string.reader_progress_combo, percent, page, totalPages)
                            }
                        },
                        onJump = { fraction ->
                            if (settings.readerMode == ReaderMode.VERTICAL) {
                                if (totalPages > 1) {
                                    pageIndex = (fraction * (totalPages - 1)).roundToInt()
                                        .coerceIn(0, totalPages - 1)
                                    requestVerticalJump(pageIndex)
                                }
                            } else if (totalPages > 1) {
                                pageIndex = (fraction * (totalPages - 1)).roundToInt()
                                    .coerceIn(0, totalPages - 1)
                            }
                        },
                        snapPages = if (isVerticalMode) 0 else totalPages,
                        progressLabelSizeSp = settings.readerProgressLabelSizeSp,
                        accentColor = accent,
                        activePanel = activePanel,
                        onThemeToggle = {
                            activePanel = if (activePanel == ReaderChromePanel.THEME) {
                                ReaderChromePanel.NONE
                            } else {
                                ReaderChromePanel.THEME
                            }
                        },
                        onOpenToc = {
                            activePanel = if (activePanel == ReaderChromePanel.TOC) {
                                ReaderChromePanel.NONE
                            } else {
                                ReaderChromePanel.TOC
                            }
                        },
                        onOpenSettings = {
                            activePanel = ReaderChromePanel.NONE
                            context.startActivity(Intent(context, ReaderSettingsActivity::class.java))
                        },
                        showTypography = true,
                        onTypographyToggle = {
                            activePanel = if (activePanel == ReaderChromePanel.TYPOGRAPHY) {
                                ReaderChromePanel.NONE
                            } else {
                                ReaderChromePanel.TYPOGRAPHY
                            }
                        },
                        modeToggleEnabled = true,
                        readerMode = settings.readerMode,
                        onReaderModeToggle = {
                            onSettingsChange { current ->
                                current.copy(
                                    readerMode = if (current.readerMode == ReaderMode.PAGED) {
                                        ReaderMode.VERTICAL
                                    } else {
                                        ReaderMode.PAGED
                                    },
                                )
                            }
                        },
                        containerColor = chromeBarColor,
                        contentColor = chromeContentColor,
                    )
                    when (activePanel) {
                        ReaderChromePanel.TOC -> ReaderTocPanel(
                            chapters = chapters,
                            currentChapter = chapterIndex,
                            onSelect = { index ->
                                activePanel = ReaderChromePanel.NONE
                                jumpToChapter(index)
                            },
                            containerColor = chromeBarColor,
                            contentColor = chromeContentColor,
                        )

                        ReaderChromePanel.THEME -> ReaderThemePanel(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            onCustomBackground = { showCustomBackground = true },
                            showTextSize = true,
                            accentColor = accent,
                            containerColor = chromeBarColor,
                            contentColor = chromeContentColor,
                        )

                        ReaderChromePanel.TYPOGRAPHY -> ReaderTypographyPanel(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            onOpenAdvanced = {
                                activePanel = ReaderChromePanel.NONE
                                context.startActivity(Intent(context, ReaderSettingsActivity::class.java))
                            },
                            onOpenFontSettings = {
                                activePanel = ReaderChromePanel.NONE
                                context.startActivity(Intent(context, ReaderFontSettingsActivity::class.java))
                            },
                            containerColor = chromeBarColor,
                            contentColor = chromeContentColor,
                            accentColor = accent,
                        )

                        else -> Unit
                    }
                }
            }
        }

        // chrome 隐藏时的页码/进度角标（与 WebView 阅读器一致）。
        AnimatedVisibility(
            visible = !chromeVisible && settings.readerPageInfo != ReaderPageInfo.HIDDEN,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 12.dp),
        ) {
            val percent = (verticalFraction * 100).roundToInt()
            val label = if (settings.readerPageInfo == ReaderPageInfo.PAGES && !isVerticalMode) {
                stringResource(R.string.reader_page_indicator, pageIndex.coerceAtLeast(0) + 1, totalPages.coerceAtLeast(1))
            } else {
                stringResource(R.string.reader_percent, percent)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = settings.readerPageIndicatorSizeSp.sp,
                    fontFeatureSettings = "tnum",
                ),
                color = readerTextColor.copy(alpha = 0.6f),
            )
        }

        // 左下角电池指示（与 WebView 阅读器一致，chrome 隐藏时显示）。
        AnimatedVisibility(
            visible = !chromeVisible && settings.readerBatteryIndicator,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 18.dp, bottom = 12.dp),
        ) {
            BatteryIndicator(
                textSizeSp = settings.readerPageIndicatorSizeSp,
                color = pageIndicatorColor,
            )
        }



        // Debug：一键切换回 WebView 阅读器，方便对比两边锚点。
        if (settings.debugMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 96.dp, end = 8.dp),
                color = Color(0xE6FFFFFF),
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 2.dp,
            ) {
                Text(
                    stringResource(R.string.reader_debug_switch_to_webview),
                    modifier = Modifier
                        .clickable {
                            val ratio = if (totalPages > 1) safePageIndex.toFloat() / (totalPages - 1) else 0f
                            if (onDebugSwitch != null) {
                                onDebugSwitch(ratio)
                            } else {
                                onSettingsChange { it.copy(nativeReader = false) }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = Color(0xFFB00020),
                )
            }
        }

        if (showCustomBackground) {
            CustomColorPickerDialog(
                initialColor = settings.customBackgroundColor,
                onDismiss = { showCustomBackground = false },
                onConfirm = { color ->
                    showCustomBackground = false
                    onSettingsChange {
                        it.copy(readerBackground = ReaderBackground.CUSTOM, customBackgroundColor = color)
                    }
                },
            )
        }
    }
}

/** 行内字符是否完全不可见（样式标记、控制符、格式符、零宽字符、空白）。 */
private fun isInvisibleNativeChar(ch: Char): Boolean {
    if (ch.code in 0x0006..0x0013) return true
    if (ch.code < 0x20 || ch.code == 0x7F) return true
    if (ch.isWhitespace() || Character.isSpaceChar(ch)) return true
    if (ch == '\uFEFF' || ch in '\u200B'..'\u200F' || ch in '\u2060'..'\u2064') return true
    // 电子书常用的“不可见占位符”：韩文填充符、盲文空白、缺省字形等，渲染出来是空白。
    if (ch == '\u3164' || ch == '\u2800' || ch == '\u115F' || ch == '\u1160' ||
        ch == '\uFFA0' || ch == '\u180E' || ch == '\u2007' || ch == '\u2063'
    ) {
        return true
    }
    val type = Character.getType(ch)
    return type == Character.FORMAT.toInt() || type == Character.UNASSIGNED.toInt()
}

/** 只有不可见内容、没有块图片/表格的行：不应占用行高，也不参与切片。 */
private fun isInvisibleNativeLine(line: NativeLine): Boolean {
    if (line.imageId >= 0) return false
    if (line.tableCols > 0) return false
    var position = 0
    while (position < line.text.length) {
        val ch = line.text[position]
        if (ch == '\u0004') {
            // 只含行内图片 token 的行没有可见正文，按空行处理（避免出现整行空白）。
            val tokenEnd = line.text.indexOf('\u0005', position)
            position = if (tokenEnd < 0) line.text.length else tokenEnd + 1
            continue
        }
        if (!isInvisibleNativeChar(ch)) return false
        position += 1
    }
    return true
}

/** 章节 HTML → 纯文本（Rust 排版只吃纯文本）。 */
private fun chapterPlainText(chapter: EbookChapter): String {
    if (chapter.imageOnly) return ""
    val text = runCatching {
        Html.fromHtml(chapter.html, Html.FROM_HTML_MODE_LEGACY).toString()
    }.getOrElse { chapter.html.replace(Regex("<[^>]+>"), "") }
    return text
        .replace('\u00A0', ' ')
        .lines()
        .map { it.trimEnd() }
        .joinToString("\n")
        .trim('\n', ' ')
}

private fun buildNativePageSet(
    chapters: List<EbookChapter>,
    width: Float,
    height: Float,
    fontSizePx: Float,
    lineHeightFactor: Float,
    typeface: Typeface,
    contentLeftPx: Float = 0f,
    contentWidthPx: Float = 1f,
    topPadPx: Float = 0f,
    bottomPadPx: Float = 0f,
    contentHeightPx: Float = 1f,
    imageFullWidth: Boolean = false,
    fullPageWidth: Float = 1f,
    imageMaxHeightRatio: Float = 0.92f,
    imagePlaceholder: String = "［图片］",
): NativePageSet? {
    if (!NativeTextLayout.isAvailable) return null
    val token = nativeParserToken.incrementAndGet()
    val text = buildString {
        for ((chapterPosition, chapter) in chapters.withIndex()) {
            val body = chapterPlainText(chapter).trim()
            if (body.isBlank()) continue
            val title = chapter.title?.trim().orEmpty()
            if (title.isNotEmpty() && chapter.showHeading) {
                append(title)
                append('\n')
            }
            append(body)
            append("\n\n")
        }
    }.trim()
    if (text.isBlank()) return NativePageSet(emptyList())

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSizePx
        this.typeface = typeface
    }
    // 字宽表：整本书去重后的码点各测一次，Rust 用它做精确断行。
    val codePoints = ArrayList<Int>(1024)
    val seen = HashSet<Int>(2048)
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (seen.add(codePoint)) codePoints.add(codePoint)
        index += Character.charCount(codePoint)
    }
    val advances = FloatArray(codePoints.size) { i ->
        val codePoint = codePoints[i]
        val measured = paint.measureText(String(Character.toChars(codePoint)))
        // 自定义/系统字体缺少 CJK 字形时，measureText 可能给出 0 或极小值，
        // 会直接导致 Rust 认为一行能塞下整段文字。宽字符给一个全角下限。
        val isWide = codePoint in 0x2E80..0x9FFF || codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFF00..0xFF60 || codePoint in 0x20000..0x2FFFD
        when {
            !measured.isFinite() || measured <= 0f ->
                if (isWide) fontSizePx else fontSizePx * 0.55f
            // CJK 字形回退到系统字体时，实测宽度可能比真实绘制窄，统一按全角宽度排版。
            isWide -> maxOf(measured, fontSizePx)
            else -> measured
        }
    }

    // 把章节 HTML 交给 Rust 解析（含书籍自带 CSS 子集）；标题缺失时补一个 h2。
    // 只记录图片所在章节与序号；解码时按需从章节 HTML 取 data URI，避免全书图片字节常驻。
    val imageRefs = ArrayList<ImageRef>()
    val imageAspects = ArrayList<Float>()
    // 必须连同 src="..." 引号整体替换，否则会生成畸形属性：src="data-salt-img="0""。
    val dataUri = Regex(
        "src=\"data:image/([A-Za-z0-9.+-]+);base64,([A-Za-z0-9+/=\\r\\n]+)\"",
        RegexOption.IGNORE_CASE,
    )
    var perChapterImageBase = 0
    val htmlChapters = ArrayList<String>(chapters.size)
    for ((chapterPosition, chapter) in chapters.withIndex()) {
        val imagesBeforeChapter = imageRefs.size
        perChapterImageBase = imagesBeforeChapter
        // image-only（彩页）章节也要参与：它们的正文就是插图，跳过会丢掉整章插画。
        val withImages = if (chapter.html.contains("data:image", ignoreCase = true)) {
            // 保留原 <img> 的 class/style（脚注/封面/插图类名决定内联还是块级），
            // 只把 data URI 换成 data-salt-img 占位。
            val imgTag = Regex("(?is)<img\\b[^>]*>")
            chapter.html.replace(imgTag) { match ->
                val tag = match.value
                val dataMatch = dataUri.find(tag)
                if (dataMatch == null) {
                    tag
                } else {
                    val payload = dataMatch.groupValues[2]
                    val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
                    if (bytes == null) {
                        tag
                    } else {
                        val id = imageRefs.size
                        imageRefs.add(ImageRef(chapterPosition, id - perChapterImageBase))
                        imageAspects.add(imageAspect(bytes))
                        tag.replaceRange(dataMatch.range, "data-salt-img=\"$id\"")
                    }
                }
            }
        } else {
            chapter.html
        }
        val body = withImages.stripHeavyPayloads()
        if (body.isBlank()) continue
        val title = chapter.title?.trim().orEmpty()
        val addHeading = !chapter.imageOnly && title.isNotEmpty() && chapter.showHeading &&
            !body.contains(title, ignoreCase = true)
        htmlChapters += if (addHeading) {
            "<h2>${title.escapeHtml()}</h2>$body"
        } else {
            body
        }
    }
    if (htmlChapters.isEmpty()) {
        return NativePageSet(emptyList())
    }
android.util.Log.i(
        "SaltReaderNative",
        "images extracted=${imageRefs.size}; " +
            "sample=${
                htmlChapters.firstOrNull()?.let { html ->
                    Regex("(?is)<img[^>]{0,160}>").find(html)?.value ?: "(no img)"
                }
            }",
    )

    return runCatching {
        val startedAt = System.nanoTime()
        val raw = NativeTextLayout.paginateHtml(
            htmlChapters.toTypedArray(),
            width,
            height,
            fontSizePx,
            lineHeightFactor,
            codePoints.toIntArray(),
            advances,
            imageAspects.toFloatArray(),
            imageFullWidth,
            fullPageWidth,
            imageMaxHeightRatio,
            imagePlaceholder,
        )
        val pages = if (raw.isNullOrEmpty()) {
            emptyList()
        } else {
            raw.split('\u0000').map { page -> page.split('\n').map(::decodeNativeLine) }
        }
        android.util.Log.i(
            "SaltReaderNative",
            "paginate ${htmlChapters.size} chapters -> ${pages.size} pages in " +
                "${(System.nanoTime() - startedAt) / 1_000_000}ms (token=$token)",
        )
        NativePageSet(
            pages, imageRefs.toList(), imageAspects.toList(),
            contentLeftPx, contentWidthPx, topPadPx, bottomPadPx, contentHeightPx,
        )
    }.getOrElse {
        android.util.Log.w("SaltReaderNative", "native paginate failed (token=$token)", it)
        null
    }
}

/**
 * 解析原生渲染用的 Typeface：自定义字体（字体目录文件或系统字体）+ 回退字体链。
 * API 29+ 用 [Typeface.CustomFallbackBuilder] 把回退字体串成真正的 fallback chain；
 * 更低版本退回主字体（Android 仍会做系统兜底）。
 */
private fun isCjkChar(ch: Char): Boolean {
    val code = ch.code
    return code in 0x2E80..0x9FFF || code in 0xF900..0xFAFF ||
        code in 0xFF00..0xFF60 || code in 0x3000..0x303F
}

/**
 * 两端对齐：仅在中日文边界/空格处拉开间距（和 WebView 的 justify 行为接近），
 * 最后一行由 Rust 标记为左对齐，不参与拉伸。
 */
private fun drawJustifiedLine(
    canvas: android.graphics.Canvas,
    text: String,
    x: Float,
    baseline: Float,
    targetWidth: Float,
    paint: Paint,
) {
    val clusters = ArrayList<String>(text.length)
    val clusterWidths = ArrayList<Float>(text.length)
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val count = Character.charCount(codePoint)
        val cluster = text.substring(index, index + count)
        clusters.add(cluster)
        clusterWidths.add(paint.measureText(cluster))
        index += count
    }
    val measured = clusterWidths.sum()
    if (measured <= 0f || targetWidth <= measured + 0.5f || clusters.size < 2) {
        canvas.drawText(text, x, baseline, paint)
        return
    }
    fun gapBoundary(position: Int): Boolean {
        if (position >= clusters.size - 1) return false
        val current = clusters[position].lastOrNull()
        val next = clusters[position + 1].firstOrNull()
        return (current != null && isCjkChar(current)) ||
            (next != null && isCjkChar(next)) ||
            current == ' '
    }
    var gaps = 0
    for (position in 0 until clusters.size - 1) if (gapBoundary(position)) gaps++
    if (gaps == 0) {
        canvas.drawText(text, x, baseline, paint)
        return
    }
    val extra = (targetWidth - measured) / gaps
    var cursor = x
    clusters.forEachIndexed { position, cluster ->
        canvas.drawText(cluster, cursor, baseline, paint)
        cursor += clusterWidths[position]
        if (gapBoundary(position)) cursor += extra
    }
}

/** 解析 Rust 返回的样式前缀；兼容无前缀的纯文本行。 */
private fun imageAspect(bytes: ByteArray): Float = runCatching {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    val width = options.outWidth
    val height = options.outHeight
    if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1.5f
}.getOrElse { 1.5f }

private const val MAX_CACHED_IMAGES = 24
private const val MAX_IMAGE_CACHE_BYTES = 48L * 1024 * 1024

/** 一页里出现的图片 id（块级图片 + 行内 \u0004 token），用于预渲染。 */
private fun pageImageIds(pageSet: NativePageSet, pageIndex: Int): List<Int> {
    val page = pageSet.pages.getOrNull(pageIndex) ?: return emptyList()
    val ids = LinkedHashSet<Int>()
    for (line in page) {
        if (line.imageId >= 0) ids.add(line.imageId)
        var position = line.text.indexOf('\u0004')
        while (position >= 0) {
            val end = line.text.indexOf('\u0005', position)
            if (end < 0) break
            line.text.substring(position + 1, end).toIntOrNull()?.let { ids.add(it) }
            position = line.text.indexOf('\u0004', end + 1)
        }
    }
    return ids.toList()
}

private fun resolveImageBytes(ref: ImageRef, chapters: List<EbookChapter>): ByteArray? {
    val html = chapters.getOrNull(ref.chapterIndex)?.html ?: return null
    val match = NATIVE_DATA_URI.findAll(html).elementAtOrNull(ref.dataUriIndex) ?: return null
    return runCatching { Base64.decode(match.groupValues[2], Base64.DEFAULT) }.getOrNull()
}

private fun decodeInlineBitmap(
    ref: ImageRef,
    chapters: List<EbookChapter>,
    targetWidth: Int,
): Bitmap? = runCatching {
    val bytes = resolveImageBytes(ref, chapters) ?: return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    if (width <= 0) return@runCatching null
    var sample = 1
    while (width / (sample * 2) >= targetWidth.coerceAtLeast(1)) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}.getOrNull()

internal fun decodeNativeLine(raw: String): NativeLine {
    if (raw.length >= 8 && raw[0] == '\u0003') {
        return NativeLine(
            text = "",
            imageId = raw.substring(1, 6).toIntOrNull() ?: -1,
            gapEm = raw.substring(6, 8).toFloatOrNull()?.div(10f) ?: 0f,
        )
    }
    if (raw.length >= 18 && raw[0] == '\u0002') {
        val col = raw.substring(1, 3).toIntOrNull() ?: 0
        val cols = raw.substring(3, 5).toIntOrNull() ?: 0
        val flags = raw[5].digitToIntOrNull() ?: 0
        val inner = decodeNativeLine(raw.substring(6))
        return inner.copy(
            tableCol = col,
            tableCols = cols,
            tableRowStart = flags and 1 != 0,
            tableLastRow = flags and 2 != 0,
            tableRowEnd = flags and 4 != 0,
        )
    }
    if (raw.length < 12 || raw[0] != '\u0001') return NativeLine(raw)
    return NativeLine(
        text = raw.substring(12),
        sizeRatio = raw.substring(1, 3).toFloatOrNull()?.div(10f) ?: 1f,
        lineHeightRatio = raw.substring(3, 5).toFloatOrNull()?.div(10f) ?: 1.8f,
        bold = raw[5] == '1' || raw[5] == '3' || raw[5] == '5' || raw[5] == '7',
        italic = raw[5] == '2' || raw[5] == '3' || raw[5] == '6' || raw[5] == '7',
        mono = raw[5] == '4' || raw[5] == '5' || raw[5] == '6' || raw[5] == '7',
        align = raw.substring(6, 8).toIntOrNull() ?: 0,
        leftIndentEm = raw.substring(8, 10).toFloatOrNull()?.div(10f) ?: 0f,
        gapEm = raw.substring(10, 12).toFloatOrNull()?.div(10f) ?: 0f,
    )
}

/**
 * 原生排版只需要文字结构：把 base64 内联图片、内联 SVG、超长 data URI 去掉，
 * 避免把整本图片书的负载复制进 Rust（否则内存会被放大到 OOM）。
 */
private fun String.stripHeavyPayloads(): String {
    if (!contains("data:") && !contains("<svg", ignoreCase = true) && length < 2_000_000) {
        return this
    }
    var result = this
    if (result.contains("<svg", ignoreCase = true)) {
        result = result.replace(Regex("(?is)<svg.*?</svg>"), "［图片］")
    }
    if (result.contains("data:")) {
        result = result.replace(Regex("(?is)data:[^\"'\\s>]{64,}"), "data:image")
    }
    if (result.contains("<img", ignoreCase = true)) {
        result = result.replace(Regex("(?is)<img(?![^>]*data-salt-img)[^>]*>"), "［图片］")
    }
    return result
}

private fun String.escapeHtml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private fun nativeTypeface(context: Context, settings: AppSettings): Typeface {
    val customRef = settings.readerCustomFont
    val customFile = if (settings.readerFontFamily == ReaderFontFamily.CUSTOM) {
        customFontFile(context, customRef)
    } else {
        null
    }
    if (customFile != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val fallbackRefs = listOfNotNull(
            settings.readerFontFallback1,
            settings.readerFontFallback2,
            settings.readerFontFallback3,
        )
        val fallbackFiles = fallbackRefs.mapNotNull { customFontFile(context, it) }
        val systemFallback = fallbackRefs.firstOrNull { it.source == ReaderFontSource.SYSTEM }?.key
        return runCatching {
            val builder = Typeface.CustomFallbackBuilder(fontFamilyOf(customFile))
            fallbackFiles.forEach { builder.addCustomFallback(fontFamilyOf(it)) }
            if (fallbackFiles.isEmpty() && !systemFallback.isNullOrBlank()) {
                builder.setSystemFallback(systemFallback)
            }
            builder.build()
        }.getOrElse { Typeface.createFromFile(customFile) }
    }
    return when (settings.readerFontFamily) {
        ReaderFontFamily.SONG -> Typeface.SERIF
        ReaderFontFamily.HEI -> Typeface.SANS_SERIF
        ReaderFontFamily.CUSTOM -> {
            if (customRef?.source == ReaderFontSource.SYSTEM) {
                runCatching { Typeface.create(customRef.key, Typeface.NORMAL) }.getOrElse { Typeface.SANS_SERIF }
            } else {
                Typeface.SANS_SERIF
            }
        }
    }
}

private fun customFontFile(context: Context, ref: ReaderFontRef?): java.io.File? {
    if (ref == null || ref.key.isBlank() || ref.source != ReaderFontSource.DOCUMENT) return null
    return ReaderFonts.ensureCached(context, ref)
}

private fun fontFamilyOf(file: java.io.File): android.graphics.fonts.FontFamily =
    android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(file).build()).build()

private fun nativeChapterIndex(offset: Long, prefix: LongArray): Int {
    var low = 0
    var high = prefix.size - 1
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (prefix[mid] <= offset) low = mid else high = mid - 1
    }
    return (low - 1).coerceIn(0, (prefix.size - 2).coerceAtLeast(0))
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).roundToInt(),
    (red * 255).roundToInt(),
    (green * 255).roundToInt(),
    (blue * 255).roundToInt(),
)
