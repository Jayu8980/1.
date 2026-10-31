package com.autotube.agents.youtube

import android.util.Base64
import com.autotube.agents.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Google OAuth 2.0 for installed apps, using the loopback redirect + PKCE.
 * Requires an OAuth client of type "Desktop app" from Google Cloud Console.
 * After the one-time consent the refresh token is stored, and agents can then
 * mint access tokens in the background forever without user interaction.
 */
class GoogleAuth(private val settings: Settings) {

    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAt: Long = 0

    class PendingLogin(val authUrl: String, internal val server: ServerSocket, internal val verifier: String, internal val redirect: String, internal val state: String)

    fun beginLogin(): PendingLogin {
        require(settings.googleClientId.isNotBlank()) { "Set the Google OAuth client ID first." }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5 * 60_000
        val redirect = "http://127.0.0.1:${server.localPort}"
        val verifier = randomUrlSafe(48)
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(16)
        val url = "https://accounts.google.com/o/oauth2/v2/auth?" + Http.form(
            mapOf(
                "client_id" to settings.googleClientId,
                "redirect_uri" to redirect,
                "response_type" to "code",
                "scope" to SCOPES,
                "access_type" to "offline",
                "prompt" to "consent",
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "state" to state,
            )
        )
        return PendingLogin(url, server, verifier, redirect, state)
    }

    /** Waits for the browser to hit the loopback redirect, then exchanges the code. */
    suspend fun completeLogin(p: PendingLogin): Unit = withContext(Dispatchers.IO) {
        val code: String
        p.server.use { server ->
            val socket = server.accept()
            socket.use { s ->
                val requestLine = s.getInputStream().bufferedReader().readLine() ?: ""
                val query = requestLine.substringAfter("?", "").substringBefore(" ")
                val params = query.split("&").filter { it.contains("=") }.associate {
                    URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
                }
                val ok = params["code"] != null && params["state"] == p.state
                val html = if (ok) "<h2>AutoTube Agents connected to YouTube.</h2><p>You can close this tab and return to the app.</p>"
                else "<h2>Sign-in failed</h2><p>${params["error"] ?: "invalid response"}</p>"
                val bytes = "<html><body style='font-family:sans-serif;padding:24px'>$html</body></html>".toByteArray()
                s.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
                code = params["code"]?.takeIf { ok } ?: throw IllegalStateException("Google sign-in failed: ${params["error"] ?: "state mismatch"}")
            }
        }
        val tokens = tokenRequest(
            mapOf(
                "code" to code,
                "client_id" to settings.googleClientId,
                "client_secret" to settings.googleClientSecret,
                "redirect_uri" to p.redirect,
                "grant_type" to "authorization_code",
                "code_verifier" to p.verifier,
            )
        )
        val refresh = tokens.optString("refresh_token")
        require(refresh.isNotBlank()) { "Google did not return a refresh token." }
        settings.googleRefreshToken = refresh
        remember(tokens)
    }

    /** Returns a valid access token, refreshing it when needed. */
    suspend fun token(): String = withContext(Dispatchers.IO) {
        accessToken?.let { if (System.currentTimeMillis() < expiresAt - 60_000) return@withContext it }
        require(settings.youtubeConnected) { "YouTube is not connected (Settings → Connect YouTube)." }
        val tokens = try {
            tokenRequest(
                mapOf(
                    "client_id" to settings.googleClientId,
                    "client_secret" to settings.googleClientSecret,
                    "refresh_token" to settings.googleRefreshToken,
                    "grant_type" to "refresh_token",
                )
            )
        } catch (e: HttpException) {
            if (e.body.contains("invalid_grant")) {
                settings.googleRefreshToken = ""
                throw IllegalStateException("YouTube access was revoked or expired. Reconnect YouTube in Settings.")
            }
            throw e
        }
        remember(tokens)
        accessToken!!
    }

    fun signOut() {
        settings.googleRefreshToken = ""; settings.channelId = ""; settings.channelTitle = ""
        accessToken = null
    }

    private fun remember(t: JSONObject) {
        accessToken = t.getString("access_token")
        expiresAt = System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000
    }

    private fun tokenRequest(params: Map<String, String>): JSONObject {
        val (_, text) = Http.request(
            "POST", "https://oauth2.googleapis.com/token",
            body = Http.form(params).toByteArray(), contentType = "application/x-www-form-urlencoded"
        )
        return JSONObject(text)
    }

    private fun randomUrlSafe(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }.let { b64(it) }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    companion object {
        const val SCOPES = "https://www.googleapis.com/auth/youtube.upload " +
            "https://www.googleapis.com/auth/youtube.force-ssl " +
            "https://www.googleapis.com/auth/youtube.readonly"
    }
}
