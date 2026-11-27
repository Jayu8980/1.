package com.aitrader.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BiometricUnavailable(msg: String) : Exception(msg)
class BiometricCancelled : Exception("Authentication cancelled")

/** Holds the foreground activity so non-UI layers can show a BiometricPrompt. */
@Singleton
class ActivityHolder @Inject constructor() {
    private var ref: WeakReference<FragmentActivity>? = null
    fun set(a: FragmentActivity?) { ref = a?.let { WeakReference(it) } }
    fun get(): FragmentActivity? = ref?.get()
}

/**
 * Every money-touching request is signed with an EC P-256 key that lives in the Android
 * Keystore and can ONLY be used right after a strong-biometric authentication. The engine
 * verifies the signature with the public key registered at login, so a stolen JWT alone
 * can't place trades, switch to LIVE, or change risk limits.
 */
@Singleton
class ActionSigner @Inject constructor(private val holder: ActivityHolder) {
    private val alias = "aitrader_action_key"

    fun publicKeyBase64(): String? = runCatching {
        ensureKey()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        Base64.encodeToString(ks.getCertificate(alias).publicKey.encoded, Base64.NO_WRAP)
    }.getOrNull()

    private fun ensureKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) return
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                else @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(-1)
            }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec); generateKeyPair()
        }
    }

    fun canUseStrongBiometric(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Biometric prompt + signature over [payload]. Returns base64 DER signature. */
    suspend fun sign(title: String, subtitle: String, payload: ByteArray): String {
        val activity = holder.get() ?: throw BiometricUnavailable("App is not in the foreground")
        if (!canUseStrongBiometric(activity))
            throw BiometricUnavailable("Enrol a fingerprint/face (strong biometric) to authorise money actions")
        val sig = withContext(Dispatchers.Default) {
            ensureKey()
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            Signature.getInstance("SHA256withECDSA").apply { initSign(ks.getKey(alias, null) as PrivateKey) }
        }
        val authed = withContext(Dispatchers.Main) {
            prompt(activity, title, subtitle, BiometricPrompt.CryptoObject(sig), BIOMETRIC_STRONG)
        }
        val s = authed!!.signature!!
        s.update(payload)
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }

    /** Demo mode: biometric/device-credential confirmation without a crypto object. */
    suspend fun confirm(title: String, subtitle: String) {
        val activity = holder.get() ?: return
        val auth = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        val usable = Build.VERSION.SDK_INT >= 30 &&
            BiometricManager.from(activity).canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS
        if (!usable) return
        withContext(Dispatchers.Main) { prompt(activity, title, subtitle, null, auth) }
    }

    private suspend fun prompt(
        activity: FragmentActivity, title: String, subtitle: String,
        crypto: BiometricPrompt.CryptoObject?, authenticators: Int,
    ): BiometricPrompt.CryptoObject? = suspendCancellableCoroutine { cont ->
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(result.cryptoObject)
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                if (cont.isActive) cont.resumeWithException(
                    if (code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        code == BiometricPrompt.ERROR_CANCELED) BiometricCancelled() else BiometricUnavailable(msg.toString())
                )
            }
        }
        val bp = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), cb)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(true)
            .apply { if (authenticators and DEVICE_CREDENTIAL == 0) setNegativeButtonText("Cancel") }
            .build()
        if (crypto != null) bp.authenticate(info, crypto) else bp.authenticate(info)
        cont.invokeOnCancellation { bp.cancelAuthentication() }
    }
}
