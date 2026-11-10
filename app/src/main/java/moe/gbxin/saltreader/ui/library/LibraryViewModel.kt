package moe.gbxin.saltreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    val uiState: StateFlow<LibraryUiState> = combine(
        repo.books, repo.ready, query, filter, sort,
    ) { books, ready, q, f, s ->
        val visible = books
            .filter { book ->
                when (f) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.READING -> !book.finished && (book.currentPage > 0 || book.lastReadAt != null)
                    LibraryFilter.UNREAD -> book.currentPage == 0 && book.lastReadAt == null
                }
            }
            .filter { book ->
                q.isBlank() || book.title.contains(q, ignoreCase = true) ||
                    (book.author?.contains(q, ignoreCase = true) == true)
            }
            .sortedWith(
                when (s) {
                    LibrarySort.RECENT -> compareByDescending<ComicBook> { it.lastReadAt ?: it.addedAt }
                    LibrarySort.ADDED -> compareByDescending<ComicBook> { it.addedAt }
                    LibrarySort.TITLE -> compareBy<ComicBook> { it.title }
                    LibrarySort.PROGRESS -> compareByDescending<ComicBook> { it.progress }
                }
            )
        LibraryUiState(ready, visible, books.size, q, f, s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

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
            _importState.value = ImportUiState(running = true, progress = 0f, label = "正在扫描目录…")
            try {
                val report = repo.importLibraryTree(uri) { progress, label ->
                    _importState.update { it.copy(progress = progress, label = label) }
                }
                _events.send(LibraryEvent.ImportFinished(report))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(LibraryEvent.Message("导入失败：${e.message ?: "未知错误"}"))
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
                            "刷新完成，${report.failures.size} 项无法读取：${first.name}（${first.reason}）",
                        ),
                    )
                }
                // Keep the MD3E indicator on screen long enough to read as a refresh.
                val remain = 2_000L - (System.currentTimeMillis() - started)
                if (remain > 0) delay(remain)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(LibraryEvent.Message("刷新失败：${e.message ?: "未知错误"}"))
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun delete(book: ComicBook) = viewModelScope.launch {
        try {
            repo.delete(book)
            _events.send(LibraryEvent.Message("《${book.title}》已移出书架"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _events.send(LibraryEvent.Message("操作失败：无法移出《${book.title}》"))
        }
    }

    fun rename(book: ComicBook, title: String) = viewModelScope.launch {
        val clean = title.trim().take(120)
        if (clean.isBlank()) {
            _events.send(LibraryEvent.Message("书名不能为空"))
            return@launch
        }
        if (clean == book.title) {
            _events.send(LibraryEvent.Message("书名没有变化"))
            return@launch
        }
        val ok = try {
            repo.rename(book, clean)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        _events.send(LibraryEvent.Message(if (ok) "已重命名为《$clean》" else "重命名失败"))
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
                    if (target) "《${book.title}》已标为读完" else "《${book.title}》已标为未读"
                } else {
                    "操作失败：无法更新《${book.title}》"
                },
            ),
        )
    }
}
