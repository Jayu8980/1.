package com.tubepilot.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tubepilot.app.ui.navigation.TubePilotRoot
import com.tubepilot.app.ui.theme.TubePilotTheme
import com.tubepilot.pipeline.Notifier
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Route requested by a notification tap, consumed by the navigation host. */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute = intent?.getStringExtra(Notifier.EXTRA_ROUTE)
        setContent {
            TubePilotTheme {
                TubePilotRoot(
                    deepLink = pendingRoute,
                    onDeepLinkConsumed = { pendingRoute = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_ROUTE)?.let { pendingRoute = it }
    }
}
