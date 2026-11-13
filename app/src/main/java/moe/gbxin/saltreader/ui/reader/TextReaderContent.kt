package moe.gbxin.saltreader.ui.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.PixelCopy
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import moe.gbxin.saltreader.BuildConfig
import moe.gbxin.saltreader.R
import moe.gbxin.saltreader.data.AppSettings
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.EbookChapter
import moe.gbxin.saltreader.data.ReaderBackground
import moe.gbxin.saltreader.data.ReaderFontFamily
import moe.gbxin.saltreader.data.ReaderFontSetup
import moe.gbxin.saltreader.data.ReaderFonts
import moe.gbxin.saltreader.data.ReaderMode
import moe.gbxin.saltreader.data.ReaderPageInfo
import moe.gbxin.saltreader.data.ReadingDirection
import moe.gbxin.saltreader.ui.components.BatteryIndicator
import moe.gbxin.saltreader.ui.components.CustomColorPickerDialog
import moe.gbxin.saltreader.ui.components.SaltLoading
import moe.gbxin.saltreader.ui.settings.ReaderFontSettingsActivity
import moe.gbxin.saltreader.ui.settings.ReaderSettingsActivity
import moe.gbxin.saltreader.ui.theme.pageBackgroundColor
import moe.gbxin.saltreader.ui.theme.readerAccentColor
import moe.gbxin.saltreader.ui.theme.readerForegroundColor
import org.json.JSONObject

/** Latest reported layout state of the text reader WebView. */
private data class TextPageInfo(val page: Int, val pages: Int, val ratio: Float)

/** Events posted from the WebView's JavaScript bridge back to the Compose side. */
internal sealed interface TextReaderEvent {
    data class State(val page: Int, val pages: Int, val ratio: Float, val token: Int) : TextReaderEvent
    data object MiddleZone : TextReaderEvent

    /** A tap at [ratio] (0..1 across the viewport); the native side maps it to page turns/chrome. */
    data class Tap(val ratio: Float) : TextReaderEvent

    /** 纵向模式内容不足一屏：需要再追加章节，否则滚动容器永远不会产生滚动事件。 */
    data object NeedMore : TextReaderEvent

    /** 纵向模式 DOM 裁掉了前面 [count] 个章节（释放图片内存），需要同步窗口起点。 */
    data class Trimmed(val count: Int) : TextReaderEvent
}

/**
 * JavaScript -> Kotlin bridge. Called on the WebView thread, so events are pushed through a
 * thread-safe [MutableSharedFlow] and consumed on the main thread.
 */
@Keep
internal class TextJavascriptBridge(private val emit: (TextReaderEvent) -> Unit) {
    @Keep
    @JavascriptInterface
    fun onState(page: Int, pages: Int, ratio: Double, token: Int) {
        emit(TextReaderEvent.State(page, pages, ratio.toFloat().coerceIn(0f, 1f), token))
    }

    @Keep
    @JavascriptInterface
    fun onMiddle() {
        emit(TextReaderEvent.MiddleZone)
    }

    @Keep
    @JavascriptInterface
    fun onTap(ratio: Double) {
        emit(TextReaderEvent.Tap(ratio.toFloat().coerceIn(0f, 1f)))
    }

    @Keep
    @JavascriptInterface
    fun onNeedMore() {
        emit(TextReaderEvent.NeedMore)
    }

    @Keep
    @JavascriptInterface
    fun onTrimmed(count: Int) {
        if (count > 0) emit(TextReaderEvent.Trimmed(count))
    }
}

/** Holds the current WebView without turning it into Compose state. */
private class WebViewHolder {
    var webView: WebView? = null
}

/**
 * Mutable value holder read from event handlers / effects without becoming Compose state.
 * Writing these must not invalidate composition: page turns only recompose the chrome, whose
 * lambdas read [TextPageInfo] directly.
 */
private class ScalarHolder(var value: Float = 0f)

/**
 * Native text reader for reflowable books (EPUB text, MOBI, AZW3).
 *
 * The sanitized chapter HTML is rendered by an [WebView] (the only reflow engine available without
 * native libraries). PAGED mode uses CSS multi-column layout and is turned page by page; VERTICAL
 * mode is a normal vertical scroll. Compose draws the chrome; in PAGED mode a transparent Compose
 * overlay owns taps/swipes (the WebView itself does not need touch there), while in VERTICAL mode the
 * WebView keeps native scrolling and reports middle-third taps through JavaScript.
 *
 * Progress is a character offset over the *visible text* of the book (not over the raw HTML length):
 * inlined base64 illustrations would otherwise dominate the offsets of image-heavy light novels.
 * Only the chapters around the current position are injected into the WebView; chapters are appended
 * or prepended as the reader approaches a window edge.
 */
