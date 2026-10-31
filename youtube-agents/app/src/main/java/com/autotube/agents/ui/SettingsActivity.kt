package com.autotube.agents.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.autotube.agents.core.ActivityLog
import com.autotube.agents.core.Orchestrator
import com.autotube.agents.core.Settings
import com.autotube.agents.work.Scheduler
import com.autotube.agents.youtube.YouTubeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : Activity() {
    private lateinit var s: Settings
    private lateinit var root: LinearLayout
    private val saveActions = ArrayList<() -> Unit>()
    private lateinit var ytStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { addView(root) })

        section("AI brain (Claude)")
        hint("Create a key at console.anthropic.com → API keys. All agents think with this model.")
        text("Anthropic API key", s.anthropicApiKey, secret = true) { s.anthropicApiKey = it }
        text("Model", s.model) { s.model = it }

        section("YouTube connection")
        hint("Google Cloud Console → enable \"YouTube Data API v3\" → OAuth consent screen (add yourself as a user, then Publish app) → Credentials → OAuth client ID → type \"Desktop app\". Paste the ID and secret here.")
        text("OAuth client ID", s.googleClientId) { s.googleClientId = it }
        text("OAuth client secret", s.googleClientSecret, secret = true) { s.googleClientSecret = it }
        ytStatus = hint("")
        updateYtStatus()
        row(button("Connect YouTube") { connectYouTube() }, button("Disconnect") {
            Orchestrator.auth(this).signOut(); updateYtStatus()
        })

        section("Channel")
        text("Niche / topic", s.niche) { s.niche = it }
        text("Persona & tone", s.persona, multiline = true) { s.persona = it }
        text("Language (BCP-47, e.g. en-US, hi-IN, es-ES)", s.language) { s.language = it }
        text("Region code (e.g. US, IN, GB)", s.regionCode) { s.regionCode = it }

        section("Production")
        spinner("Format", listOf("shorts", "landscape"), s.videoFormat) { s.videoFormat = it }
        number("Target length (seconds)", s.targetSeconds) { s.targetSeconds = it.coerceIn(15, 900) }
        text("Pexels API key (optional, free stock photos)", s.pexelsApiKey, secret = true) { s.pexelsApiKey = it }

        section("Autonomy & safety rails")
        number("Run a cycle every (hours)", s.cycleHours) { s.cycleHours = it.coerceIn(1, 48) }
        number("Max videos per day", s.uploadsPerDay) { s.uploadsPerDay = it.coerceIn(1, 6) }
        spinner("Upload privacy", listOf("public", "unlisted", "private"), s.privacy) { s.privacy = it }
        check("Auto-reply to comments", s.autoReplyComments) { s.autoReplyComments = it }
        number("Max comment replies per cycle", s.maxRepliesPerCycle) { s.maxRepliesPerCycle = it.coerceIn(0, 20) }
        check("Dry run (make videos but never upload)", s.dryRun) { s.dryRun = it }
        button("Allow running in background (battery)") { requestBatteryExemption() }.also { root.addView(it) }

        root.addView(button("Save") { save(); finish() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
        })
        root.addView(button("Clear logs") {
            ActivityLog(this).clear(); Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        })
    }

    override fun onPause() { super.onPause(); save() }

    private fun save() { saveActions.forEach { it() }; Scheduler.apply(this) }

    private fun updateYtStatus() {
        ytStatus.text = if (s.youtubeConnected) "✅ Connected${s.channelTitle.takeIf { it.isNotBlank() }?.let { " to $it" } ?: ""}" else "Not connected"
    }

    private fun connectYouTube() {
        save()
        if (s.googleClientId.isBlank() || s.googleClientSecret.isBlank()) {
            Toast.makeText(this, "Enter the OAuth client ID and secret first", Toast.LENGTH_LONG).show(); return
        }
        val auth = Orchestrator.auth(this)
        val pending = try { auth.beginLogin() } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show(); return
        }
        val app = applicationContext
        // App-scoped so the login survives this screen going to the background while the browser is open.
        scope.launch {
            try {
                auth.completeLogin(pending)
                val ch = YouTubeApi(auth).myChannel()
                s.channelId = ch.id; s.channelTitle = ch.title
                ActivityLog(app).info("System", "YouTube connected: ${ch.title}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "Connected to ${ch.title}", Toast.LENGTH_LONG).show()
                    if (!isDestroyed) updateYtStatus()
                }
            } catch (e: Exception) {
                ActivityLog(app).error("System", "YouTube sign-in failed: ${e.message}")
                withContext(Dispatchers.Main) { Toast.makeText(app, "Sign-in failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.authUrl)))
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this).setMessage("Already allowed. The agents can run in the background.").setPositiveButton("OK", null).show()
            return
        }
        try {
            startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------- tiny form builder ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun section(title: String) = root.addView(TextView(this).apply {
        text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFE53935.toInt())
        setPadding(0, dp(18), 0, dp(4))
    })

    private fun hint(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(0xFF5F6368.toInt()) }.also { root.addView(it) }

    private fun label(t: String) = root.addView(TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(8), 0, 0); setTextColor(0xFF1B1B1F.toInt()) })

    private fun text(name: String, value: String, secret: Boolean = false, multiline: Boolean = false, onSave: (String) -> Unit) {
        label(name)
        val e = EditText(this).apply {
            setText(value)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        root.addView(e); saveActions += { onSave(e.text.toString()) }
    }

    private fun number(name: String, value: Int, onSave: (Int) -> Unit) {
        label(name)
        val e = EditText(this).apply { setText(value.toString()); inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(e); saveActions += { e.text.toString().toIntOrNull()?.let(onSave) }
    }

    private fun spinner(name: String, options: List<String>, value: String, onSave: (String) -> Unit) {
        label(name)
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(options.indexOf(value).coerceAtLeast(0))
        }
        root.addView(sp); saveActions += { onSave(options[sp.selectedItemPosition]) }
    }

    private fun check(name: String, value: Boolean, onSave: (Boolean) -> Unit) {
        val c = CheckBox(this).apply { text = name; isChecked = value }
        root.addView(c); saveActions += { onSave(c.isChecked) }
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }

    private fun row(vararg views: View) = root.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    })

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
