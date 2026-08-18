package com.piremote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.piremote.platform.AndroidApp
import com.piremote.service.AgentForegroundService
import com.piremote.ui.DeepLink
import com.piremote.ui.PiRemoteApp
import com.piremote.ui.PiRemoteTheme

class MainActivity : ComponentActivity() {
    /**
     * Latest deep link from a completion notification: the launch intent on a
     * cold start, every [onNewIntent] afterwards. Observable so Compose reacts
     * to both, instead of only the first intent the activity ever saw.
     */
    private val deepLink = MutableStateFlow<DeepLink?>(null)

    private fun readDeepLink(intent: Intent): DeepLink? {
        val id = intent.getStringExtra(EXTRA_OPEN_SESSION) ?: return null
        val cwd = intent.getStringExtra(EXTRA_OPEN_SESSION_CWD) ?: return null
        return DeepLink(id, cwd)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidApp.attach(this)
        AgentForegroundService.ensureChannels(this)
        deepLink.value = readDeepLink(intent)

        setContent {
            PiRemoteTheme {
                RequestNotificationPermission()
                Surface(modifier = Modifier.fillMaxSize()) {
                    val link by deepLink.collectAsStateWithLifecycle()
                    PiRemoteApp(deepLink = link)
                }
            }
        }
    }

    /**
     * The notification's intent carries FLAG_ACTIVITY_SINGLE_TOP, so tapping it
     * while the activity is already on top lands here, not in onCreate.
     * Reflect the new extra into [deepLink]; PiRemoteApp then re-runs its
     * deep-link resolution.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = readDeepLink(intent)
    }

    companion object {
        /** Set by the completion notification so tapping it opens that session. */
        const val EXTRA_OPEN_SESSION = "open_session"
        /** The session's project dir, so the app can find it without a search. */
        const val EXTRA_OPEN_SESSION_CWD = "open_session_cwd"
    }
}

/**
 * Ask once, on first launch. Denial is fine — it costs the completion
 * notification, nothing else, so there is no rationale dialog nagging.
 */
@androidx.compose.runtime.Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
