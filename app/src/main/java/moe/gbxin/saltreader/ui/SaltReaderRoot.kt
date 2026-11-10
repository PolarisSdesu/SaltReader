package moe.gbxin.saltreader.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.ui.library.LibraryScreen
import moe.gbxin.saltreader.ui.reader.ReaderActivity
import moe.gbxin.saltreader.ui.search.SearchActivity
import moe.gbxin.saltreader.ui.settings.SettingsActivity
import moe.gbxin.saltreader.ui.theme.SaltReaderTheme

/**
 * App shell. Library access is entirely SAF based now: there is no storage permission prompt and
 * no full-disk scan entry. A folder is added from the library (OpenDocumentTree → importLibraryTree).
 */
@Composable
fun SaltReaderRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as SaltReaderApplication
    val settings by app.settings.state.collectAsStateWithLifecycle()
    SaltReaderTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, amoledBlack = settings.amoledBlack) {
        val openReader: (ComicBook) -> Unit = { book ->
            context.startActivity(Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id))
        }
        LibraryScreen(
            onOpenBook = openReader,
            onOpenSearch = { context.startActivity(Intent(context, SearchActivity::class.java)) },
            onOpenSettings = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
        )
    }
}
