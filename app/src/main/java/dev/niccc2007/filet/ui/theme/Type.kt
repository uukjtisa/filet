package dev.niccc2007.filet.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tighter than Material's defaults on purpose.
 *
 * A file manager is a dense list read at a glance, not prose. Material's 16sp/24sp body
 * costs about three rows per screen against the 14sp the mock settled on, and the extra
 * letter-spacing makes long filenames truncate sooner.
 */
val FiletTypography = Typography().let { d ->
    Typography(
        displayLarge = d.displayLarge,
        displayMedium = d.displayMedium,
        displaySmall = d.displaySmall,
        headlineLarge = d.headlineLarge.copy(letterSpacing = (-0.5).sp),
        headlineMedium = d.headlineMedium.copy(letterSpacing = (-0.4).sp),
        headlineSmall = d.headlineSmall.copy(letterSpacing = (-0.3).sp),
        titleLarge = d.titleLarge.copy(fontSize = 19.sp, letterSpacing = (-0.2).sp),
        titleMedium = d.titleMedium.copy(fontSize = 15.sp, letterSpacing = 0.sp),
        titleSmall = d.titleSmall.copy(fontSize = 13.sp, letterSpacing = 0.sp),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
        ),
        bodyMedium = d.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        bodySmall = d.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
        labelLarge = d.labelLarge.copy(fontSize = 13.sp, letterSpacing = 0.sp),
        labelMedium = d.labelMedium.copy(fontSize = 11.sp, letterSpacing = 0.2.sp),
        labelSmall = d.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.4.sp),
    )
}
