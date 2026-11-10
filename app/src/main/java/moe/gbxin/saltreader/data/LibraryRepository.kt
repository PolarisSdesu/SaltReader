package moe.gbxin.saltreader.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * 书库仓库：所有书籍都通过持久化 SAF tree URI 就地索引，应用私有目录只放派生缓存
 * （封面、电子书拍平后的 CBZ、ZIP 随机读缓存）。
 *
 * 线程约定：
 * - 公开的 suspend 方法内部切到 Dispatchers.IO，并保证锁的临界区尽量短；
 * - `pageSource()`/`roots()` 同步返回，不做 I/O（roots 走内存缓存）；
 * - `updateProgress()`/`markOpened()` 只改内存并调度防抖落盘。
 */
class LibraryRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val libraryDir = File(context.filesDir, "library")
    private val coversDir = File(libraryDir, "covers")
    private val booksFile = File(libraryDir, "books.json")

    /** ZIP 随机读缓存：cacheDir/books/<id>_<modifiedAt>_<size>.<ext> */
    private val cacheRoot = File(context.cacheDir, "books")

    /** EPUB/MOBI 派生缓存：cacheDir/ebooks/<id>.cbz */
    private val ebookCacheDir = File(context.cacheDir, "ebooks")

    private val rootsStore = LibraryRootsStore(context)

    private val _books = MutableStateFlow<List<ComicBook>>(emptyList())
    val books: StateFlow<List<ComicBook>> = _books.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** 进度变更的合并信号：CONFLATED 保证只保留“还有未落盘变更”这一事实。 */
    private val persistSignal = Channel<Unit>(Channel.CONFLATED)

    /** 后台电子书转换：单一队列 + 2 个 worker（并发上限 2），Application 级 scope。 */
    private val conversionQueue = Channel<String>(Channel.UNLIMITED)
    private val conversionLock = Any()
    private val scheduledConversions = HashSet<String>()
    private val inProgressConversions = HashSet<String>()
    private val conversionDispatcher = Dispatchers.IO.limitedParallelism(2)

    init {
        scope.launch { persistWorker() }
        repeat(CONVERSION_WORKERS) {
            scope.launch(conversionDispatcher) { conversionWorker() }
        }
    }

    // -----------------------------------------------------------------------
    // 启动
    // -----------------------------------------------------------------------

    /**
     * 启动初始化：加载索引 → 校验 SAF 持久化权限（失效条目丢弃）→ 生成缺失封面 → 清理 orphan 缓存。
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

        // 预热 roots 缓存，并同步剔除丢失持久化权限的目录，避免刷新时反复报错。
        val roots = rootsStore.all()
        val keptRoots = roots.filter { it.treeUri in persistedTrees }
        if (keptRoots.size != roots.size) {
            runCatching { rootsStore.save(keptRoots) }
                .onFailure { Log.e(TAG, "写入 roots.json 失败", it) }
        }

        mutex.withLock {
            _books.value = sortBooks(kept)
            if (kept != loaded) {
                try {
                    persistBooksLocked(_books.value)
                } catch (e: Exception) {
                    Log.e(TAG, "初始化时写入 books.json 失败", e)
                }
            }
        }

        // 权限失效的条目不留在书架上，其派生数据也一并清掉。
        dropped.forEach { removeDerivedFiles(it) }
        ensureCovers(kept)
        pruneCaches(kept)
        // 上次进程退出时没转换完的电子书重新入队（不阻塞启动）。
        enqueuePendingConversions()
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

    /**
     * 就地索引一个 SAF 文件夹：不复制任何用户文件，只记录元数据并生成派生封面/缓存。
     */
    suspend fun importLibraryTree(
        uri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): ImportReport = withContext(Dispatchers.IO) {
        if (!DocumentsContract.isTreeUri(uri)) {
            return@withContext ImportReport(failures = listOf(ImportFailure("目录", "请选择一个文件夹")))
        }
        val tree = runCatching { SafTree(context, uri) }.getOrNull()
            ?: return@withContext ImportReport(failures = listOf(ImportFailure("目录", "无法访问该文件夹")))
        val persistable = takePersistableReadPermission(context, uri)
        val root = tree.rootEntry()
            ?: return@withContext ImportReport(failures = listOf(ImportFailure("目录", "无法读取该文件夹")))

        val result = try {
            scanTree(tree, 0f..1f, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ImportReport(failures = listOf(ImportFailure(root.name, e.message ?: "扫描失败")))
        }
        if (!result.completed) {
            return@withContext ImportReport(failures = result.failures)
        }
        val persisted = mergeTreeResult(result)
        runCatching { rootsStore.add(LibraryRoot(uri.toString(), root.name)) }
            .onFailure { Log.e(TAG, "写入 roots.json 失败", it) }
        pruneCaches(_books.value)

        val failures = result.failures.toMutableList()
        if (!persistable) {
            failures += ImportFailure(root.name, "无法持久保存该目录的访问权限，重启后需要重新添加")
        }
        if (!persisted) failures += ImportFailure(root.name, "索引已更新，但保存 books.json 失败")
        // 索引立即返回，电子书拍平交给后台队列，不阻塞导入。
        enqueuePendingConversions()
        if (result.scanned.isEmpty() && failures.isEmpty()) {
            failures += ImportFailure(root.name, "没有找到 CBZ、ZIP、PDF、EPUB、MOBI 或图片文件夹")
        }
        ImportReport(result.scanned, failures)
    }

    /** 增量刷新所有已添加的 root：指纹（modifiedAt + sizeBytes）未变的书不重新打开。 */
    suspend fun refreshLibrary(
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): ImportReport = withContext(Dispatchers.IO) {
        val roots = rootsStore.all()
        if (roots.isEmpty()) {
            return@withContext ImportReport(failures = listOf(ImportFailure("书架", "还没有可刷新的漫画目录")))
        }
        val failures = ArrayList<ImportFailure>()
        for ((index, rootInfo) in roots.withIndex()) {
            coroutineContext.ensureActive()
            val span = 1f / roots.size
            val start = index * span
            onProgress(start, "正在刷新 ${rootInfo.displayName}")

            val uri = runCatching { Uri.parse(rootInfo.treeUri) }.getOrNull()
            if (uri == null || !DocumentsContract.isTreeUri(uri)) {
                failures += ImportFailure(rootInfo.displayName, "目录地址无效")
                continue
            }
            takePersistableReadPermission(context, uri)
            val tree = runCatching { SafTree(context, uri) }.getOrNull()
            if (tree == null) {
                failures += ImportFailure(rootInfo.displayName, "无法访问该目录")
                continue
            }
            val result = try {
                scanTree(tree, start..(start + span), onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += ImportFailure(rootInfo.displayName, e.message ?: "刷新失败")
                continue
            }
            failures += result.failures
            // 目录枚举失败时整棵 tree 放弃合并，避免把仍存在的书记录删掉。
            if (!result.completed) continue
            if (!mergeTreeResult(result)) {
                failures += ImportFailure(rootInfo.displayName, "保存 books.json 失败")
            }
        }
        pruneCaches(_books.value)
        enqueuePendingConversions()
        onProgress(1f, "完成")
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

    /** 索引阶段算出的页数/封面，以及电子书是否已完成拍平转换。 */
    private data class IndexedPages(
        val pageCount: Int,
        val cover: Bitmap?,
        val converted: Boolean = true,
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
        val failures = ArrayList<ImportFailure>()
        val root = tree.rootEntry()
        if (root == null) {
            return TreeScanResult(treeUri, emptyList(), listOf(ImportFailure("目录", "无法读取该文件夹")), false)
        }
        onProgress(progress.start, "正在扫描 ${root.name}…")

        val candidates = ArrayList<ScanCandidate>()
        if (!walkTree(tree, root, 0, candidates, failures)) {
            if (failures.isEmpty()) failures += ImportFailure(root.name, "无法读取目录")
            return TreeScanResult(treeUri, emptyList(), failures, false)
        }

        val existing = _books.value
            .filter { it.treeUri == treeUri }
            .associateBy { it.documentPath }

        val scanned = ArrayList<ComicBook>(candidates.size)
        val total = candidates.size
        for ((index, candidate) in candidates.withIndex()) {
            coroutineContext.ensureActive()
            val fraction = if (total == 0) 1f else index.toFloat() / total
            onProgress(
                progress.start + (progress.endInclusive - progress.start) * fraction,
                "已索引 ${index + 1}/$total",
            )
            try {
                val book = indexCandidate(treeUri, candidate, existing[candidate.documentPath], failures)
                if (book != null) scanned += book
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += ImportFailure(candidate.name, e.message ?: "无法读取")
            }
        }
        onProgress(progress.endInclusive, "完成")
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
            failures += ImportFailure(dir.name, "无法读取目录")
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
        // 增量刷新：指纹未变且封面还在，直接复用旧记录，不重新打开文件。
        if (old != null &&
            old.kind == candidate.kind &&
            old.modifiedAt == candidate.modifiedAt &&
            old.sizeBytes == candidate.sizeBytes &&
            old.pageCount > 0
        ) {
            // 未转换电子书不能在前台触发转换，封面留给后台队列。
            if (old.converted && !coverFile(old).exists()) writeCover(old)
            return old
        }

        val id = old?.id ?: stableBookId(treeUri, candidate.documentPath)
        val isEbook = isEbookCandidate(candidate)
        // 电子书文件变了：旧拍平缓存与封面已失效，先删掉，后续由后台重转。
        if (isEbook && old != null) {
            File(ebookCacheDir, "$id.cbz").delete()
            coverFile(old).delete()
        }
        val indexed = try {
            indexPages(candidate, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += ImportFailure(candidate.name, e.message ?: "无法读取")
            return old
        }
        val pageCount = indexed.pageCount
        if (pageCount <= 0) {
            indexed.cover?.recycle()
            failures += ImportFailure(candidate.name, "没有找到可读页面")
            return old
        }

        val title = titleFromName(candidate.name)
        val book = ComicBook(
            id = id,
            title = old?.title ?: title,
            author = old?.author,
            description = old?.description,
            kind = candidate.kind,
            treeUri = treeUri,
            documentPath = candidate.documentPath,
            pageCount = pageCount,
            sizeBytes = candidate.sizeBytes,
            addedAt = old?.addedAt ?: System.currentTimeMillis(),
            lastReadAt = old?.lastReadAt,
            currentPage = (old?.currentPage ?: 0).coerceIn(0, max(0, pageCount - 1)),
            finished = old?.finished ?: false,
            accent = old?.accent ?: accentFor(title),
            direction = old?.direction,
            modifiedAt = candidate.modifiedAt,
            converted = indexed.converted,
        )
        // 未转换电子书先不生成封面（UI 占位），等后台转换完成后再补。
        if (!coverFile(book).exists()) {
            if (indexed.cover != null) {
                writeCover(book, indexed.cover)
            } else if (indexed.converted) {
                writeCover(book)
            }
        } else {
            indexed.cover?.recycle()
        }
        return book
    }

    private fun isEbookCandidate(candidate: ScanCandidate): Boolean =
        candidate.kind == BookKind.ARCHIVE &&
            candidate.name.substringAfterLast('.', "").lowercase() in EBOOK_EXTENSIONS

    /**
     * 索引阶段取页数与封面：
     * - ZIP/CBZ：RemoteZipReader 只读中央目录统计图片条目，再按条目读第一张图当封面（不复制整包）；
     *   provider 不支持 seek 时回退到流式 ZipInputStream 统计。
     * - EPUB：只读 container.xml → OPF 统计 itemref，converted=false，不前台转换。
     * - MOBI/AZW3：体积估算页数，converted=false，不前台转换。
     */
    private fun indexPages(candidate: ScanCandidate, id: String): IndexedPages = when (candidate.kind) {
        BookKind.FOLDER ->
            IndexedPages(candidate.imageCount, candidate.coverUri?.let(::readDocumentBitmap))
        BookKind.PDF ->
            IndexedPages(PageIndexing.pdfPageCount(context.contentResolver, candidate.uri), null)
        BookKind.ARCHIVE -> {
            val ext = candidate.name.substringAfterLast('.', "").lowercase()
            when {
                ext == "epub" -> {
                    val pages = RemoteZipReader.open(context, candidate.uri)?.use { reader ->
                        EbookIndexing.epubPageCount(reader)
                    } ?: 1
                    // 索引期不转换、不生成封面，入队后台转换。
                    IndexedPages(pages.coerceAtLeast(1), null, converted = false)
                }
                ext in EBOOK_EXTENSIONS -> {
                    IndexedPages(
                        EbookIndexing.estimateMobiPageCount(candidate.sizeBytes),
                        null,
                        converted = false,
                    )
                }
                else -> indexZipFast(candidate)
            }
        }
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

    private suspend fun mergeTreeResult(result: TreeScanResult): Boolean {
        val merged = mutex.withLock {
            val survivors = _books.value.filterNot { it.treeUri == result.treeUri }
            val next = sortBooks(survivors + result.scanned)
            _books.value = next
            try {
                persistBooksLocked(next)
                true
            } catch (e: Exception) {
                Log.e(TAG, "写入 books.json 失败", e)
                false
            }
        }
        return merged
    }

    // -----------------------------------------------------------------------
    // 目录 / 删除 / 元数据
    // -----------------------------------------------------------------------

    fun roots(): List<LibraryRoot> = rootsStore.all()

    suspend fun removeRoot(treeUri: String) = withContext(Dispatchers.IO) {
        val removed = mutex.withLock {
            val affected = _books.value.filter { it.treeUri == treeUri }
            _books.value = _books.value.filterNot { it.treeUri == treeUri }
            try {
                persistBooksLocked(_books.value)
            } catch (e: Exception) {
                Log.e(TAG, "写入 books.json 失败", e)
            }
            affected
        }
        removed.forEach {
            cancelConversion(it.id)
            removeDerivedFiles(it)
        }
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
        pruneCaches(_books.value)
        Unit
    }

    /** 只删除索引、封面与派生缓存；SAF 引用的用户文件绝不触碰。 */
    suspend fun delete(book: ComicBook) = withContext(Dispatchers.IO) {
        cancelConversion(book.id)
        mutex.withLock {
            _books.update { list -> list.filterNot { it.id == book.id } }
            try {
                persistBooksLocked(_books.value)
            } catch (e: Exception) {
                Log.e(TAG, "写入 books.json 失败", e)
            }
        }
        removeDerivedFiles(book)
        pruneCaches(_books.value)
        Unit
    }

    suspend fun rename(book: ComicBook, title: String): Boolean = withContext(Dispatchers.IO) {
        val clean = title.trim().take(120)
        if (clean.isBlank()) return@withContext false
        mutex.withLock {
            _books.update { list -> list.map { if (it.id == book.id) it.copy(title = clean) else it } }
            try {
                persistBooksLocked(_books.value)
                true
            } catch (e: Exception) {
                Log.e(TAG, "写入 books.json 失败", e)
                false
            }
        }
    }

    suspend fun setFinished(book: ComicBook, finished: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            _books.update { list ->
                list.map {
                    if (it.id == book.id) {
                        it.copy(
                            finished = finished,
                            currentPage = if (finished) max(0, it.pageCount - 1) else 0,
                            lastReadAt = if (finished) System.currentTimeMillis() else it.lastReadAt,
                        )
                    } else {
                        it
                    }
                }
            }
            try {
                persistBooksLocked(_books.value)
                true
            } catch (e: Exception) {
                Log.e(TAG, "写入 books.json 失败", e)
                false
            }
        }
    }

    suspend fun setDirection(bookId: String, direction: ReadingDirection?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _books.update { list ->
                list.map { if (it.id == bookId) it.copy(direction = direction) else it }
            }
            try {
                persistBooksLocked(_books.value)
            } catch (e: Exception) {
                Log.e(TAG, "写入 books.json 失败", e)
            }
        }
        Unit
    }

    fun updateProgress(bookId: String, page: Int) {
        val current = _books.value.firstOrNull { it.id == bookId } ?: return
        val safePage = page.coerceIn(0, max(0, current.pageCount - 1))
        if (current.currentPage == safePage && !current.finished) return
        val updated = current.copy(
            currentPage = safePage,
            lastReadAt = System.currentTimeMillis(),
            finished = current.pageCount > 0 && safePage >= current.pageCount - 1,
        )
        _books.update { list -> list.map { if (it.id == bookId) updated else it } }
        schedulePersist()
    }

    fun markOpened(bookId: String) {
        val current = _books.value.firstOrNull { it.id == bookId } ?: return
        val updated = current.copy(lastReadAt = System.currentTimeMillis())
        _books.update { list -> list.map { if (it.id == bookId) updated else it } }
        schedulePersist()
    }

    /** 阅读器 prepare() 拿到真实页数后回填（只改内存并调度防抖落盘）。 */
    fun updatePageCount(bookId: String, pageCount: Int) {
        if (pageCount <= 0) return
        val current = _books.value.firstOrNull { it.id == bookId } ?: return
        if (current.pageCount == pageCount) return
        val updated = current.copy(
            pageCount = pageCount,
            currentPage = current.currentPage.coerceIn(0, max(0, pageCount - 1)),
        )
        _books.update { list -> list.map { if (it.id == bookId) updated else it } }
        schedulePersist()
    }

    private fun schedulePersist() {
        persistSignal.trySend(Unit)
    }

    /** 单一持久化协程 + 400ms 防抖：只落盘最新快照，不再维护 progressPending。 */
    private suspend fun persistWorker() {
        @Suppress("UNUSED_VARIABLE")
        for (signal in persistSignal) {
            delay(PERSIST_DEBOUNCE_MS)
            try {
                // 在锁内取快照：避免扫描合并刚写入的新数据被旧快照覆盖。
                mutex.withLock { persistBooksLocked(_books.value) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "保存 books.json 失败", e)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 后台电子书转换（单一队列 + 2 worker）
    // -----------------------------------------------------------------------

    /** 把所有待转换电子书放进队列（幂等：已排队/转换中的跳过）。 */
    private fun enqueuePendingConversions() {
        _books.value
            .filter { !it.converted && isEbookBook(it) }
            .forEach { enqueueConversion(it.id) }
    }

    private fun enqueueConversion(bookId: String) {
        synchronized(conversionLock) {
            if (bookId in scheduledConversions || bookId in inProgressConversions) return
            scheduledConversions += bookId
        }
        conversionQueue.trySend(bookId)
    }

    /**
     * 协作式取消：把排队中的任务移除；已在执行的阻塞 IO 无法强行中断，
     * 但转换结束时会检查“书是否还在/指纹是否变了”，不匹配就丢弃结果并删缓存。
     */
    private fun cancelConversion(bookId: String) {
        synchronized(conversionLock) { scheduledConversions.remove(bookId) }
    }

    private suspend fun conversionWorker() {
        for (bookId in conversionQueue) {
            synchronized(conversionLock) {
                scheduledConversions.remove(bookId)
                inProgressConversions.add(bookId)
            }
            try {
                convertBook(bookId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "后台转换失败：$bookId", e)
            } finally {
                synchronized(conversionLock) { inProgressConversions.remove(bookId) }
            }
        }
    }

    /** 逐个把 converted=false 的电子书拍平成 cacheDir/ebooks/<id>.cbz，并回填真实页数/封面。 */
    private suspend fun convertBook(bookId: String) {
        val snapshot = _books.value.firstOrNull { it.id == bookId } ?: return
        if (snapshot.converted || !isEbookBook(snapshot)) return
        val ext = ebookExtensionOf(snapshot) ?: return
        ebookCacheDir.mkdirs()
        val target = File(ebookCacheDir, "$bookId.cbz")
        val ok = if (target.exists() && target.length() > 0L) {
            // 阅读器 prepare() 已按需生成过缓存，直接复用，不重复转换。
            true
        } else {
            Log.i(TAG, "后台转换开始：${snapshot.title}")
            target.delete()
            val temp = try {
                File.createTempFile("ebook-", ".$ext.tmp", ebookCacheDir)
            } catch (e: Exception) {
                Log.w(TAG, "无法创建转换临时文件：${snapshot.title}", e)
                return
            }
            try {
                copyDocumentToTemp(snapshot, temp) && FormatConverters.toCbz(temp, target, ext) > 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "电子书转换失败：${snapshot.title}", e)
                false
            } finally {
                temp.delete()
            }
        }
        if (!ok) {
            target.delete()
            return
        }
        val realPageCount = countArchivePages(target)
        if (realPageCount <= 0) {
            target.delete()
            return
        }
        // 转换期间书被删除/重新索引（指纹变化）：丢弃本次结果，等下一轮。
        if (!isStillCurrent(snapshot)) {
            target.delete()
            return
        }
        val current = _books.value.firstOrNull { it.id == bookId } ?: run {
            target.delete()
            return
        }
        val cover = decodeArchiveFirstPage(target, COVER_WIDTH)
        writeCover(current.copy(pageCount = realPageCount, converted = true), cover)
        mutex.withLock {
            _books.update { list ->
                list.map { book ->
                    if (book.id == bookId && !book.converted &&
                        book.modifiedAt == snapshot.modifiedAt &&
                        book.sizeBytes == snapshot.sizeBytes
                    ) {
                        book.copy(
                            pageCount = realPageCount,
                            converted = true,
                            currentPage = book.currentPage.coerceIn(0, max(0, realPageCount - 1)),
                        )
                    } else {
                        book
                    }
                }
            }
        }
        schedulePersist()
        Log.i(TAG, "后台转换完成：${snapshot.title}（$realPageCount 页）")
    }

    private fun isStillCurrent(snapshot: ComicBook): Boolean {
        val current = _books.value.firstOrNull { it.id == snapshot.id } ?: return false
        return current.modifiedAt == snapshot.modifiedAt && current.sizeBytes == snapshot.sizeBytes
    }

    private fun copyDocumentToTemp(book: ComicBook, temp: File): Boolean = try {
        val tree = runCatching { SafTree(context, Uri.parse(book.treeUri)) }.getOrNull() ?: return false
        val entry = tree.find(book.documentPath) ?: return false
        if (entry.isDirectory) return false
        context.contentResolver.openInputStream(entry.uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } != null
    } catch (e: Exception) {
        Log.w(TAG, "读取电子书失败：${book.title}", e)
        false
    }

    private fun countArchivePages(file: File): Int = runCatching {
        ZipFile(file).use { it.imageEntries().size }
    }.getOrDefault(0)

    // -----------------------------------------------------------------------
    // 派生数据
    // -----------------------------------------------------------------------

    fun coverFile(book: ComicBook): File = File(coversDir, "${book.id}.jpg")

    /** 同步构造，绝不 I/O：具体打开动作延迟到第一次 loadPage。 */
    fun pageSource(book: ComicBook): ComicPageSource =
        ComicPageSourceFactory.open(book, context, cacheRoot, ebookCacheDir)

    /** 封面 + 派生缓存占用；遍历目录，调用方应在 IO 线程调用。 */
    fun appDataBytes(): Long =
        dirSize(coversDir) +
            dirSize(cacheRoot) +
            dirSize(ebookCacheDir)

    /**
     * 只清 cacheDir/books 与 cacheDir/ebooks（保留封面与 books.json），返回释放字节数。
     * 电子书的拍平缓存被清掉后置 converted=false 并重新入队后台转换。
     */
    suspend fun clearCaches(): Long = withContext(Dispatchers.IO) {
        synchronized(conversionLock) { scheduledConversions.clear() }
        val freed = wipeDirectory(cacheRoot) + wipeDirectory(ebookCacheDir)
        mutex.withLock {
            _books.update { list ->
                list.map { book -> if (isEbookBook(book)) book.copy(converted = false) else book }
            }
        }
        schedulePersist()
        Log.i(TAG, "已清理派生缓存 $freed 字节")
        enqueuePendingConversions()
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
        for (book in books) {
            coroutineContext.ensureActive()
            // 未转换电子书还没有可读缓存，封面等后台转换完成后再生成（UI 先占位）。
            if (!book.converted) continue
            val cover = coverFile(book)
            // 只补缺失封面，已在的不重复解码。
            if (cover.exists() && cover.length() > 512L) continue
            writeCover(book)
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
            writeFileAtomically(coverFile(book)) { out ->
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
        File(ebookCacheDir, "${book.id}.cbz").delete()
        cacheRoot.listFiles { file -> file.name.startsWith("${book.id}_") }?.forEach { it.delete() }
    }

    /** 清掉不再对应任何书籍的派生缓存（删书、重新索引、指纹变化、权限失效都会走到这里）。 */
    private fun pruneCaches(books: List<ComicBook>) {
        val expectedArchives = books.asSequence()
            .filter { it.kind == BookKind.ARCHIVE && !isEbookBook(it) }
            .map { bookCacheFile(it, cacheRoot).name }
            .toSet()
        cacheRoot.listFiles()?.forEach { file ->
            // 跳过正在写入的临时缓存，避免删掉别的协程刚复制一半的文件。
            if (file.name.endsWith(".tmp")) return@forEach
            if (file.name !in expectedArchives) file.delete()
        }
        val expectedEbooks = books.asSequence()
            .filter { isEbookBook(it) }
            .map { "${it.id}.cbz" }
            .toSet()
        ebookCacheDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) return@forEach
            if (file.name !in expectedEbooks) file.delete()
        }
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

    private fun titleFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val base = if (ext in BOOK_EXTENSIONS) name.substringBeforeLast('.') else name
        return base.replace('_', ' ').replace('.', ' ').trim()
            .ifBlank { "未命名漫画" }
            .take(120)
    }

    private fun accentFor(title: String): Long {
        val palette = longArrayOf(0xFF7A3E2F, 0xFF3D5749, 0xFF3E5268, 0xFF77552E, 0xFF5F4358, 0xFF2F5C61)
        return palette[(title.hashCode() and Int.MAX_VALUE) % palette.size]
    }

    private companion object {
        const val TAG = "LibraryRepository"
        const val MAX_SCAN_DEPTH = 10
        const val PERSIST_DEBOUNCE_MS = 400L
        const val COVER_WIDTH = 480
        const val CONVERSION_WORKERS = 2

        val BOOK_EXTENSIONS = setOf("cbz", "zip", "pdf", "epub", "mobi", "azw", "azw3")
        val EBOOK_EXTENSIONS = setOf("epub", "mobi", "azw", "azw3")
    }
}
