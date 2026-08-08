package com.piremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.piremote.service.AgentForegroundService
import com.piremote.ui.PiRemoteApp
import com.piremote.ui.PiRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AgentForegroundService.ensureChannels(this)

        setContent {
            PiRemoteTheme {
                RequestNotificationPermission()
                Surface(modifier = Modifier.fillMaxSize()) {
                    PiRemoteApp(openSessionId = intent?.getStringExtra(EXTRA_OPEN_SESSION))
                }
            }
        }
    }

    companion object {
        /** Set by the completion notification so tapping it opens that session. */
        const val EXTRA_OPEN_SESSION = "open_session"
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
