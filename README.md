# Salt Reader

本地优先的 Android 漫画 / 电子书阅读器，Jetpack Compose + Material 3 Expressive。包名 `moe.gbxin.saltreader`。

- 通过 SAF 授权目录读取，只索引原文件，不复制、不转换
- 不申请存储、全盘或网络权限
- 支持 CBZ / ZIP / EPUB / MOBI / AZW3 / PDF / 图片文件夹
- 中英日多语言，支持动态取色与浅色 / 深色 / AMOLED 主题

## 功能

- **书库**：SAF 目录索引、下拉刷新增量更新、全部 / 在读 / 未读筛选、搜索、布局与书卡标签自定义
- **图像阅读器**：横向翻页 / 纵向滚动、RTL、点击分区翻页、双指缩放、页码滑杆、进度持久化
- **文字阅读器**：EPUB / MOBI / AZW3 由原生 WebView 重排，字号调节、分页 / 滚动、字符偏移进度
- **PDF**：系统 `PdfRenderer` 按需渲染
- **设置**：外观（主题、动态取色、显示语言）、阅读器、存储、开源许可、关于

## 支持格式

| 格式 | 读取方式 |
|---|---|
| CBZ / ZIP | 自研 `RemoteZipReader` 直读中央目录，按页解压 |
| 图片型 EPUB | 按 spine 顺序直读图片条目 |
| 文字型 EPUB / MOBI / AZW3 | 抽取正文交给原生 WebView 重排 |
| PDF | 系统 `PdfRenderer` |
| 图片文件夹 | SAF 目录直读，自然序 |

## 构建

需要 JDK 21 与 Android SDK Platform 37。

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # R8 压缩后的 release APK
./gradlew :app:testDebugUnitTest # JVM 单元测试
```

release 签名读取被 git 忽略的 `keystore.properties` 或 `SALTREADER_*` 环境变量；缺失时产出未签名 APK。

## 技术栈

Kotlin 2.3.21 · AGP 9.4 · Gradle 9.7.1 · Compose BOM 2026.09 · Material 3 1.5 · `compileSdk 37` / `minSdk 26`。代码结构见 `app/src/main/java/moe/gbxin/saltreader/`（`data` / `ui` / `util`）。

## 版本与文档

- 版本以 `version.properties` 为唯一来源，用 `scripts/bump_version.py` 或 `./gradlew bumpVersion -Paction=alpha` 递增。
- 详细设计文档：`docs/`（SAF 架构、i18n、版本管理）。
- 第三方组件声明：`THIRD_PARTY_NOTICES.md`。
