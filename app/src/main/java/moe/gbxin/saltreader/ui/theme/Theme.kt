package moe.gbxin.saltreader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
        content = {
            // Draw the theme background edge-to-edge so the area behind the status/gesture bars
            // matches the page instead of showing the window background as a lighter rectangle.
            Box(Modifier.fillMaxSize().background(colorScheme.background)) {
                content()
            }
        },
    )
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

/**
 * 阅读器控件的 Material 强调色：每个纸张色对应一组 Material 颜色，slider 的 active 部分、
 * 激活图标、进度面板等都用它，而不是永远用内容色（灰/黑）。
 */
@Composable
fun readerAccentColor(
    background: moe.gbxin.saltreader.data.ReaderBackground,
    customColor: Long = 0xFFFDFCFF,
): Color = when (background) {
    // 莫奈：跟随 Material You 动态取色的主色。
    moe.gbxin.saltreader.data.ReaderBackground.AUTO -> MaterialTheme.colorScheme.primary
    // 纯白纸张：Material 主题主色（未开动态取色时即应用品牌色）。
    moe.gbxin.saltreader.data.ReaderBackground.WHITE -> MaterialTheme.colorScheme.primary
    // 米色纸张：暖棕调（Material tertiary 的暖色系）。
    moe.gbxin.saltreader.data.ReaderBackground.SEPIA -> Color(0xFF9A6B3F)
    // 浅绿纸张：同色系绿。
    moe.gbxin.saltreader.data.ReaderBackground.GREEN -> Color(0xFF4E7A57)
    // 纯黑纸张：象牙白强调，保持暗色下的对比。
    moe.gbxin.saltreader.data.ReaderBackground.INK -> Color(0xFFE8DCC4)
    // 自定义背景：保留背景色相，提高饱和度并按明暗调整亮度，得到一组 Material 强调色。
    moe.gbxin.saltreader.data.ReaderBackground.CUSTOM -> accentFromBackground(Color(customColor))
}

/**
 * 依据背景色计算阅读器前景色：用 WCAG 对比度挑黑/白，而不是只看亮度阈值，
 * 这样自定义中间调背景上的文字也能保持可读。
 */
fun readerForegroundColor(background: Color): Color {
    val white = Color(0xFFF4EEE4)
    val black = Color(0xFF1C1A18)
    return if (contrastRatio(background, white) >= contrastRatio(background, black)) white else black
}

private fun contrastRatio(background: Color, foreground: Color): Float {
    val bg = background.luminance()
    val fg = foreground.luminance()
    val lighter = kotlin.math.max(bg, fg)
    val darker = kotlin.math.min(bg, fg)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/** 从自定义背景色推导强调色：同色相、提高饱和度，按背景明暗选对比够的亮度。 */
private fun accentFromBackground(background: Color): Color {
    val argb = background.toArgb()
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    var hue = when {
        delta < 1e-4f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    if (hue < 0f) hue += 360f
    val saturation = if (max <= 0f) 0f else delta / max
    val s = saturation.coerceIn(0.35f, 0.85f)
    val l = if (background.luminance() > 0.5f) 0.38f else 0.72f
    return hslToColor(hue, s, l)
}

private fun hslToColor(hue: Float, saturation: Float, lightness: Float): Color {
    val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
    val m = lightness - c / 2f
    val (r, g, b) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

fun pageBackgroundColor(
    background: moe.gbxin.saltreader.data.ReaderBackground,
    customColor: Long = 0xFFFDFCFF,
    darkTheme: Boolean = false,
    /** "莫奈"：跟随 Material You 动态取色的 surface 颜色（未启用动态取色时即主题 surface）。 */
    monetSurface: Color = if (darkTheme) Color.Black else Color.White,
): Color = when (background) {
    // AUTO（莫奈）uses the Material You dynamic palette: the wallpaper-derived surface colour.
    moe.gbxin.saltreader.data.ReaderBackground.AUTO -> monetSurface
    moe.gbxin.saltreader.data.ReaderBackground.WHITE -> Color.White
    moe.gbxin.saltreader.data.ReaderBackground.SEPIA -> Color(0xFFF5EFE2)
    moe.gbxin.saltreader.data.ReaderBackground.GREEN -> Color(0xFFE7EFE4)
    moe.gbxin.saltreader.data.ReaderBackground.INK -> Color.Black
    moe.gbxin.saltreader.data.ReaderBackground.CUSTOM -> Color(customColor)
}
