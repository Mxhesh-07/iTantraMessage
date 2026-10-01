package `in`.isro.sih26173.itantramessage.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's colours and type.
 *
 * ## Dynamic colour is off, deliberately
 *
 * Material You would tint the app from the user's wallpaper, which is a reasonable
 * default. It is not used here because a demo has to *look* the same on both handsets or
 * a reviewer cannot tell whether a difference in the screenshot is the app or the theme.
 * The palette is fixed, and the contrast pairs in res/values/colors.xml were measured
 * rather than inherited -- see the comment there and docs/COLOR.md.
 *
 * ## Type scale
 *
 * The default M3 scale, with two deliberate deviations. Body text is 16sp rather than 14sp
 * because this is a message app and the content is what the user is here to read; and the
 * bubble text has a slightly larger line height, because dense multi-line text at a
 * comfortable reading size on a 5-inch screen is the one place this app can be genuinely
 * hard to use.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1A4C8B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3C),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF43474E),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF004884),
    onPrimaryContainer = Color(0xFFD6E3FF),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/** Colour of a delivery chip's text. Always paired with a text label, never alone. */
object StatusColors {
    val pending = Color(0xFF6B5E00)
    val sent = Color(0xFF1A4C8B)
    val delivered = Color(0xFF1E5B2E)
    val failed = Color(0xFFBA1A1A)

    val pendingDark = Color(0xFFD9C64A)
    val sentDark = Color(0xFFA9C7FF)
    val deliveredDark = Color(0xFF7FD69B)
    val failedDark = Color(0xFFFFB4AB)
}

private val AppTypography = Typography().let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
    )
}

@Composable
fun ItantraMessageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Kept as a parameter but defaulted off, so a future decision to adopt dynamic colour
     * is a one-word change at the call site rather than an edit to this function.
     */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}
