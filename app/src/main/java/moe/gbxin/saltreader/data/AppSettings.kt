package moe.gbxin.saltreader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LibraryLayout { COMPACT, COMFORTABLE, COVER_ONLY, LIST }

/** Font size for the reflowable (text EPUB) reader. */
const val READER_FONT_SIZE_MIN_SP = 14
const val READER_FONT_SIZE_MAX_SP = 28
const val READER_FONT_SIZE_DEFAULT_SP = 18

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
    val showPageNumber: Boolean = false,
    val volumeKeyPaging: Boolean = true,
    val grayscale: Boolean = false,
    val invertColors: Boolean = false,
    /** Font size (sp) used by the text EPUB reader; always within [READER_FONT_SIZE_MIN_SP]..[READER_FONT_SIZE_MAX_SP]. */
    val readerFontSizeSp: Int = READER_FONT_SIZE_DEFAULT_SP,
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
        showPageNumber = prefs.getBoolean("show_page_number", false),
        volumeKeyPaging = prefs.getBoolean("volume_key_paging", true),
        grayscale = prefs.getBoolean("reader_grayscale", false),
        invertColors = prefs.getBoolean("reader_invert", false),
        readerFontSizeSp = prefs.getInt("reader_font_size", READER_FONT_SIZE_DEFAULT_SP)
            .coerceIn(READER_FONT_SIZE_MIN_SP, READER_FONT_SIZE_MAX_SP),
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
        if (next.showPageNumber != old.showPageNumber) editor.putBoolean("show_page_number", next.showPageNumber)
        if (next.volumeKeyPaging != old.volumeKeyPaging) editor.putBoolean("volume_key_paging", next.volumeKeyPaging)
        if (next.grayscale != old.grayscale) editor.putBoolean("reader_grayscale", next.grayscale)
        if (next.invertColors != old.invertColors) editor.putBoolean("reader_invert", next.invertColors)
        if (next.readerFontSizeSp != old.readerFontSizeSp) {
            editor.putInt("reader_font_size", next.readerFontSizeSp)
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
