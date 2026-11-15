package com.tubepilot.youtube.auth

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.KeyValueStore
import com.tubepilot.core.error.AuthRequiredException
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.util.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthStart {
    data object Authorized : AuthStart
    data class NeedsConsent(val intentSender: IntentSender) : AuthStart
    data class Failed(val message: String) : AuthStart
}

/**
 * OAuth 2.0 for the YouTube APIs using Google Identity Services' AuthorizationClient. The app is
 * identified by its package name and signing-certificate SHA-1 registered as an Android OAuth
 * client in Google Cloud, so no client secret ships in the APK. Once the user has granted the
 * scopes, authorize() returns fresh access tokens silently, which lets WorkManager jobs upload
 * in the background; if consent is ever revoked, background work fails with AuthRequiredException
 * and the user is asked to reconnect.
 */
@Singleton
class YouTubeAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kv: KeyValueStore,
    private val audit: AuditLog,
    private val time: TimeSource,
) {
    private val client by lazy { Identity.getAuthorizationClient(context) }
    private val lock = Mutex()
    private var cachedToken: String? = null
    private var cachedAt = 0L

    val connected: Flow<Boolean> = kv.observe(KEY_CONNECTED).map { it == "true" }

    private fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(SCOPES.map { Scope(it) })
        .build()

    /** Starts authorisation from the UI. Returns [AuthStart.NeedsConsent] when a consent screen must be shown. */
    suspend fun authorize(): AuthStart = try {
        val result = client.authorize(request()).await()
        if (result.hasResolution()) {
            val pending = result.pendingIntent ?: return AuthStart.Failed("Google did not return a consent screen")
            AuthStart.NeedsConsent(pending.intentSender)
        } else {
            onAuthorized(result)
            AuthStart.Authorized
        }
    } catch (e: ApiException) {
        audit.record("YouTubeAuth", "auth.error", "Authorization failed: status ${e.statusCode} ${e.message}", level = AuditLevel.ERROR)
        AuthStart.Failed(
            if (e.statusCode == 10) {
                "Developer error: register this app's package name and SHA-1 as an Android OAuth client in Google Cloud (see the setup guide)."
            } else {
                e.message ?: "Authorization failed"
            },
        )
    }

    /** Completes the consent flow with the intent returned to the activity. */
    suspend fun completeConsent(data: Intent?): Boolean = try {
        val result = client.getAuthorizationResultFromIntent(data)
        onAuthorized(result)
        true
    } catch (e: ApiException) {
        audit.record("YouTubeAuth", "auth.consent_failed", "status ${e.statusCode}", level = AuditLevel.WARNING)
        false
    }

    private suspend fun onAuthorized(result: AuthorizationResult) {
        val missing = SCOPES.filterNot { scope -> result.grantedScopes.any { it == scope } }
        lock.withLock {
            cachedToken = result.accessToken
            cachedAt = time.now()
        }
        kv.put(KEY_CONNECTED, "true")
        audit.record(
            "YouTubeAuth", "auth.granted",
            if (missing.isEmpty()) "All YouTube scopes granted" else "Granted, but missing scopes: $missing",
            level = if (missing.isEmpty()) AuditLevel.SECURITY else AuditLevel.WARNING,
        )
    }

    /** Returns a valid access token for background use, refreshing silently when needed. */
    suspend fun accessToken(): String = lock.withLock {
        cachedToken?.takeIf { time.now() - cachedAt < TOKEN_TTL_MS }?.let { return it }
        val result = try {
            client.authorize(request()).await()
        } catch (e: ApiException) {
            throw AuthRequiredException("Google authorisation failed (${e.statusCode}). Reconnect YouTube in the app.")
        }
        if (result.hasResolution() || result.accessToken == null) {
            kv.put(KEY_CONNECTED, "false")
            throw AuthRequiredException()
        }
        cachedToken = result.accessToken
        cachedAt = time.now()
        result.accessToken!!
    }

    /** Drops a token the API rejected so the next call fetches a new one. */
    suspend fun invalidate(token: String) {
        lock.withLock {
            if (cachedToken == token) cachedToken = null
        }
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    suspend fun disconnect() {
        lock.withLock {
            cachedToken?.let { token -> runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() } }
            cachedToken = null
        }
        kv.put(KEY_CONNECTED, "false")
        audit.record("YouTubeAuth", "auth.disconnected", "User disconnected the YouTube channel", level = AuditLevel.SECURITY)
    }

    companion object {
        const val SCOPE_UPLOAD = "https://www.googleapis.com/auth/youtube.upload"
        const val SCOPE_FORCE_SSL = "https://www.googleapis.com/auth/youtube.force-ssl"
        const val SCOPE_ANALYTICS = "https://www.googleapis.com/auth/yt-analytics.readonly"
        val SCOPES = listOf(SCOPE_UPLOAD, SCOPE_FORCE_SSL, SCOPE_ANALYTICS)

        private const val KEY_CONNECTED = "youtube.connected"
        // Google access tokens live one hour; refresh a little early.
        private const val TOKEN_TTL_MS = 45 * 60 * 1000L
    }
}
