package moe.gbxin.saltreader.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-JVM tests for the natural page ordering used by both archive entries and
 * image folders. [PageNameComparator] does not touch Android APIs, so no
 * Robolectric/framework stubs are needed.
 */
class PageNameComparatorTest {

    private fun sorted(vararg names: String): List<String> =
        names.toList().sortedWith(PageNameComparator)

    @Test
    fun `numeric parts sort naturally not lexicographically`() {
        assertEquals(
            listOf("1.jpg", "2.jpg", "9.jpg", "10.jpg"),
            sorted("10.jpg", "2.jpg", "1.jpg", "9.jpg"),
        )
    }

    @Test
    fun `common prefix with numeric suffix sorts naturally`() {
        assertEquals(
            listOf("page_1.jpg", "page_2.jpg", "page_10.jpg"),
            sorted("page_10.jpg", "page_2.jpg", "page_1.jpg"),
        )
    }

    @Test
    fun `entries are grouped by directory with the root first`() {
        assertEquals(
            listOf("z.jpg", "a/1.jpg", "a/2.jpg", "b/1.jpg"),
            sorted("b/1.jpg", "z.jpg", "a/2.jpg", "a/1.jpg"),
        )
    }

    @Test
    fun `numbers inside the same directory sort naturally`() {
        assertEquals(
            listOf("ch/2.jpg", "ch/10.jpg"),
            sorted("ch/10.jpg", "ch/2.jpg"),
        )
    }

    @Test
    fun `comparison is case-insensitive`() {
        assertEquals(
            listOf("A.jpg", "b.jpg", "c.jpg"),
            sorted("c.jpg", "b.jpg", "A.jpg"),
        )
    }

    @Test
    fun `equal numeric parts fall back to remainder and then raw name`() {
        // "1.jpg" vs "1.png": equal leading digits, compare the remainder.
        assertEquals(
            listOf("1.jpg", "1.png"),
            sorted("1.png", "1.jpg"),
        )
    }
}
