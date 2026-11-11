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
    treeUri: String,               // SAF tree URI（必填）
    documentPath: String = "",     // 相对 tree root 的路径，"" = root 本身（图片文件夹）
    pageCount, sizeBytes, addedAt, lastReadAt, currentPage, finished, accent,
    direction: ReadingDirection? = null,
    modifiedAt: Long = 0L,         // 增量刷新指纹
    converted: Boolean = true,     // false = 电子书拍平缓存待生成（pageCount 为估算值）
) { progress, displayAuthor, pageLabel, kindLabel }
```

**不做任何旧数据兼容**（测试期可直接卸载重装）：模型里不再有 `contentPath` / `isLegacyCopy` /
`sourcePath` / `sourceUri`。`books.json` 解析失败时直接重置索引即可，不需要迁移逻辑。
`initialize()` 只需：加载索引 → 校验 tree URI 的持久化权限（失效则丢弃该条目）→ 生成缺失封面。

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
    fun updatePageCount(bookId: String, pageCount: Int) // 电子书转换完成后回填真实页数
    fun appDataBytes(): Long                           // covers + 派生缓存占用
    suspend fun clearCaches(): Long                    // 只清 cacheDir 派生缓存（books/ebooks），保留 covers，返回释放字节；
                                                       // 电子书清缓存后 converted=false 并重新入队后台转换
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
- `delete()` 只删除索引、封面与派生缓存，绝不触碰用户文件（SAF 引用无需也不能删除原文件）。
- `removeRoot(treeUri)` 删除该 root 下的书与派生缓存，但不碰用户文件。

### ComicPageSource 契约

```kotlin
interface ComicPageSource : Closeable {
    val pageCount: Int
    /** 首次打开前调用：电子书可在此生成拍平缓存并返回真实页数；默认返回 pageCount。 */
    suspend fun prepare(): Int = pageCount
    suspend fun loadPage(index: Int, targetWidth: Int): Bitmap?
}
```

### 导入速度契约（第二轮追加，必须实现）

目标：几十本 EPUB 导入应在秒级完成，绝不能在前台逐本转换。

1. **索引阶段零整本转换**：
   - ZIP/CBZ：用 SAF `openFileDescriptor` 得到的可 seek 文件描述符 + 轻量 ZIP 中央目录解析器
     （`RemoteZipReader`：读尾部 EOCD/ZIP64 EOCD + central directory）只统计图片条目数，绝不解压整包。
     该解析器同时提供按条目名 inflate 读取小文件的能力（EPUB 的 container.xml/OPF 用）。
   - EPUB：`converted=false`，`pageCount` 用 RemoteZipReader 读 container.xml → OPF → 统计 `<itemref>` 数量
     （失败则 1）；封面暂不生成（UI 显示占位），入队后台转换。
   - MOBI/AZW3：`converted=false`，`pageCount` 用文件大小估算或 1；入队后台转换。
   - PDF/文件夹：保持现有快速计数；封面生成保持（CBZ 封面改用 RemoteZipReader 读第一张图，避免把整包复制到缓存）。
2. **后台转换队列**：Repo 内的单一队列 + 2 个并发 worker（`Dispatchers.IO.limitedParallelism(2)`），
   逐个调用 `FormatConverters.toCbz`（SAF 流先落到临时文件）；成功后回填真实 `pageCount`、`converted=true`、
   生成封面，并走 debounce 持久化；失败保留 `converted=false` 以便下次重试。`initialize()` 时把
   `converted=false` 的书重新入队；`removeRoot/delete` 取消对应任务。
3. **打开即转换**：`ComicPageSource.prepare()` 在电子书缓存缺失时同步转换（IO 线程、单飞锁），
   返回真实页数；Reader 在进入 Ready 前调用 prepare，用返回页数并回填 `updatePageCount`。
4. **转换本身加速**：`FormatConverters` 的 CBZ 写入对已经是压缩格式的页图（JPEG/PNG/WebP）使用
   `ZipOutputStream.setMethod(STORED)`，避免无意义的二次压缩；EPUB 图片/文本分类只扫描一次 spine
   （缓存每个 reference 的图片引用列表），避免二次 `resource.data` 解码与正则。
5. **进度**：索引进度按“已索引 n/N”上报；后台转换不阻塞导入返回。

A 负责的实现：
- SAF 压缩包：首次 `loadPage` 时把文档流式复制到 `cacheDir/books/<id>_<modifiedAt>_<size>.<ext>`，
  再 `ZipFile` 随机读；缓存命中直接复用；删除/重新索引时清旧缓存。
- SAF PDF：`ContentResolver.openFileDescriptor` → `PdfRenderer`，惰性打开，`close()` 释放；
  构造/渲染异常要关闭 fd，渲染前 clamp 宽高，避免超长页 OOM。
- SAF 图片文件夹：惰性列举目录下图片（自然序）后按 index 读取 `openInputStream`。
- 不提供任何应用内副本读取路径；书页只来自 SAF。
- 外部 EPUB/MOBI：索引时转换到 `cacheDir/ebooks/<id>.cbz`（派生缓存，不是副本），
  读取走该缓存；转换缓存写 `.tmp` 后 rename，避免半成品被当成有效缓存。
- 解码安全：`inSampleSize` 同时约束总像素（≈16MP 上限）；单文件读取上限；
  EXIF 只在 JPEG 时解析；PDF 渲染尺寸 clamp。

## 阅读器契约（工作流 B）

- 页数来源：Reader 在 Ready 之前调用 `source.prepare()`（IO），用返回真实页数渲染，
  并调用 `repo.updatePageCount` 回填；不要直接用 `book.pageCount` 当最终页数（它可能是 EPUB 估算值）。
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

## 第三轮：EPUB 直读 + 原生文字阅读（替代拍平转换）

目标：EPUB 不再需要转成 CBZ。图片型 EPUB 直接按 ZIP 读取；文字型 EPUB 走原生重排阅读器。
不引入任何 `.so`：图片型 EPUB 用纯 Kotlin `RemoteZipReader`；文字型用 Android 内置 WebView
做排版渲染（epub4j 仅抽取章节 HTML），全部是平台能力。

### 模型（`data/Models.kt`，Lead 独占）

- `BookKind` 新增 `EPUB_TEXT`。
- `ComicBook` 新增：
  - `reflowable: Boolean = false`：true = 文字型 EPUB，走文字阅读器；
  - `progressOffset: Long = 0L`：文字阅读位置（字符偏移，唯一权威进度）；
  - `charCount: Long = 0L`：估算字符数（由 spine 中 XHTML 的未压缩大小估算，不解析正文）；
  - `pageCount`：文字书为估算页数（`max(1, charCount / 1500)`），仅用于书架进度显示；
  - `converted`：仅 MOBI/AZW3 拍平缓存使用；EPUB 恒为 true。

### 数据层契约（data-saf）

1. **EPUB 索引分类**（通过 `RemoteZipReader`，零转换）：
   - 解析 `META-INF/container.xml` → OPF → manifest + spine itemrefs。
   - 判定图片型：spine 条目 media-type 为 `image/*`，或对应 XHTML 中存在唯一可解析的 `<img src>`；
     满足比例 ≥ 80% 即按图片型处理。
   - 图片型：`kind=ARCHIVE`、`converted=true`、`reflowable=false`、`pageCount=图片页数`，
     封面取第一张图片条目；**不写任何派生缓存**。页序 = spine 顺序。
   - 文字型：`kind=EPUB_TEXT`、`converted=true`、`reflowable=true`、
     `charCount=Σ(spine XHTML uncompressedSize)*0.6`（下限 1）、`pageCount=max(1,charCount/1500)`；
     不生成封面（UI 用默认占位），**不入转换队列**。
   - MOBI/AZW3 仍然：`converted=false` + 后台 2 并发拍平（保持不变）。
2. `ComicPageSourceFactory`：图片型 EPUB 返回 `EpubImagePageSource(RemoteZipReader)`；
   `prepare()` 惰性解析 spine→image 条目映射并返回真实页数；`loadPage` 按 index inflate 单张图片，
   复用 32MB 单页 / 16MP 解码上限；`close()` 关闭 PFD。
3. 文字型 EPUB 文本提取：
   ```kotlin
   @Serializable data class EbookChapter(val title: String?, val html: String)
   suspend fun loadEbookChapters(book: ComicBook): List<EbookChapter>
   ```
   - **不要用 epub4j**：直接用 `RemoteZipReader` 读 spine 顺序的 XHTML（单章上限 8MB）；
     `title` 取章节内首个 h1/h2/title 或 null；
   - `html` 为清洗后的正文 HTML（去掉 script/style/link，内联图片转为 data URI 或直接移除，体积上限保护）；
   - 全程 IO 线程，失败返回空列表并记录失败。
4. 进度回填：`fun updateTextProgress(bookId: String, offset: Long)`：
   - 写 `progressOffset`，并按 `currentPage = (offset / charCount * pageCount)` 估算书架进度；
   - 走统一 debounce 持久化；`finished` 在 offset ≥ charCount*0.99 时置 true。
5. 删除 EPUB 的拍平相关分支（`FormatConverters.toCbz` 只保留 MOBI/AZW3；后台队列只处理 MOBI/AZW3）。

### 阅读器契约（reader）

1. `ReaderScreen`/`ReaderViewModel` 在 `book.reflowable == true` 时进入文字阅读分支
   （新增 `TextReaderContent`），否则保持现有图像分页逻辑。
2. 文字阅读器：
   - 打开时在 IO 调 `repo.loadEbookChapters(book)`，Loading/Missing/Failed 状态与现有风格一致；
   - 用 WebView 渲染：注入 CSS（`font-size: {readerFontSizeSp}px; line-height: 1.75; color/background`
     跟随阅读背景与主题），PAGED 模式用 CSS 多栏 + 横向滚动翻页，VERTICAL 模式纵向滚动；
   - 翻页/滚动由 Compose 外层手势处理：PAGED 左右 1/3 翻页（RTL 反转）、中间切工具栏；
     VERTICAL 仅上下滚动 + 中间切工具栏；
   - 进度：定期（settle/离开/暂停）通过 JS 取当前字符偏移，`repo.updateTextProgress`；
     打开时按 `book.progressOffset` 恢复位置；
   - 不支持双指缩放（文字书用字号设置）；音量键翻页在 PAGED 模式下生效；
   - 无障碍：WebView 自带文本语义，另给翻页/工具栏提供 customActions。
3. 图像 EPUB：无需阅读器改动（索引后 pageCount 即真实值，`prepare()` 返回真实页数）。

### UI 契约（ui-saf）

- `AppSettings` 新增 `readerFontSizeSp: Int = 18`（范围 14–28），在阅读设置面板「常规」里提供
  字号滑杆（文字书生效；滑杆实现在 reader 的文件内，ui-saf 只负责添加字段与持久化）。
- 书卡/搜索/操作面板：`BookKind.EPUB_TEXT` 显示「EPUB 电子书」；`reflowable` 书的进度行用
  `pageLabel`（百分比），不显示“N 页”。
- 存储页/导入提示：文案中“电子书会在后台转换”限定为 MOBI/AZW3；EPUB 显示“可直接阅读”。

### 构建/测试契约（build-qa）

- 版本提升到 `versionCode 5 / versionName 1.3.0`。
- EPUB 不再使用 epub4j：确认全仓无 `io.documentnode.epub4j` 引用后，从版本目录/依赖/proguard keep
  中移除 epub4j-core（及其 minilog/kxml2 传递依赖），并同步 README/THIRD_PARTY_NOTICES。
- 新增纯 JVM 测试：书页/字符进度换算、`charCount`→`pageCount` 估算、EPUB spine 分类纯函数
  （如拆成可测的 `EpubSpineClassifier`）、`pageLabel` 百分比。
- README：EPUB 直读/原生文字阅读说明，删除“EPUB 会拍平转换”的旧描述。

## 第四轮：性能优化（进度持久化 / 并发 / 内存）

本轮的接口约定变化，后续改动请保持：

1. **持久化拆成两个文件**
   - `file/library/books.json`：只放书库索引（紧凑 JSON，`encodeDefaults = false`，无缩进），
     只在增删 / 改名 / 重新索引 / 页数回填等**结构变化**时写。
   - `file/library/progress.json`：阅读进度覆盖层（`id -> {currentPage, progressOffset, lastReadAt, finished}`），
     翻页 / 滚动 / 打开书只写这个文件；1.5s 节流、不 `fsync`，`ON_STOP` 时 `flushProgress()` 立即落盘。
   - 载入时 progress.json 覆盖 books.json 里的旧进度字段；两者都由 `LibraryRepository` 单一来源写入。
2. **锁与写盘**：`mutex` 只保护内存快照（临界区内不做序列化 / 文件 IO）；写盘统一走 `writeMutex`
   （串行写入 + 原子 rename）。`writeFileAtomically(target, sync = false)` 只用于派生数据（进度、章节缓存）。
3. **导入**：单棵树内的候选文件按 `SCAN_CONCURRENCY = 4` 有界并发索引；多 root 刷新只在全部扫描结束后
   合并并落盘一次。
4. **并发解码**：`ReaderViewModel` 以「每页一把锁 + 全局 `Semaphore(2)`」替代单把 cacheMutex；
   同一页不会重复解码，不同页可并行；单页位图预算 16MB / 长边 8192（超长条漫强制降采样）。
5. **章节缓存**：EPUB 清洗后的章节序列化到 `cacheDir/text/<id>_<mtime>_<size>.chapters.json`
   （MOBI 仍用 `.html`）；`clearCaches()` 会一并清理。缓存文件都按 `id_` 前缀批量删除。
6. **内存**：封面 LruCache 容量 `maxMemory/8`（上限 64MB）、命中时同步返回避免首帧闪烁；
   `Application.onTrimMemory` 触发页缓存清空与封面缓存裁剪。
7. **图片型 EPUB 页码映射缓存**：`cacheDir/text/<id>_<mtime>_<size>.epubmap.json`
   （`EpubPageMap.pages`，spine 顺序的图片条目名，含缺页占位）。索引阶段 `indexEpub()` 分类完成后
   直接写入，`EpubImagePageSource` 打开时优先读它；只有缓存缺失/失效才重新逐页扫描 spine XHTML。
   实测 151 页的图片型 EPUB：首次打开 prepare 84ms（其中分类 48ms）→ 命中缓存 18–25ms；
   索引阶段写入后连首次打开也只需 24ms。
