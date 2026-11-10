# 「漫读」（com.mangareader）APK 逆向分析报告

> 分析对象：`/Users/polariss/Downloads/漫读.apk`（66,701,037 字节 ≈ 63.6 MiB）
> 分析素材：`/tmp/mandu_apk`（aapt2 解包）、`/tmp/mandu_jadx/sources`（jadx 反编译）
> 工具：aapt2 37.0.0、apksigner、dexdump、jadx
> 下文路径若无前缀，均相对 `/tmp/mandu_jadx/sources`；Manifest 行号来自 `aapt2 dump xmltree` 输出
> 结论标注：**[E]** = 直接证据（文件/行号可核）；**[I]** = 基于证据的推断

---

## 0. 结论摘要

这是一款**把三套代码库焊在一起**的本地漫画阅读器：自研的 `com.mangareader`（Room + Compose + 超分）、从 EhViewer 搬来的 `com.hippo.ehviewer` / `com.ehviewer.core`（书库索引、SMB/WebDAV、偏好设置框架）、以及 Tachiyomi 的解码器与阅读模式枚举。功能面覆盖极广（本地文件夹/压缩包/PDF/动图、SMB/WebDAV/Komga/OPDS 四种远程源、6 档 AI 超分、批量后台处理、定时备份），代价是 **63.6 MiB 安装包、181 个设置项、4 套阅读器视图、6 套页面加载器、17 张业务表**，并且冷启动会直接跳转「所有文件访问」系统授权页。

对 Salt Reader 最重要的一条：这款 App 的**每一个体验问题都能追溯到"多套实现并存"**，而不是某个功能没做好。因此 Salt Reader 的设计重点不是砍功能，而是**为每类能力只保留一条实现路径**。

---

## 1. 基础画像

| 项目 | 值 | 证据 |
|---|---|---|
| 包名 | `com.mangareader` | `aapt2 dump badging` |
| 版本 | versionName `1.3` / versionCode `34` | 同上 |
| minSdk / targetSdk / compileSdk | 26 / 37 / 37（platformBuildVersionName=`17`） | 同上 |
| 应用名 | 漫读（全部 100+ 语言一律「漫读」，未做本地化） | `badging` 的 `application-label-*` |
| 大小 | 66,701,037 B = 63.6 MiB | `ls -l` |
| 体积构成 | `lib/` 41 MiB、`assets/` 18 MiB、`classes.dex` 8.88 MiB、`res/` 388 KiB | `du -sh` |
| ABI | 仅 `arm64-v8a` + `armeabi-v7a`，**无 x86、无 ABI split** | `lib/` 目录；`unzip -l` |
| DEX | 单 dex：9,558 个类定义、57,033 个方法引用、46,119 个字符串 | `dexdump` / dex header |
| 混淆 | R8 全量混淆，jadx 中 5,280 个 `defpackage/*.java` 被重命名；资源名亦被混淆（`res/0F.jpg`、`res/5w.xml`） | 反编译目录 + `res/` |
| 加固壳 | **无**（无 libjiagu/libDexHelper/libapp-protect，`classes.dex` 可直接反编译） | `lib/` 清单 + jadx 成功 |
| 自校验 | **有**：硬编码签名摘要 + 45 s 轮询 + 失败删缓存强杀 | 见 §4-E11 |
| 签名 | 仅 **v2** scheme；RSA-2048 自签名，`CN=MangaReader, OU=Dev, O=MangaReader, L=Beijing, ST=Beijing, C=CN`；SHA-256 `cc657f10…969c22` | `apksigner verify --print-certs -v` |
| 构建工具 | AGP `9.4.0` | `META-INF/com/android/build/gradle/app-metadata.properties` |
| Application | `com.mangareader.App`，`largeHeap=true`、`allowBackup=true`、`extractNativeLibs=false`、`requestLegacyExternalStorage=true` | Manifest 行 50–57 |
| 组件数 | 2 Activity / 5 Service / 9 Receiver / 2 Provider | Manifest 统计 |
| 权限 | 14 条 `uses-permission`（13 个系统权限 + 1 个自定义） | Manifest 行 14–45 |

### 1.1 权限清单（全量）

