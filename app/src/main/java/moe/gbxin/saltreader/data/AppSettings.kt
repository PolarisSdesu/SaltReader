package moe.gbxin.saltreader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LibraryLayout { COMPACT, COMFORTABLE, COVER_ONLY, LIST }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val amoledBlack: Boolean = false,
    val readerMode: ReaderMode = ReaderMode.PAGED,
    val readingDirection: ReadingDirection = ReadingDirection.LTR,
    val readerBackground: ReaderBackground = ReaderBackground.WHITE,
    val customBackgroundColor: Long = 0xFFFDFCFF,
    val keepScreenOn: Boolean = true,
    val showPageNumber: Boolean = false,
    val volumeKeyPaging: Boolean = true,
    val grayscale: Boolean = false,
    val invertColors: Boolean = false,
    val libraryLayout: LibraryLayout = LibraryLayout.COMFORTABLE,
    val libraryColumns: Int = 0,
    val libraryTitleSizeSp: Int = 20,
    val librarySortAscending: Boolean = false,
    val librarySort: LibrarySort = LibrarySort.RECENT,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("saltreader_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun read(): AppSettings = AppSettings(
        themeMode = prefs.enum("theme_mode", ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean("dynamic_color", false),
        amoledBlack = prefs.getBoolean("amoled_black", false),
        readerMode = prefs.enum("reader_mode", ReaderMode.PAGED),
        readingDirection = prefs.enum("reading_direction", ReadingDirection.LTR),
        readerBackground = prefs.enum("reader_background", ReaderBackground.WHITE)
            .takeUnless { it == ReaderBackground.PAPER } ?: ReaderBackground.WHITE,
        customBackgroundColor = prefs.getLong("custom_background_color", 0xFFFDFCFF),
        keepScreenOn = prefs.getBoolean("keep_screen_on", true),
        showPageNumber = prefs.getBoolean("show_page_number", false),
        volumeKeyPaging = prefs.getBoolean("volume_key_paging", true),
        grayscale = prefs.getBoolean("reader_grayscale", false),
        invertColors = prefs.getBoolean("reader_invert", false),
        libraryLayout = runCatching { LibraryLayout.valueOf(prefs.getString("library_layout", LibraryLayout.COMFORTABLE.name)!!) }.getOrDefault(LibraryLayout.COMFORTABLE),
        libraryColumns = prefs.getInt("library_columns", 0),
        libraryTitleSizeSp = prefs.getInt("library_title_size", 20),
        librarySortAscending = prefs.getBoolean("library_sort_ascending", false),
        librarySort = prefs.enum("library_sort", LibrarySort.RECENT),
    )

    /**
     * Applies [transform] and persists only the keys whose value actually changed.
     * The in-memory [state] always updates immediately so the UI reacts without waiting for disk.
     */
    fun update(transform: (AppSettings) -> AppSettings) {
        val old = _state.value
        val next = transform(old)
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
        if (next.libraryLayout != old.libraryLayout) editor.putString("library_layout", next.libraryLayout.name)
        if (next.libraryColumns != old.libraryColumns) editor.putInt("library_columns", next.libraryColumns)
        if (next.libraryTitleSizeSp != old.libraryTitleSizeSp) editor.putInt("library_title_size", next.libraryTitleSizeSp)
        if (next.librarySortAscending != old.librarySortAscending) {
            editor.putBoolean("library_sort_ascending", next.librarySortAscending)
        }
        if (next.librarySort != old.librarySort) editor.putString("library_sort", next.librarySort.name)
        editor.apply()
    }
}

private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enum(key: String, fallback: T): T =
    runCatching { enumValueOf<T>(getString(key, fallback.name) ?: fallback.name) }.getOrDefault(fallback)
