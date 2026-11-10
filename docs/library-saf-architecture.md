# SaltReader Play-ready SAF 书库架构（本次重构契约）

本文件是并行改造的接口契约，四个工作流必须严格遵守。改动完成后本文件保留为架构说明。

## 目标

1. 移除 `MANAGE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE`，所有书库访问走 SAF。
2. **不做导入副本**：索引一次后直接读取用户原文件；应用私有目录只允许放派生缓存
   （封面、EPUB/MOBI 拍平后的 CBZ、ZIP 随机读所需的临时缓存）。
3. 阅读器打开书籍不再在主线程做任何 I/O。
4. 其余 #1–#22 优化项在各自工作流内完成。

## 数据模型（已完成，`data/Models.kt`，禁止再改）

```kotlin
@Serializable data class ComicBook(
    id, title, author, description, kind,
    contentPath: String = "",      // 仅旧版本应用内副本（相对 filesDir），新书为空
    treeUri: String? = null,       // SAF tree URI
    documentPath: String? = null,  // 相对 tree root 的路径，"" = root 本身（图片文件夹）
    pageCount, sizeBytes, addedAt, lastReadAt, currentPage, finished, accent,
    direction: ReadingDirection? = null,
    modifiedAt: Long = 0L,         // 增量刷新指纹
) { isLegacyCopy, isSaf, progress, displayAuthor, pageLabel, kindLabel }
```

旧 JSON 中的 `sourcePath` / `sourceUri` 字段会被 `ignoreUnknownKeys` 忽略。
`initialize()` 必须丢弃「既没有 `treeUri` 也没有有效 `contentPath`」的条目。
旧版本扫描出来的裸路径书（`sourcePath`）在 Play 版不可用，直接丢弃索引（不删用户文件）。

## 依赖关系

- `data/Models.kt`、本文件：Lead 独占，其他人只读。
- 四个工作流写文件互不重叠（见各自任务），**统一由 Lead 执行 Gradle 构建**；
  队友不要运行 `./gradlew`，避免 Gradle 锁冲突。

## 数据层契约（工作流 A 实现，UI/阅读器按此调用）

```kotlin
data class LibraryRoot(val treeUri: String, val displayName: String)

class LibraryRepository(context: Context) {
    val books: StateFlow<List<ComicBook>>
    val ready: StateFlow<Boolean>

    suspend fun initialize()   // 迁移/清理 + 生成缺失封面；不做网络与主线程 I/O
    suspend fun importLibraryTree(uri: Uri, onProgress: (Float, String) -> Unit = { _, _ -> }): ImportReport
    suspend fun refreshLibrary(onProgress: (Float, String) -> Unit = { _, _ -> }): ImportReport
    fun roots(): List<LibraryRoot>                    // 同步，来自缓存 roots.json
    suspend fun removeRoot(treeUri: String)

    suspend fun delete(book: ComicBook)
    suspend fun rename(book: ComicBook, title: String): Boolean
    suspend fun setFinished(book: ComicBook, finished: Boolean): Boolean
    suspend fun setDirection(bookId: String, direction: ReadingDirection?)
    fun updateProgress(bookId: String, page: Int)
    fun markOpened(bookId: String)

    fun coverFile(book: ComicBook): File
    fun pageSource(book: ComicBook): ComicPageSource   // 同步构造，绝不 I/O；内部惰性解析
    fun appDataBytes(): Long                           // 派生缓存 + 旧副本占用
}
```

要求：
- `pageSource()` 同步返回惰性 source：构造时不读文件、不解析 zip、不打开 PDF。
- `pageCount` 一律取 `book.pageCount`（索引阶段已算好），source 不做页面枚举 I/O。
- 索引阶段（IO 线程）计算 `pageCount/sizeBytes/modifiedAt` 并写封面。
- 增量刷新：`(modifiedAt, sizeBytes)` 未变则复用已有记录，不重新打开文件。
- 每次扫描持有锁的时间要短：先局部收集，再在短临界区合并 `_books` 并写盘。
- 写 `books.json` 原子化：临时文件 + `fd.sync()` + rename；失败向上抛/返回 Result，
  不要静默吞掉。持久化合并为单一协程（进度变更 debounce），删掉 `progressPending` 死代码。
- `delete()` 必须校验 `contentPath.isNotBlank()` 且 canonicalPath 位于 app files 下，
  禁止 `File(root, "")` 递归删除。
- `removeRoot(treeUri)` 删除该 root 下的书与派生缓存，但不碰用户文件。

### ComicPageSource 契约

