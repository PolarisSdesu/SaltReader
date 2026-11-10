package moe.gbxin.saltreader.ui.reader

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.annotation.Keep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import moe.gbxin.saltreader.data.AppSettings
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.EbookChapter
import moe.gbxin.saltreader.data.ReaderBackground
import moe.gbxin.saltreader.data.ReaderMode
import moe.gbxin.saltreader.data.ReadingDirection
import moe.gbxin.saltreader.ui.components.CustomColorPickerDialog
import moe.gbxin.saltreader.ui.theme.pageBackgroundColor
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Latest reported layout state of the text reader WebView. */
private data class TextPageInfo(val page: Int, val pages: Int, val ratio: Float)

/** Events posted from the WebView's JavaScript bridge back to the Compose side. */
internal sealed interface TextReaderEvent {
    data class State(val page: Int, val pages: Int, val ratio: Float) : TextReaderEvent
    data object MiddleZone : TextReaderEvent
}

/**
 * JavaScript -> Kotlin bridge. Called on the WebView thread, so events are pushed through a
 * thread-safe [MutableSharedFlow] and consumed on the main thread.
 */
@Keep
internal class TextJavascriptBridge(private val emit: (TextReaderEvent) -> Unit) {
    @Keep
    @JavascriptInterface
    fun onState(page: Int, pages: Int, ratio: Double) {
        emit(TextReaderEvent.State(page, pages, ratio.toFloat().coerceIn(0f, 1f)))
    }

    @Keep
    @JavascriptInterface
    fun onMiddle() {
        emit(TextReaderEvent.MiddleZone)
    }
}

/** Holds the current WebView without turning it into Compose state. */
private class WebViewHolder {
    var webView: WebView? = null
}

