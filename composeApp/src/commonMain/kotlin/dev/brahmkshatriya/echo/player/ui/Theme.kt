package dev.brahmkshatriya.echo.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Indigo = Color(0xFF6C5CE7)
private val IndigoDark = Color(0xFF4834B5)
private val Accent = Color(0xFF00CEC9)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBAEF2),
    onPrimary = Color(0xFF281A5E),
    primaryContainer = IndigoDark,
    onPrimaryContainer = Color(0xFFE5DEFF),
    secondary = Accent,
    onSecondary = Color(0xFF00302E),
    secondaryContainer = Color(0xFF004D4A),
    onSecondaryContainer = Color(0xFF62FBF5),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF2A2831),
    onSurfaceVariant = Color(0xFFC9C4D4),
    error = Color(0xFFCF6679)
)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5DEFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF006A67),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9CF1EA),
    onSecondaryContainer = Color(0xFF00201E),
    background = Color(0xFFFDF8FF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFDF8FF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0F3),
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFB3261E)
)

/**
 * Echo theme. Follows the system light/dark appearance unless the user has
 * forced a mode in settings.
 */
@Composable
fun EchoTheme(
    useDarkThemeOverride: Boolean? = null,
    amoled: Boolean = false,
    dynamic: Boolean = true,
    content: @Composable () -> Unit
) {
    val dark = useDarkThemeOverride ?: isSystemInDarkTheme()
    val palette = (if (dynamic) platformColorScheme(dark) else null) ?: if (dark) DarkColors else LightColors
    MaterialTheme(
        colorScheme = if (dark && amoled) palette.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Color.Black) else palette,
        content = content
    )
}

@Composable
expect fun platformColorScheme(dark: Boolean): androidx.compose.material3.ColorScheme?
