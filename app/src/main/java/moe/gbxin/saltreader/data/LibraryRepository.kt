package moe.gbxin.saltreader.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.gbxin.saltreader.R
import org.rr.mobi4java.MobiReader
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * 书库仓库：所有书籍都通过持久化 SAF tree URI 就地索引，应用私有目录只放派生数据
 * （封面、文字书章节提取缓存）。不做任何文件复制/格式转换。
 *
 * 线程约定：
 * - 公开的 suspend 方法内部切到 Dispatchers.IO，并保证锁的临界区尽量短；
 * - `pageSource()`/`roots()` 同步返回，不做 I/O（roots 走内存缓存）；
 * - `updateProgress()`/`markOpened()`/`updateTextProgress()` 只改内存并调度防抖落盘。
 */
class LibraryRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 状态锁：只保护内存快照的读改写，临界区里不做序列化 / 写盘。 */
    private val mutex = Mutex()

    /** 写盘串行化：序列化 + 原子写文件放到状态锁之外，但同一时刻只允许一个写入者。 */
    private val writeMutex = Mutex()

    /** 书库索引用紧凑 JSON：只保留非默认字段，体积和 encode 时间都明显下降。 */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val libraryDir = File(context.filesDir, "library")
    private val coversDir = File(libraryDir, "covers")
    private val booksFile = File(libraryDir, "books.json")

    /** 高频变化的阅读进度单独落盘（紧凑 JSON），不再随每次翻页重写整个书库。 */
    private val progressFile = File(libraryDir, "progress.json")

    /** 文字书章节提取缓存：cacheDir/text/<id>_<modifiedAt>_<size>.html */
    private val textCacheDir = File(context.cacheDir, "text")

    /** 旧版本拍平转换遗留目录，初始化/清缓存时清理掉。 */
    private val obsoleteCacheDirs = listOf(
        File(context.cacheDir, "books"),
        File(context.cacheDir, "ebooks"),
    )

    private val rootsStore = LibraryRootsStore(context)

    private val _books = MutableStateFlow<List<ComicBook>>(emptyList())
    val books: StateFlow<List<ComicBook>> = _books.asStateFlow()

    /** id -> 列表下标：翻页 / 标记已读时按 id 直接定位，不再线性扫描整张表。 */
    @Volatile
    private var bookIndex: Map<String, Int> = emptyMap()

    /**
     * 阅读进度覆盖层。内存里与 [_books] 保持一致，磁盘上单独写 [progressFile]；
     * 载入索引时用它覆盖 books.json 里可能更旧的进度字段。
     */
    private val progressEntries = ConcurrentHashMap<String, ReadingProgress>()

    /** 自上次落盘以来进度发生过变化的书籍 id。 */
    private val dirtyProgress: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** 进度变更的合并信号：CONFLATED 保证只保留“还有未落盘变更”这一事实。 */
    private val persistSignal = Channel<Unit>(Channel.CONFLATED)

    /** 阅读进度的合并信号，与书库索引的持久化彻底分开。 */
    private val progressSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { persistWorker() }
        scope.launch { progressWorker() }
    }

    // -----------------------------------------------------------------------
    // 启动
    // -----------------------------------------------------------------------

    /**
     * 启动初始化：加载索引 → 校验 SAF 持久化权限（失效条目丢弃）→ 生成缺失封面 → 清理旧缓存。
     * 不做任何旧版本数据迁移。
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        libraryDir.mkdirs()
        coversDir.mkdirs()

        val loaded = readBooksFromDisk()
        val persistedTrees = context.contentResolver.persistedUriPermissions
            .mapTo(HashSet()) { it.uri.toString() }
        val kept = loaded.filter { it.treeUri in persistedTrees }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        val dropped = loaded.filterNot { it.id in keptIds }

        // 阅读进度是独立的小文件：覆盖 books.json 里的旧进度字段。
        progressEntries.putAll(readProgressFromDisk())
        val merged = kept.map { book -> progressEntries[book.id]?.applyTo(book) ?: book }
        dropped.forEach { progressEntries.remove(it.id) }

        // 预热 roots 缓存，并同步剔除丢失持久化权限的目录，避免刷新时反复报错。
        val roots = rootsStore.all()
        val keptRoots = roots.filter { it.treeUri in persistedTrees }
        if (keptRoots.size != roots.size) {
            runCatching { rootsStore.save(keptRoots) }
                .onFailure { Log.e(TAG, "写入 roots.json 失败", it) }
        }

        val snapshot = mutex.withLock {
            publish(sortBooks(merged))
            _books.value
        }
        if (merged != loaded) persistBooks(snapshot)

        // 权限失效的条目不留在书架上，其派生数据也一并清掉。
        dropped.forEach { removeDerivedFiles(it) }
        // 封面是派生数据且绝大多数已经存在：并发补齐，避免串行打开文档拖慢启动。
        ensureCovers(kept)
        // 旧版本拍平转换留下的缓存目录不再使用。
        obsoleteCacheDirs.forEach { dir -> if (dir.exists()) runCatching { dir.deleteRecursively() } }
        _ready.value = true
        Unit
    }

    private fun readBooksFromDisk(): List<ComicBook> = runCatching {
        if (!booksFile.exists()) emptyList()
        else json.decodeFromString<List<ComicBook>>(booksFile.readText(Charsets.UTF_8))
    }.getOrElse { error ->
        // 不做迁移：解析失败直接重置索引，保留一份 corrupt 备份便于排查。
        Log.e(TAG, "books.json 解析失败，重置书库索引", error)
        runCatching {
            val backup = File(libraryDir, "books.corrupt.json")
            backup.delete()
            booksFile.renameTo(backup)
        }
        emptyList()
    }

    // -----------------------------------------------------------------------
    // 索引 / 刷新
    // -----------------------------------------------------------------------

    /** 就地索引一个 SAF 文件夹：不复制任何用户文件，只记录元数据并生成派生封面。 */
    suspend fun importLibraryTree(
        uri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): ImportReport = withContext(Dispatchers.IO) {
        if (!DocumentsContract.isTreeUri(uri)) {
            return@withContext ImportReport(failures = listOf(failure(R.string.data_source_folder, DataMessage.SELECT_FOLDER_FAILED)))
        }
        val tree = runCatching { SafTree(context, uri) }.getOrNull()
            ?: return@withContext ImportReport(
                failures = listOf(failure(R.string.data_source_folder, DataMessage.FOLDER_ACCESS_FAILED)),
            )
        val persistable = takePersistableReadPermission(context, uri)
        val root = tree.rootEntry()
            ?: return@withContext ImportReport(
                failures = listOf(failure(R.string.data_source_folder, DataMessage.FOLDER_READ_FAILED)),
            )

        val result = try {
            scanTree(tree, 0f..1f, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "扫描目录失败：${root.name}", e)
            return@withContext ImportReport(failures = listOf(failure(root.name, DataMessage.SCAN_FAILED)))
        }
        if (!result.completed) {
            return@withContext ImportReport(failures = result.failures)
        }
        val persisted = mergeTreeResult(result)
        runCatching { rootsStore.add(LibraryRoot(uri.toString(), root.name)) }
            .onFailure { Log.e(TAG, "写入 roots.json 失败", it) }

        val failures = result.failures.toMutableList()
        if (!persistable) {
            failures += failure(root.name, DataMessage.PERMISSION_NOT_PERSISTED)
        }
        if (!persisted) failures += failure(root.name, DataMessage.INDEX_SAVE_FAILED)
        if (result.scanned.isEmpty() && failures.isEmpty()) {
            failures += failure(root.name, DataMessage.NO_SUPPORTED_BOOKS)
        }
        ImportReport(result.scanned, failures)
    }

    /** 增量刷新所有已添加的 root：指纹（modifiedAt + sizeBytes）未变的书不重新打开。 */
    suspend fun refreshLibrary(
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): ImportReport = withContext(Dispatchers.IO) {
        val roots = rootsStore.all()
        if (roots.isEmpty()) {
            return@withContext ImportReport(
                failures = listOf(failure(R.string.data_source_library, DataMessage.NO_REFRESHABLE_ROOTS)),
            )
        }
        val failures = ArrayList<ImportFailure>()
        val completed = ArrayList<TreeScanResult>()
        for ((index, rootInfo) in roots.withIndex()) {
            coroutineContext.ensureActive()
            val span = 1f / roots.size
            val start = index * span
            onProgress(start, context.getString(R.string.data_progress_refreshing, rootInfo.displayName))

            val uri = runCatching { Uri.parse(rootInfo.treeUri) }.getOrNull()
            if (uri == null || !DocumentsContract.isTreeUri(uri)) {
                failures += failure(rootInfo.displayName, DataMessage.INVALID_ROOT)
                continue
            }
            takePersistableReadPermission(context, uri)
            val tree = runCatching { SafTree(context, uri) }.getOrNull()
            if (tree == null) {
                failures += failure(rootInfo.displayName, DataMessage.ROOT_UNREADABLE)
                continue
            }
            val result = try {
                scanTree(tree, start..(start + span), onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "刷新目录失败：${rootInfo.displayName}", e)
                failures += failure(rootInfo.displayName, DataMessage.REFRESH_FAILED)
                continue
            }
            failures += result.failures
            // 目录枚举失败时整棵 tree 放弃合并，避免把仍存在的书记录删掉。
            if (!result.completed) continue
            completed += result
        }
        // 多 root 刷新只在最后合并 / 落盘一次，避免每个 root 都重写一遍整库。
        if (completed.isNotEmpty() && !mergeTreeResults(completed)) {
            failures += failure(R.string.data_source_library, DataMessage.SAVE_FAILED)
        }
        onProgress(1f, context.getString(R.string.data_progress_done))
        ImportReport(emptyList(), failures)
    }

    private data class ScanCandidate(
        val documentPath: String,
        val name: String,
        val kind: BookKind,
        val sizeBytes: Long,
        val modifiedAt: Long,
        val uri: Uri,
        val imageCount: Int,
        /** 图片文件夹书自然序的第一页；索引阶段可直接用它生成封面。 */
        val coverUri: Uri?,
    )

    private data class TreeScanResult(
        val treeUri: String,
        val scanned: List<ComicBook>,
        val failures: List<ImportFailure>,
        val completed: Boolean,
    )

    /** 索引阶段算出的页数/封面/书籍分类（EPUB/MOBI 可能是图片型 ARCHIVE 或文字型 TEXT）。 */
    private data class IndexedPages(
        val pageCount: Int,
        val cover: Bitmap?,
        /** null = 沿用候选条目的 kind。 */
        val kind: BookKind? = null,
        val reflowable: Boolean = false,
        val charCount: Long = 0L,
    )

    /**
     * 扫描一棵 tree：先只在局部集合里收集候选（不持锁），最后才短临界区合并。
     * 目录枚举出错时返回 completed=false，调用方必须放弃合并。
     */
    private suspend fun scanTree(
        tree: SafTree,
        progress: ClosedFloatingPointRange<Float>,
        onProgress: (Float, String) -> Unit,
    ): TreeScanResult {
        val treeUri = tree.treeUri.toString()
        val failures: MutableList<ImportFailure> = CopyOnWriteArrayList()
        val root = tree.rootEntry()
        if (root == null) {
            return TreeScanResult(
                treeUri,
                emptyList(),
                listOf(failure(R.string.data_source_folder, DataMessage.FOLDER_READ_FAILED)),
                false,
            )
        }
        onProgress(progress.start, context.getString(R.string.data_progress_scanning, root.name))

        val candidates = ArrayList<ScanCandidate>()
        if (!walkTree(tree, root, 0, candidates, failures)) {
            if (failures.isEmpty()) failures += failure(root.name, DataMessage.DIRECTORY_UNREADABLE)
            return TreeScanResult(treeUri, emptyList(), failures, false)
        }

        val existing = _books.value
            .filter { it.treeUri == treeUri }
            .associateBy { it.documentPath }

        val total = candidates.size
        val done = AtomicInteger(0)
        // 每本书都要打开 provider / 解析中央目录，串行导入的时间与书数线性相加；
        // 每本使用独立的 reader、互不共享状态，因此可以用有界并发把大库导入压到接近 1/N。
        val books = coroutineScope {
            val limiter = Semaphore(SCAN_CONCURRENCY)
            candidates.map { candidate ->
                async(Dispatchers.IO) {
                    limiter.withPermit {
                        coroutineContext.ensureActive()
                        val index = done.incrementAndGet()
                        val fraction = if (total == 0) 1f else (index - 1).toFloat() / total
                        onProgress(
                            progress.start + (progress.endInclusive - progress.start) * fraction,
                            context.getString(R.string.data_progress_indexed, index, total),
                        )
                        try {
                            indexCandidate(treeUri, candidate, existing[candidate.documentPath], failures)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "索引失败：${candidate.name}", e)
                            failures += failure(candidate.name, DataMessage.READ_FAILED)
                            null
                        }
                    }
                }
            }.awaitAll()
        }
        val scanned = ArrayList<ComicBook>(books.size)
        books.filterNotNullTo(scanned)
        onProgress(progress.endInclusive, context.getString(R.string.data_progress_done))
        return TreeScanResult(treeUri, scanned, failures, true)
    }

    private suspend fun walkTree(
        tree: SafTree,
        dir: SafTree.Entry,
        depth: Int,
        out: MutableList<ScanCandidate>,
        failures: MutableList<ImportFailure>,
    ): Boolean {
        coroutineContext.ensureActive()
        if (depth > MAX_SCAN_DEPTH) return true
        val children = tree.children(dir.documentId, dir.documentPath)
        if (children == null) {
            failures += failure(dir.name, DataMessage.DIRECTORY_UNREADABLE)
            return false
        }

        val bookFiles = ArrayList<SafTree.Entry>(4)
        val imageFiles = ArrayList<SafTree.Entry>()
        var hasSubdirectory = false

        for (child in children) {
            coroutineContext.ensureActive()
            if (child.isDirectory) {
                hasSubdirectory = true
                if (!shouldSkipDirectory(child.name)) {
                    if (!walkTree(tree, child, depth + 1, out, failures)) return false
                }
            } else {
                val ext = child.name.substringAfterLast('.', "").lowercase()
                if (ext in BOOK_EXTENSIONS) {
                    bookFiles += child
                } else if (PageIndexing.isComicImageName(child.name)) {
                    imageFiles += child
                }
            }
        }

        for (file in bookFiles) {
            val ext = file.name.substringAfterLast('.', "").lowercase()
            out += ScanCandidate(
                documentPath = file.documentPath,
                name = file.name,
                kind = if (ext == "pdf") BookKind.PDF else BookKind.ARCHIVE,
                sizeBytes = file.sizeBytes,
                modifiedAt = file.lastModified,
                uri = file.uri,
                imageCount = 0,
                coverUri = null,
            )
        }
        // 图片文件夹书：直接子级至少 3 张图片且没有子目录（沿用旧策略）。
        if (imageFiles.size >= 3 && !hasSubdirectory) {
            imageFiles.sortWith(compareBy(PageNameComparator) { it.name })
            out += ScanCandidate(
                documentPath = dir.documentPath,
                name = dir.name,
                kind = BookKind.FOLDER,
                sizeBytes = imageFiles.sumOf { it.sizeBytes },
                modifiedAt = imageFiles.maxOfOrNull { it.lastModified } ?: 0L,
                uri = dir.uri,
                imageCount = imageFiles.size,
                coverUri = imageFiles.first().uri,
            )
        }
        return true
    }

    private suspend fun indexCandidate(
        treeUri: String,
        candidate: ScanCandidate,
        old: ComicBook?,
        failures: MutableList<ImportFailure>,
    ): ComicBook? {
        val ext = candidate.name.substringAfterLast('.', "").lowercase()
        val isEpub = candidate.kind == BookKind.ARCHIVE && ext == "epub"
        val isMobi = candidate.kind == BookKind.ARCHIVE && ext in MOBI_EXTENSIONS
        val textCandidate = isEpub || isMobi

        // 增量刷新：指纹未变且封面还在，直接复用旧记录，不重新打开文件。
        // 文字书的 kind 是 TEXT，而扫描候选是 ARCHIVE，需要视为同一本书。
        val kindMatches = old != null &&
            (old.kind == candidate.kind || (textCandidate && old.kind == BookKind.TEXT))
        if (kindMatches && old.modifiedAt == candidate.modifiedAt &&
            old.sizeBytes == candidate.sizeBytes && old.pageCount > 0
        ) {
            if (!old.reflowable && !coverFile(old).exists()) writeCover(old)
            return old
        }

        val id = old?.id ?: stableBookId(treeUri, candidate.documentPath)
        // 文件变了：旧封面/文字缓存已失效，先删掉。
        if (textCandidate && old != null) {
            coverFile(old).delete()
            deleteTextCache(id)
        }
        val indexed = try {
            indexPages(candidate, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "索引失败：${candidate.name}", e)
            failures += failure(candidate.name, DataMessage.READ_FAILED)
            return old
        }
        val pageCount = indexed.pageCount
        if (pageCount <= 0) {
            indexed.cover?.recycle()
            failures += failure(candidate.name, DataMessage.NO_READABLE_PAGES)
            return old
        }

        val title = old?.title
            ?: titleFromName(candidate.name)
            ?: context.getString(R.string.data_untitled_comic)
        val type = indexed.kind ?: candidate.kind
        val progressOffset = if (indexed.reflowable) {
            (old?.progressOffset ?: 0L).coerceIn(0L, max(0L, indexed.charCount))
        } else {
            0L
        }
        val estimatedPage = if (indexed.reflowable && indexed.charCount > 0L) {
            ((progressOffset.toDouble() / indexed.charCount) * pageCount).toInt()
        } else {
            old?.currentPage ?: 0
        }
        val book = ComicBook(
            id = id,
            title = title,
            author = old?.author,
            description = old?.description,
            kind = type,
            treeUri = treeUri,
            documentPath = candidate.documentPath,
            documentUri = candidate.uri.toString(),
            pageCount = pageCount,
            sizeBytes = candidate.sizeBytes,
            addedAt = old?.addedAt ?: System.currentTimeMillis(),
            lastReadAt = old?.lastReadAt,
            currentPage = estimatedPage.coerceIn(0, max(0, pageCount - 1)),
            finished = old?.finished ?: false,
            accent = old?.accent ?: accentFor(title),
            direction = old?.direction,
            modifiedAt = candidate.modifiedAt,
            reflowable = indexed.reflowable,
            progressOffset = progressOffset,
            charCount = indexed.charCount,
        )
        // 文字书没有固定页封面：EPUB 用占位；MOBI 的封面在章节解析时从文档内提取。
        if (!coverFile(book).exists()) {
            if (indexed.cover != null) {
                writeCover(book, indexed.cover)
            } else if (!indexed.reflowable) {
                writeCover(book)
            }
        } else {
            indexed.cover?.recycle()
        }
        return book
    }

    /**
     * 索引阶段取页数与封面（零复制、零转换）：
     * - ZIP/CBZ：RemoteZipReader 只读中央目录统计图片条目，再按条目读第一张图当封面；
     *   provider 不支持 seek 时回退到流式 ZipInputStream 统计。
     * - EPUB：RemoteZipReader 解析 container→OPF→spine，图片型按 Zip 直读，文字型只估算字符数/页数。
     * - MOBI/AZW3：按文件大小估算字符数/页数，作为 TEXT 文字书直接阅读，不转换。
     */
    private fun indexPages(candidate: ScanCandidate, bookId: String): IndexedPages = when (candidate.kind) {
        BookKind.FOLDER ->
            IndexedPages(candidate.imageCount, candidate.coverUri?.let(::readDocumentBitmap))
        BookKind.PDF ->
            IndexedPages(PageIndexing.pdfPageCount(context.contentResolver, candidate.uri), null)
        BookKind.TEXT ->
            mobileTextIndex(candidate.sizeBytes)
        BookKind.ARCHIVE -> {
            val ext = candidate.name.substringAfterLast('.', "").lowercase()
            when {
                ext == "epub" -> indexEpub(candidate, bookId)
                ext in MOBI_EXTENSIONS -> mobileTextIndex(candidate.sizeBytes)
                else -> indexZipFast(candidate)
            }
        }
    }

    /** MOBI/AZW3 按体积估算字符数：charCount = size * 0.6（下限 1），页数用于书架进度。 */
    private fun mobileTextIndex(sizeBytes: Long): IndexedPages {
        val charCount = EpubSpineClassifier.estimateCharCount(sizeBytes)
        return IndexedPages(
            pageCount = EpubSpineClassifier.estimatePageCount(charCount),
            cover = null,
            kind = BookKind.TEXT,
            reflowable = true,
            charCount = charCount,
        )
    }

    /**
     * EPUB 分类索引：图片型 → ARCHIVE（封面直接读第一张图，零派生缓存）；
     * 文字型 → TEXT（charCount 由 spine XHTML 未压缩大小估算）。
     * 解析失败时保守回退成文字型，保证仍可直接阅读。
     */
    private fun indexEpub(candidate: ScanCandidate, bookId: String): IndexedPages {
        val reader = RemoteZipReader.open(context, candidate.uri)
        if (reader != null) {
            return reader.use { opened ->
                val classification = runCatching { EpubIndexer.classify(opened) }.getOrNull()
                if (classification == null) {
                    epubTextFallback(candidate.sizeBytes)
                } else if (classification.kind == BookKind.ARCHIVE) {
                    // 索引阶段已经算出了 spine→图片映射：顺手缓存，避免首次打开再扫一遍 spine。
                    // 预算耗尽时列表里含占位条目名，与打开时重新解析的结果一致。
                    writeEpubPageMapCache(
                        context,
                        bookId,
                        candidate.modifiedAt,
                        candidate.sizeBytes,
                        classification.imageEntries,
                    )
                    val coverBytes = classification.imageEntries.firstOrNull()?.let { entry ->
                        opened.readEntry(entry, PageIndexing.MAX_PAGE_BYTES)
                    }
                    IndexedPages(
                        pageCount = classification.pageCount.coerceAtLeast(1),
                        cover = coverBytes?.let { decodePage(it, COVER_WIDTH) },
                        kind = BookKind.ARCHIVE,
                        reflowable = false,
                    )
                } else {
                    IndexedPages(
                        pageCount = classification.pageCount,
                        cover = null,
                        kind = BookKind.TEXT,
                        reflowable = true,
                        charCount = classification.charCount,
                    )
                }
            }
        }
        return epubTextFallback(candidate.sizeBytes)
    }

    /** EPUB 元数据解析失败时的保守回退：按文件大小粗估字符数，交给文字阅读器。 */
    private fun epubTextFallback(sizeBytes: Long): IndexedPages {
        val charCount = EpubSpineClassifier.estimateCharCount(if (sizeBytes > 0L) sizeBytes / 2 else 1L)
        return IndexedPages(
            pageCount = EpubSpineClassifier.estimatePageCount(charCount),
            cover = null,
            kind = BookKind.TEXT,
            reflowable = true,
            charCount = charCount,
        )
    }

    /** CBZ：远程读中央目录（快），失败回退流式统计（慢但兼容）。 */
    private fun indexZipFast(candidate: ScanCandidate): IndexedPages {
        val remote = RemoteZipReader.open(context, candidate.uri)
        if (remote != null) {
            return remote.use { reader ->
                val images = reader.imageEntries()
                val coverBytes = images.firstOrNull()?.let { entry ->
                    reader.readEntry(entry.name, PageIndexing.MAX_PAGE_BYTES)
                }
                IndexedPages(images.size, coverBytes?.let { decodePage(it, COVER_WIDTH) })
            }
        }
        val index = context.contentResolver.openInputStream(candidate.uri)?.use { input ->
            PageIndexing.indexZip(input)
        } ?: PageIndexing.ZipIndex(0, null)
        return IndexedPages(index.pageCount, index.coverBytes?.let { decodePage(it, COVER_WIDTH) })
    }

    private fun readDocumentBitmap(uri: Uri): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use { input -> readBounded(input) }
            ?.let { decodePage(it, COVER_WIDTH) }
    } catch (e: Exception) {
        Log.w(TAG, "读取图片失败：$uri", e)
        null
    }

    private suspend fun mergeTreeResult(result: TreeScanResult): Boolean =
        mergeTreeResults(listOf(result))

    /**
     * 合并一批 tree 扫描结果：状态锁内只做列表合并，序列化 + 落盘放到锁外。
     * 扫描期间用户可能还在阅读，因此用进度覆盖层兜底，避免旧记录把新进度盖回去。
     */
    private suspend fun mergeTreeResults(results: List<TreeScanResult>): Boolean {
        if (results.isEmpty()) return true
        val treeUris = results.mapTo(HashSet()) { it.treeUri }
        val next = mutex.withLock {
            val survivors = _books.value.filterNot { it.treeUri in treeUris }
            val scanned = results.flatMap { it.scanned }
                .map { book -> progressEntries[book.id]?.applyTo(book) ?: book }
            publish(sortBooks(survivors + scanned))
            _books.value
        }
        return persistBooks(next)
    }

    // -----------------------------------------------------------------------
    // 目录 / 删除 / 元数据
    // -----------------------------------------------------------------------

    fun roots(): List<LibraryRoot> = rootsStore.all()

    suspend fun removeRoot(treeUri: String) = withContext(Dispatchers.IO) {
        val (removed, next) = mutex.withLock {
            val affected = _books.value.filter { it.treeUri == treeUri }
            publish(_books.value.filterNot { it.treeUri == treeUri })
            affected.forEach { dropProgressEntry(it.id) }
            affected to _books.value
        }
        persistBooks(next)
        if (removed.isNotEmpty()) deleteTextCaches(removed.map { it.id })
        runCatching { rootsStore.remove(treeUri) }
            .onFailure { Log.e(TAG, "写入 roots.json 失败", it) }
        runCatching { Uri.parse(treeUri) }.getOrNull()?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        Unit
    }

    /** 只删除索引、封面与文本缓存；SAF 引用的用户文件绝不触碰。 */
    suspend fun delete(book: ComicBook) = withContext(Dispatchers.IO) {
        val next = mutex.withLock {
            publish(_books.value.filterNot { it.id == book.id })
            dropProgressEntry(book.id)
            _books.value
        }
        persistBooks(next)
        removeDerivedFiles(book)
        Unit
    }

    suspend fun rename(book: ComicBook, title: String): Boolean = withContext(Dispatchers.IO) {
        val clean = title.trim().take(120)
        if (clean.isBlank()) return@withContext false
        val next = mutex.withLock {
            publish(_books.value.map { if (it.id == book.id) it.copy(title = clean) else it })
            _books.value
        }
        persistBooks(next)
    }

    suspend fun setFinished(book: ComicBook, finished: Boolean): Boolean = withContext(Dispatchers.IO) {
        val next = mutex.withLock {
            val now = System.currentTimeMillis()
            publish(
                _books.value.map {
                    if (it.id == book.id) {
                        it.copy(
                            finished = finished,
                            currentPage = if (finished) max(0, it.pageCount - 1) else 0,
                            lastReadAt = if (finished) now else it.lastReadAt,
                        )
                    } else {
                        it
                    }
                },
            )
            _books.value.firstOrNull { it.id == book.id }?.let(::refreshProgressEntry)
            _books.value
        }
        persistBooks(next)
    }

    suspend fun setDirection(bookId: String, direction: ReadingDirection?) = withContext(Dispatchers.IO) {
        val next = mutex.withLock {
            publish(_books.value.map { if (it.id == bookId) it.copy(direction = direction) else it })
            _books.value
        }
        persistBooks(next)
        Unit
    }

    /**
     * 翻页进度：只更新内存快照 + 进度覆盖层，并调度 progress.json 的独立写盘。
     * 不再触发 books.json 的整库重写。
     */
    fun updateProgress(bookId: String, page: Int) {
        val index = indexOfBook(bookId)
        if (index < 0) return
        val books = _books.value
        val current = books[index]
        val safePage = page.coerceIn(0, max(0, current.pageCount - 1))
        // 停在同一页时不刷新 lastReadAt：否则已读完的书每次退出阅读器都会触发一次写盘。
        if (current.currentPage == safePage) return
        val updated = current.copy(
            currentPage = safePage,
            lastReadAt = System.currentTimeMillis(),
            finished = current.pageCount > 0 && safePage >= current.pageCount - 1,
        )
        publish(books.toMutableList().also { it[index] = updated }, rebuildIndex = false)
        refreshProgressEntry(updated)
    }

    /** 打开书时只更新「最近阅读时间」，同样走进度文件而不是整库写盘。 */
    fun markOpened(bookId: String) {
        val index = indexOfBook(bookId)
        if (index < 0) return
        val books = _books.value
        val updated = books[index].copy(lastReadAt = System.currentTimeMillis())
        publish(books.toMutableList().also { it[index] = updated }, rebuildIndex = false)
        refreshProgressEntry(updated)
    }

    /** 阅读器 prepare() 拿到真实页数后回填（结构变化，仍然写 books.json）。 */
    fun updatePageCount(bookId: String, pageCount: Int) {
        if (pageCount <= 0) return
        val index = indexOfBook(bookId)
        if (index < 0) return
        val books = _books.value
        val current = books[index]
        if (current.pageCount == pageCount) return
        val updated = current.copy(
            pageCount = pageCount,
            currentPage = current.currentPage.coerceIn(0, max(0, pageCount - 1)),
        )
        publish(books.toMutableList().also { it[index] = updated })
        refreshProgressEntry(updated)
        schedulePersist()
    }

    /**
     * 文字书解析后回填真实字符数/页数：charCount 是文字阅读进度的唯一权威分母，
     * pageCount 仅用于书架进度显示。
     */
    fun updateTextStats(bookId: String, charCount: Long) {
        if (charCount <= 0L) return
        val index = indexOfBook(bookId)
        if (index < 0) return
        val books = _books.value
        val current = books[index]
        if (!current.reflowable || current.charCount == charCount) return
        val pageCount = EpubSpineClassifier.estimatePageCount(charCount)
        val offset = current.progressOffset.coerceIn(0L, charCount)
        val page = ((offset.toDouble() / charCount) * pageCount)
            .toInt()
            .coerceIn(0, max(0, pageCount - 1))
        val updated = current.copy(
            charCount = charCount,
            pageCount = pageCount,
            progressOffset = offset,
            currentPage = page,
        )
        publish(books.toMutableList().also { it[index] = updated })
        refreshProgressEntry(updated)
        schedulePersist()
    }

    /**
     * 文字阅读进度回填：progressOffset 是唯一权威位置，currentPage 仅用于书架进度显示。
     * offset ≥ charCount * 99% 时置为已读完；写盘走进度文件的独立节流。
     */
    fun updateTextProgress(bookId: String, offset: Long) {
        val index = indexOfBook(bookId)
        if (index < 0) return
        val books = _books.value
        val current = books[index]
        if (!current.reflowable || current.charCount <= 0L) return
        val safeOffset = offset.coerceIn(0L, current.charCount)
        val finished = current.finished || safeOffset >= (current.charCount * 0.99).toLong()
        if (current.progressOffset == safeOffset && current.finished == finished) return
        val page = ((safeOffset.toDouble() / current.charCount) * current.pageCount)
            .toInt()
            .coerceIn(0, max(0, current.pageCount - 1))
        val updated = current.copy(
            progressOffset = safeOffset,
            currentPage = page,
            finished = finished,
            lastReadAt = System.currentTimeMillis(),
        )
        publish(books.toMutableList().also { it[index] = updated }, rebuildIndex = false)
        refreshProgressEntry(updated)
    }

    // -----------------------------------------------------------------------
    // 文字书章节提取（EPUB 直接读 ZIP；MOBI/AZW3 解析一次后缓存 HTML）
    // -----------------------------------------------------------------------

    /**
     * 文字书章节提取（IO 线程）：
     * - EPUB：RemoteZipReader 按 spine 顺序提取并清洗 XHTML；
     * - MOBI/AZW3：SAF 文档复制到临时文件 → MobiReader 解析 → 段落 HTML，结果缓存到
     *   cacheDir/text/<id>_<mtime>_<size>.html，命中直接返回；同时提取内嵌封面并回填字符数。
     * 失败返回空列表并记录日志。
     */
    suspend fun loadEbookChapters(book: ComicBook): List<EbookChapter> = withContext(Dispatchers.IO) {
        if (!book.reflowable) return@withContext emptyList()
        try {
            when (book.extension) {
                "epub" -> loadEpubChapters(book)
                "mobi", "azw", "azw3" -> loadMobiChapters(book)
                else -> emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "提取文字书章节失败：${book.title}", e)
            emptyList()
        }
    }

    private fun loadEpubChapters(book: ComicBook): List<EbookChapter> {
        // 章节清洗（逐章解压 + 十几遍正则 + 图片内联）是打开文字书最贵的一步；
        // 和 MOBI 一样落盘缓存，命中时直接读 JSON，二次打开几乎零成本。
        val cache = chapterCacheFile(book)
        if (cache.exists() && cache.length() > 0L) {
            val cached = runCatching {
                json.decodeFromString<List<EbookChapter>>(cache.readText(Charsets.UTF_8))
            }.getOrNull()
            if (!cached.isNullOrEmpty()) return cached
        }
        val uri = resolveDocumentUri(context, book.treeUri, book.documentPath) ?: return emptyList()
        val reader = RemoteZipReader.open(context, uri) ?: return emptyList()
        val chapters = reader.use { opened -> EpubIndexer.loadChapters(opened) }
        if (chapters.isNotEmpty()) {
            runCatching {
                val payload = json.encodeToString(chapters).toByteArray(Charsets.UTF_8)
                textCacheDir.mkdirs()
                // 派生缓存：不需要 fsync。
                writeFileAtomically(cache, sync = false) { out -> out.write(payload) }
            }.onFailure { Log.w(TAG, "写入 EPUB 章节缓存失败：${book.title}", it) }
        }
        return chapters
    }

    /** EPUB 章节缓存：派生数据，id + 指纹命名，重新索引时随旧指纹一并失效。 */
    private fun chapterCacheFile(book: ComicBook): File =
        File(textCacheDir, "${book.id}_${book.modifiedAt}_${book.sizeBytes}.chapters.json")

    private suspend fun loadMobiChapters(book: ComicBook): List<EbookChapter> {
        val cache = textCacheFile(book)
        if (cache.exists() && cache.length() > 0L) {
            val cached = runCatching { cache.readText(Charsets.UTF_8) }.getOrNull()
            if (!cached.isNullOrBlank()) {
                return listOf(EbookChapter(book.title, cached))
            }
        }
        val temp = try {
            File.createTempFile("mobi-", ".${book.extension}", context.cacheDir)
        } catch (e: Exception) {
            Log.w(TAG, "无法创建 MOBI 解析临时文件：${book.title}", e)
            return emptyList()
        }
        return try {
            if (!copyDocumentToTemp(book, temp)) return emptyList()
            val document = MobiReader().read(temp)
            val plainText = document.textContent ?: ""
            val html = mobiTextToHtml(plainText)
            if (!coverFile(book).exists()) {
                document.cover?.let { coverBytes ->
                    decodePage(coverBytes, COVER_WIDTH)?.let { bitmap -> writeCover(book, bitmap) }
                }
            }
            textCacheDir.mkdirs()
            runCatching {
                // 派生缓存：不需要 fsync。
                writeFileAtomically(cache, sync = false) { out -> out.write(html.toByteArray(Charsets.UTF_8)) }
            }.onFailure { Log.w(TAG, "写入文字缓存失败：${book.title}", it) }
            updateTextStats(book.id, plainText.length.toLong().coerceAtLeast(1L))
            val title = document.fullName?.takeIf { it.isNotBlank() } ?: book.title
            listOf(EbookChapter(title, html))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "解析 MOBI 失败：${book.title}", e)
            emptyList()
        } finally {
            temp.delete()
        }
    }

    /** MOBI 纯文本 → 简单 HTML：按行包 <p> 并转义。 */
    private fun mobiTextToHtml(text: String): String {
        val builder = StringBuilder(text.length + text.length / 8 + 32)
        // 不用 text.split('\n')：那会为整本书一次性创建几百万元素的行列表。
        var lineStart = 0
        var index = 0
        while (index <= text.length) {
            val atLineEnd = index == text.length || text[index] == '\n'
            if (atLineEnd) {
                val trimmed = text.substring(lineStart, index).trim('\r', ' ', '\t')
                if (trimmed.isNotEmpty()) {
                    builder.append("<p>").append(escapeHtml(trimmed)).append("</p>\n")
                }
                lineStart = index + 1
            }
            index++
        }
        if (builder.isEmpty()) builder.append("<p></p>")
        return builder.toString()
    }

    private fun escapeHtml(text: String): String = buildString(text.length + 16) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }

    private fun textCacheFile(book: ComicBook): File =
        File(textCacheDir, "${book.id}_${book.modifiedAt}_${book.sizeBytes}.html")

    private fun deleteTextCache(bookId: String) {
        textCacheDir.listFiles { file -> file.name.startsWith("${bookId}_") }?.forEach { it.delete() }
    }

    /**
     * 批量删除文字/章节缓存：目录只列举一次，再按文件名前缀里的 id 命中。
     * 逐个 id 调 [deleteTextCache] 在 removeRoot 场景是 O(books × cacheFiles)。
     */
    private fun deleteTextCaches(bookIds: Collection<String>) {
        if (bookIds.isEmpty()) return
        val ids = bookIds.toHashSet()
        textCacheDir.listFiles()?.forEach { file ->
            if (file.name.substringBefore('_') in ids) file.delete()
        }
    }

    private fun copyDocumentToTemp(book: ComicBook, temp: File): Boolean = try {
        val uri = resolveDocumentUri(context, book.treeUri, book.documentPath) ?: return false
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } != null
    } catch (e: Exception) {
        Log.w(TAG, "读取文字书失败：${book.title}", e)
        false
    }

    /** 错误码 → 当前语言文案。 */
    private fun message(code: DataMessage): String = context.getString(code.resId)

    /** ImportFailure 的来源是运行时名称（文件夹名等，不翻译）。 */
    private fun failure(sourceName: String, code: DataMessage): ImportFailure =
        ImportFailure(sourceName, message(code))

    /** ImportFailure 的来源本身也是固定文案（"目录"/"书架"）。 */
    private fun failure(@StringRes sourceResId: Int, code: DataMessage): ImportFailure =
        ImportFailure(context.getString(sourceResId), message(code))

    private fun schedulePersist() {
        persistSignal.trySend(Unit)
    }

    private fun scheduleProgressPersist() {
        progressSignal.trySend(Unit)
    }

    /** 单一持久化协程 + 400ms 防抖：锁内只取快照，序列化与写盘都在锁外。 */
    private suspend fun persistWorker() {
        @Suppress("UNUSED_VARIABLE")
        for (signal in persistSignal) {
            delay(PERSIST_DEBOUNCE_MS)
            // 快照在锁内取，保证不会把刚合并进来的新数据覆盖成旧值。
            val snapshot = mutex.withLock { _books.value }
            persistBooks(snapshot)
        }
    }

    /**
     * 阅读进度的独立持久化协程：写的是几十 KB 的 progress.json，与 books.json 完全分开。
     * 节流窗口比书库索引长（1.5s），进程切后台 / 退出阅读器时会 [flushProgress] 立即落盘。
     */
    private suspend fun progressWorker() {
        @Suppress("UNUSED_VARIABLE")
        for (signal in progressSignal) {
            delay(PROGRESS_DEBOUNCE_MS)
            flushProgress()
        }
    }

    /** 立即把未落盘的阅读进度写入 progress.json。 */
    suspend fun flushProgress() = withContext(Dispatchers.IO) {
        if (dirtyProgress.isEmpty()) return@withContext
        val pending = HashSet(dirtyProgress)
        dirtyProgress.removeAll(pending)
        if (progressEntries.isEmpty() && !progressFile.exists()) return@withContext
        val payload = runCatching {
            progressJson.encodeToString(ProgressSnapshot(progressEntries.toMap()))
                .toByteArray(Charsets.UTF_8)
        }.getOrElse {
            Log.e(TAG, "序列化阅读进度失败", it)
            return@withContext
        }
        writeMutex.withLock {
            try {
                // 进度是可丢失的派生数据：不 fsync，rename 已保证不会读到半成品。
                writeFileAtomically(progressFile, sync = false) { out -> out.write(payload) }
            } catch (e: Exception) {
                Log.e(TAG, "写入 progress.json 失败", e)
                dirtyProgress.addAll(pending)
            }
        }
    }

    private fun readProgressFromDisk(): Map<String, ReadingProgress> = runCatching {
        if (!progressFile.exists()) emptyMap()
        else progressJson.decodeFromString<ProgressSnapshot>(progressFile.readText(Charsets.UTF_8)).entries
    }.getOrElse { error ->
        Log.w(TAG, "progress.json 解析失败，忽略阅读进度", error)
        emptyMap()
    }

    /**
     * 发布内存快照。结构变化（增删 / 重排）时必须重建 id -> 下标索引；
     * 单纯改进度（列表长度与顺序不变）传 false，避免每翻一页都分配一张新 Map。
     */
    private fun publish(books: List<ComicBook>, rebuildIndex: Boolean = true) {
        _books.value = books
        if (rebuildIndex) {
            bookIndex = books.withIndex().associate { it.value.id to it.index }
        }
    }

    /** 按 id 定位；索引失效（列表已被替换）时回退到一次线性查找。 */
    private fun indexOfBook(bookId: String): Int {
        val books = _books.value
        val index = bookIndex[bookId]
        if (index != null && index in books.indices && books[index].id == bookId) return index
        return books.indexOfFirst { it.id == bookId }
    }

    /** 把一本书的最新进度同步进覆盖层并调度进度落盘。 */
    private fun refreshProgressEntry(book: ComicBook) {
        val hasProgress = book.currentPage > 0 || book.progressOffset > 0L ||
            book.lastReadAt != null || book.finished
        if (!hasProgress) {
            progressEntries.remove(book.id)
        } else {
            progressEntries[book.id] = ReadingProgress(
                currentPage = book.currentPage,
                progressOffset = book.progressOffset,
                lastReadAt = book.lastReadAt,
                finished = book.finished,
            )
        }
        dirtyProgress.add(book.id)
        scheduleProgressPersist()
    }

    private fun dropProgressEntry(bookId: String) {
        progressEntries.remove(bookId)
        dirtyProgress.add(bookId)
        scheduleProgressPersist()
    }

    /** 写盘统一入口：序列化 + 原子写在状态锁外执行，失败只记录日志。 */
    private suspend fun persistBooks(books: List<ComicBook>): Boolean = writeMutex.withLock {
        try {
            persistBooksLocked(books)
            true
        } catch (e: Exception) {
            Log.e(TAG, "写入 books.json 失败", e)
            false
        }
    }

    // -----------------------------------------------------------------------
    // 派生数据
    // -----------------------------------------------------------------------

    fun coverFile(book: ComicBook): File = File(coversDir, "${book.id}.jpg")

    /** 同步构造，绝不 I/O：具体打开动作延迟到第一次 prepare/loadPage。 */
    fun pageSource(book: ComicBook): ComicPageSource =
        ComicPageSourceFactory.open(book, context)

    /** 封面 + 文字提取缓存占用；遍历目录，调用方应在 IO 线程调用。 */
    fun appDataBytes(): Long = dirSize(coversDir) + dirSize(textCacheDir)

    /** 只清文字提取缓存（保留封面与 books.json），返回释放字节数；旧版拍平缓存目录一并清掉。 */
    suspend fun clearCaches(): Long = withContext(Dispatchers.IO) {
        var freed = wipeDirectory(textCacheDir)
        obsoleteCacheDirs.forEach { dir ->
            if (dir.exists()) {
                val size = dirSize(dir)
                if (dir.deleteRecursively()) freed += size
            }
        }
        Log.i(TAG, "已清理派生缓存 $freed 字节")
        freed
    }

    private fun wipeDirectory(dir: File): Long {
        val children = dir.listFiles() ?: return 0L
        var freed = 0L
        for (child in children) {
            val size = if (child.isDirectory) dirSize(child) else child.length()
            val deleted = if (child.isDirectory) child.deleteRecursively() else child.delete()
            if (deleted) freed += size
        }
        return freed
    }

    private fun dirSize(dir: File): Long {
        val children = dir.listFiles() ?: return 0L
        var total = 0L
        for (child in children) {
            total += if (child.isDirectory) dirSize(child) else child.length()
        }
        return total
    }

    private suspend fun ensureCovers(books: List<ComicBook>) {
        // 封面绝大多数已经存在（索引阶段就写好了）；缺失时并发补齐，避免串行打开文档拖慢启动。
        coroutineScope {
            val limiter = Semaphore(COVER_WORKERS)
            books.map { book ->
                async(Dispatchers.IO) {
                    limiter.withPermit {
                        coroutineContext.ensureActive()
                        // 文字书没有固定页封面：EPUB 用默认占位，MOBI 的封面在解析章节时提取。
                        if (book.reflowable) return@withPermit
                        val cover = coverFile(book)
                        // 只补缺失封面，已在的不重复解码。
                        if (cover.exists() && cover.length() > 512L) return@withPermit
                        writeCover(book)
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * 写封面：优先复用索引阶段已解码好的 [provided] 位图，否则回退到 pageSource(0,480)。
     * 无论哪条路径，位图都在这里回收。
     */
    private suspend fun writeCover(book: ComicBook, provided: Bitmap? = null): Boolean {
        val bitmap = provided ?: generateCover(book) ?: return false
        return try {
            coversDir.mkdirs()
            // 封面是派生数据：不 fsync，批量导入时省掉每本一次刷盘。
            writeFileAtomically(coverFile(book), sync = false) { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "写入封面失败：${book.title}", e)
            false
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private suspend fun generateCover(book: ComicBook): Bitmap? = try {
        pageSource(book).use { it.loadPage(0, COVER_WIDTH) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "生成封面失败：${book.title}", e)
        null
    }

    private fun removeDerivedFiles(book: ComicBook) {
        coverFile(book).delete()
        deleteTextCache(book.id)
    }

    // -----------------------------------------------------------------------
    // 持久化辅助
    // -----------------------------------------------------------------------

    private fun persistBooksLocked(books: List<ComicBook>) {
        val payload = json.encodeToString(books).toByteArray(Charsets.UTF_8)
        writeFileAtomically(booksFile) { out -> out.write(payload) }
    }

    private fun sortBooks(books: List<ComicBook>): List<ComicBook> =
        books.sortedByDescending { it.lastReadAt ?: it.addedAt }

    private fun stableBookId(treeUri: String, documentPath: String): String =
        UUID.nameUUIDFromBytes("$treeUri|$documentPath".toByteArray(Charsets.UTF_8)).toString()

    private fun shouldSkipDirectory(name: String): Boolean =
        name.startsWith(".") || name.equals("Android", ignoreCase = true) || name.equals("LOST.DIR", ignoreCase = true)

    /** 无 Context 的纯函数：只做文件名清洗，空标题返回 null，由调用方映射资源文案。 */
    private fun titleFromName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val base = if (ext in BOOK_EXTENSIONS) name.substringBeforeLast('.') else name
        return base.replace('_', ' ').replace('.', ' ').trim()
            .takeIf { it.isNotBlank() }
            ?.take(120)
    }

    private fun accentFor(title: String): Long {
        val palette = longArrayOf(0xFF7A3E2F, 0xFF3D5749, 0xFF3E5268, 0xFF77552E, 0xFF5F4358, 0xFF2F5C61)
        return palette[(title.hashCode() and Int.MAX_VALUE) % palette.size]
    }

    private companion object {
        const val TAG = "LibraryRepository"
        const val MAX_SCAN_DEPTH = 10
        const val PERSIST_DEBOUNCE_MS = 400L

        /** 阅读进度的落盘节流：比书库索引长，退出阅读器时另有立即 flush。 */
        const val PROGRESS_DEBOUNCE_MS = 1_500L

        /** 索引并发度：每本书独立打开 provider / 解析中央目录，互不共享状态。 */
        const val SCAN_CONCURRENCY = 4

        /** 启动补封面时的并发度（封面绝大多数已存在，这里只兜底缺失）。 */
        const val COVER_WORKERS = 2
        const val COVER_WIDTH = 480

        val BOOK_EXTENSIONS = setOf("cbz", "zip", "pdf", "epub", "mobi", "azw", "azw3")
        val MOBI_EXTENSIONS = setOf("mobi", "azw", "azw3")
    }
}
