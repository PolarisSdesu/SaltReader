package moe.gbxin.saltreader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import moe.gbxin.saltreader.ui.LocalizedActivity
import moe.gbxin.saltreader.ui.SaltReaderRoot

class MainActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // 边到边：内容绘制到状态栏/导航栏之下，系统栏保持透明可见。
        // 系统栏的可见性由各页面的 insets 内边距配合，不再全局隐藏导航栏。
        enableEdgeToEdge()
        setContent {
            SaltReaderRoot()
        }
    }
}