/**
 * Native text reader for reflowable books (EPUB text, MOBI, AZW3).
 *
 * The sanitized chapter HTML is rendered by an [WebView] (the only reflow engine available without
 * native libraries). PAGED mode uses CSS multi-column layout and is turned page by page; VERTICAL
 * mode is a normal vertical scroll. Compose draws the chrome; in PAGED mode a transparent Compose
 * overlay owns taps/swipes (the WebView itself does not need touch there), while in VERTICAL mode the
 * WebView keeps native scrolling and reports middle-third taps through JavaScript.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
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
) {
    val readerBackground = pageBackgroundColor(settings.readerBackground, settings.customBackgroundColor)
    val readerTextColor =
        if (readerBackground.luminance() < 0.5f) Color(0xFFEDE6DD) else Color(0xFF2A2622)
    val direction = book.direction ?: settings.readingDirection
    val isLightThemeBackground = MaterialTheme.colorScheme.background.luminance() > 0.5f

    // Estimated character count of the book (indexed). Fall back to the actual extracted HTML size
    // so progress still works when the index value is missing.
    val totalChars = remember(book.charCount, chapters) {
        book.charCount.takeIf { it > 0L }
            ?: chapters.sumOf { it.html.length.toLong() }.coerceAtLeast(1L)
    }
    val initialRatio = remember(book.id, book.progressOffset, totalChars) {
        (book.progressOffset.toDouble() / totalChars.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showCustomBackground by rememberSaveable { mutableStateOf(false) }
    var pageInfo by remember(book.id) {
        mutableStateOf(TextPageInfo(page = 0, pages = 1, ratio = initialRatio))
    }

    val html = remember(chapters, book.title) { buildBookHtml(chapters, book.title) }
    val events = remember { MutableSharedFlow<TextReaderEvent>(extraBufferCapacity = 32) }
    val bridge = remember { TextJavascriptBridge { event -> events.tryEmit(event) } }
    val holder = remember { WebViewHolder() }
    var pageFinishedToken by remember { mutableIntStateOf(0) }

    var lastSentOffset by remember { mutableLongStateOf(-1L) }
    var lastSentAt by remember { mutableLongStateOf(0L) }
    val liveRatio = rememberUpdatedState(pageInfo.ratio)
    val liveTotalChars = rememberUpdatedState(totalChars)
    val liveMode = rememberUpdatedState(settings.readerMode)
    val liveDirection = rememberUpdatedState(direction)
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun runJs(script: String) {
        val webView = holder.webView ?: return
        runCatching { webView.evaluateJavascript(script, null) }
    }

    fun turnVisual(delta: Int) {
        runJs("window.__reader && window.__reader.turn($delta);")
    }

    /** [logicalDelta] is +1 for "next page" in reading order; RTL mirrors it horizontally. */
    fun turnPage(logicalDelta: Int) {
        if (liveMode.value != ReaderMode.PAGED) return
        turnVisual(if (liveDirection.value == ReadingDirection.RTL) -logicalDelta else logicalDelta)
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

    // WebView -> Compose events (main thread).
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is TextReaderEvent.State -> {
                    pageInfo = TextPageInfo(event.page, event.pages, event.ratio)
                    persistProgress(force = false)
                }
                TextReaderEvent.MiddleZone -> chromeVisible = !chromeVisible
            }
        }
    }

    // Re-apply typography / colours / mode when they change, keeping the current reading ratio.
    val optionsJson = remember(settings.readerMode, settings.readerFontSizeSp, readerBackground, readerTextColor) {
        JSONObject().apply {
            put("mode", if (settings.readerMode == ReaderMode.PAGED) "paged" else "vertical")
            put("fontSize", settings.readerFontSizeSp)
            put("background", cssColor(readerBackground))
            put("color", cssColor(readerTextColor))
        }.toString()
    }
    LaunchedEffect(pageFinishedToken, optionsJson) {
        if (pageFinishedToken == 0) return@LaunchedEffect
        runJs("window.__reader && window.__reader.apply($optionsJson, ${liveRatio.value});")
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
    val topBarColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    val statusBarBackground = if (chromeVisible) blendOver(readerBackground, topBarColor) else readerBackground
    val navigationBarBackground =
        if (chromeVisible) blendOver(readerBackground, Color.Black.copy(alpha = 0.38f)) else readerBackground
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
            if (event == Lifecycle.Event.ON_PAUSE) persistProgress(force = true)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            persistProgress(force = true)
        }
    }

    LaunchedEffect(volumePageEvents) {
        volumePageEvents?.collect { delta -> turnPage(delta) }
    }

    val pageLabel = if (settings.readerMode == ReaderMode.PAGED) {
        "${(pageInfo.page + 1).coerceAtMost(pageInfo.pages)} / ${pageInfo.pages}"
    } else {
        "${(pageInfo.ratio * 100f).roundToInt().coerceIn(0, 100)}%"
    }
    val chromeToggleLabel = if (chromeVisible) "隐藏工具栏" else "显示工具栏"

    Box(
        modifier
            .fillMaxSize()
            .background(readerBackground)
            .semantics {
                contentDescription = "正文阅读区域"
                customActions = listOf(
                    CustomAccessibilityAction("上一页") {
                        if (liveMode.value == ReaderMode.PAGED) turnPage(-1) else scrollViewport(-1)
                        true
                    },
                    CustomAccessibilityAction("下一页") {
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
        key(html) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageFinishedToken += 1
                            }
                        }
                        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                        holder.webView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { webView ->
                    if (holder.webView === webView) holder.webView = null
                    runCatching { webView.removeJavascriptInterface(BRIDGE_NAME) }
                    runCatching { webView.stopLoading() }
                    runCatching { webView.loadUrl("about:blank") }
                    runCatching { webView.destroy() }
                },
            )
        }

        // In PAGED mode the WebView needs no touch input (we turn pages programmatically), so a
        // transparent overlay owns taps and horizontal swipes without fighting the native view.
        if (settings.readerMode == ReaderMode.PAGED) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(settings.readerMode, direction) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dx = 0f
                            var dy = 0f
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.pressed) {
                                    dx += change.positionChange().x
                                    dy += change.positionChange().y
                                    change.consume()
                                } else {
                                    pressed = false
                                }
                            }
                            val slop = viewConfiguration.touchSlop
                            when {
                                abs(dx) > slop * 2f && abs(dx) > abs(dy) -> {
                                    // Swipe left moves the content forward visually.
                                    turnVisual(if (dx < 0f) 1 else -1)
                                }
                                abs(dx) < slop && abs(dy) < slop -> {
                                    val third = size.width / 3f
                                    when {
                                        down.position.x < third ->
                                            turnPage(if (direction == ReadingDirection.RTL) 1 else -1)
                                        down.position.x > size.width - third ->
                                            turnPage(if (direction == ReadingDirection.RTL) -1 else 1)
                                        else -> chromeVisible = !chromeVisible
                                    }
                                }
                            }
                        }
                    },
            )
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
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            TextReaderBottomBar(
                mode = settings.readerMode,
                label = pageLabel,
                onToggleMode = {
                    onSettingsChange {
                        it.copy(
                            readerMode = if (it.readerMode == ReaderMode.PAGED) ReaderMode.VERTICAL else ReaderMode.PAGED,
                        )
                    }
                },
                onOpenSettings = { showSettings = true },
            )
        }

        AnimatedVisibility(
            visible = !chromeVisible && settings.showPageNumber,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (readerBackground.luminance() < 0.5f) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.72f),
                contentColor = if (readerBackground.luminance() < 0.5f) Color(0xFFEDE0D4) else Color(0xFF29231D),
            ) {
                Text(
                    pageLabel,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (showSettings) {
        ReaderControlsSheet(
            currentPage = pageInfo.page,
            count = pageInfo.pages.coerceAtLeast(1),
            settings = settings,
            direction = direction,
            onDirection = { vm.setDirection(it) },
            onGoToPage = { },
            onDismiss = { showSettings = false },
            onSettingsChange = onSettingsChange,
            onCustomBackground = { showCustomBackground = true },
            isTextBook = true,
        )
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TextReaderBottomBar(
    mode: ReaderMode,
    label: String,
    onToggleMode: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.38f))))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        HorizontalFloatingToolbar(expanded = true, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onToggleMode) {
                Icon(
                    if (mode == ReaderMode.PAGED) Icons.Rounded.ViewAgenda else Icons.Rounded.Swipe,
                    contentDescription = if (mode == ReaderMode.PAGED) "切换为纵向滚动" else "切换为横向翻页",
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Tune, contentDescription = "阅读设置")
            }
        }
    }
}

