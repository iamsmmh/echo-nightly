package dev.brahmkshatriya.echo.player.ui

@androidx.compose.runtime.Composable
actual fun platformColorScheme(dark: Boolean): androidx.compose.material3.ColorScheme? {
    if (android.os.Build.VERSION.SDK_INT < 31) return null
    val context = androidx.compose.ui.platform.LocalContext.current
    return if (dark) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
}
