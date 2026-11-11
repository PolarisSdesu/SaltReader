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
   @Serializable data class EbookChapter(val title: String?, val html: String)  // 新字段见第七轮
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
5. **章节缓存**：EPUB 清洗后的章节序列化到 `cacheDir/text/<id>_<mtime>_<size>.chapters.v2.json`
   （MOBI 仍用 `.html`；v2 起因见第七轮）；`clearCaches()` 会一并清理。缓存文件都按 `id_` 前缀批量删除。
6. **内存**：封面 LruCache 容量 `maxMemory/8`（上限 64MB）、命中时同步返回避免首帧闪烁；
   `Application.onTrimMemory` 触发页缓存清空与封面缓存裁剪。
7. **图片型 EPUB 页码映射缓存**：`cacheDir/text/<id>_<mtime>_<size>.epubmap.json`
   （`EpubPageMap.pages`，spine 顺序的图片条目名，含缺页占位）。索引阶段 `indexEpub()` 分类完成后
   直接写入，`EpubImagePageSource` 打开时优先读它；只有缓存缺失/失效才重新逐页扫描 spine XHTML。
   实测 151 页的图片型 EPUB：首次打开 prepare 84ms（其中分类 48ms）→ 命中缓存 18–25ms；
   索引阶段写入后连首次打开也只需 24ms。

## 第五轮：文字阅读器按章懒加载

- **按章窗口加载**：首屏只把阅读进度附近 `CHAPTER_BATCH = 3` 章注入 WebView；滚动到已加载窗口
  80% 边界时由 JS `append`/`prepend` 追加/前插下一批，并补偿滚动位置（分页模式补偿 scrollLeft，
  纵向模式补偿 scrollTop）。已加载窗口之外的章节不进 WebView，因此超大文字书的首次打开成本
  不再随全书线性增长。
- **进度仍是全书字符偏移**：窗口内比例会按各章可见字符数（`charCount`，见第七轮）折算回全书
  比例再持久化（`progressOffset` 不变）；UI 在分页/纵向模式都显示全书百分比，避免「页数随加载增长」的误导。
- **WebView vh 兼容**：部分 ROM 的 WebView 会把 `100vh` 解析成 0（表现为正文全黑/高度为 0）。
  CSS 改用 JS 维护的 `--app-vh`（`window.innerHeight` 的 px 值，resize 时更新），
  不再依赖 `vh`；`100vw` 可继续使用。
- 进度持久化仍在 `ON_PAUSE` / `ON_STOP` 时 flush，和图片阅读器一致。

## 第六轮：超长条漫分块解码

- 首页页高宽比 > 3 的书（条漫/Webtoon）在**纵向模式**改用分块渲染：`ComicPageSource.pageSize()`
  先读图片头得到显示高度，`openRegionPage()` 返回 `RegionPage`（持有压缩字节的
  `BitmapRegionDecoder`），垂直页只解码当前视口上下各留 0.75 屏的切片，滚动到新的
  0.75 屏区间才重新解码一块。
- 清晰度优先：切片按「高度裁剪到 12MB 像素预算」而不是提高 `inSampleSize`，保证 narrow-tall 图
  在屏幕宽度上仍是原始分辨率；普通比例页完全走原来的整页解码路径（零额外 I/O）。
- `ReaderViewModel` 缓存页尺寸（`pageSize(index)`），并在 `prepare()` 后设置 `tallPages`
  供 UI 选择渲染路径；切片由 Compose 侧负责 `close()`，页面源保持无状态。
- 实测 800×6000 测试 CBZ：滚动连续、满宽、无缝隙，janky 0.93%（90th 16ms / 99th 23ms）。

## 第七轮：文字型 EPUB 章节质量（目录 / 可见字符 / 内联插图）

本轮解决真实轻小说（正文之外还有大量整页插图/彩页）的标题、进度与插图显示问题：章节从
「标题 + 清洗后的 HTML」升级为带元数据的契约，进度分母改用可见字符之和。

### EbookChapter 契约（`data/EpubIndexer.kt`）

```kotlin
@Serializable data class EbookChapter(
    val title: String?,              // 目录/正文提取的章节标题，可空
    val html: String,                // 清洗后的正文 HTML（本地图片已内联为 data URI）
    val charCount: Int = 0,          // 可见文字字符数；0 = 旧版缓存或纯图片页
    val imageOnly: Boolean = false,  // 该章只渲染图片（封面/彩页/插图页）
    val showHeading: Boolean = true, // false = 正文开头自带标题，阅读器不得再注入 h2
)
```

