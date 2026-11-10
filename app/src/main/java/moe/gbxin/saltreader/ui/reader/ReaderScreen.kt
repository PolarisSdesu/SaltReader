package moe.gbxin.saltreader.ui.reader

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.DpOffset
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
import moe.gbxin.saltreader.R
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.AppSettings
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.READER_FONT_SIZE_MAX_SP
import moe.gbxin.saltreader.data.READER_FONT_SIZE_MIN_SP
import moe.gbxin.saltreader.data.ReaderBackground
import moe.gbxin.saltreader.data.ReaderMode
import moe.gbxin.saltreader.data.ReadingDirection
import moe.gbxin.saltreader.ui.components.CustomColorPickerDialog
import moe.gbxin.saltreader.ui.components.SaltLoading
import moe.gbxin.saltreader.ui.theme.pageBackgroundColor

/**
 * Reader entry point. Renders the top-level states instead of assuming the book is already usable:
 * [ReaderUiState.Loading] while the index is publishing or the image page source is being prepared,
 * [ReaderUiState.TextLoading] while a reflowable book's chapters are parsed, [ReaderUiState.Missing]
 * only when the book is definitively gone, [ReaderUiState.Failed] with a retry when opening the file
 * failed, and the actual reader once the page count / chapters are ready.
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

    when (val state = uiState) {
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

        ReaderUiState.TextLoading -> ReaderLoading(
            message = stringResource(R.string.reader_loading_parsing_ebook),
            modifier = modifier,
        )

        ReaderUiState.TextFailed -> ReaderFailed(
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

        ReaderUiState.Failed -> ReaderFailed(
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

@Composable
private fun ReaderLoading(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SaltLoading(sizeDp = 36)
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
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.reader_failed_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.reader_failed_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
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
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    // Use one decode width for both reading modes so switching modes hits the bitmap cache.
    val pageTarget = (screenWidthPx * 1.25f).toInt().coerceIn(720, 1800)

    // UI-only state survives rotation / process-death restore so a settings sheet that was open
    // (or a hidden toolbar) does not spring back to its default.
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
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
    )
    val pageFilter = remember(settings.grayscale, settings.invertColors) {
        readerColorFilter(settings.grayscale, settings.invertColors)
    }

    // System bar icons follow whatever is actually drawn behind them: the reader background when
    // the chrome is hidden, the blended top bar / bottom scrim when it is visible.
    val topBarColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    val statusBarBackground =
        if (chromeVisible) blendOver(readerBackground, topBarColor) else readerBackground
    val navigationBarBackground =
        if (chromeVisible) blendOver(readerBackground, Color.Black.copy(alpha = 0.38f)) else readerBackground
    val lightStatusBarIcons = statusBarBackground.luminance() > 0.5f
    val lightNavigationBarIcons = navigationBarBackground.luminance() > 0.5f
    val themeBackgroundIsLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val direction = book.direction ?: settings.readingDirection
    val currentPage =
        if (settings.readerMode == ReaderMode.PAGED) pagerState.currentPage else listState.firstVisibleItemIndex

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
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = verticalScale
                                scaleY = verticalScale
                                translationX = verticalOffset.x
                                translationY = verticalOffset.y
                            },
                    ) {
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
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                currentPage = currentPage,
                count = count,
                mode = settings.readerMode,
                onGoToPage = { page -> jumpTo(page) },
                onToggleMode = {
                    onSettingsChange {
                        it.copy(readerMode = if (it.readerMode == ReaderMode.PAGED) ReaderMode.VERTICAL else ReaderMode.PAGED)
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
                    stringResource(R.string.reader_page_indicator, currentPage + 1, count),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (showSettings) {
        ReaderControlsSheet(
            currentPage = currentPage,
            count = count,
            settings = settings,
            direction = direction,
            onDirection = { vm.setDirection(it) },
            onDismiss = { showSettings = false },
            onSettingsChange = onSettingsChange,
            onCustomBackground = { showCustomBackground = true },
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

/** Blends [overlay] on top of the opaque [base] colour, returning an opaque result. */
internal fun blendOver(base: Color, overlay: Color): Color {
    val alpha = overlay.alpha.coerceIn(0f, 1f)
    return Color(
        red = overlay.red * alpha + base.red * (1f - alpha),
        green = overlay.green * alpha + base.green * (1f - alpha),
        blue = overlay.blue * alpha + base.blue * (1f - alpha),
    )
}

