package app.sutra.data

import androidx.annotation.StringRes
import app.sutra.R

/**
 * Built-in automations. Each one uses only signals and actions that need no runtime permission:
 * runtime-registered broadcasts, the accelerometer, the torch, the vibrator and text-to-speech.
 */
enum class Rule(
    val id: String,
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    val enabledByDefault: Boolean,
) {
    CHARGER_CONNECTED("charger_on", R.string.rule_charger_on, R.string.rule_charger_on_desc, true),
    CHARGER_DISCONNECTED("charger_off", R.string.rule_charger_off, R.string.rule_charger_off_desc, true),
    BATTERY_LOW("battery_low", R.string.rule_battery_low, R.string.rule_battery_low_desc, true),
    BATTERY_FULL("battery_full", R.string.rule_battery_full, R.string.rule_battery_full_desc, true),
    HEADPHONES("headphones", R.string.rule_headphones, R.string.rule_headphones_desc, true),
    UNLOCK_COUNTER("unlock", R.string.rule_unlock, R.string.rule_unlock_desc, true),
    SHAKE_TORCH("shake_torch", R.string.rule_shake, R.string.rule_shake_desc, true),
    AIRPLANE("airplane", R.string.rule_airplane, R.string.rule_airplane_desc, false),
    POWER_SAVE("power_save", R.string.rule_power_save, R.string.rule_power_save_desc, false),
    HEARTBEAT("heartbeat", R.string.rule_heartbeat, R.string.rule_heartbeat_desc, true),
}