@Composable
internal fun TextReaderContent(
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
    val readerBackground = pageBackgroundColor(
        settings.readerBackground,
        settings.customBackgroundColor,
        isSystemInDarkTheme(),
        MaterialTheme.colorScheme.surface,
    )
    // 每个纸张色对应一组 Material 强调色，供 slider/激活图标等控件使用。
    val readerAccent = readerAccentColor(settings.readerBackground, settings.customBackgroundColor)
    // 纯黑纸张保持纯白字；其余（含自定义背景）按对比度自动计算前景色。
    val readerTextColor = if (readerBackground == Color.Black) {
        Color.White
    } else {
        readerForegroundColor(readerBackground)
    }
    val density = LocalDensity.current
    // 内联图片注入前的最大宽度（按屏幕宽度降采样，减少 WebView 解码/纹理内存）。
    val inlineImageMaxWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }.roundToInt()

    val direction = book.direction ?: settings.readingDirection
    val isLightThemeBackground = MaterialTheme.colorScheme.background.luminance() > 0.5f

    // 章节权重 = 可见文字字符数：内联的 base64 插图会让 html.length 膨胀几百倍，用它当分母
    // 会让图片页吞掉几乎全部进度。旧缓存没有 charCount（=0）时退回 html.length 以保持兼容；
    // 纯图片页按 1 个字符计，保证它仍然可以被目录定位到。
    val chapterChars = remember(chapters) {
        LongArray(chapters.size) { index ->
            val chapter = chapters[index]
            val visible = when {
                chapter.charCount > 0 -> chapter.charCount.toLong()
                chapter.imageOnly -> 0L
                else -> chapter.html.length.toLong()
            }
            visible.coerceAtLeast(1L)
        }
    }
    /** 章节 HTML 的渲染成本（已剔除 base64 图片负载），仅用于窗口预算；进度换算仍用可见字符。 */
    val chapterHtmlChars = remember(chapters) {
        LongArray(chapters.size) { index -> chapterHtmlCost(chapters[index].html) }
    }
    val chapterPrefix = remember(chapterChars) {
        val prefix = LongArray(chapterChars.size + 1)
        for (i in chapterChars.indices) prefix[i + 1] = prefix[i] + chapterChars[i]
        prefix
    }
    /**
     * 渲染窗口的字符预算：图片型文字书单章可能有几百 KB（内联插图），一次注入太多会让
     * WebView 首帧排版明显变慢；轻量章节仍可一次多注入几章，减少追加次数。
     */
    fun renderWindowEnd(
        start: Int,
        maxChapters: Int,
        maxChars: Long,
        mustIncludeIndex: Int? = null,
    ): Int {
        var end = start
        var chars = 0L
        while (end < chapters.size && end - start < maxChapters) {
            val next = chapterHtmlChars.getOrElse(end) { 0L }
            if (end > start && chars + next > maxChars) break
            chars += next
            end++
        }
        // 跳转目标章必须留在窗口内：预算不够时也至少把它包含进来，避免"跳到图片章却停在上一章"。
        if (mustIncludeIndex != null && end <= mustIncludeIndex) {
            end = (mustIncludeIndex + 1).coerceAtMost(chapters.size)
        }
        return end.coerceAtLeast((start + 1).coerceAtMost(chapters.size))
    }

    /** 同 [renderWindowEnd]，但从窗口尾部向前扩展（前插章节时保持与窗口内容连续）。 */
    fun renderWindowStart(endExclusive: Int, maxChapters: Int, maxChars: Long): Int {
        var start = endExclusive
        var chars = 0L
        while (start > 0 && endExclusive - start < maxChapters) {
            val prev = chapterHtmlChars.getOrElse(start - 1) { 0L }
            if (endExclusive - start > 0 && chars + prev > maxChars) break
            chars += prev
            start--
        }
        return start
    }

    // 全书字符数只认章节可见字符之和（book.charCount 是索引阶段的估算值，图片书会严重偏大）。
    val totalChars = remember(chapterPrefix) {
        (chapterPrefix.lastOrNull() ?: 0L).coerceAtLeast(1L)
    }
    val progressOffset = remember(book.id, initialRatioOverride) {
        if (initialRatioOverride != null) {
            (initialRatioOverride.coerceIn(0f, 1f) * totalChars.toFloat()).toLong()
                .coerceIn(0L, (totalChars - 1L).coerceAtLeast(0L))
        } else {
            maxOf(book.progressOffset, vm.latestProgressOffset())
                .coerceIn(0L, (totalChars - 1L).coerceAtLeast(0L))
        }
    }

    val initialChunk = remember(book.id, chapters) {
        val index = chapterIndexForOffset(progressOffset, chapterPrefix)
        val start = (index - CHAPTER_START_LEAD).coerceAtLeast(0)
        // 纵向模式首屏更小：图片型章节的 base64 负载很大，一次注入太多会明显卡顿。
        if (settings.readerMode == ReaderMode.VERTICAL) {
            start to renderWindowEnd(start, VERTICAL_INITIAL_CHAPTER_BATCH, VERTICAL_INITIAL_RENDER_CHAR_BUDGET, index)
        } else {
            start to renderWindowEnd(start, INITIAL_CHAPTER_BATCH, INITIAL_RENDER_CHAR_BUDGET, index)
        }
    }

    fun ratioWithinWindow(offset: Long, start: Int, end: Int): Float {
        val base = chapterPrefix.getOrElse(start) { 0L }
        val span = (chapterPrefix.getOrElse(end) { base } - base).coerceAtLeast(1L)
        return ((offset - base).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    // windowStart/windowEnd: 当前已经注入 WebView 的章节区间（追加/前插会扩展它）；
    // renderStart/renderEnd: 当前 WebView 初始 HTML 的区间（跳转时整体替换并重建 WebView）。
    // 两者在初始化时相同，跳转时一起重设；日常滚动只动 window*（JS 侧 DOM 追加，无需重建）。
    var windowStart by remember(book.id, initialChunk) { mutableIntStateOf(initialChunk.first) }
    var windowEnd by remember(book.id, initialChunk) { mutableIntStateOf(initialChunk.second) }
    var renderStart by remember(book.id, initialChunk) { mutableIntStateOf(initialChunk.first) }
    var renderEnd by remember(book.id, initialChunk) { mutableIntStateOf(initialChunk.second) }
    var renderToken by remember(book.id) { mutableIntStateOf(0) }
    var jumpVeil by remember { mutableStateOf(false) }
    var veilVisible by remember { mutableStateOf(false) }
    // Ratio inside the loaded window; read by JS commands and the options effect.
    val localRatio = remember(book.id) {
        ScalarHolder(ratioWithinWindow(progressOffset, initialChunk.first, initialChunk.second))
    }

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var activePanel by rememberSaveable { mutableStateOf(ReaderChromePanel.NONE) }
    LaunchedEffect(chromeVisible) {
        if (!chromeVisible) activePanel = ReaderChromePanel.NONE
    }
    LaunchedEffect(jumpVeil) {
        if (jumpVeil) {
            kotlinx.coroutines.delay(140)
            veilVisible = jumpVeil
            kotlinx.coroutines.delay(600)
            jumpVeil = false
            veilVisible = false
        } else {
            veilVisible = false
        }
    }
    var showCustomBackground by rememberSaveable { mutableStateOf(false) }
    val prefetchScope = rememberCoroutineScope()
    var prefetchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Fingerprint of the last page that scheduled a window prefetch, so intra-page reports do not
    // restart the debounce coroutine over and over.
    var lastPrefetchKey by remember(book.id) { mutableLongStateOf(Long.MIN_VALUE) }
    var latestReportedRatio by remember { mutableFloatStateOf(0f) }
    var pageInfo by remember(book.id) {
        mutableStateOf(
            TextPageInfo(
                page = 0,
                pages = 1,
                ratio = (progressOffset.toDouble() / totalChars.toDouble()).toFloat().coerceIn(0f, 1f),
            ),
        )
    }

    // Only recompute the top-bar chapter subtitle when the chapter actually changes, not on every
    // 90ms progress report (keeps the top bar out of the per-scroll recomposition path).
    val currentChapterIndex by remember(chapterPrefix, totalChars) {
        derivedStateOf {
            val offset = (pageInfo.ratio.toDouble() * totalChars.toDouble()).toLong()
                .coerceIn(0L, (totalChars - 1L).coerceAtLeast(0L))
            chapterIndexForOffset(offset, chapterPrefix)
        }
    }

    // Building a whole window's HTML can take tens of milliseconds on long chapters; do it off the
    // main thread so opening / jumping never blocks the UI.
    // 内联图片改为本地 URL 后，WebView 通过 shouldInterceptRequest 按 token 取回字节。
    val inlineImageStore = remember { ConcurrentHashMap<String, InlineImageResource>() }
    // 每章 -> 该章内联图片 token，trim 章节时同步释放字节，避免图片书内存只增不减。
    val imageTokensByChapter = remember {
        ConcurrentHashMap<Int, MutableSet<String>>()
    }
    val inlineImageTokens = remember { AtomicInteger(0) }
    val html by produceState(
        initialValue = "",
        renderToken,
        renderStart,
        renderEnd,
        chapters,
        book.title,
    ) {
        value = withContext(Dispatchers.Default) {
            // 整窗重建后旧文档的图片 token 不会再被请求，先释放字节，避免多次跳转后一直累积。
            inlineImageStore.clear()
            imageTokensByChapter.clear()
            inlineImageTokens.set(0)
            val buildStartedAt = System.nanoTime()
            // 逐章外部化内联图片：记录每章 token，trim 时才能释放对应字节。
            val bodyBuilder = StringBuilder(HTML_HEAD)
            for (chapterIndex in renderStart until renderEnd) {
                if (bodyBuilder.length >= MAX_TOTAL_HTML_CHARS) break
                val tokensForChapter = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                val chapterMarkup = externalizeInlineImages(
                    chapterHtml(
                        chapters[chapterIndex],
                        chapterIndex,
                        book.title,
                        chapters.size == 1,
                    ),
                    inlineImageStore,
                    inlineImageTokens,
                    inlineImageMaxWidthPx,
                ) { token -> tokensForChapter.add(token) }
                if (tokensForChapter.isNotEmpty()) {
                    imageTokensByChapter[chapterIndex] = tokensForChapter
                }
                val chunk = if (settings.readerMode == ReaderMode.VERTICAL) {
                    lazyImages(chapterMarkup)
                } else {
                    chapterMarkup
                }
                bodyBuilder.append(chunk)
            }
            bodyBuilder.append(HTML_TAIL)
            val finalHtml = bodyBuilder.toString()
            Log.i(
                TAG_TEXT_PERF,
                "build window chapters=${renderEnd - renderStart} chars=${finalHtml.length} " +
                    "mode=${settings.readerMode} elapsed=${(System.nanoTime() - buildStartedAt) / 1_000_000}ms",
            )
            finalHtml
        }
    }
    // 性能锚点：内联图片请求计数/字节数 + 缓存总字节。
    val inlineImageRequestCount = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    val inlineImageBytes = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val inlineCacheBytes = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    // UNLIMITED Channel：Trimmed/NeedMore 等窗口记账事件不能被丢弃，否则进度 base 会永久偏移。
    val events = remember { kotlinx.coroutines.channels.Channel<TextReaderEvent>(
        kotlinx.coroutines.channels.Channel.UNLIMITED,
    ) }
    val bridge = remember { TextJavascriptBridge { event -> events.trySend(event) } }
    val holder = remember { WebViewHolder() }
    var pageFinishedToken by remember { mutableIntStateOf(0) }
    // WebView 渲染进程被系统回收后，这个 WebView 实例就不可用了，必须重建。
    // webViewGeneration 作为 AndroidView 的 key 控制重建；loadStartedAt / lastPageEventAt
    // 用于加载看门狗：长时间收不到页面事件就判定加载卡死并恢复。
    var webViewGeneration by remember(book.id) { mutableIntStateOf(0) }
    // 非 0 表示"正在等待这次加载的第一份页面状态"；收到状态即清零。
    var loadStartedAt by remember(book.id) { mutableLongStateOf(0L) }
    var renderFailures by remember(book.id) { mutableIntStateOf(0) }
    var renderFailed by remember(book.id) { mutableStateOf(false) }
    var firstLayoutRendered by remember(book.id) { mutableStateOf(false) }
    val firstRenderAlpha = animateFloatAsState(
        targetValue = if (firstLayoutRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "text reader first render",
    )
    var lastAppliedOptionsJson by remember(book.id) { mutableStateOf<String?>(null) }

    var lastSentOffset by remember { mutableLongStateOf(-1L) }
    var lastSentAt by remember { mutableLongStateOf(0L) }
    // 每装载一份新文档就换一个 token；旧文档排队的事件按 token 丢弃，
    // 不再用时间窗，否则新文档自己的 page state 也可能被误丢（快速拖动时尤其明显）。
    var docToken by remember(book.id) { mutableIntStateOf(0) }
    val liveRatio = remember(book.id, totalChars) {
        ScalarHolder((progressOffset.toDouble() / totalChars.toDouble()).toFloat().coerceIn(0f, 1f))
    }
    val liveTotalChars = rememberUpdatedState(totalChars)
    val liveMode = rememberUpdatedState(settings.readerMode)
    val liveDirection = rememberUpdatedState(direction)
    val view = LocalView.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 字体方案：系统字体直接写字体族名，目录字体先复制到缓存再由 shouldInterceptRequest
    // 按 token 供给 @font-face。解析/复制都是阻塞 IO，必须离开主线程。
    val fontResources = remember { mutableStateOf<Map<String, ReaderFonts.FontResource>>(emptyMap()) }
    val fontSetup by produceState(
        initialValue = ReaderFontSetup.DEFAULT,
        settings.readerCustomFont,
        settings.readerFontHei,
        settings.readerFontSong,
        settings.readerFontMono,
    ) {
        val setup = withContext(Dispatchers.IO) { ReaderFontSetup.build(context, settings) }
        fontResources.value = setup.resources
        value = setup
    }

    // 解析完成后回填权威字符数，让书架进度和阅读器进度条使用同一个分母。
    LaunchedEffect(book.id, totalChars) {
        vm.updateTextStats(totalChars)
    }

    fun runJs(script: String) {
        val webView = holder.webView ?: return
        runCatching { webView.evaluateJavascript(script, null) }
    }

    fun turnVisual(delta: Int) {
        runJs("window.__reader && window.__reader.turn($delta);")
    }

    /** Instant page turn used by the left/right tap zones (no glide). */
    fun turnInstant(delta: Int) {
        runJs("window.__reader && window.__reader.turnInstant($delta);")
    }

    /** Short, snappy glide used by the volume keys. */
    fun turnFast(delta: Int) {
        runJs("window.__reader && window.__reader.turnFast($delta);")
    }

    /** [logicalDelta] is +1 for "next page" in reading order; RTL mirrors it horizontally. */
    fun turnPage(logicalDelta: Int, animate: Boolean = false) {
        if (liveMode.value != ReaderMode.PAGED) return
        val horizontal = if (liveDirection.value == ReadingDirection.RTL) -logicalDelta else logicalDelta
        if (animate) turnFast(horizontal) else turnInstant(horizontal)
    }

    fun scrollViewport(delta: Int) {
        runJs("window.__reader && window.__reader.scrollViewport($delta);")
    }

    fun offsetFor(ratio: Float): Long {
        val chars = liveTotalChars.value
        return (ratio.toDouble() * chars.toDouble()).toLong()
            .coerceIn(0L, (chars - 1L).coerceAtLeast(0L))
    }

    /** Throttled progress write: at most every 1.5s or after a 400-character move. */
    fun persistProgress(force: Boolean) {
        val offset = offsetFor(liveRatio.value)
        val now = SystemClock.elapsedRealtime()
        if (!force && abs(offset - lastSentOffset) < 400L && now - lastSentAt < 1_500L) return
        if (offset == lastSentOffset && now - lastSentAt < 1_500L) return
        lastSentOffset = offset
        lastSentAt = now
        vm.updateTextProgress(offset)
    }

    /** 已注入窗口内的比例 -> 全书比例；进度条和持久化都用全书比例。 */
    fun globalRatio(local: Float): Float {
        val base = chapterPrefix.getOrElse(windowStart) { 0L }
        val span = (chapterPrefix.getOrElse(windowEnd) { base } - base).coerceAtLeast(1L)
        val offset = base + (local.toDouble() * span).toLong()
        return (offset.toDouble() / totalChars.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** 窗口边界变化后把本地比例换算到新窗口，避免追加/前插瞬间进度写错。 */
    fun rebaseLocalRatio(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) {
        val oldBase = chapterPrefix.getOrElse(oldStart) { 0L }
        val oldSpan = (chapterPrefix.getOrElse(oldEnd) { oldBase } - oldBase).coerceAtLeast(1L)
        val offset = oldBase + (localRatio.value.toDouble() * oldSpan).toLong()
        localRatio.value = ratioWithinWindow(offset, newStart, newEnd)
    }

    /**
     * WebView 渲染进程被回收/加载超时后重建时，DOM 只剩初始渲染窗口，
     * 必须把窗口记账回退到 renderStart..renderEnd，否则进度分母与 restore 比例都会错位。
     */
    fun resetWindowToRenderedHtml() {
        windowStart = renderStart
        windowEnd = renderEnd
        docToken += 1
        val offset = (liveRatio.value.toDouble() * totalChars.toDouble()).toLong()
            .coerceIn(0L, (totalChars - 1L).coerceAtLeast(0L))
        localRatio.value = ratioWithinWindow(offset, renderStart, renderEnd)
    }

    fun windowForChapter(index: Int, batch: Int = CHAPTER_BATCH): Pair<Int, Int> {
        val safe = index.coerceIn(0, chapters.size - 1)
        val start = (safe - CHAPTER_START_LEAD).coerceAtLeast(0)
        return start to renderWindowEnd(start, batch, WINDOW_RENDER_CHAR_BUDGET, safe)
    }

    // Use the window container size (stable across system-bar visibility) rather than
    // Configuration.screen*Dp, which changes when the chrome hides/shows the bars and would make
    // the total page count jump around.
    val windowInfo = LocalWindowInfo.current
    val estimatedCharsPerPage = remember(
        settings.readerFontSizeSp,
        settings.readerLineHeight,
        settings.readerPageMarginDp,
        settings.readerFontFamily,
        windowInfo.containerSize,
        density,
    ) {
        val widthDp = with(density) { windowInfo.containerSize.width.toDp().value }
        val heightDp = with(density) { windowInfo.containerSize.height.toDp().value }
        estimateCharsPerPage(
            fontSizeSp = settings.readerFontSizeSp,
            lineHeightMultiplier = settings.readerLineHeight,
            pageMarginDp = settings.readerPageMarginDp,
            screenWidthDp = widthDp.roundToInt(),
            screenHeightDp = heightDp.roundToInt(),
        )
    }

    val estimatedTotalPages = remember(totalChars, estimatedCharsPerPage) {
        ceil(totalChars.toDouble() / estimatedCharsPerPage.toDouble()).toInt().coerceAtLeast(1)
    }
    val liveEstimatedTotalPages = rememberUpdatedState(estimatedTotalPages)
    // 初始页码只按“打开时的保存进度”算一次：不要在组合期读 pageInfo（每次 onState 都会全量重组）。
    val initialEstimatedPage = remember(book.id, estimatedTotalPages) {
        val ratio = (vm.latestProgressOffset().toFloat() / totalChars.toFloat()).coerceIn(0f, 1f)
        (ratio * estimatedTotalPages.toFloat()).roundToInt().coerceIn(1, estimatedTotalPages)
    }
    var pageBase by remember(book.id) { mutableIntStateOf(initialEstimatedPage - 1) }
    var pendingJumpPage by remember { mutableStateOf<Int?>(null) }
    var lastPageWindowStart by remember(book.id) { mutableIntStateOf(-1) }

    /**
     * 跳到指定字符偏移：围绕目标章节重建 WebView 的初始窗口。目录选择可能落在当前窗口之外很远
     * 的地方，所以这里同时重设 window* 与 render*，用 renderToken 强制重建 WebView，再把
     * 目标在窗口内的比例交给 JS 恢复；立即写入进度，离开阅读器也不会丢位置。
     */
    fun jumpToOffset(offset: Long) {
        val target = offset.coerceIn(0L, (totalChars - 1L).coerceAtLeast(0L))
        val chapter = chapterIndexForOffset(target, chapterPrefix)
        val (start, end) = windowForChapter(chapter)
        // The DOM may contain many chapters beyond the initial render range (append/prepend), so
        // decide by the actually loaded window; only rebuild when the target chapter is missing.
        val loadedRange = windowStart until windowEnd
        val withinRenderedWindow = chapter in loadedRange && holder.webView != null
        liveRatio.value = (target.toDouble() / totalChars.toDouble()).toFloat().coerceIn(0f, 1f)
        pageInfo = TextPageInfo(
            page = pageInfo.page,
            pages = pageInfo.pages,
            ratio = (target.toDouble() / totalChars.toDouble()).toFloat().coerceIn(0f, 1f),
        )
        lastSentOffset = target
        lastSentAt = SystemClock.elapsedRealtime()
        vm.updateTextProgress(target)
        val targetPage = (
            (target.toDouble() / totalChars.toDouble()) * liveEstimatedTotalPages.value.toDouble()
            ).roundToInt().coerceIn(1, liveEstimatedTotalPages.value)
        pendingJumpPage = targetPage
        // Keep the page label in sync immediately. The WebView's restore callback is delayed and
        // can be dropped by the stale-event guard while it is rebuilding after a distant jump.
        pageBase = targetPage - 1 - pageInfo.page
        if (withinRenderedWindow) {
            // Target already exists in the loaded document: just move the native scroller. This is
            // what keeps slider scrubbing from reflowing the whole book.
            // JS restore expects a ratio across the whole loaded DOM, which is the window
            // (windowStart..windowEnd) - not the initial render range.
            localRatio.value = ratioWithinWindow(target, windowStart, windowEnd)
            runJs("window.__reader && window.__reader.restore(${localRatio.value});")
        } else {
            val (jumpStart, jumpEnd) = windowForChapter(chapter, batch = JUMP_BATCH)
            windowStart = jumpStart
            windowEnd = jumpEnd
            renderStart = jumpStart
            renderEnd = jumpEnd
            localRatio.value = ratioWithinWindow(target, jumpStart, jumpEnd)
            jumpVeil = true
            docToken += 1
            renderToken += 1
        }
    }

    fun jumpToChapter(index: Int) {
        val target = chapterPrefix.getOrElse(index.coerceIn(0, chapters.size - 1)) { 0L }
        jumpToOffset(target)
    }

    // 拖动进度条时可能连续触发跳转；防抖到最后一帧，避免同时排队多次整窗重建。
    val jumpScope = rememberCoroutineScope()
    var pendingJumpJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun jumpToFraction(fraction: Float) {
        pendingJumpJob?.cancel()
        pendingJumpJob = jumpScope.launch {
            kotlinx.coroutines.delay(120)
            jumpToOffset((fraction.toDouble() * totalChars.toDouble()).toLong())
        }
    }

    /**
     * 接近已加载窗口边界时按批注入章节。章节 HTML 已经在内存里，这里只是重新交给 WebView，
     * 不触发任何文件读取；JS 侧会保持当前滚动位置。
     *
     * Building the payload (a few chapters, possibly with inline base64 illustrations) and quoting
     * it for `evaluateJavascript` can take tens of milliseconds, so it runs on
     * [Dispatchers.Default]; the main thread only flips the window bookkeeping and posts the script.
     */
    var lastChapterLoadAt by remember { mutableLongStateOf(0L) }
    suspend fun maybeLoadMoreChapters(ratio: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastChapterLoadAt < CHAPTER_LOAD_THROTTLE_MS) return
        val append = ratio >= PREFETCH_RATIO && windowEnd < chapters.size
        val prepend = !append && ratio <= 1f - PREFETCH_RATIO && windowStart > 0
        if (!append && !prepend) return
        val startBefore = windowStart
        val endBefore = windowEnd
        val nextStart = if (prepend) {
            renderWindowStart(windowStart, CHAPTER_BATCH, WINDOW_RENDER_CHAR_BUDGET)
        } else {
            windowStart
        }
        val nextEnd = if (append) {
            renderWindowEnd(windowEnd, CHAPTER_BATCH, WINDOW_RENDER_CHAR_BUDGET)
        } else {
            windowEnd
        }
        lastChapterLoadAt = now
        val script = withContext(Dispatchers.Default) {
            val appendStartedAt = System.nanoTime()
            // 逐章外部化并记录 token（trim 时按章释放字节）。
            val payloadBuilder = StringBuilder()
            val range = if (append) endBefore until nextEnd else nextStart until startBefore
            for (chapterIndex in range) {
                val tokensForChapter = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                val externalized = externalizeInlineImages(
                    chapterHtml(chapters[chapterIndex], chapterIndex, book.title, chapters.size == 1),
                    inlineImageStore,
                    inlineImageTokens,
                    inlineImageMaxWidthPx,
                ) { token -> tokensForChapter.add(token) }
                if (tokensForChapter.isNotEmpty()) {
                    imageTokensByChapter[chapterIndex] = tokensForChapter
                }
                val chunk = if (settings.readerMode == ReaderMode.VERTICAL) {
                    lazyImages(externalized)
                } else {
                    externalized
                }
                payloadBuilder.append(chunk)
            }
            val payload = payloadBuilder.toString()
            val runtime = Runtime.getRuntime()
            Log.i(
                TAG_TEXT_PERF,
                "append=$append payloadChars=${payload.length} " +
                    "elapsed=${(System.nanoTime() - appendStartedAt) / 1_000_000}ms mode=${settings.readerMode} " +
                    "storeTokens=${inlineImageStore.size} storeBytes=${inlineCacheBytes.get()} " +
                    "heapUsedMB=${(runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024} " +
                    "heapMaxMB=${runtime.maxMemory() / 1024 / 1024}",
            )
            if (payload.isEmpty()) {
                ""
            } else if (append) {
                "window.__reader && window.__reader.append(${JSONObject.quote(payload)});"
            } else {
                "window.__reader && window.__reader.prepend(${JSONObject.quote(payload)});"
            }
        }
        if (script.isEmpty()) return
        // A jump may have rebuilt the window while the payload was being built.
        if (windowStart != startBefore || windowEnd != endBefore) return
        windowStart = nextStart
        windowEnd = nextEnd
        rebaseLocalRatio(startBefore, endBefore, windowStart, windowEnd)
        runJs(script)
    }

    // WebView -> Compose events (main thread).
    LaunchedEffect(events) {
        events.receiveAsFlow().collect { event ->
            if (event is TextReaderEvent.State && event.token != docToken) {
                return@collect
            }
            when (event) {
                is TextReaderEvent.State -> {
                    // 本次加载已经出结果，看门狗不再计时。
                    loadStartedAt = 0L
                    renderFailures = 0
                    renderFailed = false
                    firstLayoutRendered = true
                    jumpVeil = false
                    localRatio.value = event.ratio
                    val global = globalRatio(event.ratio)
                    liveRatio.value = global
                    pageInfo = TextPageInfo(event.page, event.pages, global)
                    // Keep the displayed page continuous across jumps / window changes, then let it
                    // follow the real DOM page index so every swipe adds exactly one.
                    val totalForPages = liveEstimatedTotalPages.value
                    val expectedFromRatio = (global * totalForPages.toFloat()).roundToInt()
                        .coerceIn(1, totalForPages)
                    val candidate = pageBase + event.page + 1
                    val pending = pendingJumpPage
                    val drifted = kotlin.math.abs(candidate - expectedFromRatio) > 3
                    if (pending != null || windowStart != lastPageWindowStart || drifted) {
                        val expected = (pending ?: expectedFromRatio).coerceIn(1, totalForPages)
                        // This offset must be signed: a jump to an earlier page can map to a lower
                        // DOM page inside the same loaded chapter window.
                        pageBase = expected - 1 - event.page
                        pendingJumpPage = null
                    }
                    lastPageWindowStart = windowStart
                    persistProgress(force = false)
                    // Grow the loaded window only after the page has actually come to rest: doing
                    // it inside the last scroll report made the snap correction stutter at the end
                    // of every swipe.
                    latestReportedRatio = event.ratio
                    // The JS side already dedupes identical reports; restart the prefetch debounce
                    // only when the page (or window start) actually changed.
                    val prefetchKey = if (liveMode.value == ReaderMode.VERTICAL) {
                        // Vertical reports always have page=0. Key by the edge being approached and
                        // both window bounds so each scroll boundary can load another chapter batch.
                        val edge = when {
                            event.ratio >= PREFETCH_RATIO && windowEnd < chapters.size -> 1L
                            event.ratio <= 1f - PREFETCH_RATIO && windowStart > 0 -> -1L
                            else -> 0L
                        }
                        if (edge == 0L) {
                            0L
                        } else {
                            (windowStart.toLong() shl 33) or
                                (windowEnd.toLong() shl 1) or
                                if (edge > 0) 1L else 0L
                        }
                    } else {
                        (windowStart.toLong() shl 32) xor event.page.toLong()
                    }
                    if (prefetchKey != lastPrefetchKey) {
                        lastPrefetchKey = prefetchKey
                        prefetchJob?.cancel()
                        prefetchJob = prefetchScope.launch {
                            kotlinx.coroutines.delay(360)
                            maybeLoadMoreChapters(latestReportedRatio)
                        }
                    }
                }
                is TextReaderEvent.Trimmed -> {
                    val removedStart = windowStart
                    val removedEnd = (removedStart + event.count).coerceAtMost(windowEnd)
                    for (chapterIndex in removedStart until removedEnd) {
                        imageTokensByChapter.remove(chapterIndex)?.forEach { token ->
                            inlineImageStore.remove(token)
                        }
                    }
                    windowStart = removedEnd
                    lastPageWindowStart = -1
                    Log.i(TAG_TEXT_PERF, "trimmed=${event.count} window=${windowStart}..${windowEnd}")
                }

                TextReaderEvent.NeedMore -> {
                    if (liveMode.value == ReaderMode.VERTICAL && windowEnd < chapters.size) {
                        prefetchJob?.cancel()
                        prefetchJob = prefetchScope.launch { maybeLoadMoreChapters(1f) }
                    }
                }

                TextReaderEvent.MiddleZone -> chromeVisible = !chromeVisible
                is TextReaderEvent.Tap -> {
                    // While a panel is open, tapping the page only dismisses it: no page turn from
                    // the left/right thirds while the menu is expanded.
                    if (activePanel != ReaderChromePanel.NONE) {
                        activePanel = ReaderChromePanel.NONE
                    } else {
                    val third = 1f / 3f
                    if (liveMode.value == ReaderMode.PAGED) {
                        when {
                            event.ratio < third ->
                                turnPage(if (liveDirection.value == ReadingDirection.RTL) 1 else -1)
                            event.ratio > 1f - third ->
                                turnPage(if (liveDirection.value == ReadingDirection.RTL) -1 else 1)
                            else -> chromeVisible = !chromeVisible
                        }
                    } else if (event.ratio in third..(1f - third)) {
                        chromeVisible = !chromeVisible
                    }
                    }
                }
            }
        }
    }

    // Safe area (status bar / cutout / gesture bar): the page padding must clear these, like
    // WeChat Reading, instead of using a fixed value measured from the physical screen edge.
    // The chrome hides/shows the system bars, which changes the *current* safeDrawing insets; if we
    // re-applied options on every change the WebView would reflow each time the toolbar is toggled.
    // Keeping the largest inset seen for the current configuration makes page geometry independent
    // of chrome visibility (it only updates on rotation / cutout changes).
    val configuration = LocalConfiguration.current
    // Deterministic characters-per-page from the typography settings: total pages must be
    // recalculated whenever font size / line spacing / page margin / font family change.
    val safePadding = WindowInsets.safeDrawing.asPaddingValues()
    val currentSafeTop = safePadding.calculateTopPadding().value
    val currentSafeBottom = safePadding.calculateBottomPadding().value
    val currentSafeLeft = safePadding.calculateLeftPadding(LocalLayoutDirection.current).value
    val currentSafeRight = safePadding.calculateRightPadding(LocalLayoutDirection.current).value
    var safeTopDp by remember(configuration) { mutableFloatStateOf(currentSafeTop) }
    var safeBottomDp by remember(configuration) { mutableFloatStateOf(currentSafeBottom) }
    var safeLeftDp by remember(configuration) { mutableFloatStateOf(currentSafeLeft) }
    var safeRightDp by remember(configuration) { mutableFloatStateOf(currentSafeRight) }
    SideEffect {
        if (currentSafeTop > safeTopDp) safeTopDp = currentSafeTop
        if (currentSafeBottom > safeBottomDp) safeBottomDp = currentSafeBottom
        if (currentSafeLeft > safeLeftDp) safeLeftDp = currentSafeLeft
        if (currentSafeRight > safeRightDp) safeRightDp = currentSafeRight
    }

    val optionsJson = remember(
        docToken,
        settings.readerMode,
        settings.readerFontSizeSp,
        settings.readerLineHeight,
        settings.readerPageMarginDp,
        settings.readerFontFamily,
        fontSetup,
        readerBackground,
        readerTextColor,
        safeTopDp,
        safeBottomDp,
        safeLeftDp,
        safeRightDp,
    ) {
        JSONObject().apply {
            put("docToken", docToken)
            put("mode", if (settings.readerMode == ReaderMode.PAGED) "paged" else "vertical")
            put("fontSize", settings.readerFontSizeSp)
            put("lineHeight", settings.readerLineHeight.toDouble())
            put("pageMargin", settings.readerPageMarginDp)
            put("imageFullWidth", settings.readerImageFullWidth)
            put("fontFamily", fontSetup.familyFor(settings.readerFontFamily))
            put("fontFaces", fontSetup.fontFacesCss)
            put("monoFamily", fontSetup.familyMono)
            put("background", cssColor(readerBackground))
            put("color", cssColor(readerTextColor))
            put("safeTop", safeTopDp.toDouble())
            put("safeBottom", safeBottomDp.toDouble())
            put("safeLeft", safeLeftDp.toDouble())
            put("safeRight", safeRightDp.toDouble())
        }.toString()
    }
    // 切换纸张颜色时立即同步 WebView 的原生背景色：CSS 重绘要等下一次 apply/布局，
    // 原生 background 先变，避免出现"换色后页面还是旧底色"的一帧延迟。
    LaunchedEffect(readerBackground) {
        holder.webView?.setBackgroundColor(readerBackground.toArgb())
        runJs("window.__reader && window.__reader.paint(${JSONObject.quote(cssColor(readerBackground))});")
    }
    LaunchedEffect(chromeVisible) {
        // Hiding the chrome relayouts the page; repaint so the paper colour cannot flash back.
        runJs("window.__reader && window.__reader.paint(${JSONObject.quote(cssColor(readerBackground))});")
    }
    LaunchedEffect(pageFinishedToken, optionsJson) {
        if (pageFinishedToken == 0) return@LaunchedEffect
        if (lastAppliedOptionsJson == optionsJson) return@LaunchedEffect
        lastAppliedOptionsJson = optionsJson
        runJs("window.__reader && window.__reader.apply($optionsJson, ${localRatio.value});")
    }

    DisposableEffect(settings.keepScreenOn) {
        val window = (view.context as? Activity)?.window
        if (settings.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val activityWindow = remember(view) { (view.context as? Activity)?.window }
    val insetsController = remember(view) {
        activityWindow?.let { WindowCompat.getInsetsController(it, view) }
    }
    // 与原生阅读器共用同一份 chrome 配色逻辑。
    val chromeColors = rememberReaderChromeColors(settings, readerBackground, readerTextColor)
    val chromeBarColor = chromeColors.bar
    val chromeContentColor = chromeColors.content
    val pageIndicatorColor = chromeColors.indicator
    val statusBarBackground = if (chromeVisible) chromeBarColor else readerBackground
    val navigationBarBackground = if (chromeVisible) chromeBarColor else readerBackground
    DisposableEffect(insetsController) {
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            insetsController?.isAppearanceLightStatusBars = isLightThemeBackground
            insetsController?.isAppearanceLightNavigationBars = isLightThemeBackground
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
        insetsController?.isAppearanceLightStatusBars = statusBarBackground.luminance() > 0.5f
        insetsController?.isAppearanceLightNavigationBars = navigationBarBackground.luminance() > 0.5f
    }

    // Persist when the reader goes to the background or is closed, so the shelf reflects the
    // position even if the user never taps the back button.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                persistProgress(force = true)
                // 文字进度同样落独立进度文件，切后台时立即刷盘。
                vm.flushProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            persistProgress(force = true)
            vm.flushProgress()
        }
    }

    LaunchedEffect(volumePageEvents) {
        volumePageEvents?.collect { delta -> turnPage(delta, animate = true) }
    }

    val chromeToggleLabel =
        stringResource(if (chromeVisible) R.string.reader_hide_controls else R.string.reader_show_controls)
    val readingAreaLabel = stringResource(R.string.reader_reading_area)
    val previousPageLabel = stringResource(R.string.reader_action_previous_page)
    val nextPageLabel = stringResource(R.string.reader_action_next_page)

    Box(
        modifier
            .fillMaxSize()
            .background(readerBackground)
            .semantics {
                contentDescription = readingAreaLabel
                customActions = listOf(
                    CustomAccessibilityAction(previousPageLabel) {
                        if (liveMode.value == ReaderMode.PAGED) turnPage(-1) else scrollViewport(-1)
                        true
                    },
                    CustomAccessibilityAction(nextPageLabel) {
                        if (liveMode.value == ReaderMode.PAGED) turnPage(1) else scrollViewport(1)
                        true
                    },
                    CustomAccessibilityAction(chromeToggleLabel) {
                        chromeVisible = !chromeVisible
                        true
                    },
                )
            },
    ) {
        // One WebView instance for the whole reading session: jumps call loadDataWithBaseURL on it
        // instead of destroying/recreating the view, which is the main cost of the old jump path.
        key(webViewGeneration) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                    setBackgroundColor(readerBackground.toArgb())
                    this.settings.apply {
                        javaScriptEnabled = true
                        blockNetworkLoads = true
                        allowFileAccess = false
                        allowContentAccess = false
                        // Text books may keep illustrations as sanitized local data URIs; the
                        // network is blocked separately, so enabling image loading only shows
                        // those inline images and never fetches remote resources.
                        loadsImagesAutomatically = true
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        textZoom = 100
                        defaultTextEncodingName = "utf-8"
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addJavascriptInterface(bridge, BRIDGE_NAME)
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            // 只记录错误/警告，正常页面脚本不刷日志。
                            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                                message.message().startsWith("JSERR") ||
                                message.message().startsWith("JSPERF")
                            ) {
                                Log.w(
                                    TAG_TEXT_READER,
                                    "console: ${message.message()} (${message.sourceId()}:${message.lineNumber()})",
                                )
                            }
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val url = request.url
                            if (url.scheme == INLINE_IMAGE_SCHEME && url.host == INLINE_IMAGE_HOST) {
                                val segments = url.pathSegments
                                val token = segments.lastOrNull()
                                if (segments.size >= 2 && segments[0] == FONT_PATH_SEGMENT) {
                                    // @font-face 的字体文件：直接读缓存文件，避免整份字节进内存。
                                    val font = token?.let { fontResources.value[it] }
                                    val file = font?.file
                                    if (file != null) {
                                        return WebResourceResponse(
                                            font.mime,
                                            null,
                                            200,
                                            "OK",
                                            mapOf("Cache-Control" to "public, max-age=86400"),
                                            FileInputStream(file),
                                        )
                                    }
                                    return null
                                }
                                val resource = token?.let { inlineImageStore[it] }
                                if (resource != null) {
                                    val count = inlineImageRequestCount.incrementAndGet()
                                    val total = inlineImageBytes.addAndGet(resource.bytes.size.toLong())
                                    if (count % 10 == 0) {
                                        Log.i(
                                            TAG_TEXT_PERF,
                                            "inline images requested=$count totalBytes=$total",
                                        )
                                    }
                                    return WebResourceResponse(
                                        resource.mime,
                                        null,
                                        ByteArrayInputStream(resource.bytes),
                                    )
                                }
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            val started = loadStartedAt
                            val elapsed = if (started != 0L) SystemClock.elapsedRealtime() - started else -1L
                            Log.i(TAG_TEXT_PERF, "onPageFinished elapsed=${elapsed}ms mode=${settings.readerMode}")
                            pageFinishedToken += 1
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?,
                        ): Boolean {
                            // 渲染进程被系统回收（低内存等）后这个 WebView 已经报废：
                            // 立即销毁并重建，否则正文会一直空白、加载指示器永远转圈。
                            Log.i(TAG_TEXT_READER, "WebView 渲染进程已退出，重建阅读视图")
                            if (holder.webView === view) holder.webView = null
                            firstLayoutRendered = false
                            loadStartedAt = 0L
                            resetWindowToRenderedHtml()
                            lastPageWindowStart = -1
                            // 交给 Compose 在 key 变化时销毁旧 View，避免对已崩溃的 WebView 再操作。
                            webViewGeneration += 1
                            return true
                        }
                    }
                    holder.webView = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = firstRenderAlpha.value },
            onRelease = { webView ->
                if (holder.webView === webView) holder.webView = null
                runCatching { webView.removeJavascriptInterface(BRIDGE_NAME) }
                runCatching { webView.stopLoading() }
                runCatching { webView.loadUrl("about:blank") }
                runCatching { webView.destroy() }
            },
        )
        }
        LaunchedEffect(html, webViewGeneration) {
            val webView = holder.webView ?: return@LaunchedEffect
            if (html.isNotEmpty()) {
                loadStartedAt = SystemClock.elapsedRealtime()
                val bootstrap = """{"options":$optionsJson,"ratio":${localRatio.value}}"""
                lastAppliedOptionsJson = optionsJson
                var initialHtml = html.replace(TEXT_READER_BOOTSTRAP_PLACEHOLDER, bootstrap)
                if (settings.debugMode) {
                    // Debug 布局锚点：蓝色 = WebView 的 #book 内容框，橙色虚线 = 滚动视口。
                    initialHtml = initialHtml.replace(
                        "</head>",
                        "<style>body.paged #book{outline:2px solid #1E88E5 !important;}" +
                            "body.paged #scroll{outline:1px dashed #FF9800 !important;}</style>" +
                            "<script>(function(){" +
                            "function show(){" +
                            "var b=document.getElementById('book');if(!b)return;" +
                            "var s=document.getElementById('scroll');" +
                            "var cs=getComputedStyle(b),r=b.getBoundingClientRect(),dpr=window.devicePixelRatio||1;" +
                            "var pm=getComputedStyle(document.documentElement).getPropertyValue('--reader-page-margin-x');" +
                            "var lines=['WEBVIEW dpr='+dpr.toFixed(2)," +
                            "'inner='+window.innerWidth+'x'+window.innerHeight+' CSS'," +
                            "'book CSS: L'+r.left.toFixed(0)+' T'+r.top.toFixed(0)+' R'+r.right.toFixed(0)+' B'+r.bottom.toFixed(0)," +
                            "'book px: L'+(r.left*dpr).toFixed(0)+' T'+(r.top*dpr).toFixed(0)+' R'+(r.right*dpr).toFixed(0)+' B'+(r.bottom*dpr).toFixed(0)," +
                            "'pageMargin='+pm.trim()+' lineHeight='+cs.lineHeight+' fontSize='+cs.fontSize," +
                            "'scroll='+s.clientWidth+'x'+s.clientHeight];" +
                            "var el=document.getElementById('salt-debug');" +
                            "if(!el){el=document.createElement('div');el.id='salt-debug';" +
                            "el.style.cssText='position:fixed;left:0;top:0;z-index:99999;background:#ffffffe6;" +
                            "color:#1565c0;font:10px/1.45 monospace;padding:4px;pointer-events:none;white-space:pre';" +
                            "document.body.appendChild(el);}" +
                            "el.textContent=lines.join('\\n');" +
                            "var kids=[].slice.call(b.children||[]);" +
                            "var rows=kids.slice(0,24).map(function(el,i){" +
                            "el.style.outline='1px dashed rgba(0,150,136,.45)';" +
                            "var c=getComputedStyle(el);" +
                            "return i+':'+el.tagName.toLowerCase()+' lh='+c.lineHeight+' mt='+c.marginTop+' mb='+c.marginBottom;});" +
                            "var bel=document.getElementById('salt-debug-blocks');" +
                            "if(!bel){bel=document.createElement('div');bel.id='salt-debug-blocks';" +
                            "bel.style.cssText='position:fixed;left:0;top:96px;z-index:99999;background:#ffffffe6;" +
                            "color:#00897b;font:9px/1.35 monospace;padding:4px;pointer-events:none;white-space:pre;max-height:55%;overflow:hidden';" +
                            "document.body.appendChild(bel);}" +
                            "bel.textContent=rows.join('\\n');}" +
                            "window.addEventListener('load',function(){setTimeout(show,300);setTimeout(show,900);});" +
                            "window.addEventListener('resize',show);" +
                            "})();</script></head>",
                    )
                }
                Log.i(
                    TAG_TEXT_PERF,
                    "loadData chars=${initialHtml.length} mode=${settings.readerMode} " +
                        "render=${renderStart}..${renderEnd}",
                )
                webView.loadDataWithBaseURL(
                    INLINE_IMAGE_SCHEME + "://" + INLINE_IMAGE_HOST + "/",
                    initialHtml,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        }

        // 加载看门狗：渲染进程死亡或加载卡死时 WebView 不会再发页面事件。
        // 超过 10 秒没有新的页面状态就销毁并重建 WebView，避免一直黑屏/转圈。
        LaunchedEffect(book.id) {
            while (true) {
                kotlinx.coroutines.delay(1500)
                val started = loadStartedAt
                if (started == 0L) continue
                if (SystemClock.elapsedRealtime() - started < 10_000L) continue
                // 加载后 10 秒仍没有任何页面状态：多半是渲染进程死了，重建 WebView 再试一次。
                // 注意不能把"页面静止"当成超时——加载成功时收到状态就会清零。
                loadStartedAt = 0L
                renderFailures += 1
                firstLayoutRendered = false
                if (renderFailures > 2) {
                    // 连续多次重建仍然渲染不出来：给用户明确的失败提示和重试入口，
                    // 而不是永远转圈（WebView 渲染进程被系统卡死时会出现这种情况）。
                    Log.w(TAG_TEXT_READER, "文本阅读器多次重建仍失败，显示重试提示")
                    renderFailed = true
                } else {
                    Log.w(TAG_TEXT_READER, "文本阅读器加载超时，重建 WebView（第 $renderFailures 次）")
                    resetWindowToRenderedHtml()
                    lastPageWindowStart = -1
                    webViewGeneration += 1
                }
            }
        }

        if (renderFailed) {
            Box(
                Modifier.fillMaxSize().background(readerBackground),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.reader_render_failed_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = readerTextColor,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.reader_render_failed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = readerTextColor.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = {
                            renderFailed = false
                            renderFailures = 0
                            loadStartedAt = 0L
                            firstLayoutRendered = false
                            webViewGeneration += 1
                        },
                    ) {
                        Text(stringResource(R.string.reader_retry))
                    }
                }
            }
        }

        // 首次排版 / 拖动进度条后重建 WebView 时显示的加载指示器：统一用 Material 3 的
        // SaltLoading（Expressive LoadingIndicator），尺寸比原来大一号。
        AnimatedVisibility(
            visible = !firstLayoutRendered && !renderFailed,
            enter = fadeIn(tween(durationMillis = 90)),
            exit = fadeOut(tween(durationMillis = 150)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            SaltLoading(sizeDp = 48, color = readerAccent)
        }

        // Paged mode deliberately leaves touch handling to the WebView: the CSS-column strip is a
        // native horizontal scroller, so dragging moves the current AND neighbouring page together
        // exactly like the image pager, with GPU-accelerated scrolling. Taps are reported from JS
        // (left/right = page turn, middle = chrome) and the scroller snaps to the nearest page when
        // the finger lifts.
        // Dim the page only while a settings panel is expanded, so the panel and toolbar read as
        // one surface over the dimmed page.
        // Out-of-window jumps rebuild the WebView; cover that reflow with the paper colour until
        // the new page reports back.
        if (veilVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(readerBackground),
                contentAlignment = Alignment.Center,
            ) {
                SaltLoading(sizeDp = 40, color = readerAccent)
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it / 3 },
            exit = fadeOut() + slideOutVertically { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                book = book,
                onBack = {
                    persistProgress(force = true)
                    onBack()
                },
                subtitle = { chapters.getOrNull(currentChapterIndex)?.title?.takeIf { it.isNotBlank() } },
                containerColor = chromeBarColor,
                contentColor = chromeContentColor,
            )
        }

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
                val pagesInfo = estimatedTextPage(
                    pageInfo = pageInfo,
                    mode = settings.readerMode,
                    totalChars = totalChars,
                    charsPerPage = estimatedCharsPerPage,
                    pageBase = pageBase,
                )
                // 分页模式下进度条按"页"走：每页一个位置，百分比也由页码换算，
                // 避免出现 2/2 页却停在 75% 这种页码与进度不一致的情况。
                val pageProgress = pagesInfo?.let { (current, total) ->
                    if (total > 1) (current - 1).toFloat() / (total - 1) else 1f
                }
                ReaderBottomBar(
                    progress = { pageProgress ?: pageInfo.ratio },
                    label = {
                        if (pagesInfo != null) {
                            stringResource(
                                R.string.reader_progress_combo,
                                percentOf(pageProgress ?: pageInfo.ratio),
                                pagesInfo.first,
                                pagesInfo.second,
                            )
                        } else {
                            stringResource(R.string.reader_percent, percentOf(pageInfo.ratio))
                        }
                    },
                    snapPages = pagesInfo?.second?.takeIf { settings.readerMode == ReaderMode.PAGED } ?: 0,
                    onJump = { fraction ->
                        val target = pagesInfo
                        if (settings.readerMode == ReaderMode.PAGED && target != null && target.second > 1) {
                            val page = (fraction * (target.second - 1)).roundToInt()
                                .coerceIn(0, target.second - 1)
                            val offset = ((page + 0.5) * estimatedCharsPerPage).toLong()
                                .coerceIn(0L, (totalChars - 1L).coerceAtLeast(0L))
                            jumpToOffset(offset)
                        } else {
                            jumpToFraction(fraction)
                        }
                    },
                    progressLabelSizeSp = settings.readerProgressLabelSizeSp,
                    accentColor = readerAccent,
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
                    readerMode = settings.readerMode,
                    onReaderModeToggle = {
                        onSettingsChange {
                            it.copy(
                                readerMode = if (it.readerMode == ReaderMode.PAGED) {
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
                // Panels live inside the same flat bottom surface as the toolbar: no separate
                // slide/expand animation, the bottom sheet simply grows to include them.
                when (activePanel) {
                    ReaderChromePanel.THEME -> ReaderThemePanel(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        onCustomBackground = { showCustomBackground = true },
                        showTextSize = true,
                        accentColor = readerAccent,
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
                        accentColor = readerAccent,
                    )

                    ReaderChromePanel.TOC -> {
                        val currentChapter = chapterIndexForOffset(
                            (pageInfo.ratio.toDouble() * totalChars.toDouble()).toLong()
                                .coerceIn(0L, (totalChars - 1L).coerceAtLeast(0L)),
                            chapterPrefix,
                        )
                        ReaderTocPanel(
                            chapters = chapters,
                            currentChapter = currentChapter,
                            onSelect = { index ->
                                activePanel = ReaderChromePanel.NONE
                                jumpToChapter(index)
                            },
                            containerColor = chromeBarColor,
                            contentColor = chromeContentColor,
                        )
                    }

                    else -> Unit
                }
                }
            }
        }

        // WeChat-Reading-style page indicator: a quiet bottom-end label while the chrome is hidden.
        // The user picks page number vs. progress in the reader settings activity.
        AnimatedVisibility(
            visible = !chromeVisible && settings.readerPageInfo != ReaderPageInfo.HIDDEN,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 12.dp),
        ) {
            val estimatedPages = estimatedTextPage(
                pageInfo = pageInfo,
                mode = settings.readerMode,
                totalChars = totalChars,
                charsPerPage = estimatedCharsPerPage,
                pageBase = pageBase,
            )
            val pagePercent = estimatedPages?.let { (current, total) ->
                if (total > 1) (current - 1).toFloat() / (total - 1) else 1f
            }
            val label = if (settings.readerPageInfo == ReaderPageInfo.PAGES && estimatedPages != null) {
                stringResource(R.string.reader_page_indicator, estimatedPages.first, estimatedPages.second)
            } else {
                stringResource(R.string.reader_percent, percentOf(pagePercent ?: pageInfo.ratio))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = settings.readerPageIndicatorSizeSp.sp,
                    fontFeatureSettings = "tnum",
                ),
                color = pageIndicatorColor,
            )
        }

        // Quiet battery read-out in the lower-left corner, mirroring the page indicator on the right.
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

        // Debug：一键切换回原生阅读器，方便对比两边锚点。
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
                    stringResource(R.string.reader_debug_switch_to_native),
                    modifier = Modifier
                        .clickable {
                            if (onDebugSwitch != null) {
                                onDebugSwitch(pageInfo.ratio)
                            } else {
                                onSettingsChange { it.copy(nativeReader = true) }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = Color(0xFF1565C0),
                )
            }
        }
    }

    if (showCustomBackground) {
        CustomColorPickerDialog(
            initialColor = settings.customBackgroundColor,
            onDismiss = { showCustomBackground = false },
            onConfirm = { color ->
                showCustomBackground = false
                onSettingsChange { it.copy(readerBackground = ReaderBackground.CUSTOM, customBackgroundColor = color) }
            },
        )
    }
}

