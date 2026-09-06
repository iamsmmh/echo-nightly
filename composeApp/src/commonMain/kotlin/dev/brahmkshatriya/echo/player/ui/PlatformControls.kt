package dev.brahmkshatriya.echo.player.ui

import androidx.compose.runtime.Composable

/**
 * Platform media-route control shown in the player row.
 *
 * On iOS this renders the system [AVRoutePickerView] (AirPlay / CarPlay
 * speakers / HomePod...). On Android the control is a no-op because the
 * system Media Output Switcher (and Echo's own Cast bridge) cover it.
 */
@Composable
expect fun MediaRouteButton()
