package dev.brahmkshatriya.echo.player.ui

import androidx.compose.runtime.Composable

/** Android relies on the system media output switcher; no in-app button. */
@Composable
actual fun MediaRouteButton() = Unit
