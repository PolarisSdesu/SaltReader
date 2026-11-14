package moe.gbxin.saltreader.ui.reader

import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableSharedFlow
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.ui.LocalizedActivity
import moe.gbxin.saltreader.ui.theme.SaltReaderTheme

class ReaderActivity : LocalizedActivity() {
    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }

    private val volumePageEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private val bookId: String by lazy { intent.getStringExtra(EXTRA_BOOK_ID).orEmpty() }

    /**
     * Activity-scoped so it survives configuration changes (and process-death restore) without
     * reopening the page source. ReaderScreen renders Loading / Missing / Ready from its state;
     * existence is no longer decided against a snapshot of the library list here.
     */
    private val readerViewModel: ReaderViewModel by viewModels {
        ReaderViewModel.Factory(application, bookId)
    }

    override fun onStart() {
        super.onStart()
        ReadingLiveUpdate.cancel(this)
    }

    /**
     * Post the Android 16 Live Update only when the user leaves the app (Home / recents / another
     * app), not when an in-app screen such as Reader settings is opened.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val app = application as SaltReaderApplication
        if (!app.settings.state.value.readerLiveUpdate) return
        val book = readerViewModel.latestBook() ?: return
        val percent = if (book.reflowable) {
            val total = book.charCount.coerceAtLeast(1L)
            ((book.progressOffset.toFloat() / total.toFloat()).coerceIn(0f, 1f) * 100f).roundToInt()
        } else {
            (book.progress * 100f).roundToInt()
        }
        ReadingLiveUpdate.post(
            this,
            ReadingLiveUpdate.Snapshot(
                bookId = book.id,
                title = book.title,
                percent = percent,
                page = if (book.reflowable) 0 else book.currentPage + 1,
                total = if (book.reflowable) 0 else book.pageCount,
            ),
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val enabled = (application as SaltReaderApplication).settings.state.value.volumeKeyPaging
            if (enabled) {
                volumePageEvents.tryEmit(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * WebView 选择菜单里的"全选"会选中整个文档，对阅读器没有意义；原生阅读器已用自己的
     * "全选段落"替代。这里直接移除系统菜单中的全选项，保留复制/分享/朗读等系统项。
     */
    override fun onActionModeStarted(mode: ActionMode) {
        mode.menu?.removeItem(android.R.id.selectAll)
        super.onActionModeStarted(mode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A missing extra is a broken intent, not a library state; everything else (book gone,
        // library still loading) is represented by ReaderUiState and handled in the UI.
        if (bookId.isBlank()) {
            finish()
            return
        }
        val app = application as SaltReaderApplication
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            SaltReaderTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, amoledBlack = settings.amoledBlack) {
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(durationMillis = 180)),
                ) {
                    ReaderScreen(
                        vm = readerViewModel,
                        onBack = { finish() },
                        volumePageEvents = volumePageEvents,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