- 新字段都有默认值，旧数据反序列化不会失败；但缓存名同步升到 v2（见下），不把旧结构当新结构复用。
- `charCount` 由 `EpubTextAnalyzer.visibleTextLength` 计算：去掉 script/style/注释/标签，解码常见
  实体，忽略空白（CJK 一个字算一个字符）；图片、`&nbsp;`、标签本身都不计入。
- `imageOnly` = 无可见文字且含 `<img>`；`showHeading` = `!imageOnly &&
  !startsWithTitleElement(html) && !startsWithTitleText(html, title)`。`startsWithTitleElement`
  识别 h1–h6，以及 class 命中 pius/biaoti/title/chapter/heading 等词根的块级元素（轻小说常用
  `<p class="pius1">` 当标题），避免阅读器再注入一个重复标题。

### 章节标题来源：toc.ncx / EPUB3 nav.xhtml

- OPF 的 `<spine toc>`（缺失时取 manifest 里第一个 NCX）→ `toc.ncx` 的
  `navMap/navPoint/navLabel`；解析成「spine 条目名 → 标题」，路径用 `resolveEntry`/`normalizePath`
  相对目录文件解析。没有可用 NCX 时才回退 EPUB3 `nav.xhtml`（`epub:type="toc"` 的 nav，
  缺失时取全文 `<a>`）。
- 目录标签按 spine 顺序「向后继承」：插图页是 navPoint、正文页没有对应目录项时，正文页沿用最近的
  目录标题；纯图片页不生成标题。都没有时回退章节内 `<title>`/h1/h2。

### 图片内联（`EpubImagePolicy`）

文字型 EPUB 的插图必须能进正文（否则只剩文字），但 base64 会放大体积，所以按预算收缩：

- 单张源图读取上限 8MB；内联前若体积 > 600KB 或长边 > 1440px，先 `inSampleSize` 解码、
  等比缩到长边 1440、JPEG q82 重编码；重编码后的单张上限 1.5MB。
- 带透明通道的 PNG 不重编码（会丢透明），≤1MB 原样内联，否则丢弃；GIF/SVG/BMP 等动图/矢量同样
  不重编码，超限丢弃。
- 整本文字书内联总预算 8MB（base64 前的原始字节）；超出的图片直接移除。远程/协议相对 URL、
  `srcset`、CSS `url(http…)` 全部剥离；解码/读取失败时退回原字节或移除该 `<img>`，绝不抛异常，
  全部在 IO 线程完成。

### 章节缓存：`.chapters.v2.json`

- 仍遵守派生缓存命名规则：`cacheDir/text/<bookId>_<modifiedAt>_<sizeBytes>.chapters.v2.json`；
  v2 后缀专门隔离「新增 charCount/imageOnly/showHeading」的结构变化，旧 `.chapters.json` 不会被
  复用；MOBI 仍走原来的 `.html` 缓存。
- 写入走 `writeFileAtomically(..., sync = false)`（派生数据不 fsync）；命中缓存同样回填字符数；
  `clearCaches()` 与按 `<bookId>_` 前缀清理的规则不变。

### 进度：以章节可见字符之和为权威分母

- 章节提取完成后调用 `updateTextStats(book.id, Σ charCount.coerceAtLeast(1))`：`ComicBook.charCount`
  从索引阶段的估算值变成真实可见字符数；`pageCount` 按 `estimatePageCount(charCount)`（约
  1500 字符/页）重算，只用于书架进度显示；`progressOffset` 被 clamp 到新 charCount 内。
- 文字阅读器只认自己的 `chapterPrefix` 总和（旧缓存 `charCount == 0` 时退回 `html.length` 兼容，
  纯图片页按 1 字符计，保证目录能定位到它），加载完成后调用 `updateTextStats(totalChars)`；
  `progressOffset` 仍是全书字符偏移，`ratio = offset / totalChars`。这样内联插图不会吞掉进度，
  书架百分比与阅读器进度条共用同一分母。
- `updateTextProgress` 只写 `progress.json`（节流），`offset ≥ 99% × charCount` 时置为已读完。

### 文字阅读器契约（reader）

- **目录**：`ReaderTocSheet` 用章节标题构建（空标题回退 `reader_toc_chapter_fallback`，无题纯图片页
  不单列，相邻同名条目合并，当前章高亮）；选择条目走 `jumpToChapter`（在目标附近重建注入窗口并
  重建 WebView），进度条拖动走 `jumpToFraction`，两者都持久化新的字符偏移。
