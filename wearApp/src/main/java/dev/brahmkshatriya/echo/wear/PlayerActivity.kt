package dev.brahmkshatriya.echo.wear

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.wear.widget.RoundedDrawable
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

/**
 * Wear OS companion (Phase 7 - ecosystem).
 *
 * A deliberately small, standalone surface: it mirrors the phone's playback
 * state pushed over the Wearable Data Layer (`/echo/state`) and sends remote
 * control messages (`/echo/command`) that the phone-side
 * `EchoWearListenerService` applies to the media session. No network or
 * extension code runs on the watch.
 */
class PlayerActivity : AppCompatActivity() {

    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        findViewById<ImageButton>(R.id.button_play_pause).setOnClickListener { send(Command.PlayPause) }
        findViewById<ImageButton>(R.id.button_next).setOnClickListener { send(Command.Next) }
        findViewById<ImageButton>(R.id.button_previous).setOnClickListener { send(Command.Previous) }
        roundIcons()
    }

    private fun roundIcons() {
        listOf(R.id.button_play_pause, R.id.button_next, R.id.button_previous).forEach { id ->
            val button = findViewById<ImageButton>(id)
            button.background?.let {
                button.background = RoundedDrawable.wrap(it).apply { cornerRadius = button.width / 2f }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        messageClient.addListener(messageListener)
        // Ask the phone for the freshest state on resume. If Echo's playback
        // service is cold on the phone it needs a moment to come up, so retry
        // once shortly after.
        send(Command.RequestState)
        window.decorView.postDelayed({ send(Command.RequestState) }, 800)
    }

    override fun onStop() {
        messageClient.removeListener(messageListener)
        super.onStop()
    }

    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path != PATH_STATE) return@OnMessageReceivedListener
        val text = String(event.data)
        val parts = text.split('\n')
        runOnUiThread {
            findViewById<TextView>(R.id.text_title).text = parts.getOrElse(0) { "" }
            findViewById<TextView>(R.id.text_artist).text = parts.getOrElse(1) { "" }
            val playing = parts.getOrNull(2) == "1"
            findViewById<ImageButton>(R.id.button_play_pause)
                .setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }

    private fun send(command: Command) {
        runCatching {
            Wearable.getNodeClient(this).connectedNodes
                .addOnSuccessListener { nodes ->
                    val data = command.name.toByteArray()
                    nodes.forEach { node ->
                        runCatching {
                            messageClient.sendMessage(node.id, PATH_COMMAND, data)
                        }
                    }
                }
        }
    }

    private enum class Command { PlayPause, Next, Previous, RequestState }

    companion object {
        const val PATH_COMMAND = "/echo/command"
        const val PATH_STATE = "/echo/state"
    }
}
