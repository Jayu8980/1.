package app.sutra.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.sutra.R
import app.sutra.data.SutraStore

/**
 * Restarts the monitor after a reboot or an app update — but only if the user left it running.
 * Android 15 (API 35) blocks some FGS types from BOOT_COMPLETED; `specialUse` is still allowed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        SutraStore.init(context)
        if (!SutraStore.serviceWanted) return
        try {
            SutraService.start(context)
        } catch (e: Exception) {
            SutraStore.log(context.getString(R.string.log_start_refused, e.javaClass.simpleName))
        }
    }
}
