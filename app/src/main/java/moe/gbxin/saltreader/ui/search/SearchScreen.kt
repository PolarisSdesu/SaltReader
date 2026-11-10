@file:OptIn(ExperimentalFoundationApi::class)
package moe.gbxin.saltreader.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import moe.gbxin.saltreader.R
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.LibraryFilter
import moe.gbxin.saltreader.data.LibraryMetaField
import moe.gbxin.saltreader.ui.components.ComicCover
import moe.gbxin.saltreader.ui.components.SaltLoading
import moe.gbxin.saltreader.ui.components.bookAuthorLabel
import moe.gbxin.saltreader.ui.components.bookMetaLabel
import moe.gbxin.saltreader.ui.components.libraryFilterLabel
import moe.gbxin.saltreader.ui.library.LibraryViewModel

/**
 * Expressive search page: a collapsing large title, a pill-shaped search field, single-select
 * filter chips and library-style result cards. Search semantics are unchanged — it reuses
 * [LibraryViewModel]'s query/filter state.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchScreen(onOpenBook: (ComicBook) -> Unit, onBack: () -> Unit) {
    val vm: LibraryViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as SaltReaderApplication
    val repo = app.library
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        vm.setFilter(LibraryFilter.ALL)
        focusRequester.requestFocus()
        // Wait for the field to be laid out before asking the IME to appear, otherwise the
        // keyboard request is swallowed on some devices.
        delay(150)
        keyboard?.show()
    }
    fun close() {
        vm.setQuery("")
        onBack()
    }

    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topBarState)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        // Content runs edge-to-edge behind the gesture bar; the list adds the inset as padding.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.search_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { close() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.search_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { innerPadding ->
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            Modifier.fillMaxSize().padding(innerPadding).animateContentSize(),
        ) {
            SearchField(
                value = state.query,
                onValueChange = vm::setQuery,
                focusRequester = focusRequester,
                onClear = { vm.setQuery("") },
                onSearch = { keyboard?.hide() },
            )
            FilterRow(
                filter = state.filter,
                onFilter = vm::setFilter,
                resultCount = state.books.size,
                showCount = state.ready && state.query.isNotBlank(),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !state.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SaltLoading(sizeDp = 36)
                    }
                    state.query.isBlank() -> when {
                        state.total == 0 -> SearchEmpty(message = stringResource(R.string.search_empty_body))
                        else -> SearchEmpty(message = stringResource(R.string.search_hint_body, state.total))
                    }
                    state.books.isEmpty() -> SearchEmpty(message = stringResource(R.string.search_no_results_body))
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + navBarBottom),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.books, key = { it.id }) { book ->
                            SearchResultCard(
                                book = book,
                                coverFile = repo.coverFile(book),
                                showMeta = settings.libraryMetaVisible,
                                metaTextSizeSp = settings.libraryMetaTextSizeSp,
                                metaFields = settings.libraryMetaFields,
                                onClick = { onOpenBook(book) },
                                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onClear: () -> Unit,
    onSearch: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                stringResource(R.string.search_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            AnimatedVisibility(visible = value.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    resultCount: Int,
    showCount: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilter(option) },
                    label = { Text(libraryFilterLabel(option)) },
                    leadingIcon = if (filter == option) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            }
        }
        AnimatedVisibility(visible = showCount) {
            Text(
                pluralStringResource(R.plurals.library_result_count, resultCount, resultCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SearchResultCard(
    book: ComicBook,
    coverFile: java.io.File,
    showMeta: Boolean,
    metaTextSizeSp: Int,
    metaFields: Set<LibraryMetaField>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val meta = if (showMeta) bookMetaLabel(book, metaFields) else ""
    val secondary = meta.ifEmpty { bookAuthorLabel(book) }
    val metaStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = metaTextSizeSp.sp,
        lineHeight = (metaTextSizeSp + 4).sp,
    )
    Card(
        modifier = modifier.fillMaxWidth().heightIn(min = 88.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ComicCover(
                book = book,
                coverFile = coverFile,
                modifier = Modifier.height(64.dp).aspectRatio(0.68f),
                radius = 8,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    secondary,
                    style = metaStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SearchEmpty(message: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
