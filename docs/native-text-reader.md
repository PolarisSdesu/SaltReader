# 原生文字阅读器（实验性）

## 概览

文字书阅读器有两条渲染路径：

- 默认：WebView + 注入 CSS/JS（`TextReaderContent`），功能完整（图片、表格、字体链、竖排）。
- 实验性开关「原生阅读器（测试）」：`NativeTextReaderContent` + Rust 排版引擎，绕过 WebView。

开关在 **设置 → 阅读器 → 布局** 顶部；原生库加载失败时会自动回退 WebView。

## 架构

```
app/src/main/java/moe/gbxin/saltreader/ui/reader/NativeTextReaderContent.kt
  ├─ 章节 HTML → NativeTextLayout.paginateHtml(...)  (JNI)
  ├─ Rust 返回带样式前缀的行 → Compose Canvas 逐行 drawText
  └─ 顶栏/底栏/面板复用 ReaderChrome.kt 的原生 chrome

app/src/main/jniLibs/{arm64-v8a,x86_64}/libtext_reader.so   (构建产物)
native/text-reader/                                         (Rust crate)
  ├─ 轻量 HTML 词法 + CSS 子集（font-size/text-align/text-indent/margin/
  │  font-weight/font-style/line-height/display）
  ├─ crates.io: unicode-linebreak（UAX #14 断行）、unicode-segmentation、
  │  unicode-width、jni
  └─ 分页 → `页面1\u0000页面2…`，行 `\n` 分隔，行首 `\u0001`+11 位样式编码
```

样式编码（每行，11 位）：字号×10、行高倍率×10、粗/斜/等宽标志、对齐(0左/1中/2右/3两端)、左缩进×10、段前距×10。

## 构建

```bash
# 需要 rustup target add aarch64-linux-android x86_64-linux-android 与 Android NDK
native/text-reader/build-android.sh     # 交叉编译并复制到 app/src/main/jniLibs
./gradlew :app:assembleDebug            # 或 assembleRelease
```

NDK 路径可用 `ANDROID_NDK_HOME` 覆盖；脚本默认使用 `~/Library/Android/sdk/ndk/30.0.16248370`。

## 已知限制（分步补齐）

- 图片（内嵌插画/base64）暂以「［图片］」占位；图片书请继续用 WebView 阅读器。
- 表格按单元格文本展平，不是网格。
- 两端对齐尚未实现（目前左对齐）；行内粗体/斜体已解析，等宽代码块已支持。
- 书籍自带 CSS 只解析简单选择器（tag/.class），不支持 id/后代/伪类。
- 自定义字体与回退字体已接入（DOCUMENT 字体走 `ReaderFonts.ensureCached`）。
- 翻页、目录、主题、字号/行距/页边距、进度、音量键、沉浸式系统栏、页码角标已接入；
  纵向模式按钮先禁用（后续实现）。
