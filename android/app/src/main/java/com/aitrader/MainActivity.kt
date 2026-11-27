package com.aitrader

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.aitrader.security.ActivityHolder
import com.aitrader.ui.AppNav
import com.aitrader.ui.theme.AITraderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** FragmentActivity (not ComponentActivity) because BiometricPrompt requires it. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var holder: ActivityHolder

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val start = intent?.getStringExtra(EXTRA_ROUTE) ?: "home"
        setContent { AITraderTheme { AppNav(start) } }
    }

    override fun onResume() { super.onResume(); holder.set(this) }
    override fun onPause() { holder.set(null); super.onPause() }

    companion object { const val EXTRA_ROUTE = "route" }
}