private fun percentOf(ratio: Float): Int = (ratio * 100f).roundToInt().coerceIn(0, 100)

/**
 * Recomposition-scoped wrapper for the WeChat-style progress panel: the page ratio is read here
 * rather than in [TextReaderContent], so a page turn only recomposes the panel while it is open.
 */
@Composable
private fun TextReaderProgressPanel(
    pageInfo: TextPageInfo,
    chapterPrefix: LongArray,
    totalChars: Long,
    chapterCount: Int,
    onJump: (Float) -> Unit,
    onJumpChapter: (Int) -> Unit,
    containerColor: Color,
    contentColor: Color,
) {
    val ratio = pageInfo.ratio.coerceIn(0f, 1f)
    val offset = (ratio.toDouble() * totalChars.toDouble()).toLong()
        .coerceIn(0L, (totalChars - 1L).coerceAtLeast(0L))
    val chapter = chapterIndexForOffset(offset, chapterPrefix)
    ReaderProgressPanel(
        progress = ratio,
        percentLabel = stringResource(R.string.reader_percent, percentOf(ratio)),
        pageLabel = null,
        onJump = onJump,
        onPrevChapter = { onJumpChapter((chapter - 1).coerceAtLeast(0)) },
        onNextChapter = { onJumpChapter((chapter + 1).coerceAtMost(chapterCount - 1)) },
        containerColor = containerColor,
        contentColor = contentColor,
    )
}

