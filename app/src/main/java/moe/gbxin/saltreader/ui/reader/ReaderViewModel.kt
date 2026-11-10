package moe.gbxin.saltreader.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.ComicPageSource
import moe.gbxin.saltreader.data.ReadingDirection

/**
 * What the reader can show right now.
 *
 * [Loading] means the library repository has not published its index yet, [Missing] means the index
 * is ready and the book is definitively gone (removed / root detached), and [Ready] means the book
 * exists and can be rendered. Only [Missing] may send the user back to the shelf.
 */
sealed interface ReaderUiState {
    data object Loading : ReaderUiState
    data object Missing : ReaderUiState
    data class Ready(val book: ComicBook) : ReaderUiState
}

/**
 * State holder for one open book.
 *
 * All file / provider access happens off the main thread: the page source is created lazily on
 * [Dispatchers.IO] the first time a page is needed, never in the constructor. The library index
 * already knows the page count ([ComicBook.pageCount]), so opening the reader performs zero I/O on
 * the main thread even before the first page is decoded.
 */
class ReaderViewModel(
    application: Application,
    private val bookId: String,
) : ViewModel() {
    private val repo = (application as SaltReaderApplication).library

    /** Guards decode / cache work so two pages never race the same source. */
    private val cacheMutex = Mutex()

    /** Guards lazy creation of [source]; there is exactly one source per reader. */
    private val sourceMutex = Mutex()

    @Volatile
    private var source: ComicPageSource? = null

    @Volatile
    private var released = false

    private val maxCacheBytes = (Runtime.getRuntime().maxMemory() / 6L).coerceAtMost(96L * 1024L * 1024L)
    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes.toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun stateFor(books: List<ComicBook>, ready: Boolean): ReaderUiState {
        val indexed = books.firstOrNull { it.id == bookId }
        return when {
            indexed != null -> ReaderUiState.Ready(indexed)
            ready -> ReaderUiState.Missing
            else -> ReaderUiState.Loading
        }
    }

    /**
     * Primary state. Derived from the repository index, so progress updates and re-indexing flow
     * through without extra file access.
     */
    val uiState: StateFlow<ReaderUiState> =
        combine(repo.ready, repo.books) { ready, books -> stateFor(books, ready) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                stateFor(repo.books.value, repo.ready.value),
            )

    /** The book currently being read, or null while loading / missing. */
    val book: StateFlow<ComicBook?> = uiState
        .map { (it as? ReaderUiState.Ready)?.book }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            (stateFor(repo.books.value, repo.ready.value) as? ReaderUiState.Ready)?.book,
        )

    init {
        // bookkeeping only: never touches the page source and runs off the main thread.
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.markOpened(bookId) } }
    }

    fun peekPage(index: Int, targetWidth: Int): Bitmap? {
        val bucket = (targetWidth / 240).coerceAtLeast(1)
        return cache.get("${index}_$bucket")
    }

    suspend fun loadPage(index: Int, targetWidth: Int): Bitmap? {
        val ready = uiState.value as? ReaderUiState.Ready ?: return null
        val pageCount = ready.book.pageCount
        if (pageCount <= 0 || index !in 0 until pageCount) return null
        val bucket = (targetWidth / 240).coerceAtLeast(1)
        val key = "${index}_$bucket"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            cacheMutex.withLock {
                cache.get(key)?.let { return@withLock it }
                val pageSource = ensureSource(ready.book) ?: return@withLock null
                val bitmap = runCatching { pageSource.loadPage(index, bucket * 240) }.getOrNull()
                    ?: return@withLock null
                cache.put(key, bitmap)
                bitmap
            }
        }
    }

    /**
     * Opens the page source on an IO thread the first time a page is requested. The repository
     * builds a lazy source with no I/O, but we still keep the call off the main thread because the
     * first real page read (zip / PDF / provider open) happens right after it.
     */
    private suspend fun ensureSource(book: ComicBook): ComicPageSource? {
        source?.let { return it }
        if (released) return null
        return sourceMutex.withLock {
            if (released) return@withLock null
            source ?: withContext(Dispatchers.IO) {
                runCatching { repo.pageSource(book) }.getOrNull()
            }?.also { source = it }
        }
    }

    fun updateProgress(page: Int) {
        repo.updateProgress(bookId, page)
    }

    fun setDirection(direction: ReadingDirection) {
        viewModelScope.launch(Dispatchers.IO) { repo.setDirection(bookId, direction) }
    }

    override fun onCleared() {
        super.onCleared()
        released = true
        runCatching { source?.close() }
        source = null
        cache.evictAll()
    }

    class Factory(private val application: Application, private val bookId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(application, bookId) as T
    }
}
