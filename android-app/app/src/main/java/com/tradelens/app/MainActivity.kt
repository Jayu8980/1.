package com.tradelens.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.tradelens.app.data.secure.SettingsStore
import com.tradelens.app.ui.AppRoot
import com.tradelens.app.ui.DeepLinks
import com.tradelens.app.ui.theme.TradeLensTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var deepLinks: DeepLinks

    private var unlocked by mutableStateOf(false)
    private var lastBackground = 0L

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { lastBackground = System.currentTimeMillis() }
            override fun onStart(owner: LifecycleOwner) {
                // Re-lock after 2 minutes in the background.
                if (lastBackground > 0 && System.currentTimeMillis() - lastBackground > 120_000) { unlocked = false; authenticate() }
            }
        })
        authenticate()
        setContent {
            TradeLensTheme {
                if (unlocked) AppRoot()
                else Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TradeLens is locked", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = { authenticate() }, modifier = Modifier.padding(top = 16.dp)) { Text("Unlock") }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** OAuth redirect: tradelens://auth/{broker}?code=…|request_token=…|auth_code=… */
    private fun handleIntent(i: Intent?) {
        val uri = i?.data ?: return
        if (uri.scheme == "tradelens" && uri.host == "auth") {
            val broker = uri.pathSegments.firstOrNull() ?: return
            val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
            deepLinks.emitAuth(broker, params)
        }
    }

    private fun authenticate() {
        if (!settings.value.biometricLock) { unlocked = true; return }
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) { unlocked = true; return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock TradeLens").setSubtitle("Confirm it's you")
                .setAllowedAuthenticators(authenticators).build(),
        )
    }
}
