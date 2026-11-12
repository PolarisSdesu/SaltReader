package moe.gbxin.saltreader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LibraryLayout { COMPACT, COMFORTABLE, COVER_ONLY, LIST }

/** Font size for the reflowable (text EPUB) reader. */
const val READER_FONT_SIZE_MIN_SP = 14
const val READER_FONT_SIZE_MAX_SP = 28
const val READER_FONT_SIZE_DEFAULT_SP = 16

/** Line height multiplier for the reflowable (text EPUB) reader. */
const val READER_LINE_HEIGHT_MIN = 1.2f
const val READER_LINE_HEIGHT_MAX = 2.4f
const val READER_LINE_HEIGHT_DEFAULT = 1.8f

/** Horizontal page margin (dp) for the reflowable (text EPUB) reader, WeChat-Reading style. */
const val READER_PAGE_MARGIN_MIN = 16
const val READER_PAGE_MARGIN_MAX = 44
const val READER_PAGE_MARGIN_DEFAULT = 30
const val READER_PROGRESS_LABEL_SIZE_MIN_SP = 10
const val READER_PROGRESS_LABEL_SIZE_MAX_SP = 16
const val READER_PROGRESS_LABEL_SIZE_DEFAULT_SP = 12
const val READER_PAGE_INDICATOR_SIZE_MIN_SP = 10
const val READER_PAGE_INDICATOR_SIZE_MAX_SP = 16
const val READER_PAGE_INDICATOR_SIZE_DEFAULT_SP = 10

/** 文字书阅读器可选的字体类型；"自定义"具体用哪一款字体由 [AppSettings.readerCustomFont] 决定。 */
enum class ReaderFontFamily {
    /** 黑体 / 无衬线：阅读器的默认选择。 */
    HEI,
    /** 宋体 / 衬线：中文小说的传统排版。 */
    SONG,
    /** 用户在"字体设置"里选定的字体。 */
    CUSTOM,
}

/** Book-card metadata label font size. */
const val LIBRARY_META_TEXT_MIN_SP = 10
const val LIBRARY_META_TEXT_MAX_SP = 18
const val LIBRARY_META_TEXT_DEFAULT_SP = 12

