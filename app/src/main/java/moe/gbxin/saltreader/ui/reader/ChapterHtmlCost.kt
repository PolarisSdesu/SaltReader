package moe.gbxin.saltreader.ui.reader

/**
 * 章节 HTML 的“渲染成本”：总长度减去内联 base64 图片负载。
 *
 * WebView 与 Native 阅读器必须使用同一口径计算全书字符数，否则保存的 progressOffset
 * 除出来的比例会偏小（Native 之前用原始 HTML 长度），重开书会回到第一页并覆盖进度。
 */
internal fun chapterHtmlCost(html: String): Long {
    var cost = html.length.toLong()
    var index = html.indexOf("data:image")
    while (index >= 0) {
        val quote = html.indexOf('"', index)
        if (quote < 0) break
        cost -= (quote - index).toLong()
        index = html.indexOf("data:image", quote)
    }
    return cost.coerceAtLeast(1L)
}
