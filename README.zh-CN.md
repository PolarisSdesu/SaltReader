<p align="center">
  <img src="readme/logo.png" width="96" alt="" />
</p>

<h1 align="center">Salt Reader</h1>

<p align="center">本地优先的 Android 漫画与电子书阅读器。</p>

<p align="center">
  <strong>1.0.0-alpha.49</strong> · Android 8.0 及以上
</p>

<p align="center">
  <a href="https://github.com/PolarisSdesu/SaltReader/actions/workflows/android.yml"><img src="https://github.com/PolarisSdesu/SaltReader/actions/workflows/android.yml/badge.svg?branch=main" alt="构建状态" /></a>
  &nbsp;·&nbsp;
  <a href="README.md">English</a>
</p>

---

## 它是什么

一个直接读取设备上现有文件的阅读器。通过系统文件选择器授权文件夹后，应用在原地索引并读取：不导入、不复制、不移动。

没有账号、没有同步服务、没有网络权限。

## 截图

[<img src="readme/screenshot-library.png" width="220" hspace="10" vspace="10">](readme/screenshot-library.png)
[<img src="readme/screenshot-reader.png" width="220" hspace="10" vspace="10">](readme/screenshot-reader.png)
[<img src="readme/screenshot-settings.png" width="220" hspace="10" vspace="10">](readme/screenshot-settings.png)

## 支持的格式

| 格式 | 读取方式 |
|---|---|
| CBZ、ZIP | 从压缩包中央目录直接读取页面，不解包 |
| PDF | 交给系统 `PdfRenderer` 渲染 |
| 图片文件夹 | 按文件名自然序读取 |
| 图片型 EPUB | 按 EPUB spine 读取图片 |
| 文字型 EPUB | 重排文字，封面取自 OPF |
| MOBI、AZW、AZW3 | 重排文字；不支持 HUFF/CDIC 压缩 |

## 阅读

漫画可横向翻页或纵向连续滚动，支持 RTL、点击分区、双指缩放和页码滑杆。

文字可调字号、行距、页边距，可选系统字体或自备字体（支持回退顺序），带章节列表、页码或百分比显示。排版默认由平台 WebView 完成，也可切换到实验性的原生渲染器：Rust 排版引擎，在 Compose Canvas 上绘制。纸张配色有自动、白、米色、浅绿、墨黑和自定义；插图可满宽或收在页边距内。

## 书库

封面取自书籍的 OPF，缺失时显示纯色占位。书架支持搜索、筛选、排序，以及选择书卡上显示哪些标签。阅读进度按书保存；Android 16+ 可显示为 Live Update 通知。

## 权限

唯一可能申请的运行时权限是通知，用于可选的 Live Update。书籍文件夹来自系统选择器，可随时撤销。文字阅读器会拦截网络请求与远程资源。

## 构建

需要 JDK 21 与 Android SDK Platform 37。

```bash
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # 经过 R8 压缩的 release APK
./gradlew :app:testDebugUnitTest # JVM 单元测试
```

设备调试时可用 `-PfastRelease` 跳过 R8、资源压缩与 release lint；CI 与 Play 构建不会带这个参数。Gradle 配置缓存默认开启；迭代时不要跑 `clean`。

## 参与贡献

- [反馈问题或提出建议](https://github.com/PolarisSdesu/SaltReader/issues)。
- 翻译位于 `app/src/main/res/values*/`，四种语言必须齐全。
- 提交代码请保持现有风格，并确保 `./gradlew :app:testDebugUnitTest` 通过。
- 调试菜单默认隐藏，在「关于」页连点应用图标 5 次解锁。

## 第三方组件

AndroidX、Compose、Kotlin，以及一份 vendored 的 MOBI 解析器。许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