- **排版**：正文 2em 首行缩进 + 两端对齐；字号 14–28sp（`readerFontSizeSp`）、行距 1.2–2.4 倍
  （`readerLineHeight`，默认 1.8）、页边距 16–44 dp（`readerPageMarginDp`，默认 30 dp）与字族
  （`readerFontFamily`）经 CSS 变量 `--reader-line-height` / `--reader-page-margin-x` /
  `--reader-font-family` 和 `setOptions` 生效。阅读区留白：分页模式每页
  上下各 28 dp；纵向模式顶部 20 dp、底部 96 dp（给浮动底栏让位）；左右留白即页边距设置。
  发布者样式只保留字重/字号/对齐，颜色/背景/文字阴影统一由阅读主题覆盖（否则 .co1 之类白字白底
  在浅色主题下不可见）。`showHeading == false` 的章节不注入标题；`imageOnly` 章节居中，图片限制
  在一屏高内（`calc(var(--app-vh) - 112px)`）。
- **阅读器 chrome**（对齐微信读书）：顶栏不透明，左侧返回键 + 书名（`titleSmall`）与当前章节名
  （`labelSmall` 第二行），不是居中标题；底栏是贴住导航栏的扁平面板：**常驻进度滑杆在上、
  居中的「xx% · xx/xx」标签在下**（数字使用 tabular figures，翻页时不抖动；字号默认 12sp，可在阅读器设置里用「进度文字大小」滑杆调整）；再下一行图标为
  目录 / 主题 / 设置（图片书为 主题 / 设置；设置统一打开设置页的阅读器
  Activity，字号/字族/行距/页边距等排版项也在那里管理）。主题图标会在**工具栏下方**展开纸张配色面板，排版（字号/字体/页边距/行距/更多设置）是工具栏上独立的「排版」图标与独立面板；所有面板标题字号与目录标题一致，阅读器内的滑杆颜色取当前纸张的 chrome content color（跟随 Material theme / 深浅纸自动适配）（工具栏和面板是同一块扁平面板：面板展开时整个底栏用 `animateContentSize` 平滑长高、工具栏被
  顶上去，没有单独的面板入场动画；chrome 隐藏时自动收起）；进度滑杆随时可拖，松手即
  跳转，拖动时每约 2% 触发一次轻微触感反馈。面板展开时阅读区会盖一层 50% 纸张色的遮罩（仅面板
  展开时出现，普通工具栏显示时不遮罩），让工具栏、面板和页面背景融成一整块；底栏在 `navigationBarsPadding()` 之外再留 4dp，避开手势小白条。
  图片阅读器的顶/底栏、面板和系统状态栏/导航栏直接使用阅读背景色（菜单与页面同色，且不再有面板打开时的蒙版）；文字阅读器的顶/底栏、面板和系统状态栏/导航栏仍由当前纸张色混合得到（浅纸 → 白色半透明栏 +
  深色图标，黑纸 → 黑色半透明栏 + 暖白图标）；底栏/面板的背景会向下延伸盖住手势导航条区域，
  内容通过 `navigationBarsPadding()` 留白，视觉上菜单一直铺到屏幕底边。
- **页码 / 进度**：`ReaderPageInfo`（隐藏 / 进度 / 页码，默认隐藏）可在设置页切换；页码文字大小也可在设置页调整（默认 12sp）；chrome 隐藏时在
  右下角显示低调文字。图片书是精确的 `x / y`；文字书按「当前加载窗口的每页字符数」估算全书页数后
  显示 `x / y`，纵向滚动时自动退回百分比。
- **左下角电量**：阅读器设置里新增「显示电量」开关（默认开）。启用后 chrome 隐藏时在左下角显示电池图标 + 百分比，
  与右下角的页码/进度对称：图标是自绘的**横向电池**（Canvas，不用 Material 的竖版图标），只有 10 种状态——
  电量每 10% 一档（10% / 20% / … / 100%，向下取整，最低一档）；图标高度等于「页码文字大小」，与百分比数字等高，
  颜色与页码同为纸张色低透明度；电量通过 `ACTION_BATTERY_CHANGED` 广播读取（无需权限，不区分充电状态）。
  文字书与图片书共用 `ui/components/BatteryIndicator.kt`。
- **安全区**：页面上下左右留白取 `max(基础留白, safeDrawing inset + 8dp)`（状态栏/刘海/手势条），
  由 Compose 的 `WindowInsets.safeDrawing` 经 `setOptions` 传给 WebView，而不是从物理屏幕边缘量固定值。
