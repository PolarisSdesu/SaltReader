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

app/src/main/jniLibs/{arm64-v8a,x86_64}/libsaltreader_layout.so   (构建产物)
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

## 与 WebView 阅读器的对齐约定

优先级：**观感以 WebView 为准**。改动排版时先对照 WebView 的 CSS，再改 Rust。

- **章节分页**：正文章节另起一页；唯一例外是上一页只有插图——WebView 的插图章图片会与
  后一章标题/正文同页，此时不强制换页（否则头图独占一页、页尾大片空白）。
- **图片**：块级图片居中，宽度 = min(版面宽度, 图片原始宽度×density)，高度不超过页高×0.92；
  整页只有一张图时按满宽（`isImageOnlyPage`）。「图片全宽显示」开关关闭时按页边距内收。
  行内小图（脚注 `sup img`）高 1.15em、`vertical-align: super`、左右 0.1em。
- **代码**：代码块绝对 0.86em / 行高 1.5，无底色；行内代码 0.92em 等宽、无底色，按等宽字宽断行。
- **ruby 注音**：`<ruby>` 作为整体不可断 token，基字正常绘制、读音以 0.5 倍字号居中画在上方；
  复制时退化为「基字(读音)」。
- **表格 span**：`colspan/rowspan` 通过空单元格占位保证列对齐，暂不绘制合并边框。
  折行落在行内代码/粗体中间时，Rust 会在新行行首重新注入仍生效的行内标记，避免后半段掉回正文字体。
- **两端对齐**：可拉伸边界对照 Blink 的 `text-justify: auto`（`JustificationContext`）——
  空格类字符之后、CJK 表意文字/符号两侧；默认忽略字符（零宽空格、变体选择符）不产生边界。
  行内代码内部不产生边界（代码本身不被拉开），但它与 CJK 的边界参与对齐。段落最后一行不拉伸，
  `<br>` 结束的行仍然拉伸；没有「补间超过 0.2em 就退化为左对齐」的阈值（WebView 也没有）。
- **段落节奏**：WebView 的 `#book p` 间距、`text-indent: 2em`、章节标题样式由 Rust 的 CSS 子集复现。
  阅读器自动注入的章节标题带 `chapter-heading` class，Rust 按 WebView 的 0.35em/1.15em 间距处理。

## 已知限制（分步补齐）

- 表格按单元格文本展平为网格线绘制，不是真正的 HTML 表格语义。
- 书籍自带 CSS 只解析简单选择器（tag/.class），不支持 id/后代/伪类。
- 两端对齐的边界判定已与 Blink 对齐；剩余差异主要来自断行处的字宽估算（自定义字体、
  行内代码等宽字体）与 WebView 的实际度量。
- 超长且无空格的 token（如 `std::char::from_digit(num,radix)`）在 WebView 里不会断字，
  会溢出并被裁切；原生阅读器选择继续断行保证可读性，这是有意的行为差异。
- 正文选中为段落级（无拖拽手柄）；复制会去掉逐行换行与全角缩进。
- 翻页、目录、主题、字号/行距/页边距、进度、音量键、沉浸式系统栏、页码角标、纵向滚动已接入。

