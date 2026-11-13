package moe.gbxin.saltreader.ui.reader

/**
 * 两端对齐的“可拉伸边界”判定。
 *
 * 规则对照 Chromium Blink 的 `JustificationContext::CheckOpportunity`
 * （`third_party/blink/renderer/platform/text/justification_opportunity.cc`，
 * `text-justify: auto` 分支）：
 *
 * - 空格类字符（空格/制表符/换行/不换行空格）之后有一个可拉伸点；
 * - CJK 表意文字与符号的左右两侧都有可拉伸点；
 * - 默认忽略字符（零宽空格、变体选择符等）不产生拉伸点，也不打断相邻判定；
 * - 其它字符（拉丁字母、数字、标点）不产生拉伸点。
 *
 * 这里采用等价的边界式写法：`前一个字符是空格/CJK` 或 `后一个字符是 CJK`
 * 时边界可拉伸。Blink 的状态机保证每个边界最多只计一次，本写法同样成立。
 */
object TextJustification {
    /** 与 Blink `TreatAsSpace()` 对齐；注意 U+3000 表意空格按 CJK 符号处理。 */
    fun isTreatAsSpace(codePoint: Int): Boolean =
        codePoint == 0x20 || codePoint == 0x09 || codePoint == 0x0A || codePoint == 0xA0

    /**
     * 与 Blink `IsCjkIdeographOrSymbol()` 对齐：CJK 部首、表意文字、假名、
     * 注音、谚文、全角标点与符号，以及扩展区汉字。
     */
    fun isCjkIdeographOrSymbol(codePoint: Int): Boolean {
        if (codePoint < 0x2C7) return false
        return when {
            codePoint in 0x2C7..0x2CB -> true
            codePoint in 0x2E80..0x2EFF -> true
            codePoint in 0x2F00..0x2FDF -> true
            codePoint in 0x2FF0..0x2FFF -> true
            codePoint in 0x3000..0x303F -> true
            codePoint in 0x3041..0x30FF -> true
            codePoint in 0x3105..0x312F -> true
            codePoint in 0x3131..0x318E -> true
            codePoint in 0x3190..0x31E3 -> true
            codePoint in 0x31F0..0x31FF -> true
            codePoint in 0x3200..0x33FF -> true
            codePoint in 0x3400..0x4DBF -> true
            codePoint in 0x4E00..0x9FFF -> true
            codePoint in 0xA960..0xA97F -> true
            codePoint in 0xAC00..0xD7AF -> true
            codePoint in 0xF900..0xFAFF -> true
            codePoint in 0xFE10..0xFE19 -> true
            codePoint in 0xFE30..0xFE6F -> true
            codePoint in 0xFF01..0xFF60 -> true
            codePoint in 0xFFE0..0xFFE6 -> true
            codePoint in 0x1B000..0x1B16F -> true
            codePoint in 0x1F200..0x1F2FF -> true
            codePoint in 0x20000..0x3FFFD -> true
            else -> false
        }
    }

    /** 近似 ICU 的 `UCHAR_DEFAULT_IGNORABLE_CODE_POINT`，只覆盖常见区段。 */
    fun isDefaultIgnorable(codePoint: Int): Boolean = when {
        codePoint < 0x00AD -> false
        codePoint == 0x00AD -> true
        codePoint == 0x034F -> true
        codePoint == 0x061C -> true
        codePoint in 0x115F..0x1160 -> true
        codePoint in 0x17B4..0x17B5 -> true
        codePoint in 0x180B..0x180F -> true
        codePoint in 0x200B..0x200F -> true
        codePoint in 0x202A..0x202E -> true
        codePoint in 0x2060..0x206F -> true
        codePoint == 0x3164 -> true
        codePoint in 0xFE00..0xFE0F -> true
        codePoint == 0xFEFF -> true
        codePoint == 0xFFA0 -> true
        codePoint in 0xFFF0..0xFFF8 -> true
        codePoint in 0x1BCA0..0x1BCA3 -> true
        codePoint in 0x1D173..0x1D17A -> true
        codePoint in 0xE0000..0xE0FFF -> true
        else -> false
    }

    /**
     * [previous] 与 [next] 之间的边界是否可拉伸。
     * [previous] 为 null 表示行首（没有前一个可见字符），此时不补间距；
     * 调用方应把默认忽略字符从 [previous] 中剔除后再传入。
     */
    fun isExpansionBoundary(previous: Int?, next: Int?): Boolean {
        if (previous == null || next == null) return false
        if (isDefaultIgnorable(previous) || isDefaultIgnorable(next)) return false
        if (isTreatAsSpace(previous) || isCjkIdeographOrSymbol(previous)) return true
        return isCjkIdeographOrSymbol(next)
    }

    /** [previous] 之后是否可拉伸（用于行内图片等原子内联元素之前）。 */
    fun isExpansionAfter(previous: Int?): Boolean {
        if (previous == null || isDefaultIgnorable(previous)) return false
        return isTreatAsSpace(previous) || isCjkIdeographOrSymbol(previous)
    }
}
