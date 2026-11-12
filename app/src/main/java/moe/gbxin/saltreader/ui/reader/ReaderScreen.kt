package moe.gbxin.saltreader.ui.reader

import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import moe.gbxin.saltreader.R
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.AppSettings
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.ReaderBackground
import moe.gbxin.saltreader.data.RegionPage
import moe.gbxin.saltreader.data.ReaderMode
import moe.gbxin.saltreader.data.ReaderPageInfo
import moe.gbxin.saltreader.data.ReadingDirection
import moe.gbxin.saltreader.ui.components.BatteryIndicator
import moe.gbxin.saltreader.ui.components.CustomColorPickerDialog
import moe.gbxin.saltreader.ui.components.SaltLoading
import moe.gbxin.saltreader.ui.settings.ReaderSettingsActivity
import moe.gbxin.saltreader.ui.theme.pageBackgroundColor
import moe.gbxin.saltreader.ui.theme.readerAccentColor
import moe.gbxin.saltreader.ui.theme.readerForegroundColor

/**
 * Reader entry point. Renders the top-level states instead of assuming the book is already usable:
 * [ReaderUiState.Loading] while the index is publishing or the image page source is being prepared,
 * [ReaderUiState.TextLoading] while a reflowable book's chapters are parsed, [ReaderUiState.Missing]
 * only when the book is definitively gone,
 * [ReaderUiState.Failed] with a retry when opening the file failed, and the actual reader once the
 * page count / chapters are ready.
 */
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    onBack: () -> Unit,
    volumePageEvents: Flow<Int>? = null,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as SaltReaderApplication
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val tallPages by vm.tallPages.collectAsStateWithLifecycle()
    val textReaderBackground = pageBackgroundColor(
        settings.readerBackground,
        settings.customBackgroundColor,
        isSystemInDarkTheme(),
        MaterialTheme.colorScheme.surface,
    )

    AnimatedContent(
        targetState = uiState,
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(durationMillis = 180)) togetherWith fadeOut(tween(durationMillis = 120))
        },
        contentKey = { state ->
            when (state) {
                is ReaderUiState.Loading -> "loading"
                ReaderUiState.TextLoading, is ReaderUiState.TextReady -> "text"
                is ReaderUiState.Ready -> "ready"
                ReaderUiState.Missing -> "missing"
                is ReaderUiState.Failed, is ReaderUiState.TextFailed -> "failed"
            }
        },
        label = "reader state",
    ) { state ->
        when (state) {
            is ReaderUiState.Loading -> ReaderLoading(
                message = stringResource(
                    when (state.reason) {
                        ReaderLoadingReason.INDEX -> R.string.reader_loading_opening
                        ReaderLoadingReason.PREPARING -> R.string.reader_loading_preparing
                    },
                ),
                modifier = modifier,
            )

            ReaderUiState.Missing -> {
                // The book vanished from the index while the reader was open; show why, then return to
                // the shelf. The button lets the reader leave immediately.
                LaunchedEffect(Unit) {
                    delay(1_600)
                    onBack()
                }
                ReaderMessage(
                    title = stringResource(R.string.reader_unavailable_title),
                    message = stringResource(R.string.reader_unavailable_message),
                    onBack = onBack,
                    modifier = modifier,
                )
            }

            ReaderUiState.TextLoading -> Box(
                modifier.fillMaxSize().background(textReaderBackground),
                contentAlignment = Alignment.Center,
            ) {
                SaltLoading(sizeDp = 48)
            }

            is ReaderUiState.TextFailed -> ReaderFailed(
                errorLog = state.errorLog,
                background = textReaderBackground,
                onRetry = vm::retryLoadChapters,
                onBack = onBack,
                modifier = modifier,
            )

            is ReaderUiState.TextReady -> TextReaderContent(
                vm = vm,
                book = state.book,
                chapters = state.chapters,
                settings = settings,
                onBack = onBack,
                volumePageEvents = volumePageEvents,
                modifier = modifier,
                onSettingsChange = { transform -> app.settings.update(transform) },
            )

            is ReaderUiState.Failed -> ReaderFailed(
                errorLog = state.errorLog,
                background = textReaderBackground,
                onRetry = vm::retryPrepare,
                onBack = onBack,
                modifier = modifier,
            )

            is ReaderUiState.Ready -> {
                if (state.pageCount <= 0) {
                    // Prepared book with no readable pages: this is not "page 1 failed", there is simply
                    // nothing to display. Never render the pager with a fake single page.
                    ReaderMessage(
                        title = stringResource(R.string.reader_empty_title),
                        message = stringResource(R.string.reader_empty_message),
                        onBack = onBack,
                        modifier = modifier,
                    )
                } else {
                    ReaderContent(
                        vm = vm,
                        book = state.book,
                        pageCount = state.pageCount,
                        tallPages = tallPages,
                        settings = settings,
                        onBack = onBack,
                        volumePageEvents = volumePageEvents,
                        modifier = modifier,
                        onSettingsChange = { transform -> app.settings.update(transform) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderLoading(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SaltLoading(sizeDp = 48)
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReaderMessage(
    title: String,
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onBack) { Text(stringResource(R.string.reader_back_to_library)) }
        }
    }
}

@Composable
private fun ReaderFailed(
    errorLog: String,
    background: Color,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val foreground = if (background.luminance() < 0.5f) Color.White else Color.Black
    Box(
        modifier.fillMaxSize().background(background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.reader_failed_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.reader_failed_message),
                style = MaterialTheme.typography.bodyMedium,
                color = foreground.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(0.88f),
                colors = CardDefaults.cardColors(containerColor = foreground.copy(alpha = 0.08f)),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.reader_error_details),
                        style = MaterialTheme.typography.titleSmall,
                        color = foreground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        errorLog.lineSequence().firstOrNull { it.isNotBlank() } ?: errorLog,
                        style = MaterialTheme.typography.bodySmall,
                        color = foreground.copy(alpha = 0.78f),
                        maxLines = 3,
                    )
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Salt Reader error log", errorLog))
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.reader_error_log_copied),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }) {
                        Text(stringResource(R.string.reader_copy_error_log), color = foreground)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.reader_retry)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.reader_back_to_library)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    vm: ReaderViewModel,
    book: ComicBook,
    pageCount: Int,
    tallPages: Boolean,
    settings: AppSettings,
    onBack: () -> Unit,
    volumePageEvents: Flow<Int>?,
    modifier: Modifier = Modifier,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    // Real count reported by ComicPageSource.prepare(); this is the authoritative page count even
    // when the index only stored an estimate (e.g. remote ZIP central-directory reads).
    val count = max(1, pageCount)
    val initialPage = remember { book.currentPage.coerceIn(0, count - 1) }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { count })
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    // Use one decode width for both reading modes so switching modes hits the bitmap cache.
    val pageTarget = (screenWidthPx * 1.25f).toInt().coerceIn(720, 1800)

    // UI-only state survives rotation / process-death restore so a settings sheet that was open
    // (or a hidden toolbar) does not spring back to its default.
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var activePanel by rememberSaveable { mutableStateOf(ReaderChromePanel.NONE) }
    LaunchedEffect(chromeVisible) {
        if (!chromeVisible) activePanel = ReaderChromePanel.NONE
    }
    var showCustomBackground by rememberSaveable { mutableStateOf(false) }
    // Zoom is transient: it is reset whenever the pager settles on a different page.
    var zoomed by remember { mutableStateOf(false) }
    var zoomResetToken by remember { mutableIntStateOf(0) }
    // Vertical (continuous) mode pinch-to-zoom: the whole scrolling viewport is scaled as one
    // layer (a single GPU transform over the virtualized list), so all visible pages zoom together
    // without per-page cost. List scrolling pauses while zoomed and one-finger drags pan.
    var verticalZoomed by remember { mutableStateOf(false) }
    // The fade-in exists only to hide the first decode flash when opening a book; pages loaded
    // later while scrolling appear instantly (a white fade from a dark background hurts the eyes).
    var allowPageFade by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(900)
        allowPageFade = false
    }
    var verticalScale by remember { mutableFloatStateOf(1f) }
    var verticalOffset by remember { mutableStateOf(Offset.Zero) }
    val liveVerticalZoomed = rememberUpdatedState(verticalZoomed)

    val readerBackground = pageBackgroundColor(
        settings.readerBackground,
        settings.customBackgroundColor,
        isSystemInDarkTheme(),
        MaterialTheme.colorScheme.surface,
    )
    val pageFilter = remember(settings.grayscale, settings.invertColors) {
        readerColorFilter(settings.grayscale, settings.invertColors)
    }

    // Comic chrome uses the reading background colour itself, so the menu blends into the page.
    val readerAccent = readerAccentColor(settings.readerBackground, settings.customBackgroundColor)
    val monetChrome = settings.readerBackground == moe.gbxin.saltreader.data.ReaderBackground.AUTO
    val chromeBarColor = if (monetChrome) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        readerBackground
    }
    val chromeContentColor = when {
        monetChrome -> MaterialTheme.colorScheme.onSurface
        readerBackground == Color.Black -> Color.White
        else -> readerForegroundColor(readerBackground)
    }
    val statusBarBackground = chromeBarColor
    val navigationBarBackground = chromeBarColor
    val lightStatusBarIcons = chromeBarColor.luminance() > 0.5f
    val lightNavigationBarIcons = lightStatusBarIcons
    val themeBackgroundIsLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val direction = book.direction ?: settings.readingDirection
    // 翻页/滚动位置用 derivedStateOf 包起来，并且只在底栏、页码浮层这些小子作用域里读取：
    // 否则每翻一页都会让整个 ReaderContent（含 Pager / LazyColumn）重组一次。
    val liveModeForPage by rememberUpdatedState(settings.readerMode)
    val currentPageState = remember {
        derivedStateOf {
            if (liveModeForPage == ReaderMode.PAGED) pagerState.currentPage else listState.firstVisibleItemIndex
        }
    }

    // Compose never restarts a running pointerInput block just because captured values changed, and
    // detectTapGestures loops forever, so the tap handler would otherwise keep the page index it saw
    // when the reader opened (usually 0) and jump back to the top on every tap. Keep the values it
    // needs in state holders, and read the live scroll state at call time.
    val liveMode = rememberUpdatedState(settings.readerMode)
    val liveCount = rememberUpdatedState(count)
    val liveZoomed = rememberUpdatedState(zoomed)
    // The decode width can change without the mode changing (multi-window resize), so the prefetch
    // collector must read it live rather than capture the value from when the effect started.
    val livePageTarget = rememberUpdatedState(pageTarget)

    // Only subscribe to the scroll state that is actually driving the current mode; the other one
    // is not being scrolled and would just burn a collector.
    LaunchedEffect(settings.readerMode) {
        val vertical = settings.readerMode == ReaderMode.VERTICAL
        // Align the newly active container with where the reader was before switching modes.
        if (vertical) {
            listState.scrollToItem(pagerState.currentPage.coerceIn(0, count - 1))
        } else {
            pagerState.scrollToPage(listState.firstVisibleItemIndex.coerceIn(0, count - 1))
        }
        zoomed = false
        zoomResetToken += 1
        if (vertical) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { index ->
                    vm.updateProgress(index)
                    // Vertical reading only advances downwards: warm the next two pages.
                    vm.prefetchPages(listOf(index + 1, index + 2), livePageTarget.value)
                }
        } else {
            snapshotFlow { pagerState.settledPage }
                .distinctUntilChanged()
                .collect { page ->
                    vm.updateProgress(page)
                    // Horizontal paging: warm both neighbours, nearest first (±1 before ±2).
                    vm.prefetchPages(
                        listOf(page - 1, page + 1, page - 2, page + 2),
                        livePageTarget.value,
                    )
                }
        }
    }

    // A zoomed-in page disables horizontal paging; once the pager settles on a (new) page, drop the
    // zoom state so side swipes work again. This also resets the retained neighbours' scale.
    LaunchedEffect(pagerState.settledPage) {
        zoomed = false
        zoomResetToken += 1
    }

    DisposableEffect(settings.keepScreenOn) {
        val window = (view.context as? Activity)?.window
        if (settings.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 切后台 / 退出时立即把阅读进度落盘（平时是 1.5s 节流写 progress.json）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.flushProgress()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.flushProgress()
        }
    }

    val activityWindow = remember(view) { (view.context as? Activity)?.window }
    val insetsController = remember(view) {
        activityWindow?.let { WindowCompat.getInsetsController(it, view) }
    }
    DisposableEffect(insetsController) {
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            insetsController?.isAppearanceLightStatusBars = themeBackgroundIsLight
            insetsController?.isAppearanceLightNavigationBars = themeBackgroundIsLight
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
    // Re-applied on every composition, after the theme's own SideEffect, so a reader background
    // change (including a custom colour that stays in the same brightness bucket) always wins.
    SideEffect {
        insetsController?.isAppearanceLightStatusBars = lightStatusBarIcons
        insetsController?.isAppearanceLightNavigationBars = lightNavigationBarIcons
    }

    fun currentPageIndex(): Int =
        if (liveMode.value == ReaderMode.PAGED) pagerState.currentPage else listState.firstVisibleItemIndex

    fun goTo(page: Int) {
        val target = page.coerceIn(0, liveCount.value - 1)
        val mode = liveMode.value
        scope.launch {
            if (mode == ReaderMode.PAGED) pagerState.animateScrollToPage(target)
            else listState.animateScrollToItem(target)
        }
    }

    fun step(delta: Int) = goTo(currentPageIndex() + delta)

    // Decode the target page first, then jump instantly. Otherwise the pager briefly shows a
    // loading placeholder when a slider tap lands on an uncached page.
    fun jumpTo(page: Int) {
        val target = page.coerceIn(0, liveCount.value - 1)
        val mode = liveMode.value
        scope.launch {
            vm.loadPage(target, pageTarget)
            if (mode == ReaderMode.PAGED) pagerState.scrollToPage(target)
            else listState.scrollToItem(target)
        }
    }

    LaunchedEffect(volumePageEvents) {
        volumePageEvents?.collect { delta ->
            verticalScale = 1f
            verticalOffset = Offset.Zero
            verticalZoomed = false
            step(delta)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(readerBackground),
    ) {
        val chromeToggleLabel =
            stringResource(if (chromeVisible) R.string.reader_hide_controls else R.string.reader_show_controls)
        val readingAreaLabel = stringResource(R.string.reader_reading_area)
        val previousPageLabel = stringResource(R.string.reader_action_previous_page)
        val nextPageLabel = stringResource(R.string.reader_action_next_page)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(settings.readerMode, direction, verticalZoomed) {
                    detectTapGestures { offset ->
                        // While a settings panel is open, a tap on the page only dismisses that
                        // panel; it must not turn the page (side thirds included).
                        if (activePanel != ReaderChromePanel.NONE) {
                            activePanel = ReaderChromePanel.NONE
                            return@detectTapGestures
                        }
                        // A zoomed page consumes drags; taps must not turn the page underneath it.
                        if (liveZoomed.value) return@detectTapGestures
                        val third = size.width / 3f
                        when {
                            // Vertical scrolling: side thirds are inert, the middle toggles chrome.
                            // A single tap never resets the zoom; only pinching back to 1x does.
                            settings.readerMode == ReaderMode.VERTICAL -> {
                                if (offset.x >= third && offset.x <= size.width - third) {
                                    chromeVisible = !chromeVisible
                                }
                            }
                            offset.x < third -> {
                                if (direction == ReadingDirection.RTL) step(1) else step(-1)
                            }
                            offset.x > size.width - third -> {
                                if (direction == ReadingDirection.RTL) step(-1) else step(1)
                            }
                            else -> chromeVisible = !chromeVisible
                        }
                    }
                }
                .semantics {
                    contentDescription = readingAreaLabel
                    customActions = listOf(
                        CustomAccessibilityAction(previousPageLabel) {
                            step(-1)
                            true
                        },
                        CustomAccessibilityAction(nextPageLabel) {
                            step(1)
                            true
                        },
                        CustomAccessibilityAction(chromeToggleLabel) {
                            chromeVisible = !chromeVisible
                            true
                        },
                    )
                },
        ) {
            if (settings.readerMode == ReaderMode.PAGED) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = direction == ReadingDirection.RTL,
                    beyondViewportPageCount = 1,
                    userScrollEnabled = !zoomed,
                    key = { it },
                ) { index ->
                    PagedPage(
                        vm = vm,
                        index = index,
                        targetWidth = pageTarget,
                        colorFilter = pageFilter,
                        resetToken = zoomResetToken,
                        animateAppear = allowPageFade,
                        onZoomChanged = { zoomed = it },
                    )
                }
            } else {
                // One graphics layer over the whole lazy list: scaling the viewport keeps the list
                // virtualized, so every visible page zooms together with a single GPU transform.
                Box(
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(settings.readerMode) {
                            // Pan within the zoomed viewport. Once the translation reaches an edge,
                            // hand the remaining delta to the lazy list so zoomed reading can keep
                            // scrolling through the book instead of being pinned to one viewport.
                            fun panWithinBounds(pan: Offset) {
                                val maxX = size.width * (verticalScale - 1f) / 2f
                                val maxY = size.height * (verticalScale - 1f) / 2f
                                verticalOffset = Offset(
                                    (verticalOffset.x + pan.x).coerceIn(-maxX, maxX),
                                    (verticalOffset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            }
                            fun panAndScroll(pan: Offset) {
                                val maxX = size.width * (verticalScale - 1f) / 2f
                                val maxY = size.height * (verticalScale - 1f) / 2f
                                val desiredY = verticalOffset.y + pan.y
                                val clampedY = desiredY.coerceIn(-maxY, maxY)
                                val leftover = desiredY - clampedY
                                val consumed = if (leftover != 0f) {
                                    listState.dispatchRawDelta(-leftover / verticalScale)
                                } else {
                                    0f
                                }
                                verticalOffset = Offset(
                                    (verticalOffset.x + pan.x).coerceIn(-maxX, maxX),
                                    (desiredY - consumed * verticalScale).coerceIn(-maxY, maxY),
                                )
                            }
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var dragged = 0f
                                var event = awaitPointerEvent()
                                while (event.changes.any { it.pressed }) {
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed > 1) {
                                        val nextScale = (verticalScale * (1f + (event.calculateZoom() - 1f) * ZOOM_SCALE_GAIN))
                                            .coerceIn(1f, 4f)
                                        if (nextScale <= 1.01f) {
                                            verticalScale = 1f
                                            verticalOffset = Offset.Zero
                                            verticalZoomed = false
                                        } else {
                                            panWithinBounds(event.calculatePan() * ZOOM_PAN_GAIN)
                                            verticalScale = nextScale
                                            verticalZoomed = true
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else if (verticalZoomed && pressed == 1) {
                                        // Pan the zoomed viewport; small taps are left to the
                                        // tap detector so they can toggle the chrome.
                                        val pan = event.calculatePan() * ZOOM_PAN_GAIN
                                        dragged += pan.getDistance()
                                        if (dragged > viewConfiguration.touchSlop * 0.6f) {
                                            panAndScroll(pan)
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                    event = awaitPointerEvent()
                                }
                            }
                        },
                ) {
                    BoxWithConstraints(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = verticalScale
                                scaleY = verticalScale
                                translationX = verticalOffset.x
                                translationY = verticalOffset.y
                            },
                    ) {
                        val viewportHeightPx = constraints.maxHeight
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            userScrollEnabled = !verticalZoomed,
                        ) {
                            items(count) { index ->
                                VerticalPage(
                                    vm = vm,
                                    index = index,
                                    targetWidth = pageTarget,
                                    colorFilter = pageFilter,
                                    animateAppear = allowPageFade,
                                    tallBook = tallPages,
                                    viewportHeightPx = viewportHeightPx,
                                )
                            }
                        }
                    }
                }
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
                    vm.updateProgress(currentPageIndex())
                    onBack()
                },
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
                ReaderBottomBar(
                    progress = {
                        if (count > 1) currentPageState.value.toFloat() / (count - 1).toFloat() else 0f
                    },
                    label = {
                        val percent = if (count > 1) {
                            (currentPageState.value.toFloat() / (count - 1).toFloat() * 100f)
                                .roundToInt()
                                .coerceIn(0, 100)
                        } else {
                            0
                        }
                        stringResource(
                            R.string.reader_progress_combo,
                            percent,
                            currentPageState.value + 1,
                            count,
                        )
                    },
                    onJump = { fraction ->
                        jumpTo((fraction * (count - 1)).roundToInt().coerceIn(0, count - 1))
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
                    onOpenToc = null,
                    onOpenSettings = {
                        context.startActivity(Intent(context, ReaderSettingsActivity::class.java))
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
                // Same flat bottom surface as the toolbar: no separate slide/expand animation.
                when (activePanel) {
                    ReaderChromePanel.THEME -> ReaderThemePanel(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        onCustomBackground = { showCustomBackground = true },
                        accentColor = readerAccent,
                        containerColor = chromeBarColor,
                        contentColor = chromeContentColor,
                    )

                    else -> Unit
                }
                }
            }
        }

        // WeChat-Reading-style page indicator: quiet bottom-end "x / y" while the chrome is hidden.
        AnimatedVisibility(
            visible = !chromeVisible && settings.readerPageInfo != ReaderPageInfo.HIDDEN,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 12.dp),
        ) {
            PageNumberIndicator(
                page = currentPageState,
                count = count,
                mode = settings.readerPageInfo,
                textSizeSp = settings.readerPageIndicatorSizeSp,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
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

/** Blends [overlay] on top of the opaque [base] colour, returning an opaque result. */
internal fun blendOver(base: Color, overlay: Color): Color {
    val alpha = overlay.alpha.coerceIn(0f, 1f)
    return Color(
        red = overlay.red * alpha + base.red * (1f - alpha),
        green = overlay.green * alpha + base.green * (1f - alpha),
        blue = overlay.blue * alpha + base.blue * (1f - alpha),
    )
}

/** 无 chrome 时的页码/进度：右下角低调文字（参考微信读书），可在设置里切换显示内容。 */
@Composable
private fun PageNumberIndicator(page: State<Int>, count: Int, mode: ReaderPageInfo, textSizeSp: Int) {
    val currentPage = page.value.coerceIn(0, (count - 1).coerceAtLeast(0))
    val progress = if (count > 1) currentPage.toFloat() / (count - 1).toFloat() else 0f
    val label = if (mode == ReaderPageInfo.PAGES) {
        stringResource(R.string.reader_page_indicator, currentPage + 1, count)
    } else {
        stringResource(R.string.reader_percent, (progress * 100f).roundToInt().coerceIn(0, 100))
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = textSizeSp.sp,
            fontFeatureSettings = "tnum",
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
}

/**
 * Wrapper that keeps the progress-panel state reads inside its own recomposition scope: reading
 * [pageState] in [ReaderContent] itself would recompose the whole pager on every page turn.
 */
@Composable
private fun ImageReaderProgressPanel(
    pageState: State<Int>,
    count: Int,
    onJump: (Int) -> Unit,
) {
    val page = pageState.value.coerceIn(0, (count - 1).coerceAtLeast(0))
    val progress = if (count > 1) page.toFloat() / (count - 1).toFloat() else 0f
    ReaderProgressPanel(
        progress = progress,
        percentLabel = stringResource(R.string.reader_percent, (progress * 100f).roundToInt().coerceIn(0, 100)),
        pageLabel = stringResource(R.string.reader_page_indicator, page + 1, count),
        onJump = { fraction ->
            onJump((fraction * (count - 1)).roundToInt().coerceIn(0, count - 1))
        },
    )
}

private sealed interface PageState {
    data object Loading : PageState
    data class Ready(val image: ImageBitmap) : PageState
    data object Failed : PageState
}

@Composable
private fun rememberPageState(vm: ReaderViewModel, index: Int, targetWidth: Int): PageState {
    val initial = remember(vm, index, targetWidth) {
        vm.peekPage(index, targetWidth)?.let { PageState.Ready(it.asImageBitmap()) } ?: PageState.Loading
    }
    val state by produceState<PageState>(initial, vm, index, targetWidth) {
        if (value is PageState.Ready) return@produceState
        val bitmap = vm.loadPage(index, targetWidth)
        value = if (bitmap != null) PageState.Ready(bitmap.asImageBitmap()) else PageState.Failed
    }
    return state
}

@Composable
private fun PagedPage(
    vm: ReaderViewModel,
    index: Int,
    targetWidth: Int,
    colorFilter: ColorFilter?,
    resetToken: Int,
    animateAppear: Boolean,
    onZoomChanged: (Boolean) -> Unit,
) {
    val state = rememberPageState(vm, index, targetWidth)
    when (state) {
        PageState.Loading -> PagePlaceholder(index)
        PageState.Failed -> PageError(index)
        is PageState.Ready -> ZoomablePage(state.image, index, colorFilter, resetToken, animateAppear, onZoomChanged)
    }
}

@Composable
private fun VerticalPage(
    vm: ReaderViewModel,
    index: Int,
    targetWidth: Int,
    colorFilter: ColorFilter?,
    animateAppear: Boolean,
    tallBook: Boolean,
    viewportHeightPx: Int,
) {
    if (tallBook && viewportHeightPx > 0) {
        TallVerticalPage(vm, index, colorFilter, viewportHeightPx)
        return
    }
    val state = rememberPageState(vm, index, targetWidth)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        when (state) {
            PageState.Loading -> Box(Modifier.fillMaxWidth().height(maxWidth * 1.45f)) { PagePlaceholder(index) }
            PageState.Failed -> Box(Modifier.fillMaxWidth().height(maxWidth * 1.2f)) { PageError(index) }
            is PageState.Ready -> {
                val ratio = state.image.height.toFloat() / state.image.width.toFloat()
                val appearAlpha = rememberPageAppearAlpha(index, animateAppear)
                Image(
                    bitmap = state.image,
                    contentDescription = stringResource(R.string.reader_page_number, index + 1),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(maxWidth * ratio)
                        .graphicsLayer(alpha = appearAlpha),
                    contentScale = ContentScale.FillWidth,
                    colorFilter = colorFilter,
                )
            }
        }
    }
}

/**
 * 条漫页的分块渲染：只解出当前可见区域（上下各留一段缓冲），滚出缓冲后重新解一块。
 * 这样 800×20000 的长图既不会整块解码成几十 MB 位图，也不会因为降采样而糊。
 */
private data class RegionSlice(val bitmap: Bitmap, val topPx: Float, val heightPx: Float)

@Composable
private fun TallVerticalPage(
    vm: ReaderViewModel,
    index: Int,
    colorFilter: ColorFilter?,
    viewportHeightPx: Int,
) {
    val size by produceState<Size?>(null, vm, index) { value = vm.pageSize(index) }
    val pageSize = size
    if (pageSize == null) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(maxWidth * 1.45f)) { PagePlaceholder(index) }
        }
        return
    }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth.toFloat()
        val pageHeightPx = widthPx * (pageSize.height.toFloat() / pageSize.width.toFloat())
        val pageHeightDp = with(density) { pageHeightPx.toDp() }
        var regionPage by remember(index) { mutableStateOf<RegionPage?>(null) }
        var slice by remember(index) { mutableStateOf<RegionSlice?>(null) }
        // Scroll position is only quantised into buckets, so scrolling does not recompose per frame.
        var bucket by remember(index) { mutableIntStateOf(-1) }

        LaunchedEffect(index) { regionPage = vm.openRegionPage(index) }
        DisposableEffect(index) {
            onDispose {
                regionPage?.close()
                regionPage = null
            }
        }

        val rp = regionPage
        LaunchedEffect(rp, pageHeightPx) {
            val page = rp ?: return@LaunchedEffect
            snapshotFlow { bucket }
                .distinctUntilChanged()
                .collect { requestedBucket ->
                    if (requestedBucket < 0 || pageHeightPx <= 0f) return@collect
                    // Do not cancel a blocking BitmapRegionDecoder call every time another bucket
                    // arrives. Keep one decode in flight, drop stale results, then decode only the
                    // latest visible bucket; repeated cancellation used to pile up obsolete native
                    // decodes during a fast fling and leave the viewport waiting for the next tile.
                    val decodeBucket = bucket
                    val scale = pageSize.height / pageHeightPx
                    val viewport = viewportHeightPx.toFloat()
                    val viewportImagePx = viewport * scale
                    val bucketTopDisplay = decodeBucket * viewport * 0.75f
                    val marginDisplay = viewport * 0.75f
                    val top = ((bucketTopDisplay - marginDisplay) * scale)
                        .toInt().coerceIn(0, pageSize.height - 1)
                    val bottom = ((bucketTopDisplay + viewport + marginDisplay) * scale)
                        .toInt().coerceIn(top + 1, pageSize.height)
                    // 优先保持解码清晰度：区域过高时裁剪缓冲区高度，而不是提高 inSampleSize。
                    val maxPixels = 12L * 1024L * 1024L / 4L
                    val maxChunkHeight = (maxPixels / pageSize.width).toInt()
                        .coerceAtLeast(viewportImagePx.toInt().coerceAtLeast(1))
                    val chunkBottom = min(bottom, top + maxChunkHeight).coerceAtLeast(top + 1)
                    val rect = Rect(0, top, pageSize.width, chunkBottom)
                    val sample = regionSample(rect.width(), rect.height())
                    val decoded = withContext(Dispatchers.IO) { page.decodeRegion(rect, sample) }
                    if (decoded != null && decodeBucket == bucket) {
                        slice = RegionSlice(decoded, top / scale, rect.height() / scale)
                    }
                }
            }

        Box(
            Modifier
                .fillMaxWidth()
                .height(pageHeightDp)
                .onGloballyPositioned { coordinates ->
                    val topInWindow = coordinates.positionInWindow().y
                    val visibleTop = (-topInWindow).coerceIn(0f, pageHeightPx)
                    val next = if (viewportHeightPx > 0) {
                        (visibleTop / (viewportHeightPx * 0.75f)).toInt()
                    } else {
                        0
                    }
                    if (next != bucket) bucket = next
                },
        ) {
            val current = slice
            if (current != null) {
                Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.reader_page_number, index + 1),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { current.heightPx.toDp() })
                        .offset(y = with(density) { current.topPx.toDp() }),
                    contentScale = ContentScale.FillWidth,
                    colorFilter = colorFilter,
                )
            } else {
                PagePlaceholder(index)
            }
        }
    }
}

