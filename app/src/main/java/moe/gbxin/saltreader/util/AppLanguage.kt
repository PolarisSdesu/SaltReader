package moe.gbxin.saltreader.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale
import moe.gbxin.saltreader.MainActivity

/**
 * In-app language selection.
 *
 * Android 13+ uses the platform per-app locale (`LocaleManager`), so the system also shows the
 * choice in Settings > Apps > Salt Reader > Language. On older versions we persist the tag and
 * wrap each Activity/Application base context with the chosen configuration; changing the language
 * restarts the task so every screen re-attaches with the new locale.
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

/** The language the UI is actually using right now (system per-app locale on Android 13+). */
fun currentAppLanguage(context: Context): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return LANGUAGE_SYSTEM
        return normalizePlatformTag(manager.applicationLocales.toLanguageTags().substringBefore(',').trim())
    }
    return readStoredAppLanguage(context)
}

/** Persists the choice and applies it. On Android 12 and below this restarts the app task. */
fun setAppLanguage(context: Context, tag: String) {
    val normalized = normalizeAppLanguage(tag)
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_APP_LANGUAGE, normalized)
        .apply()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales = if (normalized == LANGUAGE_SYSTEM) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(normalized)
        }
    } else {
        restartTask(context)
    }
}

/**
 * Base-context wrapper used on Android 12 and below. On Android 13+ the framework applies the
 * per-app locale itself, so no wrapping is needed there.
 */
fun localizedContext(base: Context): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
    val tag = readStoredAppLanguage(base)
    if (tag == LANGUAGE_SYSTEM) return base
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return base.createConfigurationContext(configuration)
}

/** Maps a platform locale tag (e.g. zh-Hans-CN, zh-Hant-TW) onto our four supported choices. */
private fun normalizePlatformTag(tag: String?): String {
    if (tag.isNullOrBlank()) return LANGUAGE_SYSTEM
    return when {
        tag.startsWith("zh", ignoreCase = true) &&
            (tag.contains("TW", true) || tag.contains("HK", true) || tag.contains("Hant", true)) -> LANGUAGE_ZH_TW
        tag.startsWith("zh", ignoreCase = true) -> LANGUAGE_ZH_CN
        tag.startsWith("ja", ignoreCase = true) -> LANGUAGE_JA
        tag.startsWith("en", ignoreCase = true) -> LANGUAGE_EN
        else -> LANGUAGE_SYSTEM
    }
}

private fun restartTask(context: Context) {
    val intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    context.startActivity(intent)
    (context as? Activity)?.finishAffinity()
}
