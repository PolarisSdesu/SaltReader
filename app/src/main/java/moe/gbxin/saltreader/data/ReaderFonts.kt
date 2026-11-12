package moe.gbxin.saltreader.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 阅读器字体：来源分为系统自带字体（按 CSS 字体族名使用）和用户字体目录里的文件
 * （复制进字体缓存目录后经 WebView 的 shouldInterceptRequest 供给 @font-face）。
 *
 * 这里只包含可跑的纯逻辑与 IO，组合/绘制在 ui 层。所有目录扫描与文件复制都是阻塞 IO，
 * 调用方必须在 IO 线程执行（规则 2：主线程不做 I/O）。
 */
enum class ReaderFontSource {
    /** 系统自带字体：key 是字体族名（例如 "Noto Serif CJK SC"），直接写进 CSS font-family。 */
    SYSTEM,

    /** 用户字体目录中的一个文件：key 是持久化 SAF 目录树内的 Document URI。 */
    DOCUMENT,
}

/** 一个可持久化的字体引用。SharedPreferences 里存 JSON（见 [ReaderFontCodec]）。 */
@Serializable
data class ReaderFontRef(
    val source: ReaderFontSource = ReaderFontSource.SYSTEM,
    /** SYSTEM 时为字体族名；DOCUMENT 时为 Document URI 字符串。 */
    val key: String = "",
    /** 只用于展示；目录可能失效，所以标签跟着引用一起持久化。 */
    val label: String = "",
) {
    val id: String get() = source.name + ":" + key
}

/** 自定义字体设置页里可以单独指定的字体角色。 */
enum class ReaderFontRole { CUSTOM, HEI, SONG, MONO, FALLBACK1, FALLBACK2, FALLBACK3 }

/** 字体引用的 JSON 编解码；旧版本/损坏的值一律当成"未选择"。 */
object ReaderFontCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(ref: ReaderFontRef?): String? =
        ref?.takeIf { it.key.isNotBlank() }?.let { value ->
            runCatching { json.encodeToString(ReaderFontRef.serializer(), value) }.getOrNull()
        }

    fun decode(raw: String?): ReaderFontRef? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(ReaderFontRef.serializer(), raw) }
            .getOrNull()
            ?.takeIf { it.key.isNotBlank() }
    }
}

/** 阅读器字体栈与 CSS 片段构建（纯 Kotlin，可单测）。 */
object ReaderFonts {
    private const val TAG = "SaltReaderFonts"

    /**
     * 派生字体缓存目录。字体文件会被复制到这里，文件名是字体 id 的 SHA-1，
     * 因此换字体/清缓存都只需要删目录，不需要跟随书名/进度等业务字段。
     */
    const val FONT_CACHE_DIR = "fonts"

    /** 系统字体族兜底栈：不同 ROM 的族名不同，逐个列出来再落到 CSS 通用族。 */
    const val HEI_FALLBACK_STACK =
        "\"Noto Sans CJK SC\", \"Source Han Sans SC\", \"Noto Sans SC\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    const val SONG_FALLBACK_STACK =
        "\"Noto Serif CJK SC\", \"Source Han Serif SC\", \"Noto Serif SC\", \"Songti SC\", \"STSong\", SimSun, serif"
    const val MONO_FALLBACK_STACK =
        "ui-monospace, SFMono-Regular, Menlo, Consolas, \"Liberation Mono\", monospace"

    /**
     * 支持的字体扩展名；目录扫描与 mime 判断共用。
     * 不含 ttc：WebView 的字体消毒器（OTS）拒绝字体集合，@font-face 也不会生效。
     */
    private val FONT_EXTENSIONS = setOf("ttf", "otf", "woff", "woff2")

    fun isFontFile(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in FONT_EXTENSIONS
    }