/** 字符偏移 -> 章节下标：在章节前缀和上二分查找最后一个前缀 <= offset 的章节。 */
private fun chapterIndexForOffset(offset: Long, prefix: LongArray): Int {
    if (prefix.size <= 1) return 0
    val last = prefix.size - 2
    var low = 0
    var high = last
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (prefix[mid] <= offset) low = mid else high = mid - 1
    }
    return low
}

/**
 * Estimated global page number for the reflowable reader.
 *
 * The book is only laid out window by window, so an exact total page count is not available; the
 * pages-per-character ratio of the currently loaded window is applied to the whole book instead.
 * Returns null in vertical scrolling (where page numbers are meaningless) so the caller can fall
 * back to a percentage. The estimate is stable enough for the quiet page label and updates when the
 * typography changes (which is exactly when WeChat Reading's page count changes too).
 */
private fun estimatedTextPage(
    pageInfo: TextPageInfo,
    mode: ReaderMode,
    totalChars: Long,
    charsPerPage: Float,
    pageBase: Int,
): Pair<Int, Int>? {
    if (mode != ReaderMode.PAGED || totalChars <= 0L || charsPerPage <= 0f) return null
    val totalPages = ceil(totalChars.toDouble() / charsPerPage.toDouble()).toInt().coerceAtLeast(1)
    // pageInfo.page is the real DOM page index (each swipe = +1); pageBase anchors the loaded window
    // to a book page so the number stays continuous after jumps.
    val currentPage = (pageBase + pageInfo.page + 1).coerceIn(1, totalPages)
    return currentPage to totalPages
}