```kotlin
interface ComicPageSource : Closeable {
    val pageCount: Int
    suspend fun loadPage(index: Int, targetWidth: Int): Bitmap?
}
```

A 负责的实现：
- SAF 压缩包：首次 `loadPage` 时把文档流式复制到 `cacheDir/books/<id>_<modifiedAt>_<size>.<ext>`，
  再 `ZipFile` 随机读；缓存命中直接复用；删除/重新索引时清旧缓存。
- SAF PDF：`ContentResolver.openFileDescriptor` → `PdfRenderer`，惰性打开，`close()` 释放；
  构造/渲染异常要关闭 fd，渲染前 clamp 宽高，避免超长页 OOM。
- SAF 图片文件夹：惰性列举目录下图片（自然序）后按 index 读取 `openInputStream`。
- 旧版本应用内副本（`isLegacyCopy`）：沿用现有 File 实现，只读兼容。
- 外部 EPUB/MOBI：索引时转换到 `cacheDir/ebooks/<id>.cbz`（派生缓存，不是副本），
  读取走该缓存；转换缓存写 `.tmp` 后 rename，避免半成品被当成有效缓存。
- 解码安全：`inSampleSize` 同时约束总像素（≈16MP 上限）；单文件读取上限；
  EXIF 只在 JPEG 时解析；PDF 渲染尺寸 clamp。

## 阅读器契约（工作流 B）

- `ReaderViewModel`：`book: StateFlow<ComicBook?>` 驱动；暴露
  `ReaderUiState`（`Loading` / `Ready` / `Missing`），页数取 `book.pageCount`。
  source 在 `Dispatchers.IO` 惰性创建，主线程零 I/O；`onCleared` 关闭。
- `ReaderActivity` 不再用 `books.value` 判断存在性：等待 `book` 状态，只有明确 Missing
  才提示并返回书架；加载中显示 loading。
- 纵向模式（`ReaderMode.VERTICAL`）**左右 1/3 分区不绑定任何功能**，只有中间分区切换工具栏；
  左右翻页只在横向模式生效。纵向只允许上下滑动。
- 缩放状态跨页复位；浅色纸张下系统栏图标用深色；手势提供无障碍语义。

## UI 契约（工作流 C）

- 删除 `ui/components/AllFilesAccess.kt`，`SaltReaderRoot` 不再弹全盘权限对话框。
- 书库空态：主入口「添加文件夹」（`OpenDocumentTree` → `importLibraryTree`）；
  移除 `scanAllStorage` / `importArchives` / `importFolder` 相关 UI 与 ViewModel 方法。
- 存储设置页：展示 `roots()`（tree URI + 显示名）与 `appDataBytes()`，
  「刷新索引」走 `refreshLibrary`，「移除目录」走 `removeRoot(treeUri)`。
- 所有仓库增删改返回 `Boolean`/`Result` 的调用点要显示成功/失败 Snackbar。
- 布局预设与列数滑块不再互相覆盖；滑块用 draft 值，`onValueChangeFinished` 才落库；
  `SettingsRepository.update` 只写发生变化的 key。
- 封面加进程级 `LruCache`（key 含 id/mtime/length）+ `inSampleSize`。

## 构建/发布契约（工作流 D）

- Manifest 删除 `MANAGE_EXTERNAL_STORAGE`、`READ_EXTERNAL_STORAGE`、`requestLegacyExternalStorage`。
- 备份：`allowBackup=true`，只备份 `sharedpref/saltreader_settings.xml`；
  `file/library/**`、`saltreader_library.xml` 全部排除（fullBackupContent + dataExtractionRules 同步）。
- release 打开 `isMinifyEnabled` + `isShrinkResources`，补第三方 keep 规则（epub4j/mobi4java
  /kotlinx.serialization），保留 `material-icons-extended` 由 R8 裁剪。
- versionCode=2、versionName=1.1.0；debug 加 `applicationIdSuffix=".debug"`。
- 依赖：commons-io ≥2.17.0、commons-lang3 ≥3.18.0、commons-collections4 4.5.0（若 API 变更同步修 vendored 代码）；
  引入 `gradle/libs.versions.toml` 作为唯一版本来源；开启 Gradle 配置缓存。
- 新增 `app/src/test`：`ComicBook` 序列化往返、`PageNameComparator` 排序、`ImportReport`/进度计算等纯 JVM 测试；
  添加 `.github/workflows/android.yml`（assembleDebug + testDebugUnitTest）。
- README：Play-ready 说明、SAF 添加目录流程、不再复制导入、SDK 37、版本与签名说明。
