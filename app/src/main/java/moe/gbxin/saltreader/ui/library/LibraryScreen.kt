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
import androidx.compose.material.icons.filled.Sort
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlin.math.roundToInt
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.AppSettings
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.LibraryFilter
import moe.gbxin.saltreader.data.LibraryLayout
import moe.gbxin.saltreader.data.LibrarySort
import moe.gbxin.saltreader.ui.components.ComicCover
import moe.gbxin.saltreader.ui.components.SaltLoading
import moe.gbxin.saltreader.util.formatBytes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenBook: (ComicBook) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: LibraryViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as SaltReaderApplication
    val repo = app.library
    val allBooks by repo.books.collectAsStateWithLifecycle()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showLayoutSheet by rememberSaveable { mutableStateOf(false) }
    var selectedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedBook = remember(allBooks, selectedBookId) {
        selectedBookId?.let { id -> allBooks.firstOrNull { it.id == id } }
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
                    val message = when {
                        report.count > 0 && report.hasFailures ->
                            "已导入 ${report.count} 本，${report.failures.size} 本失败：${report.failures.first().name}"
                        report.count > 0 -> "已导入 ${report.count} 本漫画"
                        report.hasFailures -> "导入失败：${report.failures.first().reason}"
                        else -> "没有找到可导入的漫画"
                    }
                    snackbar.showSnackbar(message)
                }
            }
        }
    }

    val effectiveColumns = when {
        settings.libraryLayout == LibraryLayout.LIST -> 1
        settings.libraryColumns > 0 -> settings.libraryColumns
        settings.libraryLayout == LibraryLayout.COMPACT -> 3
        settings.libraryLayout == LibraryLayout.COVER_ONLY -> 2
        else -> 2
    }
    val displayedBooks = remember(state.books, state.sort, settings.librarySortAscending) {
        val comparator: Comparator<ComicBook> = when (state.sort) {
            LibrarySort.RECENT -> compareBy { it.lastReadAt ?: it.addedAt }
            LibrarySort.ADDED -> compareBy { it.addedAt }
            LibrarySort.TITLE -> compareBy { it.title }
            LibrarySort.PROGRESS -> compareBy { it.progress }
        }
        state.books.sortedWith(if (settings.librarySortAscending) comparator else comparator.reversed())
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
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = { showLayoutSheet = true }) {
                        Icon(Icons.Filled.GridView, contentDescription = "布局")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
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
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 120.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            SaltLoading(sizeDp = 40)
                            Spacer(Modifier.height(16.dp))
                            Text("正在整理书架…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (state.isEmpty) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyLibrary(onChooseFolder = { libraryLauncher.launch(null) })
                    }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ShelfHeader(
                            state = state,
                            sortAscending = settings.librarySortAscending,
                            onFilter = vm::setFilter,
                            onSort = vm::setSort,
                            onSortAscending = { ascending -> app.settings.update { it.copy(librarySortAscending = ascending) } },
                        )
                    }

                    if (state.noResults) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            NoResults(state.query.isNotBlank())
                        }
                    } else {
                        items(displayedBooks, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                coverFile = repo.coverFile(book),
                                titleSizeSp = settings.libraryTitleSizeSp,
                                listMode = settings.libraryLayout == LibraryLayout.LIST,
                                coverOnly = settings.libraryLayout == LibraryLayout.COVER_ONLY,
                                onClick = { onOpenBook(book) },
                                onLongClick = { selectedBookId = book.id },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = importState.running,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp, start = 16.dp, end = 16.dp),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                ImportProgress(importState)
            }
        }
    }

    if (showLayoutSheet) {
        LayoutSheet(
            settings = settings,
            onSettingsChange = { transform -> app.settings.update(transform) },
            onDismiss = { showLayoutSheet = false },
        )
    }

    selectedBook?.let { book ->
        BookActionsSheet(
            book = book,
            coverFile = repo.coverFile(book),
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
                    label = { Text(filter.label) },
                    leadingIcon = if (state.filter == filter) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                )
            }
        }
        Box {
            IconButton(onClick = { sortOpen = true }) {
                Icon(Icons.Filled.Sort, contentDescription = "排序")
            }
            DropdownMenu(
                expanded = sortOpen,
                onDismissRequest = { sortOpen = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 2.dp,
            ) {
                Text(
                    "排序方式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp),
                )
                LibrarySort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(sort.label) },
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
                    "排序方向",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 6.dp),
                )
                listOf(true to "升序", false to "降序").forEach { (ascending, label) ->
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
    titleSizeSp: Int,
    listMode: Boolean,
    coverOnly: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = titleSizeSp.sp,
        lineHeight = (titleSizeSp + 6).sp,
    )
    val meta = when {
        book.finished -> "已读完"
        book.currentPage > 0 -> "阅读至 ${book.currentPage + 1} 页"
        else -> formatBytes(book.sizeBytes)
    }
    if (listMode) {
        Card(
            modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ComicCover(book, coverFile, Modifier.width(64.dp).aspectRatio(0.68f), radius = 8)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    ComicCover(book, coverFile, Modifier.fillMaxSize(), radius = 0)
                    if (!book.finished && book.currentPage > 0) {
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
                Spacer(Modifier.height(3.dp))
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutSheet(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Slider drafts: the settings store is only written on onValueChangeFinished so dragging does
    // not thrash SharedPreferences. Both are re-synced when the stored value changes elsewhere.
    var columnDraft by remember(settings.libraryColumns) { mutableFloatStateOf(settings.libraryColumns.toFloat()) }
    var titleSizeDraft by remember(settings.libraryTitleSizeSp) { mutableFloatStateOf(settings.libraryTitleSizeSp.toFloat()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
        ) {
            Text("布局", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            Text("布局模式", style = MaterialTheme.typography.titleSmall)
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
                        label = { Text(layout.label()) },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "列数  ${columnLabel(columnDraft.roundToInt())}",
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = columnDraft,
                onValueChange = { columnDraft = it },
                onValueChangeFinished = {
                    val value = columnDraft.roundToInt()
                    onSettingsChange { it.copy(libraryColumns = value) }
                },
                valueRange = 0f..6f,
                steps = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text("书名大小  ${titleSizeDraft.roundToInt()}sp", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = titleSizeDraft,
                onValueChange = { titleSizeDraft = it },
                onValueChangeFinished = {
                    val value = titleSizeDraft.roundToInt()
                    onSettingsChange { it.copy(libraryTitleSizeSp = value) }
                },
                valueRange = 14f..28f,
                steps = 13,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun LibraryLayout.label(): String = when (this) {
    LibraryLayout.COMPACT -> "紧凑网格"
    LibraryLayout.COMFORTABLE -> "松散网格"
    LibraryLayout.COVER_ONLY -> "仅封面"
    LibraryLayout.LIST -> "列表"
}

/** 0 means "按布局预设自动"; any other value is a user-chosen custom column count. */
private fun columnLabel(columns: Int): String = if (columns <= 0) "自动" else "自定义 · $columns 列"

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
        Text("书架还是空的", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "选择一个存放漫画的文件夹，应用只会建立索引，不会复制或移动你的文件。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onChooseFolder, modifier = Modifier.height(52.dp)) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("添加文件夹", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "之后可在「设置 → 存储」中管理或移除目录。",
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
        Text("没有找到漫画", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (searching) "换个关键词试试。" else "这个筛选下暂时没有内容。",
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
                    Text("正在导入", style = MaterialTheme.typography.titleSmall)
                    Text(state.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    var draftTitle by rememberSaveable(book.id) { mutableStateOf(book.title) }

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
                        "${book.pageCount} 页 · ${formatBytes(book.sizeBytes)} · ${book.kindLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (book.currentPage > 0 || book.finished) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        book.pageLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(book.progress * 100).roundToInt()}%",
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
                    if (book.currentPage > 0) "继续阅读 · 第 ${book.currentPage + 1} 页" else "开始阅读",
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
                        title = if (book.finished) "标为未读" else "标为已读",
                    ) { onToggleFinished() }
                    SheetActionRow(
                        icon = Icons.Filled.Edit,
                        title = "重命名",
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
                    title = "移出书架",
                    tint = MaterialTheme.colorScheme.error,
                ) { deleteOpen = true }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("重命名") },
            text = {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = draftTitle,
                        onValueChange = { draftTitle = it.take(120) },
                        singleLine = true,
                        label = { Text("书名") },
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
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("取消") } },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("移出书架？") },
            text = { Text("只会移除书架索引和封面缓存，不会删除你的原文件。", modifier = Modifier.padding(vertical = 8.dp)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    onDelete()
                }) { Text("移出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("取消") } },
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
