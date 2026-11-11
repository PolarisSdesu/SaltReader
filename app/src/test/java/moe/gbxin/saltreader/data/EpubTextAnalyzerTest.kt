package moe.gbxin.saltreader.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for [EpubTextAnalyzer]: the visible-text metrics and title heuristics used to
 * build [EbookChapter.charCount] / [EbookChapter.imageOnly] / [EbookChapter.showHeading].
 */
class EpubTextAnalyzerTest {

    // --- visibleTextLength -------------------------------------------------------------------

    @Test
    fun `visible text length counts CJK characters`() {
        assertEquals(6, EpubTextAnalyzer.visibleTextLength("<p>你好，世界！</p>"))
        assertEquals(2, EpubTextAnalyzer.visibleTextLength("<div><span>你</span><b>好</b></div>"))
    }

    @Test
    fun `visible text length excludes tags whitespace and entities`() {
        assertEquals(0, EpubTextAnalyzer.visibleTextLength("   \n\t  "))
        assertEquals(3, EpubTextAnalyzer.visibleTextLength("<p>A &amp; B</p>"))
        // &nbsp; 解码成空白后不计入，&#33; 解码成 '!'
        assertEquals(3, EpubTextAnalyzer.visibleTextLength("<p>&nbsp;&nbsp;hi&#33;</p>"))
        assertEquals(1, EpubTextAnalyzer.visibleTextLength("<p>&#x4F60;</p>"))
        // 全角空格同样是空白
        assertEquals(2, EpubTextAnalyzer.visibleTextLength("<p>你　好</p>"))
    }

    @Test
    fun `script and style contents are never visible text`() {
        val html = "<style>p { color: red }</style><script>var x = 'ignored';</script><p>正文</p>"
        assertEquals(2, EpubTextAnalyzer.visibleTextLength(html))
    }

    // --- isImageOnly -------------------------------------------------------------------------

    @Test
    fun `image-only page has an image and no visible text`() {
        assertTrue(
            EpubTextAnalyzer.isImageOnly(
                "<div class=\"illus\"><img src=\"data:image/jpeg;base64,AAAA\" alt=\"\"/></div>",
            ),
        )
        assertTrue(EpubTextAnalyzer.isImageOnly("<p><img alt=\"ch1\" src=\"../Images/ch1.jpg\"/></p>"))
    }

    @Test
    fun `page with text or without image is not image-only`() {
        assertFalse(EpubTextAnalyzer.isImageOnly("<div><img src=\"a.jpg\"/><p>图注</p></div>"))
        assertFalse(EpubTextAnalyzer.isImageOnly("<p>纯文字章节</p>"))
        assertFalse(EpubTextAnalyzer.isImageOnly("<div class=\"empty\"></div>"))
    }

    // --- startsWithTitleElement --------------------------------------------------------------

    @Test
    fun `heading or title-like class means no heading injection`() {
        assertTrue(EpubTextAnalyzer.startsWithTitleElement("<h1>第一章</h1><p>正文</p>"))
        assertTrue(EpubTextAnalyzer.startsWithTitleElement("<h3 class=\"x\">小节</h3><p>正文</p>"))
        assertTrue(EpubTextAnalyzer.startsWithTitleElement("<section><h2>标题</h2></section>"))
        // 无 class 的 div 容器要继续向内找
        assertTrue(
            EpubTextAnalyzer.startsWithTitleElement(
                "<div><p class=\"pius1\">第一章</p><p class=\"pius2\">单色的晴天</p><p>正文</p></div>",
            ),
        )
        assertTrue(EpubTextAnalyzer.startsWithTitleElement("<p class=\"biaoti2\">第二章</p>"))
        assertTrue(EpubTextAnalyzer.startsWithTitleElement("<div class=\"chapter-title\">正文</div>"))
    }

    @Test
    fun `plain body text does not start with a title element`() {
        assertFalse(EpubTextAnalyzer.startsWithTitleElement("<p>我讨厌夏天。</p><p class=\"pius1\">第一章</p>"))
        assertFalse(EpubTextAnalyzer.startsWithTitleElement("<div class=\"illus\"><img src=\"a.jpg\"/></div>"))
        assertFalse(EpubTextAnalyzer.startsWithTitleElement("<div><p>正文</p></div>"))
        assertFalse(EpubTextAnalyzer.startsWithTitleElement(""))
    }

    // --- startsWithTitleText -----------------------------------------------------------------

    @Test
    fun `body that already starts with the chapter title suppresses the injected heading`() {
        val summary = "<div><p class=\"zin center em18 illus1\">简介</p><p>穿梭光阴，只为与你相遇</p></div>"
        assertTrue(EpubTextAnalyzer.startsWithTitleText(summary, "简介"))

        val message = "<div class=\"illus5\"><p class=\"meg\">制作信息</p><p class=\"message\">作者：八目 迷</p></div>"
        assertTrue(EpubTextAnalyzer.startsWithTitleText(message, "制作信息"))
    }

    @Test
    fun `body text different from the title keeps the injected heading`() {
        assertFalse(EpubTextAnalyzer.startsWithTitleText("<p>我讨厌夏天。</p>", "第一章　单色的晴天"))
        assertFalse(EpubTextAnalyzer.startsWithTitleText("<p>正文</p>", null))
        // 首段只有一个字时不参与比对，避免 "我" 误命中 "我们的故事"
        assertFalse(EpubTextAnalyzer.startsWithTitleText("<p><span>我</span>们的故事</p>", "我们的故事"))
    }

    @Test
    fun `title comparison ignores whitespace including ideographic spaces`() {
        assertTrue(EpubTextAnalyzer.startsWithTitleText("<p>第一章</p><p>单色的晴天</p>", "第一章　单色的晴天"))
    }
}