- **左右滑动（对齐漫画阅读器）**：分页模式的 CSS 多栏条带本身就是一个原生横向滚动容器
  （`#scroll { overflow-x:auto; scroll-snap-type:x mandatory; touch-action:pan-x }`），手指拖动时
  当前页与相邻页一起跟随移动，松手后由平台原生的 fling + scroll snap 曲线吸附到页边界；若只是轻轻一划
  （拖动 >24px 但原生 snap 回到原页），JS 的 touchend 会补一次「前进/后退一页」的平滑滚动，避免小划不翻页——与图片
  阅读器 `HorizontalPager` 使用的平台滚动/回弹曲线一致，且没有逐帧 JS 或 WebView 变换。由于多栏
  没有可吸附的子元素，JS 生成一层不可见的「每页一个 snap marker」将其作为吸附目标，并给 marker 加
  `scroll-snap-stop: always`：否则一次快速 fling 会越过中间吸附点、直接落到好几页之后（「一次滑动翻了好几页」）。
  另外手势收尾还有一层兜底：touchstart 记录起始页，滚动完全停稳后若落点与起始页相差超过 1 页（老 WebView
  忽略 `scroll-snap-stop`，或手指直接拖过多页），JS 把它夹回「起始页 ±1」；滑动结束后 WebView 生成的 click 也会被
  丢弃（`dragDistance > 24`），避免一次滑动又额外触发一次点击翻页。栏宽统一使用
  `scroll.clientWidth` 的整数像素（而不是可能与布局视口有小数差异的 `100vw`），否则每页会累计
  零点几像素的漂移、后面的页会露出上一页的一条边。窗口追加/前插、旋转和图片加载后都会重建吸附点
  并重新对齐当前页。轻点仍按左/中/右三分区翻页或呼出工具栏（点击由 WebView 通过 JS bridge 上报）。
- **翻页动画**：分页模式左右两侧点击是**瞬移**（直接落到页边界，不做动画）；音量键翻页是
  110ms 的短促缓出 glide，比浏览器默认 smooth scroll 更快；纵向模式保持原生滚动。
- **目录面板**：不再是独立的 ModalBottomSheet，而是和主题面板一样内嵌在底栏同一块扁平面板里（目录
  图标高亮、整块底栏随内容平滑长高）；无图标标题栏（「目录」+ 共 N 章），列表固定高度 160dp 并在右侧常显一条细 scrollbar（提示可滚动），
  行高约 42dp、正文 `bodyMedium`（比之前更紧凑），打开时按当前章定位（`initialFirstVisibleItemIndex`），当前章高亮并
  显示「当前」标签，无标题的纯图片 spine 页不单列，相邻同名条目合并。
- **插图时序**：内联的 data-URI 图片异步解码，恢复阅读位置必须等图片 load 完成（`whenImagesReady`），
  否则分页页数/滚动高度会算错。
- 分页/纵向、窗口按章懒加载、体积协议（24M 字符 HTML 上限）和 `--app-vh` 兼容机制保持不变。


### 文字阅读器性能（滑动/进度条）
- **页码实时刷新**：WebView 的 `scroll` 事件改为 90ms 节流上报（而不是停止滚动后才 debounce 上报），
  拖动/惯性滑动过程中底栏百分比、页码和右下角指示都会持续更新。
- **拖进度条不重排**：跳转目标章若已经在当前 WebView 的已加载 DOM 窗口内（`windowStart..windowEnd`，
  包含 append/prepend 进来的章节），只调用 JS 把原生滚动位置移到目标页，不重建 WebView。
- **单一 WebView 复用**：目标在窗口外时不再销毁/重建 `AndroidView`，而是对同一个 WebView 调用
  `loadDataWithBaseURL`（跳转窗口扩大到 6 章，附近拖动基本都落在 DOM 内），跳转耗时明显降低。
- **Loading 只在真的慢时出现**：换窗遮罩延迟 140ms 才显示（快速跳转根本看不到圈圈），并按时长自动
  收起；遮罩只用纸张色，不闪白。
- **HTML 后台构建**：每次换窗的整窗 HTML 在 `Dispatchers.Default` 上 `buildBookHtml`，主线程不再拼接
  大字符串。
- **滚动停止不再抖动**：章节预取（append/prepend 扩展 DOM）延后到「最后一次滚动上报后 360ms」执行，
  不会在 snap 收尾动画期间改 scrollWidth；轻扫补翻的兜底也延后到 320ms，避免和原生 snap 抢动画。
- **上报去重 + 布局读取缓存**：JS 侧按「页索引 + 总页数 + 比例变化 >0.2%」去重后再跨 bridge 上报，同一页内的
  连续 scroll 事件不再触发 Compose 重组；`clientWidth`/`scrollWidth` 等强制 layout 的读取被缓存，只在换窗、
  追加/前插、旋转和图片加载后刷新。Kotlin 侧的章节预取也只在「页索引或窗口起点真正变化」时重启 debounce。