/**
 * Approximate characters that fit on one paged page for the current typography. Used only for the
 * x / y page-number estimate: it is deterministic, so changing font size / line spacing / margins
 * immediately recomputes the total page count instead of drifting with the loaded window.
 */
private fun estimateCharsPerPage(
    fontSizeSp: Int,
    lineHeightMultiplier: Float,
    pageMarginDp: Int,
    screenWidthDp: Int,
    screenHeightDp: Int,
): Float {
    val usableWidth = (screenWidthDp - pageMarginDp * 2).coerceAtLeast(80)
    val charWidth = fontSizeSp.coerceAtLeast(8).toFloat()
    val charsPerLine = (usableWidth / charWidth).coerceAtLeast(4f)
    // Reserve a rough 56dp for the safe-area + page padding above/below the text column.
    val pageHeight = (screenHeightDp - 56).coerceAtLeast(120)
    val lineHeightDp = (fontSizeSp * lineHeightMultiplier).coerceAtLeast(8f)
    val linesPerPage = pageHeight / lineHeightDp
    // CJK glyphs are ~1em wide; subtract a little for paragraph indents and blank lines.
    return (charsPerLine * linesPerPage * 0.95f).coerceAtLeast(50f)
}

/** Converts a Compose colour to the `#RRGGBB` string used by the injected CSS/JS. */
private fun cssColor(color: Color): String =
    String.format(Locale.US, "#%06X", color.toArgb() and 0xFFFFFF)

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * 单章渲染：初始窗口和后续追加/前插共用同一段 HTML，保证章节 id 与顺序一致。
 *
 * [EbookChapter.showHeading] 为 false 时正文已经自带标题元素（例如 `<p class="pius1">`），
 * 此时不再注入 h2，否则同一章会出现两个标题；纯图片页（cover/illustration）始终不注入标题。
 */
/** 纵向模式给图片加懒加载/异步解码，避免首屏一次性解码所有 base64 图片。 */
private fun lazyImages(html: String): String =
    html.replace("<img ", "<img loading=\"lazy\" decoding=\"async\" ")

private fun chapterHtml(
    chapter: EbookChapter,
    index: Int,
    bookTitle: String,
    singleChapter: Boolean,
): String = buildString {
    append("<section class=\"chapter")
    if (chapter.imageOnly) append(" image-only")
    append("\" id=\"ch").append(index).append("\">")
    val heading = if (chapter.showHeading && !chapter.imageOnly) {
        chapter.title?.takeIf { it.isNotBlank() }
            ?: bookTitle.takeIf { singleChapter && it.isNotBlank() }
    } else {
        null
    }
    heading?.let { append("<h2 class=\"chapter-heading\">").append(escapeHtml(it)).append("</h2>") }
    append(chapter.html)
    append("</section>")
}

private fun buildBookHtml(
    chapters: List<EbookChapter>,
    bookTitle: String,
    startIndex: Int = 0,
    singleChapter: Boolean = chapters.size == 1,
): String {
    val body = StringBuilder()
    for ((offset, chapter) in chapters.withIndex()) {
        // 上限只用于拦截异常巨大的书（正常书远达不到）：整本 HTML 同时存在于内存和 WebView
        // 里，没有上限时超大文件会在打开瞬间把进程内存打满。
        if (body.length >= MAX_TOTAL_HTML_CHARS) break
        body.append(chapterHtml(chapter, startIndex + offset, bookTitle, singleChapter))
    }
    return HTML_HEAD + body + HTML_TAIL
}

/** 内联图片「降采样后字节」的小型 LRU：窗口重建/追加时避免重复解码与重编码。 */
private object InlineImageOptimizedCache {
    private const val MAX_BYTES = 12 * 1024 * 1024
    private val cache = object : android.util.LruCache<String, Pair<ByteArray, String>>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Pair<ByteArray, String>): Int = value.first.size
    }

    fun get(key: String): Pair<ByteArray, String>? = cache.get(key)?.let { it }

    fun put(key: String, value: Pair<ByteArray, String>) {
        cache.put(key, value)
    }
}

/** WebView 内部用来按需取回内联图片的本地域名；请求由 shouldInterceptRequest 截获。 */
private const val INLINE_IMAGE_HOST = "salt.local"
private const val INLINE_IMAGE_SCHEME = "https"

/** 字体资源 URL 的第一段路径（见 [ReaderFonts.FONT_URL_BASE]）。 */
private const val FONT_PATH_SEGMENT = "font"

/** 一张已内联图片的字节与 MIME，按 token 存放在阅读器会话内。 */
private class InlineImageResource(val bytes: ByteArray, val mime: String)

