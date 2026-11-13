@file:OptIn(ExperimentalFoundationApi::class)
package moe.gbxin.saltreader.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.gbxin.saltreader.R
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.AppSettings
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.LibraryFilter
import moe.gbxin.saltreader.data.LIBRARY_META_TEXT_MAX_SP
import moe.gbxin.saltreader.data.LIBRARY_META_TEXT_MIN_SP
import moe.gbxin.saltreader.data.LibraryLayout
import moe.gbxin.saltreader.data.LibraryMetaField
import moe.gbxin.saltreader.data.LibrarySort
import moe.gbxin.saltreader.ui.components.ComicCover
import moe.gbxin.saltreader.ui.components.TooltipIconButton
import moe.gbxin.saltreader.ui.components.bookFormatLabel
import moe.gbxin.saltreader.ui.components.bookMetaLabel
import moe.gbxin.saltreader.ui.components.bookProgressLabel
import moe.gbxin.saltreader.ui.components.libraryFilterLabel
import moe.gbxin.saltreader.ui.components.libraryLayoutLabel
import moe.gbxin.saltreader.ui.components.librarySortLabel
import moe.gbxin.saltreader.ui.components.metaFieldLabel
import moe.gbxin.saltreader.ui.components.SaltLoading
import moe.gbxin.saltreader.util.formatBytes

