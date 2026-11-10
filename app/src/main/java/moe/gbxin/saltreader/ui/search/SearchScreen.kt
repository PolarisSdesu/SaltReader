package moe.gbxin.saltreader.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.LibraryFilter
import moe.gbxin.saltreader.ui.components.ComicCover
import moe.gbxin.saltreader.ui.components.SaltLoading
import moe.gbxin.saltreader.ui.library.LibraryViewModel

@Composable
fun SearchScreen(onOpenBook: (ComicBook) -> Unit, onBack: () -> Unit) {
    val vm: LibraryViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val repo = (LocalContext.current.applicationContext as SaltReaderApplication).library
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.setFilter(LibraryFilter.ALL)
        focusRequester.requestFocus()
    }
    fun close() {
        vm.setQuery("")
        onBack()
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("搜索") },
                navigationIcon = {
                    IconButton(onClick = { close() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focusRequester),
                placeholder = { Text("搜索") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true,
            )

            when {
                !state.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SaltLoading(sizeDp = 36)
                }
                state.query.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("搜索本地书架", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.books.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有找到漫画", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(state.books, key = { it.id }) { book ->
                        ListItem(
                            headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${book.displayAuthor} · ${book.pageCount} 页") },
                            leadingContent = {
                                ComicCover(
                                    book = book,
                                    coverFile = repo.coverFile(book),
                                    modifier = Modifier.size(width = 48.dp, height = 68.dp),
                                    radius = 6,
                                )
                            },
                            modifier = Modifier.clickable { onOpenBook(book) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 80.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}
