package dev.brahmkshatriya.echo.ui

import android.Manifest
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.brahmkshatriya.echo.MainActivity
import dev.brahmkshatriya.echo.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real launcher lifecycle, not just Application/Keystore setup. CI runs on API 35. */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_AUDIO
    )

    @Test fun launcherCreatesItsMainAndPlayerViews() {
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    assertNotNull(activity.findViewById<View>(R.id.navHostFragment))
                    assertNotNull(activity.findViewById<View>(R.id.playerFragmentContainer))
                }
            }
        } catch (failure: Throwable) {
            val main = android.os.Looper.getMainLooper().thread
            val trace = main.stackTrace.joinToString("\n")
            throw AssertionError("${failure.message}\nMain thread (${main.state}):\n$trace", failure)
        }
    }
}
