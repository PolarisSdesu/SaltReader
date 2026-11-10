package moe.gbxin.saltreader.ui.reader

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.AppSettings
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.ReaderBackground
import moe.gbxin.saltreader.data.ReaderMode
import moe.gbxin.saltreader.data.ReadingDirection
import moe.gbxin.saltreader.ui.components.CustomColorPickerDialog
import moe.gbxin.saltreader.ui.components.SaltLoading
import moe.gbxin.saltreader.ui.theme.pageBackgroundColor

/**
 * Reader entry point. Renders one of three top-level states instead of assuming the book is
 * already in the library snapshot: [ReaderUiState.Loading] while the index is still publishing,
 * [ReaderUiState.Missing] only when the book is definitively gone, and the actual reader once the
 * book (and its pre-computed page count) is available.
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
        ReaderUiState.Loading -> ReaderLoading(modifier)

        ReaderUiState.Missing -> {
            // The book vanished from the index while the reader was open; show why, then return to
            // the shelf. The button lets the reader leave immediately.
            LaunchedEffect(Unit) {
                delay(1_600)
                onBack()
            }
            ReaderMessage(
                title = "无法打开",
                message = "这本书已经不在书架上。",
                onBack = onBack,
                modifier = modifier,
            )
        }

        is ReaderUiState.Ready -> {
            if (state.book.pageCount <= 0) {
                // Indexed book with no readable pages: this is not "page 1 failed", there is simply
                // nothing to display. Never render the pager with a fake single page.
                ReaderMessage(
                    title = "没有可读的页面",
                    message = "这本书里没有找到支持的图片或页面。",
                    onBack = onBack,
                    modifier = modifier,
                )
            } else {
                ReaderContent(
                    vm = vm,
                    book = state.book,
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
private fun ReaderLoading(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SaltLoading(sizeDp = 36)
            Spacer(Modifier.height(12.dp))
            Text(
                "正在打开…",
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
            FilledTonalButton(onClick = onBack) { Text("回到书架") }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    vm: ReaderViewModel,
    book: ComicBook,
    settings: AppSettings,
    onBack: () -> Unit,
    volumePageEvents: Flow<Int>?,
    modifier: Modifier = Modifier,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    // pageCount is pre-computed during indexing; the reader never enumerates pages itself.
    val count = max(1, book.pageCount)
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

    val readerBackground = pageBackgroundColor(settings.readerBackground, settings.customBackgroundColor)
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
                .collect { vm.updateProgress(it) }
        } else {
            snapshotFlow { pagerState.settledPage }
                .distinctUntilChanged()
                .collect { vm.updateProgress(it) }
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
        volumePageEvents?.collect { delta -> step(delta) }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(readerBackground),
    ) {
        val chromeToggleLabel = if (chromeVisible) "隐藏工具栏" else "显示工具栏"
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(settings.readerMode, direction) {
                    detectTapGestures { offset ->
                        // A zoomed page consumes drags; taps must not turn the page underneath it.
                        if (liveZoomed.value) return@detectTapGestures
                        val third = size.width / 3f
                        when {
                            // Product decision: in vertical scrolling the side thirds are inert.
                            // Only the middle third toggles the chrome; scrolling stays vertical.
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
                    contentDescription = "阅读区域"
                    customActions = listOf(
                        CustomAccessibilityAction("上一页") {
                            step(-1)
                            true
                        },
                        CustomAccessibilityAction("下一页") {
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
                        onZoomChanged = { zoomed = it },
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(count) { index ->
                        VerticalPage(vm = vm, index = index, targetWidth = pageTarget, colorFilter = pageFilter)
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
                    "${currentPage + 1} / $count",
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
            onGoToPage = { jumpTo(it) },
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
private fun blendOver(base: Color, overlay: Color): Color {
    val alpha = overlay.alpha.coerceIn(0f, 1f)
    return Color(
        red = overlay.red * alpha + base.red * (1f - alpha),
        green = overlay.green * alpha + base.green * (1f - alpha),
        blue = overlay.blue * alpha + base.blue * (1f - alpha),
    )
}

@Composable
private fun ReaderTopBar(book: ComicBook, onBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
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
    var sliderValue by remember { mutableStateOf<Float?>(null) }
    var pendingJump by remember { mutableStateOf<Int?>(null) }
    var lastTickPage by remember { mutableStateOf(currentPage) }
    val haptics = LocalHapticFeedback.current
    val sliderMax = (count - 1).coerceAtLeast(1)
    LaunchedEffect(currentPage, pendingJump) {
        if (pendingJump == currentPage) {
            pendingJump = null
            sliderValue = null
            lastTickPage = currentPage
        }
    }
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
                    contentDescription = if (mode == ReaderMode.PAGED) "切换为纵向滚动" else "切换为横向翻页",
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Slider(
                    value = (sliderValue ?: currentPage.toFloat()).coerceIn(0f, sliderMax.toFloat()),
                    onValueChange = {
                        pendingJump = null
                        val page = it.roundToInt().coerceIn(0, count - 1)
                        if (page != lastTickPage) {
                            lastTickPage = page
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                        sliderValue = it
                    },
                    onValueChangeFinished = {
                        val target = sliderValue?.roundToInt()?.coerceIn(0, count - 1)
                        if (target != null) {
                            pendingJump = target
                            onGoToPage(target)
                        }
                    },
                    // A single-page book has no meaningful range; disable instead of a 0..1 slider.
                    enabled = count > 1,
                    valueRange = 0f..sliderMax.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(sliderValue?.roundToInt() ?: currentPage).coerceIn(0, count - 1) + 1} / $count",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Tune, contentDescription = "阅读设置")
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
    onZoomChanged: (Boolean) -> Unit,
) {
    val state = rememberPageState(vm, index, targetWidth)
    when (state) {
        PageState.Loading -> PagePlaceholder(index)
        PageState.Failed -> PageError(index)
        is PageState.Ready -> ZoomablePage(state.image, index, colorFilter, resetToken, onZoomChanged)
    }
}

@Composable
private fun VerticalPage(
    vm: ReaderViewModel,
    index: Int,
    targetWidth: Int,
    colorFilter: ColorFilter?,
) {
    val state = rememberPageState(vm, index, targetWidth)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        when (state) {
            PageState.Loading -> Box(Modifier.fillMaxWidth().height(maxWidth * 1.45f)) { PagePlaceholder(index) }
            PageState.Failed -> Box(Modifier.fillMaxWidth().height(maxWidth * 1.2f)) { PageError(index) }
            is PageState.Ready -> {
                val ratio = state.image.height.toFloat() / state.image.width.toFloat()
                Image(
                    bitmap = state.image,
                    contentDescription = "第 ${index + 1} 页",
                    modifier = Modifier.fillMaxWidth().height(maxWidth * ratio),
                    contentScale = ContentScale.FillWidth,
                    colorFilter = colorFilter,
                )
            }
        }
    }
}

@Composable
private fun PagePlaceholder(index: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SaltLoading(sizeDp = 32)
            Spacer(Modifier.height(10.dp))
            Text("第 ${index + 1} 页", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun PageError(index: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("第 ${index + 1} 页读不出来", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ZoomablePage(
    image: ImageBitmap,
    index: Int,
    colorFilter: ColorFilter?,
    resetToken: Int,
    onZoomChanged: (Boolean) -> Unit,
) {
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
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
                        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
                        if (nextScale <= 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = nextScale
                            val maxX = containerSize.width * (nextScale - 1f) / 2f
                            val maxY = containerSize.height * (nextScale - 1f) / 2f
                            offset = Offset(
                                (offset.x + panChange.x).coerceIn(-maxX, maxX),
                                (offset.y + panChange.y).coerceIn(-maxY, maxY),
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
            contentDescription = "第 ${index + 1} 页",
            modifier = Modifier.fillMaxSize().graphicsLayer(
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
private fun ReaderControlsSheet(
    currentPage: Int,
    count: Int,
    settings: AppSettings,
    direction: ReadingDirection,
    onDirection: (ReadingDirection) -> Unit,
    onGoToPage: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onCustomBackground: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sliderValue by remember { mutableStateOf<Float?>(null) }
    var pendingJump by remember { mutableStateOf<Int?>(null) }
    var lastTickPage by remember { mutableStateOf(currentPage) }
    val sliderMax = (count - 1).coerceAtLeast(1)
    val haptics = LocalHapticFeedback.current
    val tabIndicator by androidx.compose.animation.core.animateFloatAsState(
        targetValue = tab.toFloat(),
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 380f),
        label = "reader-tab-indicator",
    )
    LaunchedEffect(currentPage, pendingJump) {
        if (pendingJump == currentPage) {
            pendingJump = null
            sliderValue = null
            lastTickPage = currentPage
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .animateContentSize(
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 320f),
                )
                .verticalScroll(rememberScrollState()),
        ) {
            Text("阅读设置", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))
            Slider(
                value = (sliderValue ?: currentPage.toFloat()).coerceIn(0f, sliderMax.toFloat()),
                onValueChange = {
                    pendingJump = null
                    val page = it.roundToInt().coerceIn(0, count - 1)
                    if (page != lastTickPage) {
                        lastTickPage = page
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    sliderValue = it
                },
                onValueChangeFinished = {
                    val target = sliderValue?.roundToInt()?.coerceIn(0, count - 1)
                    if (target != null) {
                        pendingJump = target
                        onGoToPage(target)
                    }
                },
                enabled = count > 1,
                valueRange = 0f..sliderMax.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "第 ${(sliderValue?.roundToInt() ?: currentPage).coerceIn(0, count - 1) + 1} / $count 页",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            ReaderTabRow(
                selectedTab = tab,
                indicatorPosition = tabIndicator,
                onSelect = { index -> tab = index },
            )
            Spacer(Modifier.height(20.dp))
            when (tab) {
                0 -> {
                    ChoiceGroup(
                        title = "阅读模式",
                        options = ReaderMode.entries.map { it to if (it == ReaderMode.PAGED) "横向翻页" else "纵向滚动" },
                        selected = settings.readerMode,
                        onSelect = { mode -> onSettingsChange { it.copy(readerMode = mode) } },
                    )
                    Spacer(Modifier.height(18.dp))
                    ChoiceGroup(
                        title = "翻页方向",
                        options = ReadingDirection.entries.map { it to if (it == ReadingDirection.LTR) "从左到右" else "从右到左" },
                        selected = direction,
                        onSelect = onDirection,
                    )
                }
                1 -> {
                    ChoiceGroup(
                        title = "背景颜色",
                        options = listOf(
                            ReaderBackground.WHITE to "纯白",
                            ReaderBackground.INK to "纯黑",
                            ReaderBackground.CUSTOM to "自定义",
                        ),
                        selected = settings.readerBackground,
                        onSelect = { background ->
                            if (background == ReaderBackground.CUSTOM) onCustomBackground()
                            else onSettingsChange { it.copy(readerBackground = background) }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ToggleRow("显示页码", settings.showPageNumber) {
                        onSettingsChange { it.copy(showPageNumber = it.showPageNumber.not()) }
                    }
                    ToggleRow("保持屏幕常亮", settings.keepScreenOn) {
                        onSettingsChange { it.copy(keepScreenOn = it.keepScreenOn.not()) }
                    }
                    ToggleRow("音量键翻页", settings.volumeKeyPaging) {
                        onSettingsChange { it.copy(volumeKeyPaging = it.volumeKeyPaging.not()) }
                    }
                }
                else -> {
                    ToggleRow("灰度模式", settings.grayscale) {
                        onSettingsChange { it.copy(grayscale = it.grayscale.not()) }
                    }
                    ToggleRow("反色", settings.invertColors) {
                        onSettingsChange { it.copy(invertColors = it.invertColors.not()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderTabRow(
    selectedTab: Int,
    indicatorPosition: Float,
    onSelect: (Int) -> Unit,
) {
    val labels = listOf("阅读模式", "常规", "滤镜")
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
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
