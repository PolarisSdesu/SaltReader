package moe.gbxin.saltreader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// Material 3 baseline type scale, slightly increased for Chinese readability.
val SaltTypography: Typography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontSize = 34.sp, lineHeight = 42.sp),
        titleLarge = base.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp),
        titleMedium = base.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
        titleSmall = base.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = base.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
        labelMedium = base.labelMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    )
}