val DEFAULT_LIBRARY_META_FIELDS: Set<LibraryMetaField> =
    setOf(LibraryMetaField.FORMAT, LibraryMetaField.PAGES, LibraryMetaField.SIZE)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val amoledBlack: Boolean = false,
    val readerMode: ReaderMode = ReaderMode.PAGED,
    val readingDirection: ReadingDirection = ReadingDirection.LTR,
    val readerBackground: ReaderBackground = ReaderBackground.AUTO,
    val customBackgroundColor: Long = 0xFFFDFCFF,
    val keepScreenOn: Boolean = true,
    /** Bottom-right corner label while chrome is hidden: hidden / progress / page number. */
    val readerPageInfo: ReaderPageInfo = ReaderPageInfo.PAGES,
    val volumeKeyPaging: Boolean = true,
    val grayscale: Boolean = false,
    val invertColors: Boolean = false,
    /** Font size (sp) used by the text EPUB reader; always within [READER_FONT_SIZE_MIN_SP]..[READER_FONT_SIZE_MAX_SP]. */
    val readerFontSizeSp: Int = READER_FONT_SIZE_DEFAULT_SP,
    /** Line height multiplier used by the text EPUB reader; within [READER_LINE_HEIGHT_MIN]..[READER_LINE_HEIGHT_MAX]. */
    val readerLineHeight: Float = READER_LINE_HEIGHT_DEFAULT,
    /** Horizontal page margin (dp) of the text EPUB reader, within [READER_PAGE_MARGIN_MIN]..[READER_PAGE_MARGIN_MAX]. */
    val readerPageMarginDp: Int = READER_PAGE_MARGIN_DEFAULT,
    /** Font size (sp) of the "xx% · xx/xx" label under the resident progress slider. */
    val readerProgressLabelSizeSp: Int = READER_PROGRESS_LABEL_SIZE_DEFAULT_SP,
    /** Font size (sp) of the bottom-end page/progress indicator while the chrome is hidden. */
    val readerPageIndicatorSizeSp: Int = READER_PAGE_INDICATOR_SIZE_DEFAULT_SP,
    /** Lower-left battery icon + percentage while the chrome is hidden, next to the page indicator. */
    val readerBatteryIndicator: Boolean = true,
    /** 文字书使用的字体类型。 */
    val readerFontFamily: ReaderFontFamily = ReaderFontFamily.HEI,
    /** "自定义"模式使用的字体；为空时跟随黑体。 */
    val readerCustomFont: ReaderFontRef? = null,
    /** 默认黑体替换字体；为空表示使用内置系统字体栈。 */
    val readerFontHei: ReaderFontRef? = null,
    /** 默认宋体替换字体；为空表示使用内置系统字体栈。 */
    val readerFontSong: ReaderFontRef? = null,
    /** 等宽字体（代码块）；为空表示使用内置等宽字体栈。 */
    val readerFontMono: ReaderFontRef? = null,
    /** 回退字体 1–3：首选字体缺字时按顺序使用，用于中英文/CJK 混排。 */
    val readerFontFallback1: ReaderFontRef? = null,
    val readerFontFallback2: ReaderFontRef? = null,
    val readerFontFallback3: ReaderFontRef? = null,
    /** 用户字体目录（持久化 SAF tree URI）。 */
    val readerFontDirectoryUri: String? = null,
    /** 字体目录的显示名，避免每次打开设置都去查 provider。 */
    val readerFontDirectoryLabel: String? = null,
    val libraryLayout: LibraryLayout = LibraryLayout.COMFORTABLE,
    val libraryColumns: Int = 0,
    val libraryTitleSizeSp: Int = 20,
    val librarySortAscending: Boolean = false,
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val libraryMetaVisible: Boolean = true,
    val libraryMetaTextSizeSp: Int = LIBRARY_META_TEXT_DEFAULT_SP,
    val libraryMetaFields: Set<LibraryMetaField> = DEFAULT_LIBRARY_META_FIELDS,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("saltreader_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun read(): AppSettings = AppSettings(
        themeMode = prefs.enum("theme_mode", ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean("dynamic_color", true),
        amoledBlack = prefs.getBoolean("amoled_black", false),
        readerMode = prefs.enum("reader_mode", ReaderMode.PAGED),
        readingDirection = prefs.enum("reading_direction", ReadingDirection.LTR),
        readerBackground = prefs.enum("reader_background", ReaderBackground.AUTO),
        customBackgroundColor = prefs.getLong("custom_background_color", 0xFFFDFCFF),
        keepScreenOn = prefs.getBoolean("keep_screen_on", true),
        readerPageInfo = prefs.enum("reader_page_info", if (prefs.getBoolean("show_page_number", false)) ReaderPageInfo.PROGRESS else ReaderPageInfo.PAGES),
        volumeKeyPaging = prefs.getBoolean("volume_key_paging", true),
        grayscale = prefs.getBoolean("reader_grayscale", false),
        invertColors = prefs.getBoolean("reader_invert", false),
        readerFontSizeSp = prefs.getInt("reader_font_size", READER_FONT_SIZE_DEFAULT_SP)
            .coerceIn(READER_FONT_SIZE_MIN_SP, READER_FONT_SIZE_MAX_SP),
        readerLineHeight = prefs.getFloat("reader_line_height", READER_LINE_HEIGHT_DEFAULT)
            .coerceIn(READER_LINE_HEIGHT_MIN, READER_LINE_HEIGHT_MAX),
        readerPageMarginDp = prefs.getInt("reader_page_margin", READER_PAGE_MARGIN_DEFAULT)
            .coerceIn(READER_PAGE_MARGIN_MIN, READER_PAGE_MARGIN_MAX),
        readerFontFamily = prefs.enum("reader_font_family", ReaderFontFamily.HEI),
        readerCustomFont = ReaderFontCodec.decode(prefs.getString("reader_font_custom", null)),
        readerFontHei = ReaderFontCodec.decode(prefs.getString("reader_font_hei", null)),
        readerFontSong = ReaderFontCodec.decode(prefs.getString("reader_font_song", null)),
        readerFontMono = ReaderFontCodec.decode(prefs.getString("reader_font_mono", null)),
        readerFontFallback1 = ReaderFontCodec.decode(prefs.getString("reader_font_fallback_1", null)),
        readerFontFallback2 = ReaderFontCodec.decode(prefs.getString("reader_font_fallback_2", null)),
        readerFontFallback3 = ReaderFontCodec.decode(prefs.getString("reader_font_fallback_3", null)),
        readerFontDirectoryUri = prefs.getString("reader_font_directory_uri", null),
        readerFontDirectoryLabel = prefs.getString("reader_font_directory_label", null),
        readerProgressLabelSizeSp = prefs.getInt("reader_progress_label_size", READER_PROGRESS_LABEL_SIZE_DEFAULT_SP)
            .coerceIn(READER_PROGRESS_LABEL_SIZE_MIN_SP, READER_PROGRESS_LABEL_SIZE_MAX_SP),
        readerPageIndicatorSizeSp = prefs.getInt("reader_page_indicator_size", READER_PAGE_INDICATOR_SIZE_DEFAULT_SP)
            .coerceIn(READER_PAGE_INDICATOR_SIZE_MIN_SP, READER_PAGE_INDICATOR_SIZE_MAX_SP),
        readerBatteryIndicator = prefs.getBoolean("reader_battery_indicator", true),
        libraryLayout = runCatching { LibraryLayout.valueOf(prefs.getString("library_layout", LibraryLayout.COMFORTABLE.name)!!) }.getOrDefault(LibraryLayout.COMFORTABLE),
        libraryColumns = prefs.getInt("library_columns", 0),
        libraryTitleSizeSp = prefs.getInt("library_title_size", 20),
        librarySortAscending = prefs.getBoolean("library_sort_ascending", false),
        librarySort = prefs.enum("library_sort", LibrarySort.RECENT),
        libraryMetaVisible = prefs.getBoolean("library_meta_visible", true),
        libraryMetaTextSizeSp = prefs.getInt("library_meta_text_size", LIBRARY_META_TEXT_DEFAULT_SP)
            .coerceIn(LIBRARY_META_TEXT_MIN_SP, LIBRARY_META_TEXT_MAX_SP),
        libraryMetaFields = parseMetaFields(
            prefs.getString("library_meta_fields", DEFAULT_LIBRARY_META_FIELDS.joinToString(",") { it.name }),
        ),
    )

    /**
     * Applies [transform] and persists only the keys whose value actually changed.
     * The in-memory [state] always updates immediately so the UI reacts without waiting for disk.
     */
    fun update(transform: (AppSettings) -> AppSettings) {
        val old = _state.value
        val next = transform(old).normalized()
        if (next == old) return
        _state.value = next

        val editor = prefs.edit()
        if (next.themeMode != old.themeMode) editor.putString("theme_mode", next.themeMode.name)
        if (next.dynamicColor != old.dynamicColor) editor.putBoolean("dynamic_color", next.dynamicColor)
        if (next.amoledBlack != old.amoledBlack) editor.putBoolean("amoled_black", next.amoledBlack)
        if (next.readerMode != old.readerMode) editor.putString("reader_mode", next.readerMode.name)
        if (next.readingDirection != old.readingDirection) editor.putString("reading_direction", next.readingDirection.name)
        if (next.readerBackground != old.readerBackground) editor.putString("reader_background", next.readerBackground.name)
        if (next.customBackgroundColor != old.customBackgroundColor) {
            editor.putLong("custom_background_color", next.customBackgroundColor)
        }
        if (next.keepScreenOn != old.keepScreenOn) editor.putBoolean("keep_screen_on", next.keepScreenOn)
        if (next.readerPageInfo != old.readerPageInfo) editor.putString("reader_page_info", next.readerPageInfo.name)
        if (next.volumeKeyPaging != old.volumeKeyPaging) editor.putBoolean("volume_key_paging", next.volumeKeyPaging)
        if (next.grayscale != old.grayscale) editor.putBoolean("reader_grayscale", next.grayscale)
        if (next.invertColors != old.invertColors) editor.putBoolean("reader_invert", next.invertColors)
        if (next.readerFontSizeSp != old.readerFontSizeSp) {
            editor.putInt("reader_font_size", next.readerFontSizeSp)
        }
        if (next.readerLineHeight != old.readerLineHeight) {
            editor.putFloat("reader_line_height", next.readerLineHeight)
        }
        if (next.readerPageMarginDp != old.readerPageMarginDp) {
            editor.putInt("reader_page_margin", next.readerPageMarginDp)
        }
        if (next.readerFontFamily != old.readerFontFamily) {
            editor.putString("reader_font_family", next.readerFontFamily.name)
        }
        if (next.readerCustomFont != old.readerCustomFont) {
            editor.putString("reader_font_custom", ReaderFontCodec.encode(next.readerCustomFont))
        }
        if (next.readerFontHei != old.readerFontHei) {
            editor.putString("reader_font_hei", ReaderFontCodec.encode(next.readerFontHei))
        }
        if (next.readerFontSong != old.readerFontSong) {
            editor.putString("reader_font_song", ReaderFontCodec.encode(next.readerFontSong))
        }
        if (next.readerFontMono != old.readerFontMono) {
            editor.putString("reader_font_mono", ReaderFontCodec.encode(next.readerFontMono))
        }
        if (next.readerFontFallback1 != old.readerFontFallback1) {
            editor.putString("reader_font_fallback_1", ReaderFontCodec.encode(next.readerFontFallback1))
        }
        if (next.readerFontFallback2 != old.readerFontFallback2) {
            editor.putString("reader_font_fallback_2", ReaderFontCodec.encode(next.readerFontFallback2))
        }
        if (next.readerFontFallback3 != old.readerFontFallback3) {
            editor.putString("reader_font_fallback_3", ReaderFontCodec.encode(next.readerFontFallback3))
        }
        if (next.readerFontDirectoryUri != old.readerFontDirectoryUri) {
            editor.putString("reader_font_directory_uri", next.readerFontDirectoryUri)
        }
        if (next.readerFontDirectoryLabel != old.readerFontDirectoryLabel) {
            editor.putString("reader_font_directory_label", next.readerFontDirectoryLabel)
        }
        if (next.readerBatteryIndicator != old.readerBatteryIndicator) {
            editor.putBoolean("reader_battery_indicator", next.readerBatteryIndicator)
        }
        if (next.readerProgressLabelSizeSp != old.readerProgressLabelSizeSp) {
            editor.putInt("reader_progress_label_size", next.readerProgressLabelSizeSp)
        }
        if (next.readerPageIndicatorSizeSp != old.readerPageIndicatorSizeSp) {
            editor.putInt("reader_page_indicator_size", next.readerPageIndicatorSizeSp)
        }
        if (next.libraryLayout != old.libraryLayout) editor.putString("library_layout", next.libraryLayout.name)
        if (next.libraryColumns != old.libraryColumns) editor.putInt("library_columns", next.libraryColumns)
        if (next.libraryTitleSizeSp != old.libraryTitleSizeSp) editor.putInt("library_title_size", next.libraryTitleSizeSp)
        if (next.librarySortAscending != old.librarySortAscending) {
            editor.putBoolean("library_sort_ascending", next.librarySortAscending)
        }
        if (next.librarySort != old.librarySort) editor.putString("library_sort", next.librarySort.name)
        if (next.libraryMetaVisible != old.libraryMetaVisible) {
            editor.putBoolean("library_meta_visible", next.libraryMetaVisible)
        }
        if (next.libraryMetaTextSizeSp != old.libraryMetaTextSizeSp) {
            editor.putInt("library_meta_text_size", next.libraryMetaTextSizeSp)
        }
        if (next.libraryMetaFields != old.libraryMetaFields) {
            editor.putString("library_meta_fields", next.libraryMetaFields.joinToString(",") { it.name })
        }
        editor.apply()
    }
}

