package moe.gbxin.saltreader.data

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import java.io.FileOutputStream
import androidx.documentfile.provider.DocumentFile
import java.util.UUID
import kotlin.math.max

class LibraryRepository(private val context: Context) {
    private val bookExtensions = setOf("cbz", "zip", "pdf", "epub", "mobi", "azw", "azw3")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    val root: File = File(context.filesDir, "library")
    private val contentDir = File(root, "content")
    private val coversDir = File(root, "covers")
    private val booksFile = File(root, "books.json")
    private val prefs = context.getSharedPreferences("saltreader_library", Context.MODE_PRIVATE)

    private val _books = MutableStateFlow<List<ComicBook>>(emptyList())
    val books: StateFlow<List<ComicBook>> = _books.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var persistJob: Job? = null
    private val progressPending = mutableMapOf<String, ComicBook>()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            root.mkdirs()
            contentDir.mkdirs()
            coversDir.mkdirs()
            val loaded = runCatching {
                if (booksFile.exists()) json.decodeFromString<List<ComicBook>>(booksFile.readText()) else emptyList()
            }.getOrElse {
                runCatching { booksFile.renameTo(File(root, "books.corrupt.json")) }
                emptyList()
            }
            val cleaned = loaded.filter {
                it.sourcePath?.let { path -> File(path).exists() } ?: File(root, it.contentPath).exists()
            }
            _books.value = cleaned.sortedByDescending { it.lastReadAt ?: it.addedAt }
            if (prefs.getStringSet("library_roots", emptySet()).isNullOrEmpty()) {
                val derived = cleaned.mapNotNull { book ->
                    book.sourcePath?.let(::File)?.parentFile?.takeIf { it.isDirectory }?.absolutePath
                }.toSet()
                if (derived.isNotEmpty()) prefs.edit().putStringSet("library_roots", derived).apply()
            }
            ensureCoversLocked(_books.value)
            writeBooksLocked(_books.value)
            _ready.value = true
        }
    }

    suspend fun importArchives(uris: List<Uri>, onProgress: (Float, String) -> Unit = { _, _ -> }): ImportReport =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val imported = mutableListOf<ComicBook>()
                val failures = mutableListOf<ImportFailure>()
                uris.forEachIndexed { index, uri ->
                    val name = displayName(uri) ?: "未命名漫画"
                    onProgress(index.toFloat() / max(1, uris.size), name)
                    runCatching { importArchiveLocked(uri, name) }
                        .onSuccess { imported += it }
                        .onFailure { failures += ImportFailure(name, it.message ?: "导入失败") }
                }
                if (imported.isNotEmpty()) {
                    _books.update { current -> (imported + current) }
                    writeBooksLocked(_books.value)
                }
                onProgress(1f, "完成")
                ImportReport(imported, failures)
            }
        }

    suspend fun importFolder(uri: Uri): ImportReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tree = DocumentFile.fromTreeUri(context, uri) ?: return@withLock ImportReport(failures = listOf(ImportFailure("文件夹", "无法读取该文件夹")))
            val title = tree.name?.trim()?.ifBlank { "未命名漫画" } ?: "未命名漫画"
            runCatching { importFolderLocked(tree, title) }
                .fold(
                    onSuccess = { book ->
                        _books.update { current -> listOf(book) + current }
                        writeBooksLocked(_books.value)
                        ImportReport(imported = listOf(book))
                    },
                    onFailure = { ImportReport(failures = listOf(ImportFailure(title, it.message ?: "导入失败"))) },
                )
        }
    }

    /**
     * Index a SAF folder in place. The source files stay where they are;
     * only metadata and covers live inside the app.
     */
    suspend fun importLibraryTree(uri: Uri, onProgress: (Float, String) -> Unit = { _, _ -> }): ImportReport =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val folder = treeUriToFile(uri)
                    ?: return@withLock ImportReport(failures = listOf(ImportFailure("目录", "无法定位该文件夹")))
                if (!folder.isDirectory) {
                    return@withLock ImportReport(failures = listOf(ImportFailure("目录", "该路径不是文件夹")))
                }
                scanExternalTreeLocked(folder, onProgress)
            }
        }

    /**
     * Index the shared storage in place. Source files are never copied into the app.
     */
    suspend fun scanAllStorage(onProgress: (Float, String) -> Unit = { _, _ -> }): ImportReport =
        withContext(Dispatchers.IO) {
            mutex.withLock { scanExternalTreeLocked(Environment.getExternalStorageDirectory(), onProgress) }
        }

    private suspend fun scanExternalTreeLocked(root: File, onProgress: (Float, String) -> Unit): ImportReport {
        val imported = mutableListOf<ComicBook>()
        val failures = mutableListOf<ImportFailure>()
        val sources = mutableListOf<File>()

        fun walk(dir: File, depth: Int) {
            if (depth > 10 || !dir.isDirectory || dir.name.startsWith(".")) return
            if (dir.name == "Android" || dir.name == "LOST.DIR") return
            val children = runCatching { dir.listFiles()?.sortedWith(PageFileComparator) }.getOrNull() ?: return
            val hasDirs = children.any { it.isDirectory }
            val images = children.count { it.isComicImage() }
            children.forEach { child ->
                if (child.isDirectory) walk(child, depth + 1)
                else if (child.extension.lowercase() in bookExtensions) sources += child
            }
            if (images >= 3 && !hasDirs) sources += dir
        }
        onProgress(0f, "正在扫描文件夹…")
        runCatching { walk(root, 0) }.onFailure { failures += ImportFailure("存储", it.message ?: "无法扫描") }
        val uniqueSources = sources.distinctBy { it.absolutePath }
        val existing = _books.value.filter { it.sourcePath != null }
            .associateBy { it.sourcePath!! }
        val rootPrefix = root.absolutePath + File.separator
        val insideRoot = _books.value.filter { book ->
            val path = book.sourcePath ?: return@filter false
            path == root.absolutePath || path.startsWith(rootPrefix)
        }
        val outsideRoot = _books.value.filterNot { it in insideRoot }
        val scanned = mutableListOf<ComicBook>()
        val total = uniqueSources.size
        uniqueSources.forEachIndexed { index, source ->
            onProgress(if (total == 0) 1f else index.toFloat() / total, "正在索引 ${source.name}")
            val old = existing[source.absolutePath]
            if (old != null) {
                val updated = runCatching {
                    val probe = old.copy(pageCount = 0)
                    val count = pageSource(probe).use { it.pageCount }
                    old.copy(
                        pageCount = if (count > 0) count else old.pageCount,
                        sizeBytes = if (source.isDirectory) folderSize(source) else source.length(),
                    ).also { if (!coverFile(it).exists()) writeCover(it) }
                }.getOrElse {
                    failures += ImportFailure(source.name, "无法读取")
                    old
                }
                scanned += updated
            } else {
                val created = runCatching { createExternalBook(source) }.getOrNull()
                if (created != null) {
                    writeCover(created)
                    scanned += created
                } else {
                    failures += ImportFailure(source.name, "没有找到可读页面")
                }
            }
        }
        val merged = (outsideRoot + scanned).distinctBy { it.id }
        _books.value = merged.sortedByDescending { it.lastReadAt ?: it.addedAt }
        writeBooksLocked(_books.value)
        prefs.edit().putStringSet(
            "library_roots",
            (prefs.getStringSet("library_roots", emptySet()) ?: emptySet()) + root.absolutePath,
        ).apply()
        onProgress(1f, "完成")
        if (uniqueSources.isEmpty() && failures.isEmpty()) {
            failures += ImportFailure("文件夹", "没有找到 CBZ、ZIP、PDF、EPUB、MOBI 或图片文件夹")
        }
        return ImportReport(scanned, failures)
    }

    private fun createExternalBook(source: File): ComicBook? {
        val path = source.absolutePath
        val id = UUID.nameUUIDFromBytes(path.toByteArray()).toString()
        val name = source.name.substringBeforeLast('.').ifBlank { source.name }
            .replace('_', ' ').trim().ifBlank { "未命名漫画" }
        val kind = when {
            source.isDirectory -> BookKind.FOLDER
            source.extension.lowercase() == "pdf" -> BookKind.PDF
            else -> BookKind.ARCHIVE
        }
        val draft = ComicBook(
            id = id,
            title = name.take(120),
            kind = kind,
            contentPath = "",
            sourcePath = path,
            pageCount = 0,
            sizeBytes = if (source.isDirectory) folderSize(source) else source.length(),
            addedAt = System.currentTimeMillis(),
            accent = accentFor(name),
        )
        val count = pageSource(draft).use { it.pageCount }
        if (count <= 0) return null
        return draft.copy(pageCount = count)
    }

    private fun folderSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun treeUriToFile(uri: Uri): File? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(':', limit = 2)
        val volume = parts[0]
        val relative = if (parts.size > 1) parts[1] else ""
        val base = if (volume.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory()
        else File("/storage/$volume")
        if (relative.isBlank()) base else File(base, relative)
    }.getOrNull()

    fun roots(): List<File> = prefs.getStringSet("library_roots", emptySet())
        ?.map(::File)?.filter { it.exists() }?.distinctBy { it.absolutePath }
        ?.sortedBy { it.absolutePath }
        .orEmpty()

    suspend fun removeRoot(root: File) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.edit().putStringSet(
                "library_roots",
                (prefs.getStringSet("library_roots", emptySet()) ?: emptySet()) - root.absolutePath,
            ).apply()
            val prefix = root.absolutePath + File.separator
            val affected = _books.value.filter { book ->
                val path = book.sourcePath ?: return@filter false
                path == root.absolutePath || path.startsWith(prefix)
            }
            affected.forEach {
                coverFile(it).delete()
                File(context.cacheDir, "ebooks/${it.id}.cbz").delete()
            }
            _books.update { list -> list.filterNot { it in affected } }
            writeBooksLocked(_books.value)
        }
    }

    suspend fun refreshLibrary(onProgress: (Float, String) -> Unit = { _, _ -> }): ImportReport =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val roots = prefs.getStringSet("library_roots", emptySet())
                    ?.map(::File)?.filter { it.exists() }?.distinctBy { it.absolutePath }
                    .orEmpty()
                if (roots.isEmpty()) {
                    return@withLock ImportReport(failures = listOf(ImportFailure("书架", "还没有可刷新的漫画目录")))
                }
                val failures = mutableListOf<ImportFailure>()
                var count = 0
                roots.forEachIndexed { index, root ->
                    onProgress(index.toFloat() / roots.size, "正在刷新 ${root.name}")
                    val report = runCatching { scanExternalTreeLocked(root) { _, _ -> } }
                        .getOrElse {
                            failures += ImportFailure(root.name, it.message ?: "刷新失败")
                            ImportReport()
                        }
                    count += report.count
                    failures += report.failures
                }
                onProgress(1f, "完成")
                ImportReport(emptyList(), failures)
            }
        }

    suspend fun delete(book: ComicBook) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (book.sourcePath == null) File(root, book.contentPath).deleteRecursively()
            File(context.cacheDir, "ebooks/${book.id}.cbz").delete()
            coverFile(book).delete()
            _books.update { list -> list.filterNot { it.id == book.id } }
            writeBooksLocked(_books.value)
        }
    }

    suspend fun rename(book: ComicBook, newTitle: String) = withContext(Dispatchers.IO) {
        val clean = newTitle.trim().take(120)
        if (clean.isBlank()) return@withContext
        mutex.withLock {
            _books.update { list -> list.map { if (it.id == book.id) it.copy(title = clean) else it } }
            writeBooksLocked(_books.value)
        }
    }

    suspend fun setDirection(bookId: String, direction: ReadingDirection?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _books.update { list ->
                list.map { if (it.id == bookId) it.copy(direction = direction) else it }
            }
            writeBooksLocked(_books.value)
        }
    }

    suspend fun setFinished(book: ComicBook, finished: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _books.update { list ->
                list.map {
                    if (it.id == book.id) it.copy(
                        finished = finished,
                        currentPage = if (finished) max(0, it.pageCount - 1) else 0,
                        lastReadAt = if (finished) System.currentTimeMillis() else it.lastReadAt,
                    ) else it
                }
            }
            writeBooksLocked(_books.value)
        }
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
        progressPending[bookId] = updated
        schedulePersist()
    }

    fun markOpened(bookId: String) {
        val current = _books.value.firstOrNull { it.id == bookId } ?: return
        val updated = current.copy(lastReadAt = System.currentTimeMillis())
        _books.update { list -> list.map { if (it.id == bookId) updated else it } }
        progressPending[bookId] = updated
        schedulePersist()
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(240)
            mutex.withLock {
                val snapshot = _books.value
                progressPending.clear()
                writeBooksLocked(snapshot)
            }
        }
    }

    fun coverFile(book: ComicBook): File = File(coversDir, "${book.id}.jpg")

    fun contentFile(book: ComicBook): File = File(root, book.contentPath)

    fun pageSource(book: ComicBook): ComicPageSource = ComicPageSourceFactory.open(book, root, context.cacheDir)

    fun storageBytes(): Long {
        fun size(f: File): Long = if (f.isDirectory) (f.listFiles()?.sumOf { size(it) } ?: 0L) else f.length()
        return size(contentDir) + size(coversDir) + size(File(context.cacheDir, "ebooks"))
    }

    private suspend fun importArchiveLocked(uri: Uri, rawName: String, sourceUri: String? = null): ComicBook {
        val id = UUID.randomUUID().toString()
        val ext = rawName.substringAfterLast('.', "cbz").lowercase()
            .let { if (it in bookExtensions) it else "cbz" }
        val isPdf = ext == "pdf"
        val isEbook = ext in setOf("epub", "mobi", "azw", "azw3")
        val dest = File(contentDir, if (isPdf) "$id.pdf" else "$id.cbz")
        val tmp = File(root, "$id.importing.$ext")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output, 1 shl 16) }
            } ?: error("无法读取文件")
            val title = titleFromName(rawName)
            val draft = when {
                isPdf -> {
                    tmp.renameTo(dest)
                    ComicBook(
                        id = id,
                        title = title,
                        kind = BookKind.PDF,
                        contentPath = dest.relativeTo(root).path,
                        pageCount = 0,
                        sizeBytes = dest.length(),
                        addedAt = System.currentTimeMillis(),
                        accent = accentFor(title),
                        sourceUri = sourceUri,
                    )
                }
                isEbook -> {
                    val converted = FormatConverters.toCbz(tmp, dest, ext)
                    if (converted <= 0) error("无法解析 ${ext.uppercase()} 内容")
                    ComicBook(
                        id = id,
                        title = title,
                        kind = BookKind.ARCHIVE,
                        contentPath = dest.relativeTo(root).path,
                        pageCount = converted,
                        sizeBytes = dest.length(),
                        addedAt = System.currentTimeMillis(),
                        accent = accentFor(title),
                        sourceUri = sourceUri,
                    )
                }
                else -> {
                    tmp.renameTo(dest)
                    ComicBook(
                        id = id,
                        title = title,
                        kind = BookKind.ARCHIVE,
                        contentPath = dest.relativeTo(root).path,
                        pageCount = 0,
                        sizeBytes = dest.length(),
                        addedAt = System.currentTimeMillis(),
                        accent = accentFor(title),
                        sourceUri = sourceUri,
                    )
                }
            }
            val pageCount = when (draft.kind) {
                BookKind.PDF -> ComicPageSourceFactory.pdfPageCount(dest)
                else -> ComicPageSourceFactory.listPageNames(draft, root).size
            }
            if (pageCount == 0) error("没有找到可读页面")
            val book = draft.copy(pageCount = pageCount)
            writeCover(book)
            book
        }.onFailure {
            tmp.delete()
            dest.delete()
        }.getOrThrow()
    }

    private suspend fun importFolderLocked(tree: DocumentFile, title: String, sourceUri: String? = null): ComicBook {
        val id = UUID.randomUUID().toString()
        val dest = File(contentDir, id)
        val tmp = File(contentDir, "$id.importing")
        runCatching {
            tmp.mkdirs()
            val images = mutableListOf<Pair<String, DocumentFile>>()
            fun walk(dir: DocumentFile, prefix: String, depth: Int) {
                if (depth > 4) return
                dir.listFiles().sortedWith(compareBy(PageNameComparator) { it.name ?: "" }).forEach { child ->
                    if (child.isDirectory) {
                        walk(child, prefix + (child.name ?: "") + "/", depth + 1)
                    } else if (child.isFile && (child.name ?: "").substringAfterLast('.', "").lowercase() in imageExtensions) {
                        images += (prefix + (child.name ?: "page")) to child
                    }
                }
            }
            walk(tree, "", 0)
            if (images.isEmpty()) error("文件夹里没有找到图片")
            images.forEachIndexed { index, (relative, doc) ->
                val ext = relative.substringAfterLast('.', "jpg").lowercase()
                val target = File(tmp, "page_%04d.%s".format(index + 1, ext))
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output, 1 shl 16) }
                } ?: error("无法读取 ${doc.name}")
            }
            tmp.renameTo(dest)
            val draft = ComicBook(
                id = id,
                title = title,
                kind = BookKind.FOLDER,
                contentPath = dest.relativeTo(root).path,
                pageCount = images.size,
                sizeBytes = dest.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                addedAt = System.currentTimeMillis(),
                accent = accentFor(title),
                sourceUri = sourceUri,
            )
            writeCover(draft)
            return draft
        }.onFailure {
            tmp.deleteRecursively()
            dest.deleteRecursively()
        }.getOrThrow()
    }

    private suspend fun ensureCoversLocked(books: List<ComicBook>) {
        books.forEach { book ->
            val cover = coverFile(book)
            if (!cover.exists() || cover.length() < 512L) writeCover(book)
        }
    }

    private suspend fun writeCover(book: ComicBook) {
        runCatching {
            pageSource(book).use { source ->
                val bitmap = source.loadPage(0, 480) ?: return@use
                FileOutputStream(coverFile(book)).use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out) }
                bitmap.recycle()
            }
        }
    }

    private fun writeBooksLocked(books: List<ComicBook>) {
        runCatching {
            root.mkdirs()
            val tmp = File(root, "books.json.tmp")
            tmp.writeText(json.encodeToString(books))
            if (!tmp.renameTo(booksFile)) {
                booksFile.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun titleFromName(name: String): String {
        val raw = name.substringBeforeLast('.').replace('_', ' ').replace('.', ' ').trim()
        return raw.ifBlank { "未命名漫画" }.take(120)
    }

    private fun accentFor(title: String): Long {
        val palette = longArrayOf(0xFF7A3E2F, 0xFF3D5749, 0xFF3E5268, 0xFF77552E, 0xFF5F4358, 0xFF2F5C61)
        return palette[(title.hashCode() and Int.MAX_VALUE) % palette.size]
    }
}


private object PageFileComparator : Comparator<File> {
    override fun compare(a: File, b: File): Int = PageNameComparator.compare(a.name, b.name)
}
