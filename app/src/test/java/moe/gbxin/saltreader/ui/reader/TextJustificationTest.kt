package moe.gbxin.saltreader.ui.reader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 可拉伸边界判定对照 Chromium Blink 的 `JustificationContext::CheckOpportunity`
 * （`text-justify: auto`）。CJK 字符用 U+4E2D/U+6587。
 */
class TextJustificationTest {

    private fun boundary(previous: Char?, next: Char?) =
        TextJustification.isExpansionBoundary(previous?.code, next?.code)

    @Test
    fun `space after latin text is an expansion opportunity`() {
        // Blink 在空格之后（而非之前）产生拉伸点。
        assertFalse(boundary('a', ' '))
        assertTrue(boundary(' ', 'b'))
        assertFalse(boundary('a', 'b'))
    }

    @Test
    fun `cjk expands on both sides`() {
        assertTrue(boundary('中', '文'))
        assertTrue(boundary('a', '中'))
        assertTrue(boundary('中', 'a'))
    }

    @Test
    fun `fullwidth punctuation counts as cjk`() {
        assertTrue(boundary('中', '，'))
        assertTrue(boundary('，', '文'))
        // 半角标点不是 CJK，也不产生边界。
        assertFalse(boundary('a', ','))
    }

    @Test
    fun `treat-as-space follows blink`() {
        assertTrue(boundary('\t', 'a'))
        assertTrue(boundary('\u00A0', 'a'))
        assertTrue(boundary('\n', 'a'))
        // U+3000 不在 TreatAsSpace 内，但按 CJK 符号处理，同样是可拉伸点。
        assertTrue(boundary('a', '\u3000'))
    }

    @Test
    fun `default ignorables neither expand nor break adjacency`() {
        val zeroWidthSpace = '\u200B'
        assertFalse(boundary('中', zeroWidthSpace))
        assertFalse(boundary(zeroWidthSpace, '文'))
        // 场景：中 + ZWSP + 文。绘制端跳过 ZWSP 后仍应看到 中/文 边界。
        assertTrue(boundary('中', '文'))
    }

    @Test
    fun `latin digits and emoji are not opportunities`() {
        assertFalse(boundary('1', '2'))
        assertFalse(TextJustification.isExpansionBoundary(0x1F600, 'a'.code))
        assertFalse(TextJustification.isExpansionBoundary('a'.code, 0x1F600))
    }

    @Test
    fun `expansion after only looks at the previous character`() {
        assertTrue(TextJustification.isExpansionAfter('中'.code))
        assertTrue(TextJustification.isExpansionAfter(' '.code))
        assertFalse(TextJustification.isExpansionAfter('a'.code))
        assertFalse(TextJustification.isExpansionAfter(null))
    }

    @Test
    fun `line does not start with an expansion gap`() {
        // 行首没有 previous，CJK 首字符之前不应补间距。
        assertFalse(TextJustification.isExpansionBoundary(null, '中'.code))
        assertFalse(TextJustification.isExpansionBoundary(null, 'a'.code))
    }
}
