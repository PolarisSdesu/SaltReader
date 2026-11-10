package moe.gbxin.saltreader.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure-JVM tests for the roots.json wire format ([LibraryRoot]). */
class LibraryRootSerializationTest {

    // Mirror LibraryRootsStore's Json configuration.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `LibraryRoot round-trips through JSON`() {
        val root = LibraryRoot(
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3AComics",
            displayName = "Comics",
        )

        val restored = json.decodeFromString(LibraryRoot.serializer(), json.encodeToString(LibraryRoot.serializer(), root))

        assertEquals(root, restored)
        assertEquals(root.treeUri, restored.treeUri)
        assertEquals("Comics", restored.displayName)
    }

    @Test
    fun `root list round-trips preserving order`() {
        val roots = listOf(
            LibraryRoot("content://tree/1", "Comics"),
            LibraryRoot("content://tree/2", "Manga"),
        )
        val serializer = ListSerializer(LibraryRoot.serializer())

        val restored = json.decodeFromString(serializer, json.encodeToString(serializer, roots))

        assertEquals(roots, restored)
    }

    @Test
    fun `unknown keys in roots JSON are ignored`() {
        val raw = """
            {"treeUri":"content://tree/1","displayName":"Comics","legacyPath":"/sdcard/old"}
        """.trimIndent()

        val root = json.decodeFromString(LibraryRoot.serializer(), raw)

        assertEquals("content://tree/1", root.treeUri)
        assertEquals("Comics", root.displayName)
    }
}
