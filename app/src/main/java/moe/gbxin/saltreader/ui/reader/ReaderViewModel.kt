package moe.gbxin.saltreader.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
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
 * - [Loading] while the library index is still publishing, or while the page source is being
 *   prepared (for e-books that can mean an on-demand conversion; [Loading.message] says which).
 * - [Missing] only when the index is ready and the book is definitively gone.
 * - [Ready] once [ComicPageSource.prepare] has reported the real page count. This count may differ
 *   from the estimated `ComicBook.pageCount` of a not-yet-converted e-book, so it is carried here
 *   instead of being read from the book record.
 * - [Failed] when preparing the book failed (corrupt file / unsupported format / conversion error);
 *   the UI offers a retry.
 */
sealed interface ReaderUiState {
    data class Loading(val message: String = "正在打开…") : ReaderUiState
    data object Missing : ReaderUiState
    data class Ready(val book: ComicBook, val pageCount: Int) : ReaderUiState
    data object Failed : ReaderUiState
}

/** Internal preparation progress for the currently open book. */
private sealed interface PrepareState {
    data object Idle : PrepareState
    data class Running(val converting: Boolean) : PrepareState
    data class Done(val pageCount: Int) : PrepareState
    data object Failed : PrepareState
}

/**
 * State holder for one open book.
 *
 * All file / provider access happens off the main thread: the page source is created lazily on
 * [Dispatchers.IO] and prepared there before the reader becomes [ReaderUiState.Ready]. For e-books
 * whose cache is not ready yet, `prepare()` performs the on-demand conversion, which is why the
 * loading state is allowed to stay up for a while (with a "converting" message) instead of
 * returning to the shelf. The main thread never performs I/O.
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

    /**
     * At most one neighbour pre-decode job at a time. A new target cancels the previous one so fast
     * page flips / fast scrolling cannot queue up a backlog of decode work.
     */
    private var prefetchJob: Job? = null

    /** Runs the one-time `prepare()` for this book; guarded against duplicate starts. */
    private var prepareJob: Job? = null

    private val prepareState = MutableStateFlow<PrepareState>(PrepareState.Idle)

    private val maxCacheBytes = (Runtime.getRuntime().maxMemory() / 6L).coerceAtMost(96L * 1024L * 1024L)
    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes.toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun stateFor(
        ready: Boolean,
        books: List<ComicBook>,
        prepare: PrepareState,
    ): ReaderUiState {
        val indexed = books.firstOrNull { it.id == bookId }
            ?: return if (ready) ReaderUiState.Missing else ReaderUiState.Loading()
        return when (prepare) {
            PrepareState.Idle -> ReaderUiState.Loading()
            is PrepareState.Running -> ReaderUiState.Loading(
                if (prepare.converting) "正在转换电子书…" else "正在准备…",
            )
            is PrepareState.Done -> ReaderUiState.Ready(indexed, prepare.pageCount)
            PrepareState.Failed -> ReaderUiState.Failed
        }
    }

    /**
     * Primary state. Derived from the repository index + one-time preparation result, so progress
     * updates and re-indexing flow through without extra file access.
     */
    val uiState: StateFlow<ReaderUiState> =
        combine(repo.ready, repo.books, prepareState) { ready, books, prepare ->
            stateFor(ready, books, prepare)
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                stateFor(repo.ready.value, repo.books.value, prepareState.value),
            )

    /** The book currently being read, or null while loading / missing / failed. */
    val book: StateFlow<ComicBook?> = uiState
        .map { (it as? ReaderUiState.Ready)?.book }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            (stateFor(repo.ready.value, repo.books.value, prepareState.value) as? ReaderUiState.Ready)?.book,
        )

    init {
        // bookkeeping only: never touches the page source and runs off the main thread.
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.markOpened(bookId) } }
        viewModelScope.launch { observePrepareTarget() }
    }

    /**
     * Starts preparing the book as soon as the index contains it, and retries once automatically if
     * the background conversion later succeeds after an open-time failure (the `converted` flag
     * changes). Re-indexing / deleting the book resets the preparation state.
     */
    private suspend fun observePrepareTarget() {
        repo.books
            .map { books -> books.firstOrNull { it.id == bookId } }
            .distinctUntilChangedBy { indexed -> indexed?.converted }
            .collect { indexed ->
                if (indexed == null) {
                    prepareState.value = PrepareState.Idle
                } else {
                    when (prepareState.value) {
                        is PrepareState.Done, is PrepareState.Running -> Unit
                        else -> startPrepare(indexed)
                    }
                }
            }
    }

    private fun startPrepare(indexed: ComicBook) {
        if (released) return
        if (prepareJob?.isActive == true) return
        prepareJob = viewModelScope.launch(Dispatchers.IO) {
            prepareState.value = PrepareState.Running(converting = !indexed.converted)
            val pageCount: Int? = runCatching {
                val pageSource = ensureSource(indexed) ?: return@runCatching null
                pageSource.prepare().takeIf { it > 0 }
            }.getOrNull()
            if (pageCount == null) {
                prepareState.value = PrepareState.Failed
            } else {
                prepareState.value = PrepareState.Done(pageCount)
                // Backfill the real count so the shelf page label / progress use it too.
                runCatching { repo.updatePageCount(bookId, pageCount) }
            }
        }
    }

    /** UI retry after [ReaderUiState.Failed]: re-open the document and prepare it again. */
    fun retryPrepare() {
        if (released || prepareState.value is PrepareState.Running) return
        viewModelScope.launch(Dispatchers.IO) {
            // Drop the failed source so the retry re-opens the document from scratch instead of
            // reusing a half-initialised zip / PDF renderer.
            sourceMutex.withLock {
                runCatching { source?.close() }
                source = null
            }
            prepareState.value = PrepareState.Idle
            repo.books.value.firstOrNull { it.id == bookId }?.let(::startPrepare)
        }
    }

    fun peekPage(index: Int, targetWidth: Int): Bitmap? {
        val bucket = (targetWidth / 240).coerceAtLeast(1)
        return cache.get("${index}_$bucket")
    }

    suspend fun loadPage(index: Int, targetWidth: Int): Bitmap? {
        val ready = uiState.value as? ReaderUiState.Ready ?: return null
        val pageCount = ready.pageCount
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
     * Opens the page source on an IO thread the first time it is needed. The repository builds a
     * lazy source with no I/O, but we still keep the call off the main thread because the first
     * real page read (zip / PDF / provider open) happens right after it.
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

    /**
     * Warms the page cache for the given page indexes without blocking the caller.
     *
     * Every page goes through [loadPage], so the cache key (page + 240px width bucket) is identical
     * to the one the UI reads and a duplicate decode is impossible: the second caller either hits
     * the cache or waits on [cacheMutex] and then finds the bitmap already cached. Lock order is
     * always cacheMutex -> sourceMutex (never the other way around), so this cannot deadlock.
     *
     * [pages] is expected to be ordered nearest-first; cancelled jobs finish their current blocking
     * decode (a page source may not be cancellable) but then stop before starting the next page.
     */
    fun prefetchPages(pages: Collection<Int>, targetWidth: Int) {
        if (released) return
        val pageCount = (uiState.value as? ReaderUiState.Ready)?.pageCount ?: return
        if (pageCount <= 0) return
        val warm = pages.asSequence()
            .filter { it in 0 until pageCount }
            .distinct()
            .toList()
        if (warm.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            for (page in warm) {
                if (!isActive) return@launch
                if (peekPage(page, targetWidth) != null) continue
                loadPage(page, targetWidth)
            }
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
        prefetchJob?.cancel()
        prefetchJob = null
        prepareJob?.cancel()
        prepareJob = null
        runCatching { source?.close() }
        source = null
        cache.evictAll()
    }

    class Factory(private val application: Application, private val bookId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(application, bookId) as T
    }
}
