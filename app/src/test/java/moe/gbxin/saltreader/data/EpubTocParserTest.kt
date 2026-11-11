package moe.gbxin.saltreader.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-JVM tests for [EpubTocParser]: toc.ncx navMap / EPUB3 nav.xhtml extraction.
 * Path resolution (relative to the toc file) is covered indirectly by the data layer and is not
 * part of this parser.
 */
class EpubTocParserTest {

    @Test
    fun `ncx navMap yields ordered href and label pairs`() {
        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="navPoint-1" playOrder="1">
                  <navLabel><text>封面</text></navLabel>
                  <content src="Text/cover.xhtml"/>
                </navPoint>
                <navPoint id="navPoint-7" playOrder="7">
                  <navLabel><text>第一章　单色的晴天</text></navLabel>
                  <content src="Text/T1.xhtml"/>
                </navPoint>
                <navPoint id="navPoint-9" playOrder="9">
                  <navLabel><text>第二章 &amp; 雨</text></navLabel>
                  <content src="Text/T2.xhtml#start"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent()

        val targets = EpubTocParser.ncxTargets(ncx)
        assertEquals(3, targets.size)
        assertEquals("Text/cover.xhtml", targets[0].href)
        assertEquals("封面", targets[0].title)
        assertEquals("Text/T1.xhtml", targets[1].href)
        assertEquals("第一章　单色的晴天", targets[1].title)
        assertEquals("Text/T2.xhtml#start", targets[2].href)
        assertEquals("第二章 & 雨", targets[2].title)
    }

    @Test
    fun `nested ncx navPoints are flattened in document order`() {
        val ncx = """
            <navMap>
              <navPoint id="part">
                <navLabel><text>第一部</text></navLabel>
                <content src="part.xhtml"/>
                <navPoint id="chapter">
                  <navLabel><text>第一章</text></navLabel>
                  <content src="ch1.xhtml"/>
                </navPoint>
              </navPoint>
            </navMap>
        """.trimIndent()

        val targets = EpubTocParser.ncxTargets(ncx)
        assertEquals(listOf("part.xhtml", "ch1.xhtml"), targets.map { it.href })
        assertEquals(listOf("第一部", "第一章"), targets.map { it.title })
    }

    @Test
    fun `epub3 nav toc yields anchors with stripped labels`() {
        val nav = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc" id="toc">
                  <ol>
                    <li><a href="Text/cover.xhtml">封面</a></li>
                    <li><a href="Text/ch1.xhtml">Chapter <span>One</span></a></li>
                  </ol>
                </nav>
              </body>
            </html>
        """.trimIndent()

        val targets = EpubTocParser.navTargets(nav)
        assertEquals(listOf("Text/cover.xhtml", "Text/ch1.xhtml"), targets.map { it.href })
        assertEquals(listOf("封面", "Chapter One"), targets.map { it.title })
    }

    @Test
    fun `nav without an explicit toc nav falls back to all anchors`() {
        val html = "<html><body><a href=\"a.xhtml\">A</a><a href='b.xhtml'>B</a></body></html>"
        val targets = EpubTocParser.navTargets(html)
        assertEquals(listOf("a.xhtml", "b.xhtml"), targets.map { it.href })
        assertEquals(listOf("A", "B"), targets.map { it.title })
    }

    @Test
    fun `malformed toc yields no targets`() {
        assertEquals(0, EpubTocParser.ncxTargets("<ncx><navMap></navMap></ncx>").size)
        assertEquals(0, EpubTocParser.ncxTargets("").size)
        assertEquals(0, EpubTocParser.navTargets("<html><body><p>no links</p></body></html>").size)
    }
}
