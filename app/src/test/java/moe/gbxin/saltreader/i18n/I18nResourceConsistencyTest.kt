package moe.gbxin.saltreader.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Repository-level i18n guard that runs with the normal JVM unit tests.
 *
 * For every per-module `strings_*.xml` file in `res/values/` (English default) the same
 * file must exist in `values-zh-rCN`, `values-zh-rTW` and `values-ja` with exactly the
 * same set of resource keys. Also verifies that `res/xml/locales_config.xml` lists the
 * four supported locales.
 *
 * This is the persistent equivalent of the ad-hoc "check i18n keys" script: CI runs it
 * via `:app:testDebugUnitTest`, so a missing translation fails the build instead of
 * silently falling back to English.
 */
class I18nResourceConsistencyTest {

    private val localeDirs = listOf("values", "values-zh-rCN", "values-zh-rTW", "values-ja")

    private fun resDir(): File {
        // Gradle unit tests run with the module directory (app/) as working dir; the
        // root-project variant keeps IDE/manual runs working as well.
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate res/ from working dir ${File(".").absolutePath}")
    }

    private fun moduleStringFiles(): List<String> =
        File(resDir(), "values")
            .listFiles { file -> file.isFile && file.name.startsWith("strings_") && file.name.endsWith(".xml") }
            .orEmpty()
            .map { it.name }
            .sorted()

    private fun resourceKeys(file: File): Set<String> {
        val element = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(file).documentElement
        val keys = HashSet<String>()
        val nodes = element.childNodes
        for (i in 0 until nodes.length) {
            val child = nodes.item(i) as? Element ?: continue
            if (child.tagName in setOf("string", "plurals", "string-array")) {
                val name = child.getAttribute("name")
                if (name.isNotEmpty()) keys += name
            }
        }
        return keys
    }

    @Test
    fun `every module strings file exists in all four locales with identical keys`() {
        val res = resDir()
        val modules = moduleStringFiles()
        assertTrue(modules.isNotEmpty(), "no per-module strings_*.xml found under ${res.path}")

        for (name in modules) {
            val english = File(res, "values/$name")
            val englishKeys = resourceKeys(english)
            for (locale in localeDirs.drop(1)) {
                val localized = File(res, "$locale/$name")
                assertTrue(
                    localized.isFile,
                    "missing ${localized.path} (compare with values/$name)",
                )
                assertEquals(
                    englishKeys,
                    resourceKeys(localized),
                    "key mismatch in ${localized.path}",
                )
            }
        }
    }

    @Test
    fun `locales_config lists exactly the supported locales`() {
        val config = File(resDir(), "xml/locales_config.xml")
        assertTrue(config.isFile, "missing ${config.path}")

        val root = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(config).documentElement
        val locales = HashSet<String>()
        val nodes = root.getElementsByTagName("locale")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val name = element.getAttribute("android:name").ifEmpty {
                element.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
            }
            if (name.isNotEmpty()) locales += name
        }
        assertEquals(setOf("en", "zh-CN", "zh-TW", "ja"), locales)
    }
}
