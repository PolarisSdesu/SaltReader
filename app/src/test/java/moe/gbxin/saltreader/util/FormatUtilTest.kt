package moe.gbxin.saltreader.util

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure-JVM tests for the storage/relative-time formatting helpers. */
class FormatUtilTest {

    @Test
    fun `formatBytes renders plain bytes below one kilobyte`() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("512 B", formatBytes(512L))
        assertEquals("1023 B", formatBytes(1023L))
    }

    @Test
    fun `formatBytes uses KB MB and GB thresholds`() {
        assertEquals("1 KB", formatBytes(1024L))
        assertEquals("2 KB", formatBytes(1536L))
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("5.0 MB", formatBytes(5L * 1024L * 1024L))
        assertEquals("1.0 GB", formatBytes(1024L * 1024L * 1024L))
        assertEquals("2.0 GB", formatBytes(2L * 1024L * 1024L * 1024L))
    }

    @Test
    fun `formatRelativeTime handles the never-opened case`() {
        assertEquals("还没翻开", formatRelativeTime(null))
    }

    @Test
    fun `formatRelativeTime renders recent activity`() {
        val now = System.currentTimeMillis()
        assertEquals("刚刚", formatRelativeTime(now - 30_000L))
        assertEquals("5 分钟前", formatRelativeTime(now - 5L * 60_000L))
        assertEquals("3 小时前", formatRelativeTime(now - 3L * 3_600_000L))
        assertEquals("2 天前", formatRelativeTime(now - 2L * 86_400_000L))
    }
}
