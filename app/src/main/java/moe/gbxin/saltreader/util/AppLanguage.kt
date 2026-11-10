package moe.gbxin.saltreader.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app language selection.
 *
 * The chosen language is applied live in Compose: [moe.gbxin.saltreader.ui.theme.SaltReaderTheme]
 * provides a localized Context/Configuration/Resources, so switching language recomposes the UI
 * instead of recreating the Activity (which used to flash the screen).
 */

const val SETTINGS_PREFS = "saltreader_settings"
const val KEY_APP_LANGUAGE = "app_language"
const val LANGUAGE_SYSTEM = "system"
const val LANGUAGE_ZH_CN = "zh-CN"
const val LANGUAGE_ZH_TW = "zh-TW"
const val LANGUAGE_EN = "en"
const val LANGUAGE_JA = "ja"

/** Order shown in the appearance screen. */
val SUPPORTED_APP_LANGUAGES = listOf(LANGUAGE_SYSTEM, LANGUAGE_ZH_CN, LANGUAGE_ZH_TW, LANGUAGE_EN, LANGUAGE_JA)

fun normalizeAppLanguage(tag: String?): String =
    if (tag != null && tag in SUPPORTED_APP_LANGUAGES) tag else LANGUAGE_SYSTEM

fun readStoredAppLanguage(context: Context): String = normalizeAppLanguage(
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).getString(KEY_APP_LANGUAGE, LANGUAGE_SYSTEM),
)

/** Process-wide source of truth observed by the theme; no Activity recreation is involved. */
object AppLanguageStore {
    private val _language = MutableStateFlow(LANGUAGE_SYSTEM)
    val language: StateFlow<String> = _language.asStateFlow()

    fun init(context: Context) {
        _language.value = readStoredAppLanguage(context)
    }

    fun set(context: Context, tag: String) {
        val normalized = normalizeAppLanguage(tag)
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_LANGUAGE, normalized)
            .apply()
        _language.value = normalized
    }
}

fun currentAppLanguage(context: Context): String = AppLanguageStore.language.value

fun setAppLanguage(context: Context, tag: String) = AppLanguageStore.set(context, tag)

/** Configuration context for the chosen language; [LANGUAGE_SYSTEM] keeps the system locale. */
fun localizedContext(base: Context, tag: String): Context {
    if (tag == LANGUAGE_SYSTEM) return base
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return base.createConfigurationContext(configuration)
}
