package moe.gbxin.saltreader.ui.search

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.ui.reader.ReaderActivity
import moe.gbxin.saltreader.ui.theme.SaltReaderTheme

class SearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SaltReaderApplication
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            SaltReaderTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, amoledBlack = settings.amoledBlack) {
                SearchScreen(
                    onOpenBook = { book ->
                        startActivity(
                            Intent(this, ReaderActivity::class.java)
                                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id),
                        )
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}