/** 列表模式封面显示宽 64dp，约 200px 解码足够清晰，也避免按默认 640px 解码浪费内存。 */
private const val LIST_COVER_TARGET_WIDTH_PX = 200

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenBook: (ComicBook) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: LibraryViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val app = context.applicationContext as SaltReaderApplication
    val repo = app.library
    // 按字段订阅设置：整体订阅 settings 会让主题/阅读器等无关偏好变更也重组整个书库。
    val settingsState = app.settings.state
    val libraryLayout by rememberSettingField(settingsState) { it.libraryLayout }
    val libraryColumns by rememberSettingField(settingsState) { it.libraryColumns }
    val titleSizeSp by rememberSettingField(settingsState) { it.libraryTitleSizeSp }
    val metaVisible by rememberSettingField(settingsState) { it.libraryMetaVisible }
    val metaTextSizeSp by rememberSettingField(settingsState) { it.libraryMetaTextSizeSp }
    val metaFields by rememberSettingField(settingsState) { it.libraryMetaFields }
    val sortAscending by rememberSettingField(settingsState) { it.librarySortAscending }
    val snackbar = remember { SnackbarHostState() }
    var showLayoutSheet by rememberSaveable { mutableStateOf(false) }
    var selectedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    // 选中项直接读仓库快照，不再额外订阅整份 books 列表造成重复重组。
    val selectedBook = remember(selectedBookId) {
        selectedBookId?.let { id -> repo.books.value.firstOrNull { it.id == id } }
    }
    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topBarState)
    val pullState = rememberPullToRefreshState()
    val gridState = rememberLazyGridState()
    val refreshEnabled by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 &&
                gridState.firstVisibleItemScrollOffset == 0 &&
                (topBarState.heightOffset.isNaN() || topBarState.heightOffset == 0f) &&
                (topBarState.collapsedFraction.isNaN() || topBarState.collapsedFraction == 0f)
        }
    }

    val listMode = libraryLayout == LibraryLayout.LIST
    val effectiveColumns = remember(libraryLayout, libraryColumns) {
        when {
            libraryLayout == LibraryLayout.LIST -> 1
            libraryColumns > 0 -> libraryColumns
            libraryLayout == LibraryLayout.COMPACT -> 3
            libraryLayout == LibraryLayout.COVER_ONLY -> 2
            else -> 2
        }
    }
    // 按实际列宽算封面目标像素宽：2 倍列宽在缩放/高分屏下仍清晰，又比统一 640px 省内存。
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val coverTargetWidthPx = remember(configuration.screenWidthDp, density, effectiveColumns, listMode) {
        if (listMode) {
            LIST_COVER_TARGET_WIDTH_PX
        } else with(density) {
            val screenPx = configuration.screenWidthDp.dp.roundToPx()
            val contentPx = (screenPx - 16.dp.roundToPx() * 2).coerceAtLeast(1)
            val gapPx = 12.dp.roundToPx() * (effectiveColumns - 1).coerceAtLeast(0)
            val columnPx = ((contentPx - gapPx) / effectiveColumns).coerceAtLeast(1)
            (columnPx * 2).coerceIn(240, 1280)
        }
    }

    val libraryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importLibraryTree(uri)
    }

    LaunchedEffect(Unit) {
        vm.setQuery("")
        vm.events.collect { event ->
            when (event) {
                is LibraryEvent.Message -> snackbar.showSnackbar(event.text)
                is LibraryEvent.ImportFinished -> {
                    val report = event.report
                    // Every supported format is readable in place now; no conversion step remains.
                    val message = when {
                        report.count > 0 && report.hasFailures -> context.getString(
                            R.string.library_import_done_failed,
                            report.count,
                            report.failures.size,
                            report.failures.first().name,
                        )
                        report.count > 0 -> context.getString(R.string.library_import_done, report.count)
                        report.hasFailures -> context.getString(R.string.library_import_failed_one, report.failures.first().reason)
                        else -> context.getString(R.string.library_import_empty)
                    }
                    snackbar.showSnackbar(message)
                }
            }
        }
    }

    val layoutDirection = LocalLayoutDirection.current
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Salt Reader", maxLines = 1) },
                actions = {
                    TooltipIconButton(
                        icon = Icons.Filled.Search,
                        label = stringResource(R.string.library_action_search),
                        onClick = onOpenSearch,
                    )
                    TooltipIconButton(
                        icon = Icons.Filled.GridView,
                        label = stringResource(R.string.library_action_layout),
                        onClick = { showLayoutSheet = true },
                    )
                    TooltipIconButton(
                        icon = Icons.Filled.Settings,
                        label = stringResource(R.string.library_action_settings),
                        onClick = onOpenSettings,
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            state = pullState,
            enabled = refreshEnabled,
            // Immersive: the shelf runs under the gesture/navigation bar; the extra bottom
            // content padding below keeps the last row reachable above it.
            modifier = Modifier.fillMaxSize().padding(
                PaddingValues(
                    start = innerPadding.calculateLeftPadding(layoutDirection),
                    top = innerPadding.calculateTopPadding(),
                    end = innerPadding.calculateRightPadding(layoutDirection),
                    bottom = 0.dp,
                )
            ),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(effectiveColumns),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp + navBarBottom),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!state.ready) {
                    item(key = "library-loading", contentType = "loading", span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 120.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            SaltLoading(sizeDp = 40)
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.library_loading_shelf), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (state.isEmpty) {
                    item(key = "library-empty", contentType = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        EmptyLibrary(onChooseFolder = { libraryLauncher.launch(null) })
                    }
                } else {
                    item(key = "shelf-header", contentType = "header", span = { GridItemSpan(maxLineSpan) }) {
                        ShelfHeader(
                            state = state,
                            sortAscending = sortAscending,
                            onFilter = vm::setFilter,
                            onSort = vm::setSort,
                            onSortAscending = { ascending -> app.settings.update { it.copy(librarySortAscending = ascending) } },
                        )
                    }

                    if (state.noResults) {
                        item(key = "library-no-results", contentType = "no-results", span = { GridItemSpan(maxLineSpan) }) {
                            NoResults(state.query.isNotBlank())
                        }
                    } else {
                        items(
                            items = state.books,
                            key = { it.id },
                            contentType = { "book" },
                        ) { book ->
                            BookCard(
                                book = book,
                                coverFile = remember(book.id) { repo.coverFile(book) },
                                coverTargetWidthPx = coverTargetWidthPx,
                                titleSizeSp = titleSizeSp,
                                listMode = listMode,
                                coverOnly = libraryLayout == LibraryLayout.COVER_ONLY,
                                showMeta = metaVisible,
                                metaTextSizeSp = metaTextSizeSp,
                                metaFields = metaFields,
                                onClick = { onOpenBook(book) },
                                onLongClick = { selectedBookId = book.id },
                                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                            )
                        }
                    }
                }
            }

            // 导入进度独立订阅，父级不再因进度 tick 而重组整个书库。
            ImportOverlay(vm, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (showLayoutSheet) {
        LayoutSheet(
            settingsState = settingsState,
            onSettingsChange = { transform -> app.settings.update(transform) },
            onDismiss = { showLayoutSheet = false },
        )
    }

    selectedBook?.let { book ->
        BookActionsSheet(
            book = book,
            coverFile = remember(book.id) { repo.coverFile(book) },
            onDismiss = { selectedBookId = null },
            onRead = {
                selectedBookId = null
                onOpenBook(book)
            },
            onToggleFinished = {
                vm.toggleFinished(book)
                selectedBookId = null
            },
            onRename = { title ->
                vm.rename(book, title)
                selectedBookId = null
            },
            onDelete = {
                vm.delete(book)
                selectedBookId = null
            },
        )
    }
}

