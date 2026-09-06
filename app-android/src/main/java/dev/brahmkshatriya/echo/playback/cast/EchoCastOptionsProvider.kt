package dev.brahmkshatriya.echo.playback.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Provides the default Google Cast receiver options for the Cast framework
 * (initialized lazily by Play Services via manifest meta-data). Using the
 * default receiver app id means no cloud console setup is required.
 */
class EchoCastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(DEFAULT_RECEIVER)
        .setStopReceiverApplicationWhenEndingSession(false)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        const val DEFAULT_RECEIVER =
            "CC1AD845" // Default Media Receiver
    }
}
