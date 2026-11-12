package moe.gbxin.saltreader.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 纯 JVM 测试：字体引用编解码、文件类型识别、CSS 栈/字体族拼接。
 * 这些逻辑不触碰 Android API，因此不需要 Robolectric。
 */
class ReaderFontsTest {

    @Test
    fun `font references round-trip through prefs encoding`() {
        val ref = ReaderFontRef(ReaderFontSource.DOCUMENT, "content://tree/doc%2Ffonts%2Fa.ttf", "a.ttf")
        val decoded = ReaderFontCodec.decode(ReaderFontCodec.encode(ref))
        assertEquals(ref, decoded)
    }

    @Test
    fun `malformed or empty font references decode to null`() {
        assertNull(ReaderFontCodec.decode(null))
        assertNull(ReaderFontCodec.decode(""))
        assertNull(ReaderFontCodec.decode("not json"))
        // key 为空的引用没有意义，即使 JSON 合法也当成未选择。
        assertNull(ReaderFontCodec.decode(ReaderFontCodec.encode(ReaderFontRef(ReaderFontSource.SYSTEM, "", "x"))))
    }

    @Test
    fun `font file extensions are detected case-insensitively`() {
        assertTrue(ReaderFonts.isFontFile("SourceHanSerifSC-Regular.otf"))
        assertTrue(ReaderFonts.isFontFile("NotoSerifSC.OTF"))
        assertTrue(ReaderFonts.isFontFile("custom.ttf"))
        assertTrue(ReaderFonts.isFontFile("web.woff2"))
        assertTrue(!ReaderFonts.isFontFile("collection.ttc"))
        assertTrue(!ReaderFonts.isFontFile("cover.jpg"))
        assertTrue(!ReaderFonts.isFontFile("README"))
        assertTrue(!ReaderFonts.isFontFile("font.ttf.txt"))
    }

    @Test
    fun `mime type follows the file extension`() {
        assertEquals("font/ttf", ReaderFonts.mimeFor("a.ttf"))
        assertEquals("font/otf", ReaderFonts.mimeFor("a.otf"))
        assertEquals("font/woff", ReaderFonts.mimeFor("a.woff"))
        assertEquals("font/woff2", ReaderFonts.mimeFor("a.woff2"))
        assertEquals("font/ttf", ReaderFonts.mimeFor("unexpected.bin"))
    }

    @Test
    fun `css family stacks put the chosen font first`() {
        assertEquals(ReaderFonts.SONG_FALLBACK_STACK, ReaderFonts.stack(null, ReaderFonts.SONG_FALLBACK_STACK))
        assertEquals(
            "\"Noto Serif CJK SC\", " + ReaderFonts.SONG_FALLBACK_STACK,
            ReaderFonts.stack(ReaderFonts.quoteCssFamily("Noto Serif CJK SC"), ReaderFonts.SONG_FALLBACK_STACK),
        )
    }

    @Test
    fun `css family names are quoted and escaped`() {
        assertEquals("\"Noto Sans CJK SC\"", ReaderFonts.quoteCssFamily("Noto Sans CJK SC"))
        assertEquals("\"a\\\"b\"", ReaderFonts.quoteCssFamily("a\"b"))
    }

    @Test
    fun `font face rule references the served url and generated family`() {
        val token = ReaderFonts.tokenFor(ReaderFontRef(ReaderFontSource.DOCUMENT, "content://a/b", "b.ttf"))
        val rule = ReaderFonts.fontFace(ReaderFonts.documentFamilyName(token), ReaderFonts.FONT_URL_BASE + token)
        assertTrue(rule.startsWith("@font-face{font-family:'SaltFont_"))
        assertTrue(rule.contains("src:url('https://salt.local/font/$token')"))
    }

    @Test
    fun `font tokens are stable and unique per source`() {
        val a = ReaderFontRef(ReaderFontSource.DOCUMENT, "content://a", "a.ttf")
        val same = ReaderFontRef(ReaderFontSource.DOCUMENT, "content://a", "renamed.ttf")
        val other = ReaderFontRef(ReaderFontSource.DOCUMENT, "content://b", "a.ttf")
        val system = ReaderFontRef(ReaderFontSource.SYSTEM, "Noto Serif CJK SC", "Noto Serif CJK SC")
        assertEquals(ReaderFonts.tokenFor(a), ReaderFonts.tokenFor(same))
        assertNotEquals(ReaderFonts.tokenFor(a), ReaderFonts.tokenFor(other))
        assertNotEquals(ReaderFonts.tokenFor(a), ReaderFonts.tokenFor(system))
        assertEquals(20, ReaderFonts.tokenFor(a).length)
    }

    @Test
    fun `font config parser extracts family and alias names`() {
        val xml = """
            <familyset version="23">
                <family name="sans-serif">
                    <font weight="400" style="normal">Roboto-Regular.ttf</font>
                </family>
                <family name="Noto Serif CJK SC" lang="zh-Hans">
                    <font weight="400" style="normal">NotoSerifCJK-Regular.ttc</font>
                </family>
                <alias name="serif" to="Noto Serif"/>
                <alias name="monospace"/>
                <family>
                    <font weight="400" style="normal">DroidSansFallback.ttf</font>
                </family>
            </familyset>
        """.trimIndent()
        assertEquals(
            listOf("sans-serif", "Noto Serif CJK SC", "serif", "monospace"),
            ReaderFonts.parseFontConfigFamilies(xml),
        )
    }

    @Test
    fun `reader font setup falls back to built-in stacks without selections`() {
        assertEquals(ReaderFonts.HEI_FALLBACK_STACK, ReaderFontSetup.DEFAULT.familyHei)
        assertEquals(ReaderFonts.SONG_FALLBACK_STACK, ReaderFontSetup.DEFAULT.familySong)
        assertEquals(ReaderFonts.HEI_FALLBACK_STACK, ReaderFontSetup.DEFAULT.familyFor(ReaderFontFamily.CUSTOM))
        assertEquals(ReaderFonts.MONO_FALLBACK_STACK, ReaderFontSetup.DEFAULT.familyMono)
        assertEquals("", ReaderFontSetup.DEFAULT.fontFacesCss)
        assertTrue(ReaderFontSetup.DEFAULT.resources.isEmpty())
    }
}