/** 匹配 sanitizer 生成的 data URI 图片（只有图片才会内联）。 */
private val INLINE_DATA_URI = Regex("""src="(data:(image/[^";]+);base64,([^"]+))"""")

/**
 * 把章节 HTML 里的 base64 图片改成指向 [INLINE_IMAGE_HOST] 的短 URL，并把字节存进 [store]。
 * WebView 解析 HTML 时不用再吞下几百 KB 的 base64 文本；布局所需宽高直接写进 img 标签，
 * 图片本身由 shouldInterceptRequest 异步取回。调用方在 Default/IO 线程执行。
 */
private fun externalizeInlineImages(
    html: String,
    store: ConcurrentHashMap<String, InlineImageResource>,
    tokenCounter: AtomicInteger,
    maxImageWidthPx: Int = 0,
    tokenSink: ((String) -> Unit)? = null,
): String {
    if (!html.contains("data:image")) return html
    return INLINE_DATA_URI.replace(html) { match ->
        val mime = match.groupValues[2]
        val payload = match.groupValues[3]
        val rawBytes = runCatching { java.util.Base64.getDecoder().decode(payload) }.getOrNull()
            ?: return@replace match.value
        val cacheKey = mime + ":" + payload.length + ":" + payload.hashCode()
        val optimized = InlineImageOptimizedCache.get(cacheKey)
            ?: downscaleInlineImage(rawBytes, mime, maxImageWidthPx).also {
                InlineImageOptimizedCache.put(cacheKey, it)
            }
        val bytes = optimized.first
        val finalMime = optimized.second
        val token = "img" + tokenCounter.incrementAndGet()
        store[token] = InlineImageResource(bytes, finalMime)
        tokenSink?.invoke(token)
        val bounds = runCatching {
            android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                .also { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
        }.getOrNull()
        val dims = if (bounds != null && bounds.outWidth > 0 && bounds.outHeight > 0) {
            " width=\"" + bounds.outWidth + "\" height=\"" + bounds.outHeight + "\""
        } else {
            ""
        }
        "src=\"" + INLINE_IMAGE_SCHEME + "://" + INLINE_IMAGE_HOST + "/inline/" + token + "\"" + dims
    }
}

/**
 * 按屏宽降采样大图：WebView 滚动时一张整页插画（2000px+）的解码/上传会卡主线程。
 * 超过 [maxWidthPx] 的图片按比例缩到屏宽并重编码（有透明通道用 PNG，否则 JPEG 82），
 * 大幅降低解码量和纹理内存；小图原样保留。
 */
private fun downscaleInlineImage(bytes: ByteArray, mime: String, maxWidthPx: Int): Pair<ByteArray, String> {
    if (maxWidthPx <= 0) return bytes to mime
    val bounds = runCatching {
        android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
    }.getOrNull() ?: return bytes to mime
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0 || width <= maxWidthPx * 1.15f) return bytes to mime
    return runCatching {
        var sample = 1
        while (width / (sample * 2) >= maxWidthPx) sample *= 2
        val decoded = android.graphics.BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@runCatching bytes to mime
        val targetHeight = (decoded.height.toFloat() * maxWidthPx / decoded.width).roundToInt().coerceAtLeast(1)
        val scaled = android.graphics.Bitmap.createScaledBitmap(decoded, maxWidthPx, targetHeight, true)
        if (scaled !== decoded) decoded.recycle()
        val hasAlpha = scaled.hasAlpha()
        val out = java.io.ByteArrayOutputStream(scaled.byteCount.coerceAtMost(4 * 1024 * 1024))
        val format = if (hasAlpha) {
            android.graphics.Bitmap.CompressFormat.PNG
        } else {
            android.graphics.Bitmap.CompressFormat.JPEG
        }
        scaled.compress(format, 82, out)
        scaled.recycle()
        val encoded = out.toByteArray()
        val finalMime = if (hasAlpha) "image/png" else "image/jpeg"
        if (encoded.size < bytes.size) encoded to finalMime else bytes to mime
    }.getOrElse { bytes to mime }
}

/**
 * 章节 HTML 的渲染成本：总长度减去 base64 图片负载。图片会被 externalizeInlineImages 换成
 * 本地短 URL，真正交给 WebView 解析的只有文本和短 URL，因此预算要按这个成本算，
 * 否则图片章会被误判成超大章节而被排除出窗口。
 */
private fun chapterHtmlCost(html: String): Long {
    var cost = html.length.toLong()
    var index = html.indexOf("data:image")
    while (index >= 0) {
        val quote = html.indexOf('"', index)
        if (quote < 0) break
        cost -= (quote - index).toLong()
        index = html.indexOf("data:image", quote)
    }
    return cost.coerceAtLeast(1L)
}

/** 单本书拼出的 HTML 总量上限（约 24M 字符，正常文字书在 1M 量级）。 */
private const val MAX_TOTAL_HTML_CHARS = 24 * 1024 * 1024

/** 每次注入 WebView 的章节数：只加载阅读位置附近的窗口。 */
private const val CHAPTER_BATCH = 3
private const val INITIAL_CHAPTER_BATCH = 2
private const val JUMP_BATCH = 3

/** 单次注入的字符预算：图片型文字书按章限流，避免首帧/追加时 WebView 排版过重。 */
private const val INITIAL_RENDER_CHAR_BUDGET = 360_000L
// 纵向模式首屏至少要两章：单章可能只是封面图，内容不足一屏时滚动容器不会产生滚动事件，
// 后续章节也就不会被追加（表现为“划不动”）。
private const val VERTICAL_INITIAL_CHAPTER_BATCH = 2
private const val VERTICAL_INITIAL_RENDER_CHAR_BUDGET = 160_000L
private const val WINDOW_RENDER_CHAR_BUDGET = 600_000L

/** 跳转时目标章节前保留的章节数，给"上一页"留一点缓冲。 */
private const val CHAPTER_START_LEAD = 1

/** 距离已加载窗口边界多少比例时开始追加/前插。 */
private const val PREFETCH_RATIO = 0.8f

/** 追加/前插的最小间隔，避免一次滚动连续注入多批。 */
private const val CHAPTER_LOAD_THROTTLE_MS = 300L

private const val TAG_TEXT_READER = "SaltReaderText"
private const val TAG_TEXT_PERF = "SaltReaderPerf"
private const val BRIDGE_NAME = "SaltReader"
private const val TEXT_READER_BOOTSTRAP_PLACEHOLDER = "__SALT_READER_BOOTSTRAP__"

/**
 * 注入到 WebView 的 CSS。发布者样式表在数据层已经被剥离，这里负责中文小说的排版基调；
 * 同时用 `#book *` 的强制覆盖把发布者遗留的行内样式（白字、底色、文字阴影）压回阅读器主题。
 */
private val HTML_HEAD = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<script>
window.onerror = function (msg, src, line, col) {
  console.log('JSERR ' + msg + ' @' + src + ':' + line);
};
window.__saltReaderBootstrap = __SALT_READER_BOOTSTRAP__;
</script>
<style>
/* --app-vh is maintained from JS: some WebView builds resolve `vh` to 0.
   The reading area follows WeChat Reading: ~30dp side margins, 1.8 line height and a small
   paragraph gap, with fixed top/bottom breathing room so text never touches the screen edges. */
:root { --reader-line-height: 1.8; --reader-page-margin-x: 30px; --reader-font-family: sans-serif; --reader-vertical-pad-top: 20px; --reader-vertical-pad-bottom: 96px; }
html, body { margin: 0; padding: 0; height: 100%; }
body { -webkit-text-size-adjust: 100%; text-size-adjust: 100%; line-height: var(--reader-line-height, 1.8); -webkit-font-smoothing: antialiased; text-rendering: optimizeLegibility; font-family: var(--reader-font-family, sans-serif); }
#book { line-height: var(--reader-line-height, 1.8); font-family: var(--reader-font-family, sans-serif); }

/* Chinese novel paragraph rhythm: two-character indent, justified, compact vertical gaps. */
#book p { margin: 0 var(--reader-page-margin-x, 30px) 0.55em; text-indent: 2em; text-align: justify; line-height: var(--reader-line-height, 1.8) !important; }
#book div, #book li, #book dd, #book dt, #book ol, #book ul, #book aside { line-height: var(--reader-line-height, 1.8) !important; }

/* Publisher classes that already control alignment must not also be indented. */
#book .center, #book .zin, #book .illus, #book .images, #book .cover, #book .message,
#book .right, #book .left, #book .author, #book .contents, #book .mulu,
#book h1, #book h2, #book h3, #book h4, #book h5, #book h6 { text-indent: 0; }
#book .center, #book .illus, #book .images, #book .cover, #book .summary { text-align: center; }
#book .right, #book .author { text-align: right; }
#book .left { text-align: left; }
#book .center, #book .zin, #book .illus, #book .images, #book .cover, #book .message,
#book .author { margin-left: 0; margin-right: 0; }

/* Chapter headings: strong, centered, calm spacing (no decorative extras, WeChat-Reading style). */
#book h1, #book h2, #book h3, #book h4, #book h5, #book h6 { margin: 0.9em var(--reader-page-margin-x, 30px) 0.85em; text-align: center; font-weight: 700; line-height: 1.3 !important; }
#book h1 { font-size: 1.6em; }
#book h2 { font-size: 1.4em; }
#book h3 { font-size: 1.25em; }
#book h4, #book h5, #book h6 { font-size: 1.1em; }
#book h2.chapter-heading { margin: 0.35em var(--reader-page-margin-x, 30px) 1.15em; letter-spacing: 0.02em; }

/* Common Chinese light-novel class vocabulary (chapter number/name, titles, emphasis, notes). */
#book .pius1, #book .pius2, #book .pius3 { text-indent: 0; text-align: center; font-weight: 700; margin-left: var(--reader-page-margin-x, 30px); margin-right: var(--reader-page-margin-x, 30px); }
#book p.pius1, #book p.pius2, #book p.pius3 { line-height: 1.25 !important; }
#book .pius1 { font-size: 1.05em; margin-top: 1.5em; margin-bottom: 0.1em; opacity: 0.7; }
#book .pius2 { font-size: 1.4em; margin-top: 0; margin-bottom: 1.35em; }
#book .pius3 { font-size: 1.3em; margin-top: 1.2em; margin-bottom: 1.2em; }
#book .biaoti1 { text-indent: 0; text-align: center; font-weight: 700; font-size: 1.2em; margin: 0.4em var(--reader-page-margin-x, 30px) 0.9em; }
#book .biaoti2 { text-indent: 0; text-align: left; font-weight: 700; font-size: 1.2em; margin: 0.4em 0 0.9em 3em; }
#book .stress { font-weight: 700; font-size: 1.1em; }
#book .meg { text-indent: 0; text-align: center; font-weight: 700; font-size: 1.3em; margin: 0.6em var(--reader-page-margin-x, 30px); }
#book .message { text-indent: 0; margin-top: 0.2em; margin-bottom: 0.2em; }
#book .author { font-style: italic; font-weight: 700; margin-right: 1em; }
#book .em-dot { -webkit-text-emphasis: filled dot; text-emphasis: filled dot; -webkit-text-emphasis-position: under; text-emphasis-position: under; }
#book .footnote, #book .duokan-footnote, #book sup img { display: inline; width: auto; height: 1.15em; max-width: 1.15em; max-height: 1.15em; vertical-align: super; margin: 0 0.1em; }
#book .duokan-footnote-item { text-indent: 0; font-size: 0.9em; }
#book sup { font-size: 0.75em; line-height: 1; vertical-align: super; }
#book ruby { ruby-position: over; }
#book rt { font-size: 0.55em; line-height: 1; }
#book a { color: inherit !important; text-decoration: none !important; }

/* Illustrations: block images are centered and never overflow the page. */
#book img { display: block; max-width: var(--reader-image-max-w, 100%); height: auto; margin: 0.8em auto; }
#book .illus img, #book .images img, #book .cover img { margin-left: auto; margin-right: auto; max-width: var(--reader-image-max-w, 100%); }
/* Full-page illustrations / colour pages must fit one screen, in both reading modes. */
#book section.chapter.image-only { text-align: center; }
#book section.chapter.image-only img { display: block; width: auto; max-width: 100%; max-height: var(--reader-image-max-h, calc(var(--app-vh, 100%) - 112px)); margin: 8px auto; }
#book section.chapter.image-only p { text-indent: 0; margin: 0 var(--reader-page-margin-x, 30px); }
#book section.chapter + section.chapter { margin-top: 0.4em; }

/* Publisher readability override: the reader theme owns colour/background/text-shadow, while the
   book's font weight/size/alignment/style are kept. Publisher classes such as .co1 (.white text on
   #FFF) would otherwise be invisible in light mode. */
#book, #book * { color: inherit !important; text-shadow: none !important; background-color: transparent !important; background-image: none !important; }

/* Non-fiction / programming books: code blocks, tables and lists the publisher CSS would have
   styled. Code must wrap inside a page column instead of being clipped horizontally. */
#book pre, #book code, #book kbd, #book samp, #book tt {
  /* 等宽字体由"字体设置"里的默认等宽字体决定，--reader-mono-family 由 JS apply() 写入。 */
  font-family: var(--reader-mono-family, ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace);
}
#book pre {
  white-space: pre-wrap; overflow-wrap: anywhere; word-break: break-word;
  font-size: 0.86em; line-height: 1.5 !important; text-indent: 0;
  margin: 0.7em var(--reader-page-margin-x, 30px); padding: 0.55em 0.7em;
  border-radius: 8px; background: rgba(127, 127, 127, 0.10);
}
#book pre code { font-size: 1em; background: transparent; padding: 0; }
#book code { font-size: 0.92em; padding: 0 0.15em; border-radius: 3px; background: rgba(127, 127, 127, 0.10); }
/* WebView 156（Android 17）对个别书的原生表格排版会异常占用数 GB 内存并拖垮渲染进程，
   因此阅读器里不使用原生 table 布局，改成"每行一块、单元格行内排列"的等价排版。
   这是规避 Chromium 表格布局缺陷的降级方案，样式上仍保持可读。 */
#book table, #book thead, #book tbody, #book tfoot, #book caption, #book colgroup, #book col { display: block; }
#book table { display: inline-block;
  width: calc(100% - 2 * var(--reader-page-margin-x, 30px));
  max-width: 100%; box-sizing: border-box; margin: 0.8em var(--reader-page-margin-x, 30px);
  font-size: 0.86em; vertical-align: top;
  border: 1px solid rgba(127, 127, 127, 0.35); }
#book tr { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; align-items: stretch;
  box-sizing: border-box; margin: 0; border: 0; border-top: 1px solid rgba(127, 127, 127, 0.35); }
#book tr:first-child { border-top: 0; }
#book thead tr, #book tr:first-child { background: rgba(127, 127, 127, 0.08); }
#book td, #book th { display: block; min-width: 0; box-sizing: border-box;
  border: 0; border-left: 1px solid rgba(127, 127, 127, 0.25);
  padding: 0.35em 0.5em; vertical-align: top; text-indent: 0; text-align: left;
  overflow-wrap: anywhere; word-break: break-word; }
#book td:first-child, #book th:first-child { border-left: 0; }
#book td:first-child, #book th:first-child { border-left: 0; }
#book th { font-weight: 700; }
/* 单元格内的段落不能沿用正文的左右 30px 边距/齐两端：窄列里会造成负的可用宽度。 */
#book td p, #book th p { margin: 0; text-indent: 0; text-align: left; line-height: 1.5 !important; }
#book td pre, #book th pre, #book td code, #book th code { margin: 0.2em 0; }
#book blockquote { margin: 0.7em var(--reader-page-margin-x, 30px); padding-left: 0.9em;
  border-left: 3px solid rgba(127, 127, 127, 0.35); }
#book blockquote p { margin-left: 0; margin-right: 0; }
#book ul, #book ol { margin: 0.5em var(--reader-page-margin-x, 30px); padding-left: 1.6em; }
#book li { text-indent: 0; }
#book hr { border: 0; border-top: 1px solid rgba(127, 127, 127, 0.35); margin: 1em var(--reader-page-margin-x, 30px); }
#book figure { margin: 0.8em var(--reader-page-margin-x, 30px); text-align: center; }
#book figcaption, #book .caption { font-size: 0.85em; text-align: center; opacity: 0.85; text-indent: 0; }
#book sub, #book sup { line-height: 0; }

/* Paged mode (CSS columns): the column box is inset by page padding so text keeps clear of the
   status bar and the bottom toolbar, like WeChat Reading's page margins. */
body.paged { height: 100%; overflow: hidden; }
body.paged #scroll { position: relative; width: 100vw; height: var(--app-vh, 100%); overflow-x: hidden; overflow-y: hidden; -webkit-overflow-scrolling: touch; overscroll-behavior-x: contain; scrollbar-width: none; touch-action: none; scroll-snap-type: none; -webkit-tap-highlight-color: transparent; user-select: none; -webkit-user-select: none; }
body.paged #scroll::-webkit-scrollbar { display: none; }
body.paged #book { height: calc(var(--app-vh, 100%) - 56px); margin: 28px 0; column-width: 100vw; column-gap: 0; column-fill: auto; -webkit-column-width: 100vw; -webkit-column-gap: 0; -webkit-column-fill: auto; }
/* Vertical mode: breathing room at the top and enough bottom padding that the last lines can be
   scrolled clear of the floating toolbar. */
