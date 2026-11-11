package moe.gbxin.saltreader.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.util.Size
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.ComicPageSource
import moe.gbxin.saltreader.data.EbookChapter
import moe.gbxin.saltreader.data.ReadingDirection
import moe.gbxin.saltreader.data.RegionPage

/**
 * What the reader can show right now.
 *
 * - [Loading] while the library index is still publishing, or while the image page source is being
 *   prepared ([Loading.reason] says which; the UI maps it to a localized string).
 * - [Missing] only when the index is ready and the book is definitively gone.
 * - [Ready] once [ComicPageSource.prepare] has reported the real page count. This count is the
 *   authoritative one (it can differ from the estimate stored in the index, e.g. remote ZIP reads),
 *   so it is carried here instead of being read from the book record.
 * - [Failed] when preparing the book failed (corrupt file / unsupported format); UI offers a retry.
 */
/** Why the reader is still loading. The UI maps each reason to a localized string. */
enum class ReaderLoadingReason {
    /** Library index not published yet, or the book is not in it yet. */
    INDEX,

    /** Image page source being prepared (remote ZIP / PDF renderer / provider open). */
    PREPARING,
}

sealed interface ReaderUiState {
    data class Loading(val reason: ReaderLoadingReason = ReaderLoadingReason.INDEX) : ReaderUiState
    data object Missing : ReaderUiState
    data class Ready(val book: ComicBook, val pageCount: Int) : ReaderUiState
    data object Failed : ReaderUiState

    /**
     * Reflowable text book (EPUB/MOBI/AZW3): chapters are being parsed / extracted on IO. The UI
     * shows a single generic "parsing e-book" message so it never has to know the container format.
     */
    data object TextLoading : ReaderUiState

    /** Reflowable text book ready to render; [chapters] are already sanitized HTML. */
    data class TextReady(val book: ComicBook, val chapters: List<EbookChapter>) : ReaderUiState

    /** Reflowable text book failed to load (corrupt file / extraction error) - retryable. */
    data object TextFailed : ReaderUiState
}

/** Internal chapter-loading progress for reflowable books. */
private sealed interface TextLoadState {
    data object Idle : TextLoadState
    data object Loading : TextLoadState
    data class Done(val chapters: List<EbookChapter>) : TextLoadState
    data object Failed : TextLoadState
}

/** Internal preparation progress for the image page source of the currently open book. */
private sealed interface PrepareState {
    data object Idle : PrepareState
    data object Running : PrepareState
    data class Done(val pageCount: Int) : PrepareState
    data object Failed : PrepareState
}

/**
 * State holder for one open book.
 *
 * All file / provider access happens off the main thread: the page source is created lazily on
 * [Dispatchers.IO] and prepared there before the reader becomes [ReaderUiState.Ready]. Preparing an
 * image source (remote ZIP central directory, PDF renderer, provider open) only happens off the
 * main thread, which is why the loading state is allowed to stay up for a moment instead of
 * returning to the shelf. Text books (reflowable) load their chapters on IO instead. The main
 * thread never performs I/O.
 */
