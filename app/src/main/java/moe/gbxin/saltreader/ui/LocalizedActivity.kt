package moe.gbxin.saltreader.ui

import android.content.Context
import androidx.activity.ComponentActivity
import moe.gbxin.saltreader.util.localizedContext

/**
 * ComponentActivity that re-attaches its base context with the in-app language chosen by the user.
 * Activities should extend this class instead of ComponentActivity directly.
 */
abstract class LocalizedActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase))
    }
}
