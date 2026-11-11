package moe.gbxin.saltreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.gbxin.saltreader.R
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.ImportReport
import moe.gbxin.saltreader.data.LibraryFilter
import moe.gbxin.saltreader.data.LibrarySort

data class LibraryUiState(
    val ready: Boolean = false,
    val books: List<ComicBook> = emptyList(),
    val total: Int = 0,
    val query: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySort = LibrarySort.RECENT,
) {
    val isEmpty: Boolean get() = total == 0
    val noResults: Boolean get() = total > 0 && books.isEmpty()
}

data class ImportUiState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val label: String = "",
)

sealed interface LibraryEvent {
    data class Message(val text: String) : LibraryEvent
    data class ImportFinished(val report: ImportReport) : LibraryEvent
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as SaltReaderApplication).library
    private val settings = (application as SaltReaderApplication).settings
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val sort = MutableStateFlow(settings.state.value.librarySort)

    private val _importState = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _importState

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private fun str(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun errorText(e: Exception): String = e.message ?: str(R.string.library_error_unknown)

    /**
     * 升降序由设置驱动，但只在 VM 里参与一次排序：`map + distinctUntilChanged` 让无关设置
     * 变更（主题、阅读器偏好等）不会重新派发书单。
     */
    private val sortAscending = settings.state.map { it.librarySortAscending }.distinctUntilChanged()

    /** 自然升序比较器；降序由上层的 reversed() 得到，语义与原 LibraryScreen 一致。 */
    private fun comparatorFor(sort: LibrarySort): Comparator<ComicBook> = when (sort) {
        LibrarySort.RECENT -> compareBy { it.lastReadAt ?: it.addedAt }
        LibrarySort.ADDED -> compareBy { it.addedAt }
        LibrarySort.TITLE -> compareBy { it.title }
        LibrarySort.PROGRESS -> compareBy { it.progress }
    }

    private fun matchesFilter(book: ComicBook, filter: LibraryFilter): Boolean = when (filter) {
        LibraryFilter.ALL -> true
        LibraryFilter.READING -> !book.finished && (book.currentPage > 0 || book.lastReadAt != null)
        LibraryFilter.UNREAD -> book.currentPage == 0 && book.lastReadAt == null
    }

    private fun matchesQuery(book: ComicBook, query: String): Boolean =
        query.isBlank() || book.title.contains(query, ignoreCase = true) ||
            (book.author?.contains(query, ignoreCase = true) == true)

    @OptIn(FlowPreview::class) // debounce 目前仍标记为 FlowPreview
    val uiState: StateFlow<LibraryUiState> = combine(
        repo.books,
        repo.ready,
        // 只对过滤条件做 debounce；输入框回显随后用原始 query 覆盖，避免受控 TextField 丢字。
        query.debounce(120).onStart { emit(query.value) },
        filter,
        sort,
    ) { books, ready, q, f, s ->
        // 单次遍历完成过滤（不再 filter().filter() 建两张中间表），排序也只在这里做一次。
        val visible = ArrayList<ComicBook>(books.size)
        for (book in books) {
            if (!matchesFilter(book, f) || !matchesQuery(book, q)) continue
            visible.add(book)
        }
        LibraryUiState(ready, visible, books.size, q, f, s)
    }.combine(sortAscending) { state, ascending ->
        // 升降序统一由 VM 决定；LibraryScreen 不再二次排序。
        val comparator = comparatorFor(state.sort)
        state.copy(books = state.books.sortedWith(if (ascending) comparator else comparator.reversed()))
    }.combine(query) { state, rawQuery ->
        // 回显即时：输入框直接读 state.query，过滤结果仍跟随 debounce 后的值。
        state.copy(query = rawQuery)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(value: String) { query.value = value }
    fun setFilter(value: LibraryFilter) { filter.value = value }
    fun setSort(value: LibrarySort) {
        sort.value = value
        settings.update { it.copy(librarySort = value) }
    }

    /**
     * Indexes a SAF tree picked with OpenDocumentTree. The progress overlay is always reset in
     * [finally]; repository failures surface as an event instead of leaving the UI stuck.
     */
    fun importLibraryTree(uri: Uri) {
        if (_importState.value.running) return
        viewModelScope.launch {
            _importState.value = ImportUiState(running = true, progress = 0f, label = str(R.string.library_import_scanning))
            try {
                val report = repo.importLibraryTree(uri) { progress, label ->
                    _importState.update { it.copy(progress = progress, label = label) }
                }
                _events.send(LibraryEvent.ImportFinished(report))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(LibraryEvent.Message(str(R.string.library_import_failed_one, errorText(e))))
            } finally {
                _importState.value = ImportUiState()
            }
        }
    }

    /** Re-indexes every added folder; failures are reported as a snackbar event. */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val started = System.currentTimeMillis()
            try {
                val report = repo.refreshLibrary()
                if (report.hasFailures) {
                    val first = report.failures.first()
                    _events.send(
                        LibraryEvent.Message(
                            str(R.string.library_refresh_partial, report.failures.size, first.name, first.reason),
                        ),
                    )
                }
                // Keep the MD3E indicator on screen long enough to read as a refresh.
                val remain = 2_000L - (System.currentTimeMillis() - started)
                if (remain > 0) delay(remain)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(LibraryEvent.Message(str(R.string.library_refresh_failed, errorText(e))))
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun delete(book: ComicBook) = viewModelScope.launch {
        try {
            repo.delete(book)
            _events.send(LibraryEvent.Message(str(R.string.library_deleted, book.title)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _events.send(LibraryEvent.Message(str(R.string.library_delete_failed, book.title)))
        }
    }

    fun rename(book: ComicBook, title: String) = viewModelScope.launch {
        val clean = title.trim().take(120)
        if (clean.isBlank()) {
            _events.send(LibraryEvent.Message(str(R.string.library_title_empty)))
            return@launch
        }
        if (clean == book.title) {
            _events.send(LibraryEvent.Message(str(R.string.library_title_unchanged)))
            return@launch
        }
        val ok = try {
            repo.rename(book, clean)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        _events.send(
            LibraryEvent.Message(
                if (ok) str(R.string.library_renamed, clean) else str(R.string.library_rename_failed),
            ),
        )
    }

    fun toggleFinished(book: ComicBook) = viewModelScope.launch {
        val target = !book.finished
        val ok = try {
            repo.setFinished(book, target)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        _events.send(
            LibraryEvent.Message(
                if (ok) {
                    if (target) {
                        str(R.string.library_marked_read, book.title)
                    } else {
                        str(R.string.library_marked_unread, book.title)
                    }
                } else {
                    str(R.string.library_update_failed, book.title)
                },
            ),
        )
    }
}
