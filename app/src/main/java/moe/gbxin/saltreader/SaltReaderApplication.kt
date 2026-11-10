package moe.gbxin.saltreader

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.gbxin.saltreader.data.LibraryRepository
import moe.gbxin.saltreader.data.SettingsRepository

class SaltReaderApplication : Application() {
    lateinit var library: LibraryRepository
        private set
    lateinit var settings: SettingsRepository
        private set

    /** Play the cover-intro only once per process, not when returning from the reader. */
    var hasShownBookReveal: Boolean = false

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        library = LibraryRepository(this)
        settings = SettingsRepository(this)
        appScope.launch { library.initialize() }
    }
}