private fun AppSettings.normalized(): AppSettings = copy(
    readerFontSizeSp = readerFontSizeSp.coerceIn(READER_FONT_SIZE_MIN_SP, READER_FONT_SIZE_MAX_SP),
    readerLineHeight = readerLineHeight.coerceIn(READER_LINE_HEIGHT_MIN, READER_LINE_HEIGHT_MAX),
    readerPageMarginDp = readerPageMarginDp.coerceIn(READER_PAGE_MARGIN_MIN, READER_PAGE_MARGIN_MAX),
    libraryMetaTextSizeSp = libraryMetaTextSizeSp.coerceIn(LIBRARY_META_TEXT_MIN_SP, LIBRARY_META_TEXT_MAX_SP),
)

private fun parseMetaFields(raw: String?): Set<LibraryMetaField> {
    if (raw.isNullOrBlank()) return DEFAULT_LIBRARY_META_FIELDS
    val parsed = raw.split(',').mapNotNull { name ->
        runCatching { LibraryMetaField.valueOf(name.trim()) }.getOrNull()
    }.toSet()
    return parsed.ifEmpty { emptySet() }
}

private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enum(key: String, fallback: T): T =
    runCatching { enumValueOf<T>(getString(key, fallback.name) ?: fallback.name) }.getOrDefault(fallback)
