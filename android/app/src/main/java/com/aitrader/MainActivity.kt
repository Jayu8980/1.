package com.aitrader

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.aitrader.security.ActivityHolder
import com.aitrader.ui.Root
import com.aitrader.ui.theme.AITraderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** FragmentActivity (not ComponentActivity) because BiometricPrompt requires it. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var holder: ActivityHolder

    private val pendingRoute = mutableStateOf<String?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val start = routeOf(intent) ?: "home"
        setContent { AITraderTheme { Root(start, pendingRoute.value) { pendingRoute.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeOf(intent)?.let { pendingRoute.value = it }
    }

    /** Notification extras, or the aitrader://brokers deep link the engine's OAuth page links back to. */
    private fun routeOf(i: Intent?): String? =
        i?.getStringExtra(EXTRA_ROUTE) ?: i?.data?.takeIf { it.scheme == "aitrader" }?.host

    override fun onResume() { super.onResume(); holder.set(this) }
    override fun onPause() { holder.set(null); super.onPause() }

    companion object { const val EXTRA_ROUTE = "route" }
}
