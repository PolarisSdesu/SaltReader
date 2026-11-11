package moe.gbxin.saltreader

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.gbxin.saltreader.data.LibraryRepository
import moe.gbxin.saltreader.data.SettingsRepository
import moe.gbxin.saltreader.ui.components.trimCoverCache
import moe.gbxin.saltreader.util.localizedContext
import java.util.concurrent.CopyOnWriteArrayList

class SaltReaderApplication : Application() {
    lateinit var library: LibraryRepository
        private set
    lateinit var settings: SettingsRepository
        private set

    /** Play the cover-intro only once per process, not when returning from the reader. */
    var hasShownBookReveal: Boolean = false

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 位图缓存的裁剪回调（阅读器页缓存等）。位图缓存是按堆比例分配的，
     * 系统回收内存时必须主动让出，否则后台进程很容易被整体杀掉。
     */
    private val bitmapCacheTrimmers = CopyOnWriteArrayList<() -> Unit>()

    fun addBitmapCacheTrimmer(trimmer: () -> Unit) {
        bitmapCacheTrimmers += trimmer
    }

    fun removeBitmapCacheTrimmer(trimmer: () -> Unit) {
        bitmapCacheTrimmers -= trimmer
    }

    @Suppress("DEPRECATION") // 常量在 Android 15 被标记弃用，但语义未变且全平台可用。
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // RUNNING_LOW 及以上（含 RUNNING_CRITICAL / UI_HIDDEN / BACKGROUND）都主动让出位图缓存。
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            bitmapCacheTrimmers.forEach { trimmer -> runCatching { trimmer() } }
            trimCoverCache()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        library = LibraryRepository(this)
        settings = SettingsRepository(this)
        appScope.launch { library.initialize() }
    }
}
