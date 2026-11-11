package moe.gbxin.saltreader.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for [EpubCoverResolver]: the EPUB cover resolution order
 * (meta id/href/filename -> properties cover-image -> guide page image -> name heuristic ->
 * first image) and malformed OPF tolerance. No Android, no file system.
 */
class EpubCoverResolverTest {

    private val defaultAvailable = setOf(
        "OEBPS/content.opf",
        "OEBPS/Text/cover.xhtml",
        "OEBPS/Images/cover.jpg",
        "OEBPS/Images/guide.jpg",
        "OEBPS/Images/illustration.jpg",
        "OEBPS/Images/page1.png",
        "OEBPS/Styles/style.css",
    )

    private fun opf(
        manifest: String,
        metadata: String = "",
        guide: String = "",
    ): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata>
            $metadata
          </metadata>
          <manifest>
            $manifest
          </manifest>
          <spine>
            <itemref idref="ch1"/>
          </spine>
          $guide
        </package>
    """.trimIndent()

    private fun resolve(
        opfText: String,
        available: Set<String> = defaultAvailable,
        guideImage: () -> String? = { null },
    ): String? {
        val source = EpubCoverResolver.parse(opfText, "OEBPS", available)
        return EpubCoverResolver.resolve(source, guideImage)
    }

    // --- 1. meta cover resolution order -----------------------------------------------------

    @Test
    fun `meta cover content matches manifest id`() {
        val text = opf(
            manifest = """
                <item id="cover" href="Images/cover.jpg" media-type="image/jpeg"/>
                <item id="ch1" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
            """,
            metadata = """<meta name="cover" content="cover"/>""",
        )
        assertEquals("OEBPS/Images/cover.jpg", resolve(text))
    }

    @Test
    fun `meta cover content matches relative href`() {
        val text = opf(
            manifest = """
                <item id="cover-image" href="Images/cover.jpg" media-type="image/jpeg"/>
            """,
            metadata = """<meta name="cover" content="Images/cover.jpg"/>""",
        )
        assertEquals("OEBPS/Images/cover.jpg", resolve(text))
    }

    @Test
    fun `meta cover content matches bare filename`() {
        // 真实样例书：meta content="cover.jpg"，manifest id 也是 cover.jpg，href 是 Images/cover.jpg。
        val text = opf(
            manifest = """
                <item id="cover-image" href="Images/cover.jpg" media-type="image/jpeg"/>
            """,
            metadata = """<meta name="cover" content="cover.jpg"/>""",
        )
        assertEquals("OEBPS/Images/cover.jpg", resolve(text))
    }

    @Test
    fun `meta cover wins over properties and name heuristic`() {
        val text = opf(
            manifest = """
                <item id="meta-cover" href="Images/guide.jpg" media-type="image/jpeg"/>
                <item id="prop-cover" href="Images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
            """,
            metadata = """<meta name="cover" content="meta-cover"/>""",
        )
        assertEquals("OEBPS/Images/guide.jpg", resolve(text))
    }

    // --- 2. EPUB3 properties ----------------------------------------------------------------

    @Test
    fun `manifest properties cover-image is used when meta is missing`() {
        val text = opf(
            manifest = """
                <item id="page1" href="Images/illustration.jpg" media-type="image/jpeg"/>
                <item id="cover" href="Images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
            """,
        )
        assertEquals("OEBPS/Images/cover.jpg", resolve(text))
    }

    @Test
    fun `properties nav is not mistaken for a cover`() {
        val text = opf(
            manifest = """
                <item id="nav" href="Text/cover.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="page1" href="Images/illustration.jpg" media-type="image/jpeg"/>
            """,
        )
        // 没有 cover 相关线索时回退到第一个图片条目。
        assertEquals("OEBPS/Images/illustration.jpg", resolve(text))
    }

    // --- 3. guide page image ----------------------------------------------------------------

    @Test
    fun `guide cover page image wins over name heuristic`() {
        val text = opf(
            manifest = """
                <item id="guide-page" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
                <item id="hint" href="Images/cover.jpg" media-type="image/jpeg"/>
                <item id="page1" href="Images/illustration.jpg" media-type="image/jpeg"/>
            """,
            guide = """<guide><reference type="cover" title="封面" href="Text/cover.xhtml"/></guide>""",
        )
        assertEquals(
            "OEBPS/Images/guide.jpg",
            resolve(text) { "OEBPS/Images/guide.jpg" },
        )
    }

    @Test
    fun `guide image lookup is lazy and skipped when meta already resolved`() {
        var lookups = 0
        val text = opf(
            manifest = """
                <item id="cover" href="Images/cover.jpg" media-type="image/jpeg"/>
                <item id="guide-page" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
            """,
            metadata = """<meta name="cover" content="cover"/>""",
            guide = """<guide><reference type="cover" href="Text/cover.xhtml"/></guide>""",
        )
        val entry = resolve(text) {
            lookups++
            "OEBPS/Images/guide.jpg"
        }
        assertEquals("OEBPS/Images/cover.jpg", entry)
        assertEquals(0, lookups)
    }

    @Test
    fun `unresolvable guide page falls back to name heuristic`() {
        val text = opf(
            manifest = """
                <item id="hint" href="Images/cover.jpg" media-type="image/jpeg"/>
            """,
            guide = """<guide><reference type="cover" href="Text/missing.xhtml"/></guide>""",
        )
        assertEquals("OEBPS/Images/cover.jpg", resolve(text) { error("guide 不应被调用") })
    }

    @Test
    fun `guide pointing directly at an image is resolved as an image entry`() {
        val text = opf(
            manifest = """
                <item id="page1" href="Images/illustration.jpg" media-type="image/jpeg"/>
            """,
            guide = """<guide><reference type="cover" href="Images/cover.jpg"/></guide>""",
        )
        val source = EpubCoverResolver.parse(text, "OEBPS", defaultAvailable)
        assertEquals("OEBPS/Images/cover.jpg", source.guideCoverEntry)
        assertTrue(EpubCoverResolver.isImageEntry(source.guideCoverEntry!!))
        assertEquals("OEBPS/Images/cover.jpg", EpubCoverResolver.resolve(source) { null })
    }

    // --- 4./5. name heuristic and first image fallback --------------------------------------

    @Test
    fun `image filename containing cover beats the first image`() {
        val text = opf(
            manifest = """
                <item id="first" href="Images/illustration.jpg" media-type="image/jpeg"/>
                <item id="cover" href="Images/cover.jpg" media-type="image/jpeg"/>
            """,
        )
        assertEquals("OEBPS/Images/cover.jpg", resolve(text))
    }

    @Test
    fun `first image manifest item is the last fallback`() {
        val text = opf(
            manifest = """
                <item id="style" href="Styles/style.css" media-type="text/css"/>
                <item id="first" href="Images/illustration.jpg" media-type="image/jpeg"/>
                <item id="second" href="Images/page1.png" media-type="image/png"/>
            """,
        )
        assertEquals("OEBPS/Images/illustration.jpg", resolve(text))
    }

    @Test
    fun `no image entries yields null`() {
        val text = opf(
            manifest = """
                <item id="style" href="Styles/style.css" media-type="text/css"/>
                <item id="ch1" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
            """,
        )
        assertNull(resolve(text))
    }

    // --- malformed input --------------------------------------------------------------------

    @Test
    fun `malformed or empty opf never throws and yields null`() {
        for (text in listOf("", "not xml at all", "<package><manifest></manifest></package>")) {
            val source = EpubCoverResolver.parse(text, "OEBPS", defaultAvailable)
            assertNull(EpubCoverResolver.resolve(source) { null })
        }
    }

    @Test
    fun `manifest entries that do not exist in the zip are ignored`() {
        val text = opf(
            manifest = """
                <item id="cover" href="Images/cover.jpg" media-type="image/jpeg"/>
                <item id="missing" href="Images/missing.jpg" media-type="image/jpeg"/>
            """,
        )
        // cover.jpg 不在 available 里时不能返回，只能继续找下一个真实存在的图片。
        assertNull(resolve(text, available = setOf("OEBPS/content.opf")))
        assertEquals(
            "OEBPS/Images/missing.jpg",
            resolve(text, available = setOf("OEBPS/content.opf", "OEBPS/Images/missing.jpg")),
        )
    }

    // --- guide page XHTML parsing -----------------------------------------------------------

    @Test
    fun `guide page with a single local image yields that image`() {
        val xhtml = """
            <html><body><div class="cover"><img src="../Images/cover.jpg" alt="cover"/></div></body></html>
        """.trimIndent()
        assertEquals(
            "OEBPS/Images/cover.jpg",
            EpubCoverResolver.singleImageEntry(xhtml, "OEBPS/Text", defaultAvailable),
        )
    }

    @Test
    fun `guide page with multiple or remote images yields null`() {
        val multiple = """<html><body><img src="../Images/cover.jpg"/><img src="../Images/page1.png"/></body></html>"""
        assertNull(EpubCoverResolver.singleImageEntry(multiple, "OEBPS/Text", defaultAvailable))
        val remote = """<html><body><img src="https://example.com/cover.jpg"/></body></html>"""
        assertNull(EpubCoverResolver.singleImageEntry(remote, "OEBPS/Text", defaultAvailable))
    }
}
