package dev.brahmkshatriya.echo.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = EchoCyan,
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF004D6A),
    onPrimaryContainer = EchoCyanHighlight,
    secondary = Color(0xFF7FD4FF),
    onSecondary = Color(0xFF00344A),
    secondaryContainer = Color(0xFF004D6A),
    onSecondaryContainer = EchoCyanHighlight,
    background = Color(0xFF121212),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF2A2831),
    onSurfaceVariant = Color(0xFFC9C4D4),
    error = Color(0xFFCF6679)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0077B3),
    onPrimary = Color.White,
    primaryContainer = EchoCyanHighlight,
    onPrimaryContainer = Color(0xFF001E2C),
    secondary = Color(0xFF00658C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8ECFF),
    onSecondaryContainer = Color(0xFF001E2C),
    background = Color(0xFFF7FBFF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFF7FBFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFDCEAF3),
    onSurfaceVariant = Color(0xFF40484E),
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
