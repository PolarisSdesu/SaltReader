package moe.gbxin.saltreader.data

/**
 * Rust 原生排版核心（`libtext_reader.so`，见 `native/text-reader`）。
 *
 * 负责轻小说/文字书的断行与分页：Kotlin 传入纯文本、版心尺寸、字号/行高/缩进和
 * Paint 实测字宽表，Rust 返回 `页面1\u0000页面2…`（页面内行用 `\n` 分隔）。
 * 相比 WebView，这条路径没有 HTML/CSS 解析与多栏布局开销。
 */
object NativeTextLayout {
    /** 当前设备是否加载到了原生库；缺失时阅读器自动回退到 WebView。 */
    val isAvailable: Boolean = runCatching { System.loadLibrary("text_reader") }.isSuccess

    /**
     * 解析章节 HTML（含内联 CSS 子集）并分页：返回 `页面1\u0000页面2…`，
     * 行以 `\n` 分隔，每行以 `\u0001` + 11 位样式编码开头（字号×10、行高×10、标志、对齐、左缩进×10、段前距×10）
     * （字号×10、行高×10、粗/斜/等宽标志、对齐、左缩进×10）。
     */
    external fun paginateHtml(
        chapters: Array<String>,
        width: Float,
        height: Float,
        fontSize: Float,
        lineHeight: Float,
        codePoints: IntArray,
        advances: FloatArray,
        imageAspects: FloatArray,
        imageFullWidth: Boolean,
        fullPageWidth: Float,
        imageMaxHeightRatio: Float,
    ): String
}