@Composable
internal fun ReaderTopBar(book: ComicBook, onBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.reader_back))
            }
            Text(
                book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReaderBottomBar(
    currentPage: Int,
    count: Int,
    mode: ReaderMode,
    onGoToPage: (Int) -> Unit,
    onToggleMode: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var pendingJump by remember { mutableStateOf<Int?>(null) }
    var lastTickPage by remember { mutableStateOf(currentPage) }
    val haptics = LocalHapticFeedback.current
    val sliderMax = (count - 1).coerceAtLeast(1)
    val toggleModeLabel = stringResource(
        if (mode == ReaderMode.PAGED) R.string.reader_switch_to_vertical else R.string.reader_switch_to_paged,
    )
    val settingsLabel = stringResource(R.string.reader_settings_title)
    // Material3 1.5 state-based Slider: the state is the single source of truth for the thumb.
    val sliderState = rememberSliderState(
        value = currentPage.toFloat().coerceIn(0f, sliderMax.toFloat()),
        trackRange = 0f..sliderMax.toFloat(),
    )
    // Follow page changes made elsewhere (swipe, volume keys, tap zones) unless the user is actively
    // dragging or a slider jump is still animating.
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.38f))))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onToggleMode) {
                Icon(
                    if (mode == ReaderMode.PAGED) Icons.Rounded.ViewAgenda else Icons.Rounded.Swipe,
                    contentDescription = toggleModeLabel,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Slider(
                    state = sliderState,
                    onValueChange = { value ->
                        // The state-based overload leaves SliderState untouched when an
                        // onValueChange callback is supplied, so update it explicitly.
                        sliderState.value = value
                        pendingJump = null
                        val page = value.roundToInt().coerceIn(0, count - 1)
                        if (page != lastTickPage) {
                            lastTickPage = page
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    },
                    onValueChangeFinished = {
                        val target = sliderState.value.roundToInt().coerceIn(0, count - 1)
                        pendingJump = target
                        lastTickPage = target
                        onGoToPage(target)
                    },
                    // A single-page book has no meaningful range; disable instead of a 0..1 slider.
                    enabled = count > 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        R.string.reader_page_indicator,
                        sliderState.value.roundToInt().coerceIn(0, count - 1) + 1,
                        count,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Tune, contentDescription = settingsLabel)
            }
        }
    }
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
) {
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
            if (showIndicator) SaltLoading(sizeDp = 32)
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReaderControlsSheet(
    currentPage: Int,
    count: Int,
    settings: AppSettings,
    direction: ReadingDirection,
    onDirection: (ReadingDirection) -> Unit,
    onDismiss: () -> Unit,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onCustomBackground: () -> Unit = {},
    isTextBook: Boolean = false,
) {
    // Material3 1.5 replacement for the deprecated rememberModalBottomSheetState: restricting
    // enabledValues to Hidden/Expanded keeps the old skipPartiallyExpanded = true behaviour.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .animateContentSize(
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 320f),
                )
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.reader_settings_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))
            if (isTextBook) {
                Spacer(Modifier.height(6.dp))
            }
            ReaderMenuRow(
                title = stringResource(R.string.reader_section_mode),
                selected = settings.readerMode,
                options = listOf(
                    ReaderMode.PAGED to stringResource(R.string.reader_mode_paged),
                    ReaderMode.VERTICAL to stringResource(R.string.reader_mode_vertical),
                ),
                onSelect = { mode -> onSettingsChange { it.copy(readerMode = mode) } },
            )
            ReaderMenuRow(
                title = stringResource(R.string.reader_section_direction),
                selected = direction,
                options = listOf(
                    ReadingDirection.LTR to stringResource(R.string.reader_direction_ltr),
                    ReadingDirection.RTL to stringResource(R.string.reader_direction_rtl),
                ),
                onSelect = onDirection,
            )
            ReaderMenuRow(
                title = stringResource(R.string.reader_section_background),
                selected = settings.readerBackground,
                options = listOf(
                    ReaderBackground.AUTO to stringResource(R.string.reader_background_auto),
                    ReaderBackground.WHITE to stringResource(R.string.reader_background_white),
                    ReaderBackground.INK to stringResource(R.string.reader_background_ink),
                    ReaderBackground.CUSTOM to stringResource(R.string.reader_background_custom),
                ),
                onSelect = { background ->
                    if (background == ReaderBackground.CUSTOM) onCustomBackground()
                    else onSettingsChange { it.copy(readerBackground = background) }
                },
            )
            if (isTextBook) {
                Spacer(Modifier.height(10.dp))
                // Font size only affects the reflowable text reader; committed on release so
                // re-layout happens once per adjustment.
                val fontMin = READER_FONT_SIZE_MIN_SP.toFloat()
                val fontMax = READER_FONT_SIZE_MAX_SP.toFloat()
                val fontSliderState = rememberSliderState(
                    value = settings.readerFontSizeSp.toFloat().coerceIn(fontMin, fontMax),
                    steps = (READER_FONT_SIZE_MAX_SP - READER_FONT_SIZE_MIN_SP - 1).coerceAtLeast(0),
                    trackRange = fontMin..fontMax,
                )
                LaunchedEffect(settings.readerFontSizeSp) {
                    if (!fontSliderState.isDragging) {
                        fontSliderState.value =
                            settings.readerFontSizeSp.toFloat().coerceIn(fontMin, fontMax)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.reader_font_size), style = MaterialTheme.typography.titleSmall)
                Slider(
                    state = fontSliderState,
                    onValueChange = { value -> fontSliderState.value = value },
                    onValueChangeFinished = {
                        val sp = fontSliderState.value.roundToInt().coerceIn(
                            READER_FONT_SIZE_MIN_SP,
                            READER_FONT_SIZE_MAX_SP,
                        )
                        onSettingsChange { it.copy(readerFontSizeSp = sp) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        R.string.reader_font_size_value,
                        fontSliderState.value.roundToInt()
                            .coerceIn(READER_FONT_SIZE_MIN_SP, READER_FONT_SIZE_MAX_SP),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            ToggleRow(stringResource(R.string.reader_show_page_number), settings.showPageNumber) {
                onSettingsChange { it.copy(showPageNumber = it.showPageNumber.not()) }
            }
            ToggleRow(stringResource(R.string.reader_keep_screen_on), settings.keepScreenOn) {
                onSettingsChange { it.copy(keepScreenOn = it.keepScreenOn.not()) }
            }
            ToggleRow(stringResource(R.string.reader_volume_keys), settings.volumeKeyPaging) {
                onSettingsChange { it.copy(volumeKeyPaging = it.volumeKeyPaging.not()) }
            }
            Spacer(Modifier.height(10.dp))
            if (isTextBook) {
                Text(
                    stringResource(R.string.reader_text_filters_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ToggleRow(stringResource(R.string.reader_grayscale), settings.grayscale) {
                    onSettingsChange { it.copy(grayscale = it.grayscale.not()) }
                }
                ToggleRow(stringResource(R.string.reader_invert_colors), settings.invertColors) {
                    onSettingsChange { it.copy(invertColors = it.invertColors.not()) }
                }
            }
        }
    }
}

/** Menu-style option row used by the reader settings sheet (theme/language style dropdown). */
@Composable
private fun <T> ReaderMenuRow(
    title: String,
    selected: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { expanded = true }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                options.firstOrNull { it.first == selected }?.second.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val menuWidth = 220.dp
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(x = (maxWidth - menuWidth).coerceAtLeast(0.dp), y = 0.dp),
            modifier = Modifier.width(menuWidth),
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = if (value == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun ReaderTabRow(
    labels: List<String>,
    selectedTab: Int,
    indicatorPosition: Float,
    onSelect: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tabWidth = maxWidth / labels.size
        Column {
            Row(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { index, label ->
                    // `selectable` with Role.Tab exposes the selected state and the proper "tab"
                    // role to TalkBack, unlike a plain clickable Box.
                    Box(
                        Modifier
                            .weight(1f)
                            .selectable(
                                selected = selectedTab == index,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            )
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp)) {
                Box(
                    Modifier
                        .width(tabWidth)
                        .offset(x = tabWidth * indicatorPosition.coerceIn(0f, 2f))
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Composable
private fun <T> ChoiceGroup(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
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
