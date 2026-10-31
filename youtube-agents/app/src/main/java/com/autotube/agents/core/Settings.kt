package com.autotube.agents.core

import android.content.Context
import android.content.SharedPreferences

/** All user configuration. Stored in app-private SharedPreferences. */
class Settings(context: Context) {
    private val p: SharedPreferences =
        context.getSharedPreferences("autotube_settings", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = "") = p.getString(key, def) ?: def
    private fun put(key: String, v: String) = p.edit().putString(key, v).apply()

    // --- AI brain ---
    var anthropicApiKey: String get() = str("anthropic_key"); set(v) = put("anthropic_key", v.trim())
    var model: String get() = str("model", DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }; set(v) = put("model", v.trim())

    // --- Google / YouTube OAuth (Desktop-app client from Google Cloud Console) ---
    var googleClientId: String get() = str("g_client_id"); set(v) = put("g_client_id", v.trim())
    var googleClientSecret: String get() = str("g_client_secret"); set(v) = put("g_client_secret", v.trim())
    var googleRefreshToken: String get() = str("g_refresh"); set(v) = put("g_refresh", v)
    var channelId: String get() = str("channel_id"); set(v) = put("channel_id", v)
    var channelTitle: String get() = str("channel_title"); set(v) = put("channel_title", v)

    // --- Optional stock imagery ---
    var pexelsApiKey: String get() = str("pexels_key"); set(v) = put("pexels_key", v.trim())

    // --- Channel identity ---
    var niche: String get() = str("niche", "Mind-blowing science facts"); set(v) = put("niche", v)
    var persona: String
        get() = str("persona", "Curious, upbeat, clear explainer. Short punchy sentences. No clickbait lies.")
        set(v) = put("persona", v)
    var language: String get() = str("language", "en-US"); set(v) = put("language", v.trim())
    var regionCode: String get() = str("region", "US"); set(v) = put("region", v.trim().uppercase())

    // --- Production ---
    /** "shorts" (1080x1920 vertical, <=60s) or "landscape" (1280x720). */
    var videoFormat: String get() = str("format", "shorts"); set(v) = put("format", v)
    var targetSeconds: Int get() = p.getInt("target_seconds", 45); set(v) = p.edit().putInt("target_seconds", v).apply()

    // --- Autonomy & safety rails ---
    var autopilot: Boolean get() = p.getBoolean("autopilot", false); set(v) = p.edit().putBoolean("autopilot", v).apply()
    var cycleHours: Int get() = p.getInt("cycle_hours", 6); set(v) = p.edit().putInt("cycle_hours", v).apply()
    var uploadsPerDay: Int get() = p.getInt("uploads_per_day", 1); set(v) = p.edit().putInt("uploads_per_day", v).apply()
    /** public | unlisted | private */
    var privacy: String get() = str("privacy", "public"); set(v) = put("privacy", v)
    var autoReplyComments: Boolean get() = p.getBoolean("auto_reply", true); set(v) = p.edit().putBoolean("auto_reply", v).apply()
    var maxRepliesPerCycle: Int get() = p.getInt("max_replies", 5); set(v) = p.edit().putInt("max_replies", v).apply()
    /** Produce videos but never upload (for testing). */
    var dryRun: Boolean get() = p.getBoolean("dry_run", false); set(v) = p.edit().putBoolean("dry_run", v).apply()

    val youtubeConnected: Boolean get() = googleRefreshToken.isNotBlank()
    val aiConfigured: Boolean get() = anthropicApiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
    }
}