```
READ_EXTERNAL_STORAGE (maxSdk 32)   WRITE_EXTERNAL_STORAGE (maxSdk 28)
READ_MEDIA_IMAGES                   READ_MEDIA_VIDEO
MANAGE_EXTERNAL_STORAGE             INTERNET
ACCESS_NETWORK_STATE                ACCESS_LOCAL_NETWORK
FOREGROUND_SERVICE                  FOREGROUND_SERVICE_DATA_SYNC
POST_NOTIFICATIONS                  WAKE_LOCK
RECEIVE_BOOT_COMPLETED              com.mangareader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

### 1.2 组件清单（非库内建部分）

- `com.mangareader.MainActivity`（exported=true，LAUNCHER，**唯一真实界面**）
- `com.mangareader.sr.SrBatchService`（exported=false，`foregroundServiceType=0x1` 即 `dataSync`）
- `androidx.compose.ui.tooling.PreviewActivity`（**exported=true，调试工具被打进正式包**）
- `FileProvider`（file_paths 由资源 `@0x7f0d0000` 定义）
- WorkManager 的 4 个内建 service/receiver、Room `MultiInstanceInvalidationService`、`profileinstaller.ProfileInstallReceiver`（`permission=DUMP`）

---

## 2. 技术栈逐项证据

### 2.1 语言与 UI 框架

| 技术 | 版本/判定 | 证据 |
|---|---|---|
| Kotlin | 2.2.x **[I]** | R8 剥离了 `Lkotlin/Metadata;`（dexdump 中 0 次），无法直读 `mv`；旁证：AGP 9.4.0、coroutines 1.11.0、Compose 1.10.0 均要求 Kotlin 2.2+ |
| Jetpack Compose UI | `1.10.0` | `META-INF/androidx.compose.ui_ui.version` |
| Compose Foundation | `1.9.4` | `…compose.foundation_foundation.version` |
| Compose Runtime | `1.10.0` | `…compose.runtime_runtime.version` |
| **Material3** | `1.3.0` | `…compose.material3_material3.version`（明显落后于 Compose 1.10 主线） |
| **Material You 动态取色** | **未使用** | 全仓库 `grep` 无 `dynamicLightColorScheme` / `dynamicDarkColorScheme` |
| Activity-Compose | `1.9.1`；**单 Activity 架构**，UI 全由 `setContent` 提供 | `…activity_activity-compose.version`；`com/mangareader/MainActivity.java:129,145` |
| Navigation-Compose | `2.7.7` | `…navigation_navigation-compose.version` |
| Lifecycle | `2.9.4` | `…lifecycle_lifecycle-runtime.version` |

### 2.2 数据 / 后台 / 网络 / 图片

| 技术 | 版本 | 证据 |
|---|---|---|
| Room | `2.7.0` | `…room_room-runtime.version`；`com/mangareader/data/MangaDatabase.java` |
| Room + Paging | `paging-common 3.5.0`、`room-paging` | `…paging_paging-common.version` |
| Paging Compose | `paging-compose` 存在 | `META-INF/…paging-compose.version` |
| WorkManager | `2.9.1` | `…work_work-runtime.version`；`com/mangareader/backup/scheduled/ScheduledBackupWorker.java` |
| DataStore | `1.2.1`（Preferences） | `…datastore_datastore-preferences.version` |
| 偏好封装 | 自研 `PrefDelegate<T>` + `DataStorePreferences` + `MutableSnapshot` | `com/ehviewer/core/preferences/PrefDelegate.java:15` |
| Kotlin Coroutines | `1.11.0` | `META-INF/kotlinx_coroutines_core.version` |
| Ktor（client） | `ktor-cio` 引擎 | dex 字符串 `ktor-cio`、`Ktor http-client`、`io/ktor/*` 类树 |
| OkHttp | 存在（`PlatformInitializer` 注册在 `InitializationProvider`） | Manifest 行 100；`okhttp3/*` |
| Coil（图片） | coil3（`coil3_disk_cache` 等 cache key） | dex 字符串 `Coil`、`coil3_disk_cache` |
| SubsamplingScaleImageView | `com.davemorrissey.labs.subscaleview` | 该类名仍在 dex 字符串与 jadx 中 |
| SMB 客户端 | **smbj**（`com.hierynomus.smbj`，含 NTLM/ASN.1/DFS） | `com/hierynomus/**` 完整包树 |
| 事件总线 | `net.engio.mbassy`（EhViewer 遗留） | `net/engio/mbassy` |
| 工具库 | `splitties`（`AppCtxInitializer`） | Manifest 行 97 |
| PDF | **pdfium + `android.graphics.pdf.PdfRenderer` 双实现** | `libpdfium.so`；`com/hippo/ehviewer/gallery/PdfiumPageLoaderKt.java`；`yh.java` 导入 `PdfRenderer` |

### 2.3 原生库（`lib/arm64-v8a/`，14 个，双 ABI 共 28 个文件，41 MiB）

| 库 | 大小 | 用途/证据 |
|---|---|---|
| `libsr_engine.so` | 8.65 MB | NCNN + Vulkan 超分推理；`com/mangareader/sr/VulkanHelper.java`、`SuperResolutionManager` |
| `libpdfium.so` | 6.34 MB | PDF 渲染（`PdfiumPageLoaderKt`） |
| `libimagedecoder.so` | 4.97 MB | Tachiyomi 解码器：`Ltachiyomi/decoder/ImageDecoder;` |
| `libarchive-jni.so` + `libarchive_stream.so` | 2.41 MB + 47 KB | libarchive JNI（`me.zhanghai.android.libarchive`），支持 zip/rar/7z 等；`App.java:193` `System.loadLibrary("archive_stream")` |
| `libc++_shared.so` | 1.37 MB | NDK 运行时 |
| `libzip.so` | 0.66 MB | 独立 zip 读取（与 libarchive 能力重叠） |
| `libnative_engine.so` | 0.31 MB | `com.mangareader.p000native.NativeEngine` |
| `libanimation-decoder-gif.so` | 0.28 MB | `com.github.penfeizhou.animation`（GIF/WebP 动图） |
| `libnative_reader.so` | 59 KB | `NativeReader` / `NativeReaderView` |
| `liblanczos_engine.so` | 46 KB | `com/mangareader/sr/LanczosProcessor.java` |
| `libnative_renderer.so` | 11 KB | `NativeRendererView` |
| `libandroidx.graphics.path.so`、`libdatastore_shared_counter.so` | 小 | AndroidX 内建 |

> **注意**：`libzip` 与 `libarchive`、`pdfium` 与 `PdfRenderer`、`NativeReader` 与 `PagerReaderView` 三组都是"同一件事两套实现"——这在本报告中反复出现。

### 2.4 AI 超分模型与档位

**代码侧枚举 `SRModel`（6 档，`com/mangareader/sr/SRModel.java:17-24`）：**

| key | 显示名 | 倍率 | 模型目录 | 需 Vulkan |
|---|---|---|---|---|
| `quick_optimize` | 快速优化 1x（算法） | 1x | 无（CPU 双边滤波+锐化） | 否 |
| `lanczos_2x` | 无损放大 2x | 2x | 无（Lanczos 核） | 否 |
| `realcugan_2x_conservative` | CUGAN 2x 保守 | 2x | `realcugan-2x-conservative` | 是 |
| `animevideov3_2x` | 动漫V3 2x 极速 | 2x | `animevideov3-2x` | 是 |
| `realesrgan_anime_fast` | 动漫V3 4x 大屏 | 4x | `realesrgan-anime-fast` | 是 |
| `waifu2x_cunet_1x` | waifu2x 1x 清理 | 1x | `waifu2x-cunet-1x` | 是 |

**资源侧实际打包 8 个目录（17.7 MiB）**：除上表 4 个模型目录外，还多打包了 `realcugan-2x-denoise1x`、`realcugan-2x-denoise3x`、`waifu2x-cunet-1x-noise1`、`waifu2x-cunet-1x-noise3` —— **这 4 个目录不被 `SRModel` 任何枚举值引用，合计 10.2 MiB 是死重量**（`du -sk assets/models/*`）。

- 模型格式：NCNN（`.param` 文本网络 + `.bin` 权重），来源与选型理由写在 `assets/models/README.md`（Real-CUGAN / Real-ESRGAN / waifu2x）。
- 首次使用时 `ModelManager.copyModelAssets()` 把 asset 复制到内部存储（`com/mangareader/sr/ModelManager.java:71-95`）→ **同一份权重在设备上占两份**（APK 内一份 + 内部存储一份）。
- 设备分级：`DeviceTier` = `FAST / MID / SLOW / UNKNOWN`（`DeviceProfileManager`）；质量档 `Quality` = 速度优先/均衡/画质优先（`Quality.java:15-17`）；降噪档 `DenoiseLevel`。
- 批量处理走前台服务 `SrBatchService`（`dataSync` 类型，通知渠道 `sr_progress` / `sr_done`），队列持久化在 `SRQueueStore`，另有 `SrBenchmark`、`SRDiskCache`、`SRPreloadDispatcher`。

---

## 3. 产品功能地图

| 能力域 | 实现 | 证据 |
|---|---|---|
| 书库/书架 | `manga_books` + `bookshelves` + `bookshelf_items` + `tags`/`tag_links` | `MangaDatabase_Impl.java` 的 `CREATE TABLE` |
| 本地导入 | **两条并行路径**：①SAF `OPEN_DOCUMENT_TREE` → `imported_folders.treeUri`；②`LIBRARY_ROOTS.TREE_URI` + `LOCAL_GALLERIES`（EhViewer 血统） | dex 字符串 `android.intent.action.OPEN_DOCUMENT_TREE`；两张表结构 |
| 书库根 | 本地目录 / SMB / WebDAV 三类源表（`LIBRARY_ROOTS`、`SMB_SOURCES`、`WEBDAV_SOURCES`） | 同上 |
| 格式支持 | 文件夹图集、压缩包（libarchive/zip，含 solid 流式解包 `SolidExtractEngine`）、PDF（pdfium）、动图（GIF/WebP）、视频（`KIND_VIDEO`） | `FolderIndexDisk.java:41-45`；`gallery/*PageLoaderKt` |
| 下载 | `download_tasks` 表（进度/字节/错误/status）+ `FolderNetworkDownloadKt` | 表结构；`com/hippo/ehviewer/gallery/FolderNetworkDownloadKt.java` |
| 在线源 | **Komga**（series/book/memo/进度同步队列）、**OPDS**（`opds-spec.org` 链接）、**WebDAV**、**SMB** | `komga_*` 四张表；dex 字符串 `http://opds-spec.org/acquisition`；`com/hippo/ehviewer/webdav/*`；`com/hippo/ehviewer/smb/*` |
| ~~EhViewer 在线画廊~~ | **已失效/死代码**：`EhDB` 四个方法全为空实现（`loadGalleryInfo` 直接 `return null`），全仓库无 e-hentai/exhentai 端点 | `com/hippo/ehviewer/EhDB.java` |
| 阅读器 | **4 套视图**：`NativeReaderView`、`PagerReaderView`、`AnimPagerReaderView`、`WebtoonReaderView`（另加 `SubsamplingScaleImageView` 缩放） | `com/mangareader/p000native/`（77 个文件） |
| 阅读布局 | 分页/卷轴/双页/条漫/右→左/竖排、双页拆分与偏移、E-Ink 模式、裁白边、灰度、反色、翻页动画、自动翻页、无缝进下一卷 | `ReaderConfig.java`（39 个字段）；`ReadingDirection`、`NavigationKind` |
| 页面加载 | **6 套 PageLoader**：Archive / DocumentExtract / Folder / Pdfium / SmbFolder / SolidExtract | `com/hippo/ehviewer/gallery/` 目录 |
| 超分 | 6 档、单页/批量、前台服务、队列持久化、基准测试、磁盘缓存、诊断导出 | `com/mangareader/sr/`（37 个类） |
| 备份 | 定时备份 `ScheduledBackupWorker`（WorkManager）+ `backupDirUri` + `backupBeforeUpdate` | `com/mangareader/backup/scheduled/`；`Settings.java` 字段 |
| 同步 | 仅 Komga 阅读进度回写队列（`komga_progress_sync_queue`）；WebDAV 只有缓存，无双向同步 | 表结构 |
| 诊断 | `DiagLogExporter` 经 MediaStore 写入 Downloads；`App` 的 ANR watchdog 写 `anr_log.txt` | `com/mangareader/sr/DiagLogExporter.java:249`；`App.java:234-247` |

---

## 4. 体验问题定位

### 4.1 有证据的问题

**E1｜冷启动直接跳「所有文件访问」授权页，且与 SAF 并存（最严重的首次体验问题）**
`MainActivity.onCreate` 在 `setContent` **之前**判断：`SDK>=30 && !Environment.isExternalStorageManager()` 就 `startActivity(MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)`，失败再退到全局设置页（`com/mangareader/MainActivity.java:111-121`）。这意味着：**冷启动 → 系统设置页 → 用户返回 → 才看到主界面**。而且它完全没必要：App 同时实现了 `OPEN_DOCUMENT_TREE`（SAF）与 `LIBRARY_ROOTS.TREE_URI`，即安全目录授权路径是存在的，只是被这条"全盘授权"快路径覆盖了。

**E2｜`largeHeap=true`**
Manifest 行 52。配合 41 MiB 原生库、8.88 MiB 单 dex、动图/PDF/超分并行解码，说明作者已经遇到 OOM 并选择扩大堆，而不是控制内存占用。

**E3｜体积：63.6 MiB，无 ABI 拆分、无按需下发**
`lib/` 41 MiB（双 ABI 全量打包）+ `assets/models` 17.7 MiB。用户装一个"本地漫画阅读器"要付出 63.6 MiB 和两份 ABI 的代价。

**E4｜模型双重占用 + 10.2 MiB 死权重**
`ModelManager.copyModelAssets()` 首次使用把 asset 复制到内部存储（设备上双份）；`assets/models` 里 4 个目录不被 `SRModel` 枚举引用（见 §2.4），纯死重量。且 README 只列 5 档、代码 6 档、资源 8 目录——**三处口径不一致**。

**E5｜设置项爆炸：181 个开关**
`com/hippo/ehviewer/Settings.java` 有 **181 个 `PrefDelegate` 字段（行 41–225）**，且暴露了大量传输层内部参数：`smb3Only`、`smbAsyncTransport`、`smbEncryptData`、`smbOpsPerSession`、`smbReaderSafeConcurrency`、`webDavHttp2`、`webDavInsecureTls`、`streamKeepAliveUnlimited`（`Settings$*.java` 与字段名）。普通用户被要求为协议栈调参。

**E6｜两套书库模型并存，靠运行期去重兜底**
`MangaDatabase_Impl` 里同时存在 `manga_books` + `imported_folders`（旧模型）和 `LIBRARY_ROOTS` + `LOCAL_GALLERIES`（EhViewer 模型），两套都能代表"本地一本书"。为此专门写了 `BookDeduper`（TAG `DedupBooks`，`com/mangareader/data/BookDeduper.java`）在运行期合并。**这是"同一本书出现两次/进度对不上"的结构性根因**——不是 bug，是数据模型重复。

**E7｜4 套阅读器视图**
`NativeReaderView` / `PagerReaderView` / `AnimPagerReaderView` / `WebtoonReaderView` 各自实现加载、缓存、手势、翻转（`com/mangareader/p000native/`，77 个文件，内嵌大量 `$loadPageAsync$…$1` 协程类）。不同入口（本地/压缩包/SMB/WebDAV/Komga）可能落到不同视图 → 手势、翻页动画、进度记录口径都可能不一致。**[I]** 具体表现为"同一个 App 里翻页手感因书而异"。

**E8｜6 套 PageLoader + 双 PDF 引擎 + 双 zip 引擎**
`gallery/` 下 `ArchivePageLoader*`、`DocumentExtractPageLoader*`、`FolderPageLoader*`、`PdfiumPageLoader*`、`SmbFolderPageLoader*`、`SolidExtractPageLoader*`；PDF 同时有 `libpdfium` 与 `android.graphics.pdf.PdfRenderer`；压缩同时有 `libarchive-jni`、`libarchive_stream`、`libzip`。能力重叠带来的是**行为分叉**（同一 PDF/压缩包在不同入口渲染尺寸、进度、裁切不同）和**维护成本**。**[I]** 这是渲染差异类 bug 的温床。

**E9｜EhViewer 遗留（死代码 + 死设置）**
`com/hippo/ehviewer/**`、`com/ehviewer/core/**` 完整保留；`Settings` 里仍有 `gallerySite`（`gallery_site_2`）、`requestNews`、`showTagTranslations`、`mediaScan` 等 EhViewer 专用项；`EhDB` 已是空壳；还带着 `net.engio.mbassy` 事件总线、`splitties`。**用户能看到的后果**是：设置里存在点了没反应或语义不明的项。**[I]** 更隐蔽的后果是阅读器里混入了 EhViewer 的 `ReadingModeType`/`OrientationType` 枚举与 Tachiyomi 语义，与新实现并存。

**E10｜调试产物进正式包**
`DebugProbesKt.bin`（kotlinx-coroutines 调试探针，1.7 KB）、`assets/dexopt/baseline.prof`、以及 **exported=true 的 `androidx.compose.ui.tooling.PreviewActivity`**（Manifest 行 76–78）。后者是一个可被外部 App 拉起的调试入口，属于安全性与"未做 release 收尾"的硬证据。

**E11｜自校验：45 s 轮询，失败删缓存并强杀**
`App.onCreate` 起 `SigVerifyBg` 线程（`App.java:203-205`），循环 `sleep(45_000)` → `md1.w(app)` 复查签名（常量见 `KeepOpenSmbFileSource.STICKY_IDLE_PING_MS = 45000`，`:75`）。校验逻辑 `md1.N()` 比对硬编码摘要 `CC657F10…969C22`（`md1.java:443-457`，另有一处 `:1308`）；失败时 `md1.o()` 先 `deleteRecursively` 掉 `files/covers` 与 `files/page_cache`，再 `NativeEngine.nativeForceExit(1)`（`md1.java:923-937`）。**后果**：任何非原签名的分发（应用商店重签、用户改包）都会表现为"用一会儿突然闪退且封面/缓存被清空"，且**没有任何提示**。

**E12｜异常处理密度：397 处静默捕获**
全仓库 `catch (Exception unused)` / `catch (Throwable unused)` 形式的捕获合计 **397** 处（`grep -rc` 在 `com/`、`defpackage/` 求和），绝大多数捕获体为空。典型如 `App.java:191-195` 把 `loadLibrary("archive_stream")` 的失败整个吞掉（压缩包能力静默降级），`MainActivity.java:119-124` 把两次 `startActivity` 失败都吞掉。**后果**：故障不冒泡、用户只看到"打不开/没反应"，也没有可上报的归因（见 E14）。

**E13｜存储模型与 Play 政策风险**
`targetSdk=37` 仍声明 `MANAGE_EXTERNAL_STORAGE`，并带 `requestLegacyExternalStorage="true"`（该属性仅在 Android 10 生效，对 targetSdk 37 无意义，属复制粘贴遗留）。同时 `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` 与 `MANAGE_EXTERNAL_STORAGE` 并存。

**E14｜无任何第三方统计/崩溃上报（双面）**
dex 中搜不到 Firebase/Crashlytics/友盟/Bugly/Sentry/AppsFlyer 等（负向证据）。好处是隐私干净；坏处是叠加 E12 的 397 处静默 catch，**作者拿不到线上崩溃与失败率**，只能靠本地 `anr_log.txt` 与用户口述。

**E15｜ANR watchdog 常驻线程**
`App.onCreate` 另起 `AnrWatchdog` daemon：`sleep(2000/5000ms)` → `handler.post` → `sleep(3000/8000ms)` → 主线程超时则抓 30 帧堆栈写 `anr_log.txt`（`App.java:214-256`）。这本身是"已知会卡顿"的自证；常驻 2 s 心跳对续航不友好。

**E16｜首启/增量扫描**
书库扫描入口 `scanDir`、`scanMediaStoreFolderGalleries`（`com/hippo/ehviewer/library/`），配合 `FolderIndexDisk`（索引落盘）与 `Settings.mediaScan`。**[I]** 大目录首扫会长时间占用 IO，且与 E1 的授权跳转叠加，构成"第一次打开很久没内容"的典型观感。

### 4.2 仅为推测的部分（无直接证据，供 Salt Reader 参考）

- **[I]** 后台耗电：`SrBatchService`（dataSync 前台服务）+ WorkManager 定时备份 + ANR watchdog + 45 s 签名轮询，四者叠加在息屏后仍可能有唤醒。
- **[I]** 内存峰值：`largeHeap` + 动图解码 + 超分 + PDF 并行时，低端机 OOM 概率高（`MAGMA` 无证据，仅由 E2/E4/E8 推断）。
- **[I]** "设置项爆炸"导致的功能可发现性差：181 项里超分/mihon 式阅读选项混杂，新用户很难找到"裁白边"这类高频项。
- **[I]** Komga/OPDS/WebDAV/SMB 四套远程源各自的错误提示文案与重试策略不统一（各类 `*Client`、`*ByteSource` 独立实现，未见统一错误面）。

---

## 5. 对 Salt Reader 的设计建议（12 条，均可落地）

> 总纲：**本地优先、单一实现、零权限启动、体积预算硬约束。** 漫读的所有问题都不是"缺功能"，而是"同一能力有 2–6 份实现"。Salt Reader 的差异化不是加功能，而是**把每条能力收敛成唯一路径**。

1. **冷启动零权限，SAF 作为唯一导入通道。** 首帧必须直接进书库；导入走 `ACTION_OPEN_DOCUMENT_TREE` 持久化 URI 授权。**不申请 `MANAGE_EXTERNAL_STORAGE`**，不做启动跳转系统设置（正面对照漫读 `MainActivity.java:111-121`）。权限申请只在用户点击"导入文件夹"时发生，且可拒绝后继续用内置示例。

2. **单一书库模型。** 一张 `library_roots`（含 `tree_uri`/`access_mode`）+ 一张 `library_entries`，导入即索引；不设"旧模型 → 新模型"的迁移双写，也不在运行期做去重（反面对照 E6 的 `BookDeduper`）。标题/系列用规范化 key 在**写入时**唯一，而非读取时合并。

3. **阅读器只保留一套实现。** 一个 `ReaderSurface`（Compose Canvas + 手势层）+ 一个 `PageSource` 接口；分页 / 卷轴 / 双页 / 条漫 / 右→左 / 双页拆分全部是**同一引擎的布局参数**，不是四个 View（反面对照 E7）。这样"翻页手感"天然一致，也不需要为每个入口重写手势。

4. **页面加载器只保留一套。** 统一 `PageSource` 抽象，实现层按容器类型注册（folder / archive / pdf），PDF 只选 **pdfium 或 PdfRenderer 之一**（建议 PdfRenderer 起步，体积为 0；需要高级裁剪再上 pdfium），压缩只选 **libarchive 一个**。删除 libzip、libarchive_stream 与 libarchive-jni 的冗余组合（反面对照 E8，可省 ~3 MB）。

5. **体积预算写进 CI：首装 ≤ 15 MiB，含模型 ≤ 25 MiB。** 具体手段：仅 `arm64-v8a` 起步 + App Bundle ABI split；超分模型**不随包**，作为可选下载（`Play Feature Delivery` 或首启按需拉取），默认只保留 `快速优化 1x`（纯 CPU，0 MB）。若必须内置，最多 1 个模型（≈2.5 MB），并去掉"复制到内部存储"的二次拷贝（反面对照 E3/E4，可省 40+ MiB）。

6. **超分只留 3 档用户可见档位，去掉"批量前台服务"作为默认路径。** UI 上：`关 / 快速优化 / 高清`；批量处理仅在用户显式选择"整本优化"时走 WorkManager 唯一队列，进度在书库条目上内联显示，不做常驻通知渠道（反面对照 `SrBatchService` + `sr_progress`/`sr_done` 双渠道）。

7. **设置项硬上限 30 项，三层信息架构。** 「阅读」（4–6 项高频：布局/方向/裁边/亮度）→「书库」（2–3 项）→「高级」（默认折叠，只放诊断与导入导出）。传输层参数（SMB 版本、HTTP/2、并发数）**不进 UI**，固定在代码里 + 诊断页只读展示（反面对照 E5 的 181 项与 `smbOpsPerSession`）。

8. **无网络权限版本作为默认形态。** 本地阅读不需要 `INTERNET`；Komga/OPDS/WebDAV/SMB 作为**可选模块**（独立 variant 或用户显式开启后才声明/使用网络，SMB 走 `ACCESS_LOCAL_NETWORK` 语义）。这直接消灭"网络依赖"与隐私顾虑，也是对漫读"四种远程源全塞进主包"的反向设计。

9. **MD3E 交互规范落成可检查的清单。** 一屏一决策；主操作单按钮；列表项 72 dp、触控目标 ≥48 dp；**默认启用 Material You 动态取色**（漫读明明用 Material3 1.3 却完全没接动态色，是低成本高感知的差距）；封面用共享轴转场，阅读器进出用容器变换；禁止在启动路径上弹任何对话框。

10. **后台任务只有一个入口。** 所有后台工作（备份、按需超分、下载）统一走 WorkManager，唯一前台服务类型，任务可在"任务中心"取消/暂停/重试，进度对用户可见且可解释。删除 45 s 轮询类线程与 ANR watchdog 常驻心跳（反面对照 E11/E15）。

11. **错误必须可见、可自诊断、但不上传。** 禁止静默捕获（漫读有 397 处，E12）；统一 `Result` + 全局 `ErrorSurface`（"打不开 + 原因 + 重试/跳过"）；提供"导出诊断包"（设备信息 + 最近 N 条结构化日志）由用户手动分享。崩溃采集用本地 ring buffer，不做静默上报。

12. **发布收尾检查表。** 移除 `PreviewActivity`（exported 调试入口）、`DebugProbesKt.bin`、baseline profiler 之外的调试资产；移除自校验与反调试（不做签名轮询、不因签名失败删用户缓存，E11）；`largeHeap=false` 并把内存预算写进测试（E2）；`allowBackup` 与 `requestLegacyExternalStorage` 显式决策而非遗留。

### 5.1 三条"不要做"（从漫读踩坑反推）

- **不要**为了"支持所有格式"引入 6 套加载器；先做文件夹 + zip，PDF 第二阶段，动图第三阶段。
- **不要**保留任何"点了没反应"的历史设置项；宁可从零设计设置树，也不要在旧树上打补丁（E9）。
- **不要**用"运行期去重 / 双写迁移"解决数据模型问题；这是漫读最贵的架构债（E6）。

---

## 附录：关键证据索引

| 结论 | 文件:行 |
|---|---|
| 启动强制所有文件授权 | `com/mangareader/MainActivity.java:111-121` |
| Application 初始化、库加载、崩溃/ANR/签名线程 | `com/mangareader/App.java:190-256` |
| 签名摘要比对 | `defpackage/md1.java:443-457`、`:1308` |
| 签名失败删缓存并强杀 | `defpackage/md1.java:923-937` |
| 45 s 轮询常量 | `com/hippo/ehviewer/smb/KeepOpenSmbFileSource.java:75` |
| 181 个设置项 | `com/hippo/ehviewer/Settings.java:41-225` |
| 39 个阅读配置字段 | `com/mangareader/data/ReaderConfig.java:25-63` |
| 6 档超分枚举 | `com/mangareader/sr/SRModel.java:17-24` |
| 模型 asset 复制 | `com/mangareader/sr/ModelManager.java:71-95` |
| 模型档位说明（5 档） | `assets/models/README.md` |
| 17 张业务表结构 | `com/mangareader/data/MangaDatabase_Impl.java` |
| EhViewer 在线能力已空壳 | `com/hippo/ehviewer/EhDB.java` |
| 书库扫描 | `com/hippo/ehviewer/library/`（`scanDir`、`scanMediaStoreFolderGalleries`） |
| 4 套阅读器视图 | `com/mangareader/p000native/`（77 文件） |
| 6 套 PageLoader | `com/hippo/ehviewer/gallery/*PageLoaderKt.java` |
| 依赖版本 | `META-INF/*.version` |
| Manifest 结构 | `aapt2 dump xmltree`（行 47-220） |

## 6. Salt Reader 的落地映射

| 建议 | 实现 |
|---|---|
| 本地优先 / 零权限启动 | Manifest 不声明任何权限；首次启动直接进书架 |
| SAF 目录扫描入口 | 目录树递归扫描 CBZ/ZIP/图片文件夹，一次加入书架；另保留单本文件导入 |
| 单一阅读器 / 单一 PageLoader | `ReaderScreen` + `ComicPageSource`（Archive/Folder 两个实现，共用解码） |
| 模型不随包 | 不打包超分模型；APK 只包含 3 本示例漫画 |
| 设置 ≤ 30 项 | 主题、动态取色、阅读方式、方向、纸张、常亮、页码，共 7 项 |
| 无网络权限默认形态 | 没有 INTERNET 权限，也没有在线源 / 同步 / 遥测 |
| 错误可见可诊断 | 导入失败按文件返回原因；缺页、缺书有明确占位与提示 |
| MD3 规范 | 蓝色 seed 生成完整亮/暗 Scheme；标准组件、类型、形状与间距 |
| 体积预算 | 不内置 AI 权重；示例漫画约 10.5 MB，debug APK 约 30 MB |
