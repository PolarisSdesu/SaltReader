package moe.gbxin.saltreader.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 固化 Rust 输出协议（11 位样式编码 / 表格 / 图片行）的 JVM 契约测试。 */
class NativeLineProtocolTest {
    private val style = "3818" + "3" + "00" + "00" + "00" // size 3.8, line 1.8, bold+italic

    @Test
    fun decodes_styled_text_line() {
        val line = decodeNativeLine("\u0001" + style + "正文")
        assertEquals("正文", line.text)
        assertEquals(3.8f, line.sizeRatio)
        assertEquals(1.8f, line.lineHeightRatio)
        assertTrue(line.bold)
        assertTrue(line.italic)
        assertFalse(line.mono)
        assertEquals(0, line.align)
        assertEquals(0f, line.gapEm)
    }

    @Test
    fun decodes_table_line() {
        val raw = "\u0002" + "01" + "02" + "4" + "\u0001" + style + "a"
        val line = decodeNativeLine(raw)
        assertEquals("a", line.text)
        assertEquals(1, line.tableCol)
        assertEquals(2, line.tableCols)
        assertFalse(line.tableRowStart)
        assertFalse(line.tableLastRow)
        assertTrue(line.tableRowEnd)
    }

    @Test
    fun decodes_block_image_line() {
        val line = decodeNativeLine("\u0003" + "00004" + "08")
        assertEquals(4, line.imageId)
        assertEquals(0.8f, line.gapEm)
        assertEquals("", line.text)
    }
}
