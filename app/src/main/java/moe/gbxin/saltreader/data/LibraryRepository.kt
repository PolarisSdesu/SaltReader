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
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * 书库仓库：所有书籍都通过持久化 SAF tree URI 就地索引，应用私有目录只放派生缓存
 * （封面、电子书拍平后的 CBZ、ZIP 随机读缓存、旧版本副本）。
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
    private val legacyContentDir = File(libraryDir, "content")
    private val coversDir = File(libraryDir, "covers")
    private val booksFile = File(libraryDir, "books.json")

    /** ZIP 随机读缓存：cacheDir/books/<id>_<modifiedAt>_<size>.<ext> */
    private val cacheRoot = File(context.cacheDir, "books")

    /** EPUB/MOBI 派生缓存：cacheDir/ebooks/<id>.cbz */
    private val ebookCacheDir = File(context.cacheDir, "ebooks")
    private val tempCacheDir = File(context.cacheDir, "tmp")

    private val rootsStore = LibraryRootsStore(context)

    private val _books = MutableStateFlow<List<ComicBook>>(emptyList())
    val books: StateFlow<List<ComicBook>> = _books.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** 进度变更的合并信号：CONFLATED 保证只保留“还有未落盘变更”这一事实。 */
    private val persistSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { persistWorker() }
    }

    // -----------------------------------------------------------------------
    // 启动
    // -----------------------------------------------------------------------

    suspend fun initialize() = withContext(Dispatchers.IO) {
        libraryDir.mkdirs()
        coversDir.mkdirs()

        val loaded = readBooksFromDisk()
        // 只有旧副本缺失、或既不是 SAF 也没有 contentPath 的条目才丢弃；
        // SAF 书籍即使当前 tree 暂不可达也保留索引。
        val kept = loaded.filter {
            (it.isSaf && !it.treeUri.isNullOrBlank()) || (it.isLegacyCopy && legacyFile(it).exists())
        }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        val dropped = loaded.filterNot { it.id in keptIds }

        // 预热 roots 缓存（之后 roots() 可在任意线程同步返回）。
        val roots = rootsStore.all()
        if (roots.isEmpty()) migrateRoots(kept)

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

        ensureCovers(kept)
        dropped.forEach { removeDerivedFiles(it) }
        pruneCaches(_books.value)
        _ready.value = true
        Unit
    }

    private fun readBooksFromDisk(): List<ComicBook> = runCatching {
        if (!booksFile.exists()) emptyList()
        else json.decodeFromString<List<ComicBook>>(booksFile.readText(Charsets.UTF_8))
    }.getOrElse { error ->
        Log.e(TAG, "books.json 解析失败，已备份为 books.corrupt.json", error)
        runCatching { booksFile.renameTo(File(libraryDir, "books.corrupt.json")) }
        emptyList()
    }

    /** 老版本只持久化了裸路径；升级后从 SAF 书籍反推 root 列表并写入 roots.json。 */
    private fun migrateRoots(books: List<ComicBook>) {
        val derived = books.asSequence()
            .mapNotNull { it.treeUri }
            .distinct()
            .map { treeUri ->
                val name = runCatching {
                    val uri = Uri.parse(treeUri)
                    if (!DocumentsContract.isTreeUri(uri)) return@runCatching null
                    SafTree(context, uri).rootName()
                }.getOrNull()
                LibraryRoot(treeUri, name ?: treeUri)
            }
            .toList()
        if (derived.isNotEmpty()) {
            runCatching { rootsStore.save(derived) }
                .onFailure { Log.e(TAG, "迁移 roots.json 失败", it) }
        }
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
        takePersistableReadPermission(context, uri)
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
        if (!persisted) failures += ImportFailure(root.name, "索引已更新，但保存 books.json 失败")
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
    )

    private data class TreeScanResult(
        val treeUri: String,
        val scanned: List<ComicBook>,
        val failures: List<ImportFailure>,
        val completed: Boolean,
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
            .filter { it.isSaf && it.treeUri == treeUri }
            .associateBy { it.documentPath ?: "" }

        val scanned = ArrayList<ComicBook>(candidates.size)
        val total = candidates.size
        for ((index, candidate) in candidates.withIndex()) {
            coroutineContext.ensureActive()
            val fraction = if (total == 0) 1f else index.toFloat() / total
            onProgress(
                progress.start + (progress.endInclusive - progress.start) * fraction,
                "正在索引 ${candidate.name}",
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
        var hasSubdirectory = false
        var imageCount = 0
        var imageBytes = 0L
        var imageModified = 0L

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
                    imageCount++
                    imageBytes += child.sizeBytes
                    if (child.lastModified > imageModified) imageModified = child.lastModified
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
            )
        }
        // 图片文件夹书：直接子级至少 3 张图片且没有子目录（沿用旧策略）。
        if (imageCount >= 3 && !hasSubdirectory) {
            out += ScanCandidate(
                documentPath = dir.documentPath,
                name = dir.name,
                kind = BookKind.FOLDER,
                sizeBytes = imageBytes,
                modifiedAt = imageModified,
                uri = dir.uri,
                imageCount = imageCount,
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
            if (!coverFile(old).exists()) writeCover(old)
            return old
        }

        val id = old?.id ?: stableBookId(treeUri, candidate.documentPath)
        val pageCount = try {
            countPages(candidate, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += ImportFailure(candidate.name, e.message ?: "无法读取")
            return old
        }
        if (pageCount <= 0) {
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
            contentPath = "",
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
        )
        if (!coverFile(book).exists()) writeCover(book)
        return book
    }

    private fun countPages(candidate: ScanCandidate, id: String): Int = when (candidate.kind) {
        BookKind.FOLDER -> candidate.imageCount
        BookKind.PDF -> PageIndexing.pdfPageCount(context.contentResolver, candidate.uri)
        BookKind.ARCHIVE -> {
            val ext = candidate.name.substringAfterLast('.', "").lowercase()
            if (ext in EBOOK_EXTENSIONS) {
                convertEbook(candidate, id, ext)
            } else {
                context.contentResolver.openInputStream(candidate.uri)?.use { input ->
                    PageIndexing.countZipPages(input)
                } ?: 0
            }
        }
    }

    /** EPUB/MOBI 在索引时拍平到派生缓存（先写 .tmp，成功后 rename，失败清理）。 */
    private fun convertEbook(candidate: ScanCandidate, id: String, ext: String): Int {
        ebookCacheDir.mkdirs()
        val target = File(ebookCacheDir, "$id.cbz")
        target.delete()
        tempCacheDir.mkdirs()
        val temp = File.createTempFile("ebook-", ".$ext", tempCacheDir)
        return try {
            context.contentResolver.openInputStream(candidate.uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return 0
            FormatConverters.toCbz(temp, target, ext)
        } finally {
            temp.delete()
        }
    }

    private suspend fun mergeTreeResult(result: TreeScanResult): Boolean {
        val merged = mutex.withLock {
            val survivors = _books.value.filterNot { it.isSaf && it.treeUri == result.treeUri }
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
            val affected = _books.value.filter { it.isSaf && it.treeUri == treeUri }
            _books.value = _books.value.filterNot { it.isSaf && it.treeUri == treeUri }
            try {
                persistBooksLocked(_books.value)
            } catch (e: Exception) {
                Log.e(TAG, "写入 books.json 失败", e)
            }
            affected
        }
        removed.forEach { removeDerivedFiles(it) }
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

    suspend fun delete(book: ComicBook) = withContext(Dispatchers.IO) {
        // SAF 书只删派生数据，绝不碰用户文件；旧副本删除前做路径守卫。
        if (book.isLegacyCopy) deleteLegacyCopy(book)
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

    private fun deleteLegacyCopy(book: ComicBook) {
        val contentPath = book.contentPath
        check(contentPath.isNotBlank()) { "旧副本缺少 contentPath，拒绝删除" }
        val filesRoot = context.filesDir.canonicalFile
        val target = legacyFile(book).canonicalFile
        val insideFilesDir = target.path == filesRoot.path ||
            target.path.startsWith(filesRoot.path + File.separator)
        check(insideFilesDir) { "拒绝删除应用私有目录之外的文件：$target" }
        if (target.exists() && !target.deleteRecursively()) {
            Log.w(TAG, "删除旧副本失败：$target")
        }
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
    // 派生数据
    // -----------------------------------------------------------------------

    fun coverFile(book: ComicBook): File = File(coversDir, "${book.id}.jpg")

    /** 同步构造，绝不 I/O：具体打开动作延迟到第一次 loadPage。 */
    fun pageSource(book: ComicBook): ComicPageSource =
        ComicPageSourceFactory.open(book, context, cacheRoot, ebookCacheDir)

    /** 派生缓存 + 旧副本占用；遍历目录，调用方应在 IO 线程调用。 */
    fun appDataBytes(): Long =
        dirSize(coversDir) +
            dirSize(ebookCacheDir) +
            dirSize(cacheRoot) +
            dirSize(tempCacheDir) +
            dirSize(legacyContentDir)

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
            val cover = coverFile(book)
            if (cover.exists() && cover.length() > 512L) continue
            writeCover(book)
        }
    }

    private suspend fun writeCover(book: ComicBook) {
        val bitmap = try {
            pageSource(book).use { it.loadPage(0, COVER_WIDTH) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "生成封面失败：${book.title}", e)
            null
        } ?: return
        try {
            coversDir.mkdirs()
            writeFileAtomically(coverFile(book)) { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "写入封面失败：${book.title}", e)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun removeDerivedFiles(book: ComicBook) {
        coverFile(book).delete()
        File(ebookCacheDir, "${book.id}.cbz").delete()
        cacheRoot.listFiles { file -> file.name.startsWith("${book.id}_") }?.forEach { it.delete() }
    }

    /** 清掉不再对应任何书籍的派生缓存（删书、重新索引、指纹变化都会走到这里）。 */
    private fun pruneCaches(books: List<ComicBook>) {
        val expectedArchives = books.asSequence()
            .filter { it.isSaf && it.kind == BookKind.ARCHIVE && !isEbookBook(it) }
            .map { bookCacheFile(it, cacheRoot).name }
            .toSet()
        cacheRoot.listFiles()?.forEach { file ->
            // 跳过正在写入的临时缓存，避免删掉别的协程刚复制一半的文件。
            if (file.name.endsWith(".tmp")) return@forEach
            if (file.name !in expectedArchives) file.delete()
        }
        val expectedEbooks = books.asSequence()
            .filter { it.isSaf && isEbookBook(it) }
            .map { "${it.id}.cbz" }
            .toSet()
        ebookCacheDir.listFiles()?.forEach { file ->
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

    private fun legacyFile(book: ComicBook): File = File(legacyContentDir, book.contentPath)

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

        val BOOK_EXTENSIONS = setOf("cbz", "zip", "pdf", "epub", "mobi", "azw", "azw3")
        val EBOOK_EXTENSIONS = setOf("epub", "mobi", "azw", "azw3")
    }
}
