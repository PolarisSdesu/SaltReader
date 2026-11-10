package moe.gbxin.saltreader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import moe.gbxin.saltreader.data.ThemeMode

// Standard MD3 shape tokens: 4 / 8 / 12 / 16 / 28 dp.
private val SaltShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

fun saltLightColorScheme(): ColorScheme = lightColorScheme(
    primary = BlueLightPrimary,
    onPrimary = BlueLightOnPrimary,
    primaryContainer = BlueLightPrimaryContainer,
    onPrimaryContainer = BlueLightOnPrimaryContainer,
    inversePrimary = BlueLightInversePrimary,
    secondary = BlueLightSecondary,
    onSecondary = BlueLightOnSecondary,
    secondaryContainer = BlueLightSecondaryContainer,
    onSecondaryContainer = BlueLightOnSecondaryContainer,
    tertiary = BlueLightTertiary,
    onTertiary = BlueLightOnTertiary,
    tertiaryContainer = BlueLightTertiaryContainer,
    onTertiaryContainer = BlueLightOnTertiaryContainer,
    background = BlueLightBackground,
    onBackground = BlueLightOnBackground,
    surface = BlueLightSurface,
    onSurface = BlueLightOnSurface,
    surfaceVariant = BlueLightSurfaceVariant,
    onSurfaceVariant = BlueLightOnSurfaceVariant,
    surfaceTint = BlueLightPrimary,
    inverseSurface = BlueLightInverseSurface,
    inverseOnSurface = BlueLightInverseOnSurface,
    error = BlueLightError,
    onError = BlueLightOnError,
    errorContainer = BlueLightErrorContainer,
    onErrorContainer = BlueLightOnErrorContainer,
    outline = BlueLightOutline,
    outlineVariant = BlueLightOutlineVariant,
    scrim = Color.Black,
    surfaceBright = BlueLightSurfaceBright,
    surfaceDim = BlueLightSurfaceDim,
    surfaceContainerLowest = BlueLightSurfaceContainerLowest,
    surfaceContainerLow = BlueLightSurfaceContainerLow,
    surfaceContainer = BlueLightSurfaceContainer,
    surfaceContainerHigh = BlueLightSurfaceContainerHigh,
    surfaceContainerHighest = BlueLightSurfaceContainerHighest,
)

fun saltDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = BlueDarkPrimary,
    onPrimary = BlueDarkOnPrimary,
    primaryContainer = BlueDarkPrimaryContainer,
    onPrimaryContainer = BlueDarkOnPrimaryContainer,
    inversePrimary = BlueDarkInversePrimary,
    secondary = BlueDarkSecondary,
    onSecondary = BlueDarkOnSecondary,
    secondaryContainer = BlueDarkSecondaryContainer,
    onSecondaryContainer = BlueDarkOnSecondaryContainer,
    tertiary = BlueDarkTertiary,
    onTertiary = BlueDarkOnTertiary,
    tertiaryContainer = BlueDarkTertiaryContainer,
    onTertiaryContainer = BlueDarkOnTertiaryContainer,
    background = BlueDarkBackground,
    onBackground = BlueDarkOnBackground,
    surface = BlueDarkSurface,
    onSurface = BlueDarkOnSurface,
    surfaceVariant = BlueDarkSurfaceVariant,
    onSurfaceVariant = BlueDarkOnSurfaceVariant,
    surfaceTint = BlueDarkPrimary,
    inverseSurface = BlueDarkInverseSurface,
    inverseOnSurface = BlueDarkInverseOnSurface,
    error = BlueDarkError,
    onError = BlueDarkOnError,
    errorContainer = BlueDarkErrorContainer,
    onErrorContainer = BlueDarkOnErrorContainer,
    outline = BlueDarkOutline,
    outlineVariant = BlueDarkOutlineVariant,
    scrim = Color.Black,
    surfaceBright = BlueDarkSurfaceBright,
    surfaceDim = BlueDarkSurfaceDim,
    surfaceContainerLowest = BlueDarkSurfaceContainerLowest,
    surfaceContainerLow = BlueDarkSurfaceContainerLow,
    surfaceContainer = BlueDarkSurfaceContainer,
    surfaceContainerHigh = BlueDarkSurfaceContainerHigh,
    surfaceContainerHighest = BlueDarkSurfaceContainerHighest,
)

@Composable
fun SaltReaderTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val baseContext = LocalContext.current
    val languageTag by moe.gbxin.saltreader.util.AppLanguageStore.language.collectAsState()
    val localized = remember(languageTag, baseContext) {
        moe.gbxin.saltreader.util.localizedContext(baseContext, languageTag)
    }
    CompositionLocalProvider(
        LocalConfiguration provides localized.resources.configuration,
        LocalContext provides localized,
        LocalResources provides localized.resources,
    ) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> saltDarkColorScheme()
        else -> saltLightColorScheme()
    }
    // AMOLED mode: true black surfaces so dark pixels can be switched off on OLED panels.
    val colorScheme = if (dark && amoledBlack) baseScheme.withAmoledBlack() else baseScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Edge-to-edge (enableEdgeToEdge in every Activity) already makes the system bars
            // transparent, so only the icon contrast is set here; the deprecated
            // statusBarColor / navigationBarColor / contrast-enforced APIs are not touched.
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = SaltShapes,
        typography = SaltTypography,
        content = content,
    )
    }
}

private fun ColorScheme.withAmoledBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF262A2E),
    surfaceVariant = Color(0xFF1A1C1E),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0C0E),
    surfaceContainer = Color(0xFF121417),
    surfaceContainerHigh = Color(0xFF191B1E),
    surfaceContainerHighest = Color(0xFF202225),
)

@Composable
fun pageBackgroundColor(
    background: moe.gbxin.saltreader.data.ReaderBackground,
    customColor: Long = 0xFFFDFCFF,
    darkTheme: Boolean = false,
): Color = when (background) {
    // AUTO follows the system theme: pure white in light mode, pure black in dark mode.
    moe.gbxin.saltreader.data.ReaderBackground.AUTO -> if (darkTheme) Color.Black else Color.White
    moe.gbxin.saltreader.data.ReaderBackground.WHITE -> Color.White
    moe.gbxin.saltreader.data.ReaderBackground.INK -> Color.Black
    moe.gbxin.saltreader.data.ReaderBackground.CUSTOM -> Color(customColor)
}