- **页码与排版解耦**：`y` 由字号/行距/页边距/字族和窗口尺寸推得，改排版立即重算；`x` 取 WebView 的真实
  DOM 页索引（每滑一页严格 +1），并用「跳转目标页 / 百分比偏差 > 3 页」两种锚点做自校正，拖进度条和
  初次打开时也不会出现页码与百分比对不上的误报。

## 第八轮：阅读外观（纸张预设 / 字族 / 设置面板分层）与文字书封面

### 阅读外观设置

- `ReaderBackground` 新增 `SEPIA`（米色 `#F5EFE2`）与 `GREEN`（浅绿 `#E7EFE4`）两个纸张预设：
  阅读器主题行（自动/白/米色/浅绿/黑/自定义）与全局设置页的阅读背景选项同步
  （`settings_background_sepia/green`）。`pageBackgroundColor()` 是唯一映射点，图片阅读器、文字
  阅读器和浅色系统栏判断都走它，自定义颜色仍保留。
- `AppSettings` 新增 `readerFontFamily: ReaderFontFamily`（SYSTEM / SONG / HEI，默认 SYSTEM）与
  `readerPageMarginDp`（16–44，默认 30），两者都持久化在设置里。
  文字阅读器把字族映射成 CSS `font-family`：SONG 是「Noto Serif CJK / 思源宋体 / 宋体 / SimSun +
  serif」栈，HEI 是「Noto Sans CJK / 思源黑体 / 苹方 / 微软雅黑 + sans-serif」栈，SYSTEM 用 WebView
  默认字体。字族、字号 14–28sp、行距 1.2–2.4×（默认 1.8）、页边距都由 `setOptions` 的 JSON
  一次性下发；字号滑杆松手才提交，页边距/行距三档胶囊点按即提交，避免每次拖动都重排 WebView。
- 文字阅读器的顶/底栏与系统栏跟随纸张色（见第七轮 chrome），纸张配色（自动/白/米色/浅绿/黑/自定义）
  与自定义色共用同一 `pageBackgroundColor()` 映射。
- 阅读器设置**统一收敛到设置页的 `ReaderSettingsActivity`**：排版（字族、字号、行距、页边距）、
  阅读模式、横向翻页方向、纸张配色、页码显示（隐藏/进度/页码），以及显示页码以外的常用开关和
  （仅图片书）灰度/反色。阅读器内只有微信读书式的快速面板，点「详细设置」或图片书的设置图标
  直接打开该 Activity，漫画与轻小说不再各自维护一套设置面板。

### 文字书封面（EPUB 直读）

- 之前文字型 EPUB 没有固定页封面，书架只能显示占位；现在索引阶段从 OPF 解析封面条目。
  `EpubCoverResolver` 是纯逻辑（可 JVM 单测），解析顺序为（全部失败返回 null，绝不抛异常）：
  1. `<meta name="cover" content="...">`：content 依次按 manifest id、href、文件名（不区分大小写）匹配；
  2. manifest 中 `properties` 含 `cover-image` 的条目（EPUB3）；
  3. `<guide><reference type="cover" href="..."/>`：指向图片直接用，指向 XHTML 则取页面里唯一的本地图片；
  4. manifest 图片条目中文件名含 "cover"（不区分大小写）；
  5. 第一个 manifest 图片条目。
- 索引阶段读该条目 → `decodePage(COVER_WIDTH)` → 走既有 `writeCover` 落盘（`covers/<bookId>.jpg`，
  派生缓存，可清理）。图片型 EPUB 仍取第一张图片页，行为不变。
- 封面写入成功后调用 `DerivedCoverRevision.notifyCoverChanged()`（进程内 `StateFlow<Long>`）；
  书架 `rememberCoverBitmap` 把 revision 纳入 remember/produceState 的 key，因此「打开书时才补出的
  封面」会替换书架上的占位图，不需要轮询文件。
- 旧书库补齐：`loadEpubChapters` 的「命中章节缓存」和「重新提取」两条路径都会调用
  `ensureEpubCover`，封面文件缺失时才重新读 OPF；真正没有封面的书按 `id_modifiedAt_size` 指纹在
  本进程内只尝试一次，不会每次打开都解 ZIP。`ensureCovers()` 启动阶段跳过文字书（避免启动时逐本
  打开 EPUB），封面文件小于 512B 视为无效需要重建。
- 没有封面的书在书架显示纯色占位（书 accent 色），不再用渐变——渐变会被误读成加载态。
