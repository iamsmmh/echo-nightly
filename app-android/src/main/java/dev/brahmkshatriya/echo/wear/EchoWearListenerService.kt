package dev.brahmkshatriya.echo.wear

import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives transport commands from the Echo Wear OS app and applies them to
 * the live player through [WearBridge]. Registered in the app manifest;
 * only ever bound by Play Services, so it stays unexported.
 */
class EchoWearListenerService : WearableListenerService() {

    override fun onMessageReceived(message: MessageEvent) {
        if (message.path != WearProtocol.PATH_COMMAND) return
        // Remember which watch is listening even when playback is not started yet.
        val command = runCatching {
            WearProtocol.Command.valueOf(String(message.data))
        }.getOrNull() ?: return
        val nodeId = message.sourceNodeId
        android.os.Handler(android.os.Looper.getMainLooper()).post {
        val bridge = WearBridge.active
        if (bridge == null) {
            // No live player: wake PlayerService (its crash-resume path restores
            // the queue); the command itself is intentionally dropped.
            runCatching {
                startService(
                    Intent(this, dev.brahmkshatriya.echo.playback.PlayerService::class.java)
                )
            }
            return@post
        }
        bridge.onWatchConnected(nodeId)
        runCatching { bridge.handleCommand(command) }
        }
    }

}
