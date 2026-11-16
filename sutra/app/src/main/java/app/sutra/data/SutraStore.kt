package app.sutra.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small process-wide store backed by SharedPreferences. Shared by the UI and the service. */
object SutraStore {
    private const val PREFS = "sutra"
    private const val KEY_WANTED = "service_wanted"
    private const val KEY_LOG = "log"
    private const val KEY_UNLOCK_DAY = "unlock_day"
    private const val KEY_UNLOCK_COUNT = "unlock_count"
    private const val MAX_LOG = 100
    private const val SEP = "\u001F"

    private lateinit var prefs: SharedPreferences

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _rules = MutableStateFlow<Map<Rule, Boolean>>(emptyMap())
    val rules: StateFlow<Map<Rule, Boolean>> = _rules.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _log.value = prefs.getString(KEY_LOG, "")!!.split(SEP).filter { it.isNotEmpty() }
        _rules.value = Rule.entries.associateWith { prefs.getBoolean("rule_${it.id}", it.enabledByDefault) }
    }

    /** True while the user wants the service alive. Only the user's Stop clears it. */
    var serviceWanted: Boolean
        get() = prefs.getBoolean(KEY_WANTED, false)
        set(value) { prefs.edit().putBoolean(KEY_WANTED, value).apply() }

    fun setRunning(value: Boolean) { _running.value = value }

    fun isEnabled(rule: Rule): Boolean = _rules.value[rule] ?: rule.enabledByDefault

    fun setEnabled(rule: Rule, enabled: Boolean) {
        prefs.edit().putBoolean("rule_${rule.id}", enabled).apply()
        _rules.value = _rules.value + (rule to enabled)
    }

    @Synchronized
    fun log(message: String) {
        val time = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
        val updated = (listOf("$time  ·  $message") + _log.value).take(MAX_LOG)
        _log.value = updated
        prefs.edit().putString(KEY_LOG, updated.joinToString(SEP)).apply()
    }

    fun clearLog() {
        _log.value = emptyList()
        prefs.edit().remove(KEY_LOG).apply()
    }

    /** Returns today's unlock count after incrementing it. */
    fun incrementUnlocks(): Int {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val count = if (prefs.getString(KEY_UNLOCK_DAY, null) == today) prefs.getInt(KEY_UNLOCK_COUNT, 0) + 1 else 1
        prefs.edit().putString(KEY_UNLOCK_DAY, today).putInt(KEY_UNLOCK_COUNT, count).apply()
        return count
    }
}
