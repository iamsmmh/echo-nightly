@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.echo.player.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import platform.AVKit.AVRoutePickerView
import platform.UIKit.*

/**
 * iOS: the native AirPlay route picker. Tapping opens the system sheet with
 * AirPlay devices, HomePods, CarPlay and Bluetooth audio; selecting AirPlay
 * streams through the playback session already configured by [IosAudioPlayer]
 * (route changes are handled there, so playback continues seamlessly).
 */
@Composable
actual fun MediaRouteButton() {
    UIKitView(
        factory = {
            AVRoutePickerView().apply {
                prioritizesVideoDevices = false
                activeTintColor = UIColor.systemBlueColor
                tintColor = UIColor.labelColor
            }
        },
        modifier = Modifier
            .size(28.dp)
            .semantics { contentDescription = "Choose audio output" }
    )
}
