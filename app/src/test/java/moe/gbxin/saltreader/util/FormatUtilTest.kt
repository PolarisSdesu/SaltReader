package moe.gbxin.saltreader.util

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure-JVM tests for the storage formatting helpers (relative time is platform-localized). */
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
    fun `formatRelativeTime returns empty for a never-opened book`() {
        // Non-null times are rendered by Android's DateUtils in the system locale, which is
        // an Android framework API and therefore not exercised by this plain-JVM test.
        assertEquals("", formatRelativeTime(null))
    }
}