class ReaderViewModel(
    application: Application,
    private val bookId: String,
) : ViewModel() {
    private val app = application as SaltReaderApplication
    private val repo = app.library

    /**
     * 解码并发限制。同一页仍然串行（按 key 的锁避免重复解码），不同页最多并行
     * [DECODE_CONCURRENCY] 张：开书时 Pager 的相邻页、预取和可见页不再互相排队。
     */
    private val decodeLimiter = Semaphore(DECODE_CONCURRENCY)

    /** 每页一把锁：保证同一个 key 不会被并发解码两次，同时不同页可以并行。 */
    private val pageLocks = ConcurrentHashMap<String, Mutex>()

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

    /** 条漫判定：首屏页高宽比很大时，垂直模式改用按区域解码的分块渲染。 */
    private val _tallPages = MutableStateFlow(false)
    val tallPages: StateFlow<Boolean> = _tallPages

    /** 页尺寸缓存（只读图片头）；分块渲染需要先知道每页的显示高度。 */
    private val pageSizes = LruCache<Int, Size>(64)

    /** Runs the one-time `prepare()` for this book; guarded against duplicate starts. */
    private var prepareJob: Job? = null

    private val prepareState = MutableStateFlow<PrepareState>(PrepareState.Idle)

    /** Runs the one-time chapter extraction for reflowable EPUBs; guarded against duplicate starts. */
    private var textJob: Job? = null

    private val textState = MutableStateFlow<TextLoadState>(TextLoadState.Idle)

    private val maxCacheBytes = (Runtime.getRuntime().maxMemory() / 6L).coerceAtMost(96L * 1024L * 1024L)
    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes.toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 系统内存回收时清空页缓存：内容随时可以从文件重新解码。 */
    private val cacheTrimmer: () -> Unit = { cache.evictAll() }

    private fun stateFor(
        ready: Boolean,
        indexed: ComicBook?,
        prepare: PrepareState,
        text: TextLoadState,
    ): ReaderUiState {
        if (indexed == null) return if (ready) ReaderUiState.Missing else ReaderUiState.Loading()
        // Reflowable text books (EPUB/MOBI/AZW3) never go through the image page source.
        if (indexed.reflowable) {
            return when (text) {
                TextLoadState.Idle, TextLoadState.Loading -> ReaderUiState.TextLoading
                is TextLoadState.Done -> ReaderUiState.TextReady(indexed, text.chapters)
                TextLoadState.Failed -> ReaderUiState.TextFailed
            }
        }
        return when (prepare) {
            // Book is indexed but its image page source has not started opening yet: same message as
            // while preparing so the loading state is consistent for image books.
            PrepareState.Idle -> ReaderUiState.Loading(ReaderLoadingReason.PREPARING)
            PrepareState.Running -> ReaderUiState.Loading(ReaderLoadingReason.PREPARING)
            is PrepareState.Done -> ReaderUiState.Ready(indexed, prepare.pageCount)
            PrepareState.Failed -> ReaderUiState.Failed
        }
    }

    /**
     * 当前书在索引里的记录。翻页只会改 currentPage / lastReadAt，这些变化不应让整个阅读器
     * 重新组合；这里只保留真正影响阅读器的字段做去重（标题 / 方向 / 格式 / 文件指纹）。
     */
    private val indexedBook: Flow<ComicBook?> = repo.books
        .map { books -> books.firstOrNull { it.id == bookId } }
        .distinctUntilChangedBy { book ->
            book?.let {
                listOf(it.id, it.title, it.kind, it.extension, it.reflowable, it.modifiedAt, it.direction)
            }
        }

    /**
     * Primary state. Derived from the repository index + one-time preparation result, so progress
     * updates and re-indexing flow through without extra file access.
     */
    val uiState: StateFlow<ReaderUiState> =
        combine(repo.ready, indexedBook, prepareState, textState) { ready, indexed, prepare, text ->
            stateFor(ready, indexed, prepare, text)
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                stateFor(
                    repo.ready.value,
                    repo.books.value.firstOrNull { it.id == bookId },
                    prepareState.value,
                    textState.value,
                ),
            )

    /** The book currently being read, or null while loading / missing / failed. */
    val book: StateFlow<ComicBook?> = uiState
        .map { state ->
            when (state) {
                is ReaderUiState.Ready -> state.book
                is ReaderUiState.TextReady -> state.book
                else -> null
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            initialBook(),
        )

    private fun initialBook(): ComicBook? = when (
        val state = stateFor(
            repo.ready.value,
            repo.books.value.firstOrNull { it.id == bookId },
            prepareState.value,
            textState.value,
        )
    ) {
        is ReaderUiState.Ready -> state.book
        is ReaderUiState.TextReady -> state.book
        else -> null
    }

    init {
        app.addBitmapCacheTrimmer(cacheTrimmer)
        // bookkeeping only: never touches the page source and runs off the main thread.
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.markOpened(bookId) } }
        viewModelScope.launch { observePrepareTarget() }
    }

    /**
     * Reacts to the book appearing / changing in the index:
     * - reflowable EPUB: extract chapters on IO (no page source, no prepare);
     * - image book: prepare the page source (and auto-retry when a background conversion finishes).
     * Re-indexing (modifiedAt changes) reloads the content; deleting the book resets both states.
     */
    private suspend fun observePrepareTarget() {
        indexedBook.collect { indexed ->
            if (indexed == null) {
                    prepareState.value = PrepareState.Idle
                    textState.value = TextLoadState.Idle
                } else if (indexed.reflowable) {
                    when (textState.value) {
                        is TextLoadState.Loading, is TextLoadState.Done -> Unit
                        else -> startLoadChapters(indexed)
                    }
                } else {
                    when (prepareState.value) {
                        is PrepareState.Done, is PrepareState.Running -> Unit
                        else -> startPrepare(indexed)
                    }
                }
            }
    }

    private fun startLoadChapters(indexed: ComicBook) {
        if (released) return
        if (textJob?.isActive == true) return
        textJob = viewModelScope.launch(Dispatchers.IO) {
            textState.value = TextLoadState.Loading
            // Data layer returns an empty list when extraction fails (contract), so an empty result
            // is treated as a retryable failure instead of an empty book.
            val chapters = runCatching { repo.loadEbookChapters(indexed) }.getOrNull()
            textState.value = if (chapters.isNullOrEmpty()) {
                TextLoadState.Failed
            } else {
                TextLoadState.Done(chapters)
            }
        }
    }

    private fun startPrepare(indexed: ComicBook) {
        if (released) return
        if (prepareJob?.isActive == true) return
        prepareJob = viewModelScope.launch(Dispatchers.IO) {
            prepareState.value = PrepareState.Running
            val pageCount: Int? = runCatching {
                val pageSource = ensureSource(indexed) ?: return@runCatching null
                val count = pageSource.prepare().takeIf { it > 0 }
                if (count != null) {
                    runCatching { pageSource.pageSize(0) }.getOrNull()?.let { size ->
                        _tallPages.value = size.height > size.width * TALL_PAGE_ASPECT_RATIO
                    }
                }
                count
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

    /** 页像素尺寸（只读图片头，带缓存）；仅条漫分块渲染使用。 */
    suspend fun pageSize(index: Int): Size? {
        pageSizes.get(index)?.let { return it }
        val ready = uiState.value as? ReaderUiState.Ready ?: return null
        return withContext(Dispatchers.IO) {
            val src = ensureSource(ready.book) ?: return@withContext null
            runCatching { src.pageSize(index) }.getOrNull()?.also { pageSizes.put(index, it) }
        }
    }

    /** 打开按区域解码的页；调用方负责在离开组合时 close。 */
    suspend fun openRegionPage(index: Int): RegionPage? {
        val ready = uiState.value as? ReaderUiState.Ready ?: return null
        return withContext(Dispatchers.IO) {
            val src = ensureSource(ready.book) ?: return@withContext null
            runCatching { src.openRegionPage(index) }.getOrNull()
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

    /** UI retry after [ReaderUiState.TextFailed]: reload and re-extract the chapters. */
    fun retryLoadChapters() {
        if (released || textState.value is TextLoadState.Loading) return
        textJob?.cancel()
        textJob = null
        textState.value = TextLoadState.Idle
        repo.books.value.firstOrNull { it.id == bookId }?.takeIf { it.reflowable }?.let(::startLoadChapters)
    }

    /**
     * Persists the text reading position as a character offset. The repository maps it back to the
     * estimated shelf page, handles debounced persistence and marks the book finished near the end.
     */
    fun updateTextProgress(offset: Long) {
        repo.updateTextProgress(bookId, offset)
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
        return withContext(Dispatchers.IO) { decodePage(key, index, bucket, ready.book) }
    }

    /**
     * 解码一页：同一个 key 上只有一次解码（避免预取和可见页重复解同一张），
     * 不同页之间最多并行 [DECODE_CONCURRENCY] 张，不再被一把全局锁串行化。
     */
    private suspend fun decodePage(key: String, index: Int, bucket: Int, book: ComicBook): Bitmap? =
        pageLocks.computeIfAbsent(key) { Mutex() }.withLock page@ {
            cache.get(key)?.let { return@page it }
            decodeLimiter.withPermit {
                val pageSource = ensureSource(book) ?: return@withPermit null
                val bitmap = runCatching { pageSource.loadPage(index, bucket * 240) }.getOrNull()
                    ?: return@withPermit null
                cache.put(key, bitmap)
                bitmap
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
     * the cache or waits on that page's own lock and then finds the bitmap already cached. Decodes
     * run at most [DECODE_CONCURRENCY] at a time (see [decodeLimiter]); lock order is always
     * page lock -> decode limiter -> sourceMutex, never the other way around, so this cannot deadlock.
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

    /** 立即把未落盘的阅读进度写盘（Activity 切后台 / 退出阅读器时调用）。 */
    fun flushProgress() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.flushProgress() } }
    }

    fun setDirection(direction: ReadingDirection) {
        viewModelScope.launch(Dispatchers.IO) { repo.setDirection(bookId, direction) }
    }

    override fun onCleared() {
        super.onCleared()
        app.removeBitmapCacheTrimmer(cacheTrimmer)
        released = true
        prefetchJob?.cancel()
        prefetchJob = null
        prepareJob?.cancel()
        prepareJob = null
        textJob?.cancel()
        textJob = null
        runCatching { source?.close() }
        source = null
        cache.evictAll()
    }

    private companion object {
        /** 同时解码的页数上限：过大只会增加内存峰值，2 足以覆盖相邻页预取。 */
        const val DECODE_CONCURRENCY = 2

        /** 页高宽比超过该值即视为条漫，垂直模式改用分块解码。 */
        const val TALL_PAGE_ASPECT_RATIO = 3f
    }

    class Factory(private val application: Application, private val bookId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(application, bookId) as T
    }
}