body.vertical { height: auto; }
body.vertical #scroll { width: 100vw; height: var(--app-vh, 100%); overflow-y: auto; overflow-x: hidden; -webkit-overflow-scrolling: touch; padding: var(--reader-vertical-pad-top, 20px) 0 var(--reader-vertical-pad-bottom, 96px); box-sizing: border-box; }
body.vertical #book { height: auto; column-width: auto; }
body.vertical #book img { content-visibility: auto; }
</style>
</head>
<body class="paged">
<div id="scroll"><div id="book">
""".trimIndent()

private val HTML_TAIL = """
</div></div>
<script>
(function () {
  var scroll = document.getElementById('scroll');
  var book = document.getElementById('book');
  // Some WebView builds resolve `vh` to 0 with this viewport meta; keep an explicit height in px.
  function applyPageWidth() {
    // Use one integer pixel width for both the scroll viewport and the CSS columns. Mixing
    // `100vw` (fractional layout viewport) with `scroll.clientWidth` made every page boundary
    // drift by a fraction of a pixel, so later pages showed a sliver of the previous page.
    var pageWidth = Math.max(1, scroll.clientWidth || window.innerWidth);
    cachedWidth = pageWidth;
    book.style.webkitColumnWidth = pageWidth + 'px';
    book.style.columnWidth = pageWidth + 'px';
    book.style.width = pageWidth + 'px';
    return pageWidth;
  }
  function updateViewportHeight() {
    var viewportHeight = Math.max(1, window.innerHeight || 0);
    document.documentElement.style.setProperty('--app-vh', viewportHeight + 'px');
    // Paged mode sizes the column box in px as well; keep it in sync on rotation / multi-window.
    // Re-flowing the columns invalidates scrollLeft, so re-align to the nearest page afterwards
    // (otherwise a sliver of the previous page slides into view and text/images look shifted).
    if (mode === 'paged' && typeof PAGE_PAD_TOP === 'number') {
      var pageWidth = applyPageWidth();
      var alignPage = Math.round(scroll.scrollLeft / pageWidth);
      book.style.height = Math.max(1, viewportHeight - PAGE_PAD_TOP - PAGE_PAD_BOTTOM) + 'px';
      book.style.marginTop = PAGE_PAD_TOP + 'px';
      book.style.marginBottom = PAGE_PAD_BOTTOM + 'px';
      requestAnimationFrame(function () {
        refreshSnapPoints();
        scroll.scrollLeft = alignPage * pageWidth;
      });
    }
  }
  updateViewportHeight();
  window.addEventListener('resize', updateViewportHeight);
  var mode = 'paged';
  var DOC_TOKEN = 0;
  var restoring = false;
  var scrollTimer = null;
  // CSS scroll snap needs one snap target per page. The pages are CSS columns (not elements), so
  // an absolutely-positioned marker layer provides one invisible snap point per page boundary.
  // This lets the platform's native fling + snap physics settle pages, the same curve the image
  // pager's Compose fling uses, without any per-frame JS.
  var snapLayer = document.createElement('div');
  snapLayer.id = 'snap-layer';
  snapLayer.style.cssText = 'position:absolute;top:0;left:0;width:1px;height:1px;pointer-events:none;';
  scroll.appendChild(snapLayer);
  // Page width the markers were last positioned with; appending chapters only adds markers at the
  // end, so a refresh then only touches the new ones instead of rewriting every marker's style.
  var snapWidth = -1;
  // WebView 在某些系统（如 Android 17）上会在尺寸确定前就跑到脚本里：此时 clientWidth /
  // innerHeight 为 0，按 1px 分栏会生成海量分栏和 snap 标记，内存会瞬间涨到数 GB 并拖垮
  // 渲染进程。所有分页相关计算都必须先确认视口有效。
  function viewportReady() {
    var w = window.innerWidth || scroll.clientWidth || 0;
    var h = window.innerHeight || scroll.clientHeight || 0;
    return w >= 64 && h >= 64;
  }
  // 分页数量的合理上限：正常书籍一窗口最多几百页，超过说明布局异常（视口为 0 等）。
  var MAX_SANE_PAGES = 2000;
  function refreshSnapPoints() {
    refreshMetrics();
    if (mode !== 'paged') {
      snapLayer.innerHTML = '';
      snapWidth = -1;
      return;
    }
    var w = cachedWidth;
    var pages = cachedPages;
    if (!viewportReady() || !isFinite(pages) || pages < 1 || pages > MAX_SANE_PAGES) {
      // 布局尚未就绪或明显异常：先清掉标记，等下一次正常的 refresh 再建。
      snapLayer.innerHTML = '';
      snapWidth = -1;
      return;
    }
    while (snapLayer.childElementCount > pages) snapLayer.removeChild(snapLayer.lastChild);
    while (snapLayer.childElementCount < pages) {
      var marker = document.createElement('div');
      // `scroll-snap-stop: always` is what makes a swipe turn exactly one page: without it a fast
      // fling skips intermediate snap points and can land several pages away (Chrome >= 75).
      marker.style.cssText = 'position:absolute;top:0;left:0;width:1px;height:1px;scroll-snap-align:start;scroll-snap-stop:always;pointer-events:none;';
      marker.style.left = (snapLayer.childElementCount * w) + 'px';
      snapLayer.appendChild(marker);
    }
    if (w !== snapWidth) {
      snapWidth = w;
      for (var i = 0; i < snapLayer.childElementCount; i++) {
        snapLayer.children[i].style.left = (i * w) + 'px';
      }
    }
  }
  // The page keeps clear of the status bar / cutout / gesture bar: the native side passes the
  // safe-area insets, and the padding is max(base, inset + a small gap), like WeChat Reading.
  var SAFE_TOP = 0, SAFE_BOTTOM = 0, SAFE_LEFT = 0, SAFE_RIGHT = 0;
  var PAGE_PAD_TOP = 28, PAGE_PAD_BOTTOM = 28;
  function applySafeMetrics() {
    PAGE_PAD_TOP = Math.max(28, SAFE_TOP + 8);
    PAGE_PAD_BOTTOM = Math.max(28, SAFE_BOTTOM + 8);
    document.documentElement.style.setProperty('--reader-vertical-pad-top', Math.max(20, SAFE_TOP + 8) + 'px');
    document.documentElement.style.setProperty('--reader-vertical-pad-bottom', Math.max(96, SAFE_BOTTOM + 8) + 'px');
    var vh = Math.max(1, window.innerHeight || 0);
    document.documentElement.style.setProperty(
      '--reader-image-max-h',
      Math.max(120, vh - PAGE_PAD_TOP - PAGE_PAD_BOTTOM - 8) + 'px'
    );
    return Math.max(SAFE_LEFT, SAFE_RIGHT);
  }

  function clamp(v) { return Math.min(1, Math.max(0, v || 0)); }
  // Layout reads (`clientWidth` / `scrollWidth`) force a style/layout pass, so they are cached and
  // refreshed only when the viewport or the column layout can actually have changed. A scroll
  // report then costs no layout work at all.
  var cachedWidth = 0, cachedHeight = 0, cachedScrollWidth = 0, cachedScrollHeight = 0, cachedPages = 1;
  function refreshMetrics() {
    cachedWidth = Math.max(1, scroll.clientWidth || window.innerWidth);
    cachedHeight = Math.max(1, scroll.clientHeight || window.innerHeight || 0);
    cachedScrollWidth = scroll.scrollWidth;
    cachedScrollHeight = scroll.scrollHeight;
    var pages = Math.round(cachedScrollWidth / cachedWidth);
    if (!isFinite(pages) || pages < 1) pages = 1;
    cachedPages = Math.min(pages, MAX_SANE_PAGES);
  }
  function viewportWidth() {
    if (!cachedWidth) refreshMetrics();
    return cachedWidth;
  }
  function pagedPages() { return cachedPages; }
  function pagedPage() { return Math.round(scroll.scrollLeft / viewportWidth()); }
  function ratio() {
    if (mode === 'paged') {
      var denom = Math.max(1, cachedScrollWidth - cachedWidth);
      return clamp(scroll.scrollLeft / denom);
    }
    var d = Math.max(1, cachedScrollHeight - cachedHeight);
    return clamp(scroll.scrollTop / d);
  }
  // Every onState call crosses the JS bridge and recomposes the reading chrome, so identical
  // reports are dropped: the progress label is integer percent, the page numbers are integers.
  var lastReportPage = -1, lastReportPages = -1, lastReportRatio = -2;
  var lastTrimAt = 0;
  function trimEarlierSections() {
    if (mode !== 'vertical') { trimEarlierSectionsPaged(); return; }
    var now = Date.now();
    if (now - lastTrimAt < 1200) return;
    var secs = book.querySelectorAll('section.chapter');
    if (secs.length <= 8) return;
    var st = scroll.scrollTop;
    var firstVisible = 0;
    for (var i = 0; i < secs.length; i++) {
      if (secs[i].offsetTop + secs[i].offsetHeight > st) { firstVisible = i; break; }
    }
    var keepFrom = Math.max(0, firstVisible - 2);
    if (keepFrom <= 0) return;
    lastTrimAt = now;
    var removedHeight = 0;
    for (var j = 0; j < keepFrom; j++) {
      removedHeight += secs[j].offsetHeight;
      secs[j].parentNode.removeChild(secs[j]);
    }
    scroll.scrollTop = Math.max(0, st - removedHeight);
    console.log('JSPERF trim removed=' + keepFrom + ' sh=' + scroll.scrollHeight + ' st=' + scroll.scrollTop);
    if (window['$BRIDGE_NAME'] && window['$BRIDGE_NAME'].onTrimmed) {
      window['$BRIDGE_NAME'].onTrimmed(keepFrom);
    }
  }
  function trimEarlierSectionsPaged() {
    var now = Date.now();
    if (now - lastTrimAt < 1200) return;
    var secs = book.querySelectorAll('section.chapter');
    if (secs.length <= 8) return;
    var sl = scroll.scrollLeft;
    var firstVisible = 0;
    for (var i = 0; i < secs.length; i++) {
      if (secs[i].offsetLeft + secs[i].offsetWidth > sl) { firstVisible = i; break; }
    }
    var keepFrom = Math.max(0, firstVisible - 2);
    if (keepFrom <= 0) return;
    lastTrimAt = now;
    var removedWidth = 0;
    for (var j = 0; j < keepFrom; j++) {
      removedWidth += secs[j].offsetWidth;
      secs[j].parentNode.removeChild(secs[j]);
    }
    scroll.scrollLeft = Math.max(0, sl - removedWidth);
    refreshSnapPoints();
    report(true);
    console.log('JSPERF trim-pages removed=' + keepFrom + ' sw=' + scroll.scrollWidth);
    if (window['$BRIDGE_NAME'] && window['$BRIDGE_NAME'].onTrimmed) {
      window['$BRIDGE_NAME'].onTrimmed(keepFrom);
    }
  }
  var lastNeedMoreAt = 0;
  function maybeRequestMoreForVertical() {
    if (mode !== 'vertical') return;
    if (scroll.scrollHeight > scroll.clientHeight + 8) return;
    var now = Date.now();
    if (now - lastNeedMoreAt < 500) return;
    lastNeedMoreAt = now;
    if (window['$BRIDGE_NAME'] && window['$BRIDGE_NAME'].onNeedMore) {
      window['$BRIDGE_NAME'].onNeedMore();
    }
  }
  function report(force) {
    // Scrolling does not change the document dimensions. Re-reading scrollWidth/scrollHeight on
    // every throttled scroll event forces a synchronous layout; refresh them only at known layout
    // boundaries (or for an explicit state refresh).
    if (force || !cachedWidth) refreshMetrics();
    // Late image decode can change the page count; a missing snap target would let a fling run past
    // the end of the marker layer, so keep the marker count in sync with the real column layout.
    if (mode === 'paged' && snapLayer.childElementCount !== cachedPages) refreshSnapPoints();
    var p = mode === 'paged' ? pagedPage() : 0;
    var n = mode === 'paged' ? cachedPages : 1;
    var r = ratio();
    if (!force && p === lastReportPage && n === lastReportPages &&
        Math.abs(r - lastReportRatio) < 0.002) {
      return;
    }
    lastReportPage = p;
    lastReportPages = n;
    lastReportRatio = r;
    try {
      if (window['$BRIDGE_NAME'] && window['$BRIDGE_NAME'].onState) {
        window['$BRIDGE_NAME'].onState(p, n, r, DOC_TOKEN);
      }
    } catch (e) {}
    maybeRequestMoreForVertical();
    trimEarlierSections();
  }
  // Inline data-URI illustrations decode asynchronously. Layout measured before they are ready
  // gives a wrong page count / scroll height, which is exactly what full-page colour pages need,
  // so wait for the images before restoring a position.
  function whenImagesReady(callback) {
    var images = book.querySelectorAll('img');
    var pending = 0;
    var finished = false;
    function finish() {
      if (finished) return;
      finished = true;
      requestAnimationFrame(function () {
        requestAnimationFrame(callback);
      });
    }
    function oneDone() { pending--; if (pending <= 0) finish(); }
    for (var i = 0; i < images.length; i++) {
      if (images[i].complete) continue;
      pending++;
      images[i].addEventListener('load', oneDone);
      images[i].addEventListener('error', oneDone);
    }
    if (pending === 0) finish();
    setTimeout(finish, 1500);
  }
  function whenFontsReady(callback) {
    var finished = false;
    function finish() {
      if (finished) return;
      finished = true;
      callback();
    }
    // 本地字体同样是网络式加载：@font-face 插入后 status 会短暂变成 loading。
    if (document.fonts && document.fonts.status === 'loading') {
      document.fonts.ready.then(finish);
      setTimeout(finish, 2500);
    } else {
      finish();
    }
  }
  function paint(color) {
    // A dedicated !important style guarantees the page background follows the paper colour even if
    // a later relayout (or a WebView that ignores inline html/body styles) would repaint the old one.
    var style = document.getElementById('salt-reader-bg');
    if (!style) {
      style = document.createElement('style');
      style.id = 'salt-reader-bg';
      document.head.appendChild(style);
    }
    style.textContent = 'html,body,#scroll,#book{background-color:' + color + ' !important;}';
    document.body.style.background = color;
    document.documentElement.style.background = color;
  }
  function setOptions(opts) {
    DOC_TOKEN = Number(opts.docToken) || 0;
    mode = opts.mode === 'vertical' ? 'vertical' : 'paged';
    document.body.className = mode;
    paint(opts.background);
    document.body.style.color = opts.color;
    document.body.style.fontSize = opts.fontSize + 'px';
    var lineHeight = Number(opts.lineHeight);
    if (!isFinite(lineHeight) || lineHeight <= 0) lineHeight = 1.8;
    document.documentElement.style.setProperty('--reader-line-height', String(lineHeight));
    document.body.style.lineHeight = String(lineHeight);
    var pageMargin = Number(opts.pageMargin);
    if (!isFinite(pageMargin) || pageMargin < 8) pageMargin = 30;
    SAFE_TOP = Number(opts.safeTop) || 0;
    SAFE_BOTTOM = Number(opts.safeBottom) || 0;
    SAFE_LEFT = Number(opts.safeLeft) || 0;
    SAFE_RIGHT = Number(opts.safeRight) || 0;
    var safeSide = applySafeMetrics();
    pageMargin = Math.max(pageMargin, safeSide + 8);
    document.documentElement.style.setProperty('--reader-page-margin-x', pageMargin + 'px');
    document.documentElement.style.setProperty(
      '--reader-image-max-w',
      opts.imageFullWidth ? '100%' : 'calc(100% - 2 * ' + pageMargin + 'px)'
    );
    var fontFamily = typeof opts.fontFamily === 'string' ? opts.fontFamily : '';
    if (fontFamily) {
      document.documentElement.style.setProperty('--reader-font-family', fontFamily);
    } else {
      document.documentElement.style.removeProperty('--reader-font-family');
    }
    // 自定义字体目录里的字体：@font-face 规则随选项一起下发，字体字节由原生拦截器供给。
    var fontFaces = typeof opts.fontFaces === 'string' ? opts.fontFaces : '';
    var fontFacesStyle = document.getElementById('salt-reader-fonts');
    if (fontFaces) {
      if (!fontFacesStyle) {
        fontFacesStyle = document.createElement('style');
        fontFacesStyle.id = 'salt-reader-fonts';
        document.head.appendChild(fontFacesStyle);
      }
      if (fontFacesStyle.textContent !== fontFaces) fontFacesStyle.textContent = fontFaces;
    } else if (fontFacesStyle) {
      fontFacesStyle.textContent = '';
    }
    var monoFamily = typeof opts.monoFamily === 'string' ? opts.monoFamily : '';
    if (monoFamily) {
      document.documentElement.style.setProperty('--reader-mono-family', monoFamily);
    } else {
      document.documentElement.style.removeProperty('--reader-mono-family');
    }
    if (mode === 'paged') {
      var pageWidth = applyPageWidth();
      book.style.height = Math.max(1, (window.innerHeight || 0) - PAGE_PAD_TOP - PAGE_PAD_BOTTOM) + 'px';
      book.style.marginTop = PAGE_PAD_TOP + 'px';
      book.style.marginBottom = PAGE_PAD_BOTTOM + 'px';
      scroll.style.scrollSnapType = 'x mandatory';
      refreshSnapPoints();
    } else {
      book.style.webkitColumnWidth = 'auto';
      book.style.width = 'auto';
      book.style.height = 'auto';
      book.style.marginTop = '0';
      book.style.marginBottom = '0';
    }
  }
  function snapPageBoundary() {
    if (mode !== 'paged') return;
    var w = Math.max(1, scroll.clientWidth || window.innerWidth);
    var target = Math.round(scroll.scrollLeft / w) * w;
    if (Math.abs(target - scroll.scrollLeft) > 0.5) scroll.scrollLeft = target;
  }
  function restore(r) {
    // 图片解码会改变 scrollHeight：跳转/恢复位置前先等图片就绪，避免落点偏移。
    whenImagesReady(function () { restoreNow(r); });
  }
  function restoreNow(r) {
    restoring = true;
    r = clamp(r);
    refreshMetrics();
    if (mode === 'paged') {
      var pages = pagedPages();
      var p = Math.round(r * Math.max(0, pages - 1));
      scroll.scrollLeft = p * viewportWidth();
    } else {
      scroll.scrollTop = r * Math.max(0, scroll.scrollHeight - scroll.clientHeight);
    }
    setTimeout(function () {
      restoring = false;
      // A late column re-flow (fonts/images) can leave scrollLeft mid-page; realign before report.
      refreshSnapPoints();
      snapPageBoundary();
      report(true);
      var rectDiff = book.getBoundingClientRect().left - scroll.getBoundingClientRect().left;
    }, 120);
  }
  var applyRetries = 0;
  function apply(opts, r) {
    // 初始视口还没测量出来时先不布局（见 viewportReady 注释）；最多等约 2 秒。
    if (!viewportReady()) {
      applyRetries++;
      if (applyRetries < 600) {
        requestAnimationFrame(function () { apply(opts, r); });
        return;
      }
      // 视口始终无效：绝不按 1px 建分栏（会瞬间吃掉数百 MB 内存）。
      console.log('JSPERF apply-abort invalid-viewport');
      return;
    }
    applyRetries = 0;
    updateViewportHeight();
    setOptions(opts);
    // 字体加载会改变分页宽度：先等字体就绪再恢复位置，避免跳到半页/旧页。
    whenFontsReady(function () {
      whenImagesReady(function () {
        restore(r);
        console.log('JSPERF applied mode=' + mode + ' body=' + document.body.className +
          ' sh=' + scroll.scrollHeight + ' ch=' + scroll.clientHeight +
          ' sw=' + scroll.scrollWidth + ' cw=' + scroll.clientWidth);
        maybeRequestMoreForVertical();
      });
    });
  }
  // 三次贝塞尔缓动求解（x 轴 Newton 迭代 + y 轴求值），用于翻页与音量键动画。
  function cubicBezier(x1, y1, x2, y2) {
    function sampleCurveX(t) { return ((1 - 3 * x2 + 3 * x1) * t + (3 * x2 - 6 * x1)) * t * t + 3 * x1 * t; }
    function sampleCurveY(t) { return ((1 - 3 * y2 + 3 * y1) * t + (3 * y2 - 6 * y1)) * t * t + 3 * y1 * t; }
    function solveX(x) {
      var t = x;
      for (var i = 0; i < 6; i++) {
        var dx = sampleCurveX(t) - x;
        var slope = (3 * (1 - 3 * x2 + 3 * x1) * t + 2 * (3 * x2 - 6 * x1)) * t + 3 * x1;
        if (Math.abs(slope) < 1e-4) break;
        t -= dx / slope;
        if (t < 0) t = 0; else if (t > 1) t = 1;
      }
      return t;
    }
    return function (x) { return sampleCurveY(solveX(x)); };
  }

  function turn(delta) {
    if (mode !== 'paged') return;
    refreshMetrics();
    var pages = pagedPages();
    var p = Math.min(pages - 1, Math.max(0, pagedPage() + delta));
    // Native smooth scrolling + CSS scroll snap: the settle curve is the platform fling curve,
    // which is what the image pager also uses, and it costs no per-frame JS.
    scroll.scrollTo({ left: p * viewportWidth(), behavior: 'smooth' });
    report();
  }
  // Tap zones: jump straight to the page boundary, no animation at all.
  function turnInstant(delta) {
    if (mode !== 'paged') return;
    refreshMetrics();
    var pages = pagedPages();
    var p = Math.min(pages - 1, Math.max(0, pagedPage() + delta));
    scroll.scrollLeft = p * viewportWidth();
    report();
  }
  // Volume keys: a short, snappy glide instead of the browser's longer smooth scroll.
  function turnFast(delta) {
    if (mode !== 'paged') return;
    refreshMetrics();
    var pages = pagedPages();
    var p = Math.min(pages - 1, Math.max(0, pagedPage() + delta));
    var start = scroll.scrollLeft;
    var target = p * viewportWidth();
    if (Math.abs(target - start) < 1) {
      report();
      return;
    }
    var duration = 110;
    var startedAt = performance.now();
    var volumeEase = cubicBezier(0.25, 0.46, 0.45, 0.94);
    function step(now) {
      var t = Math.min(1, (now - startedAt) / duration);
      var eased = volumeEase(t);
      scroll.scrollLeft = start + (target - start) * eased;
      if (t < 1) {
        requestAnimationFrame(step);
      } else {
        report();
      }
    }
    requestAnimationFrame(step);
  }
  function scrollViewport(delta) {
    if (mode === 'paged') {
      turn(delta);
    } else {
      var step = scroll.clientHeight * 0.9;
      scroll.scrollTop = Math.min(scroll.scrollHeight, Math.max(0, scroll.scrollTop + delta * step));
      report();
    }
  }

  // 统一翻页物理：WebView 与 native 阅读器完全一致（跟手拖动 -> 30%/350px/s 阈值 -> 220ms 缓动吸附）。
  var customPagedDrag = true;
  var swipeDrag = { active: false, startX: 0, startY: 0, startLeft: 0, startPage: 0, lastX: 0, lastAt: 0, velocity: 0, moved: false };
  var lastScrollAt = 0;
  var dragDistance = 0;
  var swipeRaf = 0;
  function swipeEase(t) {
    // cubic-bezier(0.25, 0.46, 0.45, 0.94)
    return cubicBezier(0.25, 0.46, 0.45, 0.94)(t);
  }
  function cancelSwipeAnimation() {
    if (swipeRaf) cancelAnimationFrame(swipeRaf);
    swipeRaf = 0;
  }
  function animateSwipeTo(target) {
    cancelSwipeAnimation();
    var start = scroll.scrollLeft;
    var delta = target - start;
    if (Math.abs(delta) < 0.5) {
      scroll.scrollLeft = target;
      report(true);
      return;
    }
    var duration = 220;
    var startedAt = performance.now();
    function step(now) {
      var t = Math.min(1, (now - startedAt) / duration);
      scroll.scrollLeft = start + delta * swipeEase(t);
      if (t < 1) {
        swipeRaf = requestAnimationFrame(step);
      } else {
        swipeRaf = 0;
        scroll.scrollLeft = target;
        report(true);
      }
    }
    swipeRaf = requestAnimationFrame(step);
  }
  function endPagedDrag() {
    if (!swipeDrag.active) return;
    swipeDrag.active = false;
    if (mode !== 'paged') return;
    var w = viewportWidth();
    var max = Math.max(0, scroll.scrollWidth - scroll.clientWidth);
    var dx = swipeDrag.lastX - swipeDrag.startX;
    var turned = Math.abs(dx) > w * 0.3 || Math.abs(swipeDrag.velocity) > 350;
    var pages = pagedPages();
    // 起始页 + 一页：不能拿“拖动后已过的页”再加一页，否则会一次翻两页。
    var page = swipeDrag.startPage;
    if (turned) {
      page = Math.min(pages - 1, Math.max(0, page + (dx < 0 ? 1 : -1)));
    }
    var target = Math.min(max, Math.max(0, page * w));
    animateSwipeTo(target);
  }
  // 分页手势：跟手拖动 + 松手按与 native 相同的阈值/时长/曲线吸附。
  scroll.addEventListener('touchstart', function (e) {
    if (mode !== 'paged' || e.touches.length !== 1) return;
    cancelSwipeAnimation();
    var touch = e.touches[0];
    swipeDrag.active = true;
    swipeDrag.startX = touch.clientX;
    swipeDrag.startY = touch.clientY;
    swipeDrag.lastX = touch.clientX;
    swipeDrag.startLeft = scroll.scrollLeft;
    swipeDrag.startPage = pagedPage();
    swipeDrag.lastAt = performance.now();
    swipeDrag.velocity = 0;
    swipeDrag.moved = false;
    dragDistance = 0;
  }, { passive: false });
  scroll.addEventListener('touchmove', function (e) {
    if (!swipeDrag.active || e.touches.length !== 1) return;
    var touch = e.touches[0];
    var dx = touch.clientX - swipeDrag.startX;
    dragDistance = Math.max(dragDistance, Math.abs(dx));
    var dy = touch.clientY - swipeDrag.startY;
    if (Math.abs(dx) > 6) {
      swipeDrag.moved = true;
      if (e.cancelable) e.preventDefault();
    }
    var max = Math.max(0, scroll.scrollWidth - scroll.clientWidth);
    scroll.scrollLeft = Math.min(max, Math.max(0, swipeDrag.startLeft - dx));
    var now = performance.now();
    var dt = now - swipeDrag.lastAt;
    if (dt > 0) {
      swipeDrag.velocity = (touch.clientX - swipeDrag.lastX) / dt * 1000;
    }
    swipeDrag.lastX = touch.clientX;
    swipeDrag.lastAt = now;
  }, { passive: false });
  scroll.addEventListener('touchend', function (e) {
    if (!swipeDrag.active) return;
    if (swipeDrag.moved && e.cancelable) e.preventDefault();
    endPagedDrag();
  }, { passive: false });
  scroll.addEventListener('touchcancel', function () {
    if (!swipeDrag.active) return;
    endPagedDrag();
  }, { passive: false });

  scroll.addEventListener('scroll', function () {
    lastScrollAt = performance.now();
    if (restoring) return;
    // Throttle (not debounce): the page number and progress must keep updating while the page is
    // still moving, not only after the finger lifts.
    if (scrollTimer) return;
    scrollTimer = setTimeout(function () {
      scrollTimer = null;
      report();
    }, 90);
  }, { passive: true });
  document.addEventListener('click', function (e) {
    // In-book links are not navigable inside the reader shell: swallow them instead of letting the
    // WebView attempt a navigation that would leave a blank page.
    var anchor = e.target && e.target.closest ? e.target.closest('a') : null;
    if (anchor) { e.preventDefault(); }
    // A swipe that ends over the left/right tap zone must not also turn the page.
    if (dragDistance > 24) return;
    var x = e.clientX || 0;
    var w = window.innerWidth || 1;
    try {
      if (window['$BRIDGE_NAME'] && window['$BRIDGE_NAME'].onTap) {
        window['$BRIDGE_NAME'].onTap(Math.min(1, Math.max(0, x / w)));
      }
    } catch (err) {}
  }, { passive: false });
  function appendChapters(html) {
    if (!html) return;
    book.insertAdjacentHTML('beforeend', html);
    refreshSnapPoints();
    report(true);
    // Inline illustrations decode asynchronously and add columns: the snap markers must follow,
    // otherwise a later fling has no snap target for the new pages and can run past them.
    whenImagesReady(function () {
      refreshSnapPoints();
      report(true);
      console.log('JSPERF append nodes=' + book.querySelectorAll('*').length +
        ' imgs=' + book.querySelectorAll('img').length +
        ' sh=' + scroll.scrollHeight + ' st=' + scroll.scrollTop);
    });
  }
  function prependChapters(html) {
    if (!html) return;
    var paged = mode === 'paged';
    var oldSize = paged ? scroll.scrollWidth : scroll.scrollHeight;
    var anchor = paged ? scroll.scrollLeft : scroll.scrollTop;
    book.insertAdjacentHTML('afterbegin', html);
    // 保持当前阅读位置：前插的内容（尤其内联插图）会同步/异步地增加文档尺寸，按增量补偿滚动偏移。
    function compensate() {
      var size = paged ? scroll.scrollWidth : scroll.scrollHeight;
      var added = size - oldSize;
      if (added > 0) {
        if (paged) scroll.scrollLeft = anchor + added;
        else scroll.scrollTop = anchor + added;
      }
      // Late image loads change the page count: keep the snap targets in sync with the new DOM.
      refreshSnapPoints();
      report(true);
    }
    refreshSnapPoints();
    compensate();
    whenImagesReady(compensate);
  }
  window.__reader = {
    apply: apply,
    paint: paint,
    turn: turn,
    turnInstant: turnInstant,
    turnFast: turnFast,
    restore: restore,
    scrollViewport: scrollViewport,
    state: report,
    append: appendChapters,
    prepend: prependChapters
  };
  if (window.__saltReaderBootstrap) {
    apply(window.__saltReaderBootstrap.options, window.__saltReaderBootstrap.ratio);
  }
})();
</script>
</body>
</html>
""".trimIndent()
