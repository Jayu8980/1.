package com.tradelens.app.data.net

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Normalises user-entered server URLs and enforces: plain http:// only for private/LAN hosts
 * (home Wi-Fi, Tailscale, emulator); anything on the public internet must use https://.
 */
object UrlPolicy {
    fun normalize(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isEmpty()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = (if (isPrivateHost(hostOf("http://$u"))) "http://" else "https://") + u
        u = u.removeSuffix("/api").removeSuffix("/docs").trimEnd('/')
        return u
    }

    fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".ts.net") || h == "::1") return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 100 && p[1] in 64..127) || (p[0] == 169 && p[1] == 254)
    }

    /** Throws a readable error if [url] is not allowed. */
    fun check(url: String, what: String) {
        require(url.isNotBlank()) { "$what URL not set" }
        val host = hostOf(url)
        require(host.isNotBlank()) { "$what URL looks invalid: $url" }
        if (url.startsWith("http://") && !isPrivateHost(host))
            throw IllegalArgumentException("$what: plain http:// is only allowed for home-network addresses (192.168.x.x, 10.x.x.x, Tailscale). Use https:// for $host")
    }

    /** Turns low-level network failures into actionable advice. */
    fun explain(e: Throwable, url: String, what: String): String {
        val host = hostOf(url)
        val lan = isPrivateHost(host)
        return when {
            e is ApiException && e.code == 401 -> "$what reached, but the token was rejected (401). Copy the token again exactly (no spaces)."
            e is ApiException && e.code == 404 -> "$what reached, but the path was not found (404). Enter only the base URL, e.g. http://192.168.1.20:8000"
            e is ApiException && e.code == 403 -> "$what refused this client (403) – check RELAY_ALLOW_IPS / firewall rules."
            e is ApiException && e.code == 503 -> "$what is running but not configured: ${e.body?.take(150)}"
            e is ApiException -> "$what answered HTTP ${e.code}: ${e.body?.take(150)}"
            e.message?.contains("CLEARTEXT", true) == true -> "Android blocked http:// to $host. Use https:// or a LAN address."
            e is UnknownHostException -> "Cannot resolve $host – check the spelling or your DNS / tunnel."
            e is SSLException -> "HTTPS handshake with $host failed (${e.message}). Use a valid certificate (Caddy / Cloudflare tunnel) or http:// on your LAN."
            e is ConnectException && lan -> "Connection refused by $host. Is the engine running with --host 0.0.0.0, on the same Wi-Fi, and is port open in Windows Firewall?"
            e is ConnectException -> "Connection refused by $host – server not running or port closed."
            e is SocketTimeoutException && lan -> "Timed out reaching $host. Phone and PC must be on the same Wi-Fi (not mobile data / guest network) and Windows Firewall must allow the port."
            e is SocketTimeoutException -> "Timed out reaching $host – firewall, wrong port or server down."
            else -> "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