/** Converts a Compose colour to the `#RRGGBB` string used by the injected CSS/JS. */
private fun cssColor(color: Color): String =
    String.format(Locale.US, "#%06X", color.toArgb() and 0xFFFFFF)

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * Builds one HTML document for all chapters and embeds the paging/scroll/state JavaScript.
 * Chapter HTML is sanitized by the data layer (scripts/styles/links removed), so it can be
 * embedded as-is.
 *
 * Single-chapter containers (typically MOBI/AZW3) often have no in-document heading, so the book
 * title is used as the chapter heading there.
 */
private fun buildBookHtml(chapters: List<EbookChapter>, bookTitle: String): String {
    val body = StringBuilder()
    chapters.forEachIndexed { index, chapter ->
        body.append("<section class=\"chapter\" id=\"ch").append(index).append("\">")
        val heading = chapter.title?.takeIf { it.isNotBlank() }
            ?: bookTitle.takeIf { chapters.size == 1 && it.isNotBlank() }
        heading?.let { title ->
            body.append("<h2>").append(escapeHtml(title)).append("</h2>")
        }
        body.append(chapter.html)
        body.append("</section>")
    }
    return HTML_HEAD + body + HTML_TAIL
}

private const val BRIDGE_NAME = "SaltReader"

private val HTML_HEAD = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>
html, body { margin: 0; padding: 0; }
body { -webkit-text-size-adjust: 100%; text-size-adjust: 100%; }
h1, h2 { font-size: 1.35em; margin: 0.2em 22px 0.8em 22px; line-height: 1.4; }
p { margin: 0 22px 0.9em 22px; }
img { max-width: 100%; height: auto; }
body.paged { height: 100%; overflow: hidden; }
body.paged #scroll { width: 100vw; height: 100vh; overflow: hidden; }
body.paged #book { height: 100vh; column-width: 100vw; column-gap: 0; column-fill: auto; -webkit-column-width: 100vw; -webkit-column-gap: 0; -webkit-column-fill: auto; }
body.vertical { height: auto; }
body.vertical #scroll { width: 100vw; height: 100vh; overflow-y: auto; overflow-x: hidden; -webkit-overflow-scrolling: touch; }
body.vertical #book { height: auto; column-width: auto; }
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
  var mode = 'paged';
  var restoring = false;
  var scrollTimer = null;

  function clamp(v) { return Math.min(1, Math.max(0, v || 0)); }
  function viewportWidth() { return Math.max(1, scroll.clientWidth || window.innerWidth); }
  function pagedPages() { return Math.max(1, Math.round(scroll.scrollWidth / viewportWidth())); }
  function pagedPage() { return Math.round(scroll.scrollLeft / viewportWidth()); }
  function ratio() {
    if (mode === 'paged') {
      var denom = Math.max(1, scroll.scrollWidth - scroll.clientWidth);
      return clamp(scroll.scrollLeft / denom);
    }
    var d = Math.max(1, scroll.scrollHeight - scroll.clientHeight);
    return clamp(scroll.scrollTop / d);
  }
  function report() {
    var p = mode === 'paged' ? pagedPage() : 0;
    var n = mode === 'paged' ? pagedPages() : 1;
    try {
      if (window['$BRIDGE_NAME'] && window['$BRIDGE_NAME'].onState) {
        window['$BRIDGE_NAME'].onState(p, n, ratio());
      }
    } catch (e) {}
  }
  function setOptions(opts) {
    mode = opts.mode === 'vertical' ? 'vertical' : 'paged';
    document.body.className = mode;
    document.body.style.background = opts.background;
    document.documentElement.style.background = opts.background;
    document.body.style.color = opts.color;
    document.body.style.fontSize = opts.fontSize + 'px';
    document.body.style.lineHeight = '1.75';
    if (mode === 'paged') {
      book.style.webkitColumnWidth = '100vw';
      book.style.width = '100vw';
      book.style.height = '100vh';
    } else {
      book.style.webkitColumnWidth = 'auto';
      book.style.width = 'auto';
      book.style.height = 'auto';
    }
  }
  function restore(r) {
    restoring = true;
    r = clamp(r);
    if (mode === 'paged') {
      var pages = pagedPages();
      var p = Math.round(r * Math.max(0, pages - 1));
      scroll.scrollLeft = p * viewportWidth();
    } else {
      scroll.scrollTop = r * Math.max(0, scroll.scrollHeight - scroll.clientHeight);
    }
    setTimeout(function () { restoring = false; report(); }, 120);
  }
  function apply(opts, r) {
    setOptions(opts);
    requestAnimationFrame(function () {
      requestAnimationFrame(function () { restore(r); });
    });
  }
  function turn(delta) {
    if (mode !== 'paged') return;
    var pages = pagedPages();
    var p = Math.min(pages - 1, Math.max(0, pagedPage() + delta));
    scroll.scrollLeft = p * viewportWidth();
    report();
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
  scroll.addEventListener('scroll', function () {
    if (restoring) return;
    if (scrollTimer) clearTimeout(scrollTimer);
    scrollTimer = setTimeout(report, 180);
  }, { passive: true });
  document.addEventListener('click', function (e) {
    // In-book links are not navigable inside the reader shell: swallow them instead of letting the
    // WebView attempt a navigation that would leave a blank page.
    var anchor = e.target && e.target.closest ? e.target.closest('a') : null;
    if (anchor) { e.preventDefault(); }
    if (mode !== 'vertical') return;
    var x = e.clientX || 0;
    var w = window.innerWidth || 1;
    if (x > w / 3 && x < (w * 2 / 3)) {
      try {
        if (window['$BRIDGE_NAME'] && window['$BRIDGE_NAME'].onMiddle) {
          window['$BRIDGE_NAME'].onMiddle();
        }
      } catch (err) {}
    }
  }, { passive: false });
  window.__reader = {
    apply: apply,
    turn: turn,
    restore: restore,
    scrollViewport: scrollViewport,
    state: report
  };
})();
</script>
</body>
</html>
""".trimIndent()