/** 导入进度浮层：自己订阅 [LibraryViewModel.importState]，只有进度更新时才重组这一块。 */
@Composable
private fun ImportOverlay(vm: LibraryViewModel, modifier: Modifier = Modifier) {
    val importState by vm.importState.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = importState.running,
        modifier = modifier.navigationBarsPadding().padding(bottom = 92.dp, start = 16.dp, end = 16.dp),
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        ImportProgress(importState)
    }
}

/**
 * 按字段订阅设置：只有该字段真正变化时才触发读取方重组。selector 每次重组都是新 lambda，
 * 所以用 remember 固定「按字段投影」这条流本身，初始值直接取当前快照。
 */
@Composable
private fun <T> rememberSettingField(
    settings: StateFlow<AppSettings>,
    selector: (AppSettings) -> T,
): State<T> {
    val fieldFlow = remember(settings) { settings.map(selector).distinctUntilChanged() }
    return fieldFlow.collectAsStateWithLifecycle(settings.value.let(selector))
}

@Composable
private fun ShelfHeader(
    state: LibraryUiState,
    sortAscending: Boolean,
    onFilter: (LibraryFilter) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onSortAscending: (Boolean) -> Unit,
) {
    var sortOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { onFilter(filter) },
                    label = { Text(libraryFilterLabel(filter)) },
                    leadingIcon = if (state.filter == filter) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                )
            }
        }
        Box {
            TooltipIconButton(
                icon = Icons.AutoMirrored.Filled.Sort,
                label = stringResource(R.string.library_sort_action),
                onClick = { sortOpen = true },
            )
            DropdownMenu(
                expanded = sortOpen,
                onDismissRequest = { sortOpen = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 2.dp,
            ) {
                Text(
                    stringResource(R.string.library_sort_by),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp),
                )
                LibrarySort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(librarySortLabel(sort)) },
                        onClick = {
                            onSort(sort)
                            sortOpen = false
                        },
                        trailingIcon = {
                            if (state.sort == sort) Icon(Icons.Filled.Check, contentDescription = null)
                        },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    stringResource(R.string.library_sort_direction),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 6.dp),
                )
                listOf(
                    true to stringResource(R.string.library_sort_ascending),
                    false to stringResource(R.string.library_sort_descending),
                ).forEach { (ascending, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onSortAscending(ascending)
                            sortOpen = false
                        },
                        leadingIcon = {
                            Icon(
                                if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            if (sortAscending == ascending) Icon(Icons.Filled.Check, contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: ComicBook,
    coverFile: File,
    coverTargetWidthPx: Int,
    titleSizeSp: Int,
    listMode: Boolean,
    coverOnly: Boolean,
    showMeta: Boolean,
    metaTextSizeSp: Int,
    metaFields: Set<LibraryMetaField>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 卡片每次重组都 copy() 一个 TextStyle 会持续分配对象；样式只随字号/主题变化。
    val typography = MaterialTheme.typography
    val titleStyle = remember(typography, titleSizeSp) {
        typography.titleLarge.copy(fontSize = titleSizeSp.sp, lineHeight = (titleSizeSp + 6).sp)
    }
    val meta = if (showMeta) bookMetaLabel(book, metaFields) else ""
    val metaStyle = remember(typography, metaTextSizeSp) {
        typography.bodySmall.copy(fontSize = metaTextSizeSp.sp, lineHeight = (metaTextSizeSp + 4).sp)
    }
    if (listMode) {
        Card(
            modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ComicCover(book, coverFile, Modifier.width(64.dp).aspectRatio(0.68f), radius = 8, targetWidthPx = coverTargetWidthPx)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(meta, style = metaStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    } else {
        Column(
            modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.68f)) {
                    ComicCover(book, coverFile, Modifier.fillMaxSize(), radius = 0, targetWidthPx = coverTargetWidthPx)
                    if (!book.finished && (book.currentPage > 0 || book.progressOffset > 0L)) {
                        LinearProgressIndicator(
                            progress = { book.progress },
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }
            if (!coverOnly) {
                Spacer(Modifier.height(8.dp))
                Text(
                    book.title,
                    style = titleStyle,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(meta, style = metaStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutSheet(
    settingsState: StateFlow<AppSettings>,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    // 版面设置页需要整份 settings，订阅放在组件内部：sheet 关闭时不再有任何整体订阅。
    val settings by settingsState.collectAsStateWithLifecycle()
    // New sheet-state API: skip the half-expanded stop by only enabling Hidden/Expanded.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    // Slider drafts: the settings store is only written on onValueChangeFinished so dragging does
    // not thrash SharedPreferences. Both are re-synced when the stored value changes elsewhere.
    var columnDraft by remember(settings.libraryColumns) { mutableFloatStateOf(settings.libraryColumns.toFloat()) }
    var titleSizeDraft by remember(settings.libraryTitleSizeSp) { mutableFloatStateOf(settings.libraryTitleSizeSp.toFloat()) }
    var metaSizeDraft by remember(settings.libraryMetaTextSizeSp) { mutableFloatStateOf(settings.libraryMetaTextSizeSp.toFloat()) }
    // Material3 1.5 state-based Sliders: the state owns the thumb, the drafts are the persisted
    // values committed in onValueChangeFinished. Follow external changes unless dragging.
    val columnSlider = rememberSliderState(value = columnDraft, steps = 5, trackRange = 0f..6f)
    val titleSizeSlider = rememberSliderState(value = titleSizeDraft, steps = 13, trackRange = 14f..28f)
    val metaSizeSlider = rememberSliderState(
        value = metaSizeDraft,
        steps = LIBRARY_META_TEXT_MAX_SP - LIBRARY_META_TEXT_MIN_SP - 1,
        trackRange = LIBRARY_META_TEXT_MIN_SP.toFloat()..LIBRARY_META_TEXT_MAX_SP.toFloat(),
    )
    LaunchedEffect(columnDraft) {
        if (!columnSlider.isDragging && columnSlider.value != columnDraft) columnSlider.value = columnDraft
    }
    LaunchedEffect(titleSizeDraft) {
        if (!titleSizeSlider.isDragging && titleSizeSlider.value != titleSizeDraft) titleSizeSlider.value = titleSizeDraft
    }
    LaunchedEffect(metaSizeDraft) {
        if (!metaSizeSlider.isDragging && metaSizeSlider.value != metaSizeDraft) metaSizeSlider.value = metaSizeDraft
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
        ) {
            Text(stringResource(R.string.library_layout_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.library_layout_mode), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryLayout.entries.forEach { layout ->
                    FilterChip(
                        selected = settings.libraryLayout == layout,
                        onClick = {
                            // A preset owns the column count again: drop any custom value so
                            // "紧凑/松散/仅封面" actually take effect.
                            columnDraft = 0f
                            onSettingsChange { it.copy(libraryLayout = layout, libraryColumns = 0) }
                        },
                        label = { Text(libraryLayoutLabel(layout)) },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.library_layout_columns, columnLabel(columnDraft.roundToInt())),
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                state = columnSlider,
                onValueChange = { value ->
                    columnDraft = value
                    columnSlider.value = value
                },
                onValueChangeFinished = {
                    val value = columnDraft.roundToInt()
                    onSettingsChange { it.copy(libraryColumns = value) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.library_layout_title_size, titleSizeDraft.roundToInt()),
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                state = titleSizeSlider,
                onValueChange = { value ->
                    titleSizeDraft = value
                    titleSizeSlider.value = value
                },
                onValueChangeFinished = {
                    val value = titleSizeDraft.roundToInt()
                    onSettingsChange { it.copy(libraryTitleSizeSp = value) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.library_layout_meta_visible), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.library_layout_meta_visible_supporting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.libraryMetaVisible,
                    onCheckedChange = { visible -> onSettingsChange { it.copy(libraryMetaVisible = visible) } },
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.library_layout_meta_size, metaSizeDraft.roundToInt()),
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                state = metaSizeSlider,
                onValueChange = { value ->
                    metaSizeDraft = value
                    metaSizeSlider.value = value
                },
                onValueChangeFinished = {
                    onSettingsChange { it.copy(libraryMetaTextSizeSp = metaSizeDraft.roundToInt()) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.library_layout_meta_fields), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryMetaField.entries.forEach { field ->
                    val selected = field in settings.libraryMetaFields
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = if (selected) settings.libraryMetaFields - field
                            else settings.libraryMetaFields + field
                            onSettingsChange { it.copy(libraryMetaFields = next) }
                        },
                        label = { Text(metaFieldLabel(field)) },
                    )
                }
            }
        }
    }
}

/** 0 means "auto per layout preset"; any other value is a user-chosen custom column count. */
@Composable
private fun columnLabel(columns: Int): String = if (columns <= 0) {
    stringResource(R.string.library_layout_columns_auto)
} else {
    stringResource(R.string.library_layout_columns_custom, columns)
}

@Composable
private fun EmptyLibrary(onChooseFolder: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 32.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.AutoStories,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onChooseFolder, modifier = Modifier.height(52.dp)) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.library_add_folder), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.library_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoResults(searching: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.library_no_results_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (searching) {
                stringResource(R.string.library_no_results_search)
            } else {
                stringResource(R.string.library_no_results_filter)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportProgress(state: ImportUiState) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SaltLoading(sizeDp = 22)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.library_importing), style = MaterialTheme.typography.titleSmall)
                    Text(state.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    stringResource(R.string.library_import_progress_percent, (state.progress * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookActionsSheet(
    book: ComicBook,
    coverFile: File,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    onToggleFinished: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    // New sheet-state API: skip the half-expanded stop by only enabling Hidden/Expanded.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    var draftTitle by rememberSaveable(book.id) { mutableStateOf(book.title) }
    // 72dp 宽的封面按 2 倍宽解码即可，不必走默认 640px。
    val density = LocalDensity.current
    val coverTargetWidthPx = remember(density) { with(density) { (72.dp * 2).roundToPx() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ComicCover(
                    book = book,
                    coverFile = coverFile,
                    modifier = Modifier.width(72.dp).aspectRatio(0.68f),
                    radius = 12,
                    targetWidthPx = coverTargetWidthPx,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (book.reflowable) {
                            stringResource(
                                R.string.library_book_meta_format,
                                bookFormatLabel(book),
                                formatBytes(book.sizeBytes),
                            )
                        } else {
                            stringResource(
                                R.string.library_book_meta_pages,
                                book.pageCount,
                                formatBytes(book.sizeBytes),
                                bookFormatLabel(book),
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (book.currentPage > 0 || book.finished || book.progressOffset > 0L) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bookProgressLabel(book),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.library_book_percent, (book.progress * 100).roundToInt()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { book.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRead,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    when {
                        book.reflowable && (book.finished || book.progressOffset > 0L) -> stringResource(
                            R.string.library_action_continue_percent,
                            (book.progress * 100).roundToInt(),
                        )
                        book.currentPage > 0 -> stringResource(R.string.library_action_continue_page, book.currentPage + 1)
                        else -> stringResource(R.string.library_action_start_reading)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(16.dp))
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    SheetActionRow(
                        icon = if (book.finished) Icons.Filled.RadioButtonUnchecked else Icons.Filled.CheckCircle,
                        title = if (book.finished) stringResource(R.string.library_action_mark_unread) else stringResource(R.string.library_action_mark_read),
                    ) { onToggleFinished() }
                    SheetActionRow(
                        icon = Icons.Filled.Edit,
                        title = stringResource(R.string.library_action_rename),
                    ) { renameOpen = true }
                }
            }

            Spacer(Modifier.height(10.dp))
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SheetActionRow(
                    icon = Icons.Filled.Delete,
                    title = stringResource(R.string.library_action_remove),
                    tint = MaterialTheme.colorScheme.error,
                ) { deleteOpen = true }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text(stringResource(R.string.library_rename_title)) },
            text = {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = draftTitle,
                        onValueChange = { draftTitle = it.take(120) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.library_rename_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameOpen = false
                        onRename(draftTitle)
                    },
                    enabled = draftTitle.isNotBlank(),
                ) { Text(stringResource(R.string.library_save)) }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text(stringResource(R.string.library_remove_title)) },
            text = { Text(stringResource(R.string.library_remove_body), modifier = Modifier.padding(vertical = 8.dp)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    onDelete()
                }) { Text(stringResource(R.string.library_remove_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    title: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (tint == MaterialTheme.colorScheme.onSurface) MaterialTheme.colorScheme.onSurfaceVariant else tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
    }
}