/** 单块区域解码的采样：保证区域位图不超过约 12MB，同时尽量保持清晰。 */
private fun regionSample(width: Int, height: Int): Int {
    var sample = 1
    while (sample < 8) {
        val pixels = (width.toLong() / sample) * (height.toLong() / sample)
        if (pixels * 4L <= 12L * 1024L * 1024L) break
        sample *= 2
    }
    return sample
}

/** Fades a freshly decoded page in over ~150ms; cached pages appear with the same subtle fade. */
@Composable
private fun rememberPageAppearAlpha(index: Int, animate: Boolean): Float {
    var visible by remember(index, animate) { mutableStateOf(!animate) }
    LaunchedEffect(index, animate) { if (animate) visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "page-fade-alpha",
    )
    return alpha
}

@Composable
private fun PagePlaceholder(index: Int) {
    // Show the spinner only if decoding actually takes a moment; a one-frame flash for pages that
    // decode immediately is what made the reader look like it was flickering.
    var showIndicator by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(90)
        showIndicator = true
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showIndicator) SaltLoading(sizeDp = 40)
            if (showIndicator) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.reader_page_number, index + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun PageError(index: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.reader_page_failed, index + 1),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ZoomablePage(
    image: ImageBitmap,
    index: Int,
    colorFilter: ColorFilter?,
    resetToken: Int,
    animateAppear: Boolean,
    onZoomChanged: (Boolean) -> Unit,
) {
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val appearAlpha = rememberPageAppearAlpha(index, animateAppear)
    // Reset the transform when the pager settles on another page (or the mode changes), so a
    // retained page can never come back zoomed and keep horizontal paging disabled.
    LaunchedEffect(resetToken) {
        if (resetToken != 0 && (scale > 1.01f || offset != Offset.Zero)) {
            scale = 1f
            offset = Offset.Zero
            onZoomChanged(false)
        }
    }
    DisposableEffect(index) {
        onDispose {
            if (scale > 1.01f) onZoomChanged(false)
        }
    }
    Box(
        Modifier.fillMaxSize().onSizeChanged { containerSize = it }.pointerInput(index) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var event = awaitPointerEvent()
                while (event.changes.any { it.pressed }) {
                    val pointerCount = event.changes.count { it.pressed }
                    if (pointerCount > 1 || scale > 1.01f) {
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val nextScale = (scale * (1f + (zoomChange - 1f) * ZOOM_SCALE_GAIN))
                            .coerceIn(1f, 4f)
                        if (nextScale <= 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = nextScale
                            val maxX = containerSize.width * (nextScale - 1f) / 2f
                            val maxY = containerSize.height * (nextScale - 1f) / 2f
                            offset = Offset(
                                (offset.x + panChange.x * ZOOM_PAN_GAIN).coerceIn(-maxX, maxX),
                                (offset.y + panChange.y * ZOOM_PAN_GAIN).coerceIn(-maxY, maxY),
                            )
                        }
                        event.changes.forEach { it.consume() }
                        onZoomChanged(scale > 1.01f)
                    }
                    event = awaitPointerEvent()
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = image,
            contentDescription = stringResource(R.string.reader_page_number, index + 1),
            modifier = Modifier.fillMaxSize().graphicsLayer(
                alpha = appearAlpha,
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            ),
            contentScale = ContentScale.Fit,
            colorFilter = colorFilter,
        )
    }
}

private fun readerColorFilter(grayscale: Boolean, invert: Boolean): ColorFilter? {
    if (!grayscale && !invert) return null
    val lr = if (grayscale) 0.2126f else 1f
    val lg = if (grayscale) 0.7152f else 0f
    val lb = if (grayscale) 0.0722f else 0f
    val sign = if (invert) -1f else 1f
    val offset = if (invert) 255f else 0f
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                sign * lr, sign * lg, sign * lb, 0f, offset,
                sign * lr, sign * lg, sign * lb, 0f, offset,
                sign * lr, sign * lg, sign * lb, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
}

// Shared zoom feel for the image reader (both paged and continuous modes).
private const val ZOOM_SCALE_GAIN = 1.08f
private const val ZOOM_PAN_GAIN = 1.15f