    /** @font-face 响应使用的 MIME；WebView 依赖它把字节当字体解析。 */
    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "font/ttf"
    }

    fun fontCacheDir(context: Context): File = File(context.cacheDir, FONT_CACHE_DIR)

    /** 清空派生字体缓存；字体引用本身仍保留，下次打开书籍时会重新复制。 */
    fun clearCache(context: Context): Long {
        val dir = fontCacheDir(context)
        if (!dir.exists()) return 0L
        var freed = 0L
        dir.listFiles()?.forEach { file ->
            val size = file.length()
            if (file.delete()) freed += size
        }
        return freed
    }

    /** CSS 字体族名的引号形式；名字里的引号/反斜杠会被转义，避免拼出非法 CSS。 */
    fun quoteCssFamily(name: String): String {
        val escaped = name.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    /** 把"自定义字体族 + 兜底栈"拼成完整的 CSS font-family 值。 */
    fun stack(customFamily: String?, fallback: String): String =
        if (customFamily.isNullOrBlank()) fallback else "$customFamily, $fallback"

    /** 生成 @font-face 规则；familyName 由调用方保证唯一（这里用 id 的哈希）。 */
    fun fontFace(familyName: String, url: String): String =
        "@font-face{font-family:'$familyName';src:url('$url');font-display:block;}"

    /** 文档字体的 CSS 族名：不能包含用户文件名，避免同一本书换字体时命名冲突。 */
    fun documentFamilyName(token: String): String = "SaltFont_$token"

    /** 字体 id 的稳定短哈希，同时用作缓存文件名与 CSS 族名。 */
    fun tokenFor(ref: ReaderFontRef): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(ref.id.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.substring(0, 20)
    }

    fun cacheFile(context: Context, ref: ReaderFontRef): File {
        val extension = ref.label.substringAfterLast('.', "ttf").lowercase(Locale.ROOT)
            .let { if (it in FONT_EXTENSIONS) it else "ttf" }
        return File(fontCacheDir(context), tokenFor(ref) + "." + extension)
    }

    /** 一份可直接交给 WebView 的字体资源。 */
    class FontResource(val token: String, val file: File, val mime: String)

    /**
     * 解析后的单个字体：familyName 是可直接放进 CSS 字体链的一个字体族片段
     * （如 `"casual"` 或 `'SaltFont_x'`），null 表示没有可用字体；
     * resource 非空时需要由 WebView 拦截器供给字节。
     */
    class ResolvedFont(val familyName: String?, val resource: FontResource?, val faceCss: String?)

    /**
     * 把 [ref] 解析成单个字体族（不含兜底栈；字体链由 [ReaderFontSetup] 拼接）。
     *
     * - 系统字体：直接使用字体族名（WebView 通过平台字体配置解析，无需复制大文件）；
     * - 字体目录文件：复制到缓存并生成 @font-face，族名用哈希避免碰撞。
     */
    fun resolve(context: Context, ref: ReaderFontRef?): ResolvedFont {
        if (ref == null || ref.key.isBlank()) return ResolvedFont(null, null, null)
        return when (ref.source) {
            ReaderFontSource.SYSTEM -> ResolvedFont(quoteCssFamily(ref.key), null, null)

            ReaderFontSource.DOCUMENT -> {
                val cached = ensureCached(context, ref)
                if (cached == null) {
                    ResolvedFont(null, null, null)
                } else {
                    val token = tokenFor(ref)
                    val familyName = documentFamilyName(token)
                    val url = FONT_URL_BASE + token
                    val resource = FontResource(token, cached, mimeFor(ref.label))
                    ResolvedFont(
                        "'$familyName'",
                        resource,
                        fontFace(familyName, url),
                    )
                }
            }
        }
    }

    /**
     * 复制字体文件到缓存（已存在则直接复用）。SAF 授权失效、provider 报错、字节为空
     * 都返回 null，调用方回退到默认字体栈而不是让整本书打不开。
     */
    fun ensureCached(context: Context, ref: ReaderFontRef): File? {
        val target = cacheFile(context, ref)
        if (target.isFile && target.length() > 0L) return target
        val uri = runCatching { Uri.parse(ref.key) }.getOrNull() ?: return null
        return runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            if (temp.length() <= 0L) {
                temp.delete()
                return@runCatching null
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return@runCatching null
            }
            target
        }.getOrElse { error ->
            Log.w(TAG, "字体复制失败: ${ref.label}", error)
            null
        }
    }

    /**
     * 系统自带字体列表。
     *
     * Android 没有公开"枚举所有字体族名"的 API（android.graphics.fonts.Font 不暴露 family），
     * 但平台自己就是用 [FONT_CONFIG_FILES] 里的 fonts.xml 建立字体族映射的：这里解析同样的
     * 配置（family/alias 的 name），得到的名字正是 Typeface 与 WebView 能解析的。
     * 配置读不到时退回常见字体族名，不影响阅读器本身。
     */
    fun systemFonts(): List<ReaderFontRef> {
        val families = LinkedHashSet<String>()
        for (path in FONT_CONFIG_FILES) {
            val file = File(path)
            if (!file.isFile) continue
            runCatching { parseFontConfigFamilies(file.readText()) }
                .onSuccess { families += it }
                .onFailure { error -> Log.w(TAG, "字体配置读取失败: $path", error) }
        }
        val names = families.takeIf { it.isNotEmpty() } ?: LEGACY_SYSTEM_FONTS
        return names
            .map { ReaderFontRef(ReaderFontSource.SYSTEM, it, it) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /**
     * 从平台字体配置里提取字体族名（纯函数，便于单测）。只关心 family/alias 的 name 属性：
     * 这正是 CSS font-family 与 Typeface.create 共用的名字空间。
     */
    fun parseFontConfigFamilies(xml: String): List<String> {
        val result = LinkedHashSet<String>()
        FAMILY_NAME_PATTERN.findAll(xml).forEach { match ->
            val name = match.groupValues[1].trim()
            if (name.isNotEmpty()) result += name
        }
        return result.toList()
    }

    /** 平台字体配置路径：主配置在 /system，Android 12+ 还可能合并 /product 与 /system_ext。 */
    private val FONT_CONFIG_FILES = listOf(
        "/system/etc/fonts.xml",
        "/product/etc/fonts.xml",
        "/system_ext/etc/fonts.xml",
        "/vendor/etc/fonts.xml",
    )

    /** 匹配 `<family name="…">` 与 `<alias name="…">`（含自闭合写法）。 */
    private val FAMILY_NAME_PATTERN =
        Regex("""<(?:family|alias)\b[^>]*\bname="([^"]+)"""")

    /**
     * 扫描用户选择的字体目录（只列直接子文件）。目录失效时返回空列表，
     * 而不是抛异常打断设置页。
     */
    fun directoryFonts(context: Context, treeUri: String?): List<ReaderFontRef> {
        val tree = treeUri?.takeIf { it.isNotBlank() } ?: return emptyList()
        val uri = runCatching { Uri.parse(tree) }.getOrNull() ?: return emptyList()
        return runCatching {
            val safTree = SafTree(context, uri)
            safTree.children(safTree.rootDocumentId, "")
                ?.asSequence()
                ?.filter { !it.isDirectory && isFontFile(it.name) }
                ?.map { ReaderFontRef(ReaderFontSource.DOCUMENT, it.uri.toString(), it.name) }
                ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                ?.toList()
                .orEmpty()
        }.getOrElse { error ->
            Log.w(TAG, "字体目录扫描失败", error)
            emptyList()
        }
    }

    /** 读取字体目录本身的显示名；选目录后立即用来填标题，不必等扫描完成。 */
    fun directoryName(context: Context, treeUri: String?): String? {
        val uri = treeUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return null
        return runCatching { SafTree(context, uri).rootName() }.getOrNull()
    }

    /** WebView 里字体资源的本地 URL；由 [moe.gbxin.saltreader.ui.reader] 的拦截器供给。 */
    const val FONT_URL_BASE = "https://salt.local/font/"

    /**
     * 低版本（API 26–28）可用的常见字体族。SystemFonts 是 API 29 才有的，
     * 而这些族名在旧 ROM 的 WebView 字体配置里基本都存在。
     */
    private val LEGACY_SYSTEM_FONTS = listOf(
        "sans-serif",
        "serif",
        "monospace",
        "cursive",
        "casual",
        "Roboto",
        "Noto Sans CJK SC",
        "Noto Serif CJK SC",
        "Noto Sans Mono CJK SC",
        "Droid Sans",
        "Droid Serif",
    )
}

/**
 * 一本书打开时解析出的完整字体方案：四种角色各自的 font-family 值、
 * 所有 @font-face 规则和需要由 WebView 拦截器供给的字体文件。
 */
data class ReaderFontSetup(
    val familyHei: String,
    val familySong: String,
    val familyCustom: String,
    val familyMono: String,
    val fontFacesCss: String,
    val resources: Map<String, ReaderFonts.FontResource>,
) {
    fun familyFor(family: ReaderFontFamily): String = when (family) {
        ReaderFontFamily.HEI -> familyHei
        ReaderFontFamily.SONG -> familySong
        ReaderFontFamily.CUSTOM -> familyCustom
    }

    companion object {
        val DEFAULT = ReaderFontSetup(
            familyHei = ReaderFonts.HEI_FALLBACK_STACK,
            familySong = ReaderFonts.SONG_FALLBACK_STACK,
            familyCustom = ReaderFonts.HEI_FALLBACK_STACK,
            familyMono = ReaderFonts.MONO_FALLBACK_STACK,
            fontFacesCss = "",
            resources = emptyMap(),
        )

        /**
         * 按设置解析全部字体角色并拼出 CSS 字体链。
         *
         * 字体链 = 首选字体 → 回退字体 1/2/3 → 内置兜底栈。这样可以让西文字体排在前面、
         * CJK 字体排在后面，WebView 会按字形逐个回退。任何一步失败都回退到默认栈
         * （打开书籍优先于字体完美）。
         */
        fun build(context: Context, settings: AppSettings): ReaderFontSetup {
            val hei = ReaderFonts.resolve(context, settings.readerFontHei)
            val song = ReaderFonts.resolve(context, settings.readerFontSong)
            val mono = ReaderFonts.resolve(context, settings.readerFontMono)
            // 自定义模式没有选字体时跟随"黑体"，这样至少和默认阅读体验一致。
            val custom = if (settings.readerCustomFont == null) {
                hei
            } else {
                ReaderFonts.resolve(context, settings.readerCustomFont)
            }
            val fallbacks = listOf(
                settings.readerFontFallback1,
                settings.readerFontFallback2,
                settings.readerFontFallback3,
            ).map { ref -> ReaderFonts.resolve(context, ref) }
            val fallbackFamilies = fallbacks.mapNotNull { it.familyName }

            /** 首选字体 + 回退字体 + 内置兜底栈。 */
            fun chain(main: ReaderFonts.ResolvedFont, base: String): String {
                val families = listOfNotNull(main.familyName) + fallbackFamilies
                return ReaderFonts.stack(families.joinToString(", ").takeIf { it.isNotBlank() }, base)
            }

            val resources = LinkedHashMap<String, ReaderFonts.FontResource>()
            val faces = StringBuilder()
            for (font in listOf(hei, song, mono, custom) + fallbacks) {
                val resource = font.resource ?: continue
                if (resources.put(resource.token, resource) == null) {
                    faces.append(font.faceCss.orEmpty())
                }
            }
            return ReaderFontSetup(
                familyHei = chain(hei, ReaderFonts.HEI_FALLBACK_STACK),
                familySong = chain(song, ReaderFonts.SONG_FALLBACK_STACK),
                familyCustom = chain(custom, ReaderFonts.HEI_FALLBACK_STACK),
                // 等宽字体只作用于代码块，不参与正文的中英混排回退。
                familyMono = ReaderFonts.stack(mono.familyName, ReaderFonts.MONO_FALLBACK_STACK),
                fontFacesCss = faces.toString(),
                resources = resources,
            )
        }
    }
}
